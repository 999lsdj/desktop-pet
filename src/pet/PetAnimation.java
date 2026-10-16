package pet;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** PetWindow 与离屏验收共同使用的正式动作及图像接口。 */
public final class PetAnimation {
    public static final int PADDING = 32;
    private final LayeredRig rig;
    private final GroundedGait gait;
    private final LayeredDynamics dynamics;
    private BufferedImage frame;
    private double cycles, blend;
    private double velocity;
    private final AffineTransform plantedStaff;

    public PetAnimation(LayeredRig rig, Config config) {
        this.rig = rig;
        rig.resetPose();
        gait = new GroundedGait(rig.skeleton(), config.d("motion.hip.deg", 12), config.d("motion.foot.lift.px", 0.6));
        dynamics = new LayeredDynamics(rig, config);
        Skeleton s = rig.skeleton();
        Point2D.Double bottom = s.tip("staff");
        plantedStaff = AffineTransform.getTranslateInstance(-13, 121 - bottom.y);
        plantedStaff.rotate(Math.toRadians(12), bottom.x, bottom.y);
        plantedStaff.concatenate(s.bone("staff").restWorld());
        frame = rig.render(PADDING);
    }

    /** speed 和 externalX 均为朝向角色坐标的原生像素单位，dt 为秒。 */
    public void update(double dt, boolean walking, double speed, double tilt, double rest, double menu, double externalX) {
        if (!(dt > 0) || !Double.isFinite(dt)) throw new IllegalArgumentException("Invalid timestep");
        int steps = Math.max(1, (int) Math.ceil(Math.min(dt, 0.1) * 120));
        double step = Math.min(dt, 0.1) / steps;
        for (int i = 0; i < steps; i++) {
            double target = walking ? 1 : 0;
            blend += (target - blend) * (1 - Math.exp(-step * 9));
            if (Math.abs(target - blend) < 1e-5) blend = target;
            velocity = Math.max(0, speed) * blend;
            cycles += velocity * step / gait.stride();
            Skeleton s = rig.skeleton();
            for (String name : s.names()) s.setDelta(name, 0);
            s.overrideWorld("staff", null);
            s.setDelta("torso", Math.max(-15, Math.min(15, tilt)));
            s.setDelta("head", -tilt * 0.25);
            s.update();
            gait.apply(cycles, blend, tilt);
            double arms = Math.sin(cycles * Math.PI * 2) * 2 * blend;
            s.setDelta("upperArm", arms);
            s.setDelta("upperArmFar", -arms - menu * 12);
            s.setDelta("foreArmFar", -menu * 16);
            s.update();
            if (rest > 0) {
                AffineTransform held = s.bone("staff").world();
                double a = Math.atan2(held.getShearY(), held.getScaleX());
                double b = Math.atan2(plantedStaff.getShearY(), plantedStaff.getScaleX());
                double mix = Math.max(0, Math.min(1, rest));
                mix = mix * mix * (3 - 2 * mix);
                AffineTransform placed = AffineTransform.getTranslateInstance(
                        held.getTranslateX() * (1 - mix) + plantedStaff.getTranslateX() * mix,
                        held.getTranslateY() * (1 - mix) + plantedStaff.getTranslateY() * mix);
                placed.rotate(a + Math.atan2(Math.sin(b - a), Math.cos(b - a)) * mix);
                s.overrideWorld("staff", placed);
                s.update();
                Point2D.Double grip = s.start("staff"), shoulder = s.start("upperArm");
                Ik.Solution reach = Ik.solve(shoulder.x, shoulder.y, s.bone("upperArm").lengthPx,
                        s.bone("foreArm").lengthPx, grip.x, grip.y, true);
                s.aim("upperArm", reach.firstWorldDeg);
                s.aim("foreArm", reach.secondWorldDeg);
            }
            // 灯杖依手臂移动，独立灯体由阻尼单摆控制。
            dynamics.update(step, externalX - velocity * step * (steps - i - 1), rest);
        }
        rig.update();
        frame = rig.render(PADDING);
    }

    public BufferedImage frame() { return frame; }
    public LayeredRig rig() { return rig; }
    public double phase() { return cycles; }
    public double walkBlend() { return blend; }
    public double flexibleMotion() { return dynamics.maxMotion(); }

    public void draw(Graphics2D graphics, double groundX, double groundY, int scale, boolean mirrored) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.translate(groundX, groundY);
        g.scale(mirrored ? -scale : scale, scale);
        g.drawImage(frame, (int) -Math.ceil(rig.width() / 2.0) - PADDING, -rig.height() - PADDING, null);
        g.dispose();
    }

    public boolean hit(double x, double y, double gx, double gy, int scale, boolean mirrored) {
        double localX = (x - gx) / scale * (mirrored ? -1 : 1) + Math.ceil(rig.width() / 2.0) + PADDING;
        int ix = (int) Math.floor(localX);
        int iy = (int) Math.floor((y - gy) / scale + rig.height() + PADDING);
        return ix >= 0 && iy >= 0 && ix < frame.getWidth() && iy < frame.getHeight()
                && (frame.getRGB(ix, iy) >>> 24) > 8;
    }

    /** 当帧 alpha 行段，与绘制和命中完全共用同一帧，避免旧静态包络切掉肢体。 */
    public Area shape(double gx, double gy, int scale, boolean mirrored) {
        Path2D.Double strips = new Path2D.Double();
        double left = -Math.ceil(rig.width() / 2.0) - PADDING;
        for (int y = 0; y < frame.getHeight(); y++) {
            int start = -1;
            for (int x = 0; x <= frame.getWidth(); x++) {
                boolean solid = x < frame.getWidth() && (frame.getRGB(x, y) >>> 24) > 8;
                if (solid && start < 0) start = x;
                if (!solid && start >= 0) {
                    double px = mirrored ? -(left + x) : left + start;
                    strips.append(new Rectangle2D.Double(gx + px * scale,
                            gy + (y - rig.height() - PADDING) * scale, (x - start) * scale, scale), false);
                    start = -1;
                }
            }
        }
        return new Area(strips);
    }
}
