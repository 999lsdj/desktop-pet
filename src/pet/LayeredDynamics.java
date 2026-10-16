package pet;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/** 将连续的惯性模拟绑定到导出包已有的真实材质骨，不再生成一套无贴图的骨架。 */
public final class LayeredDynamics {
    private static final class Chain {
        final List<String> bones;
        final double[] rest;
        final VerletChain sim;
        final double limit;
        Chain(List<String> bones, Skeleton skeleton, Config config, String kind) {
            this.bones = bones;
            rest = new double[bones.size()];
            for (int i = 0; i < rest.length; i++) rest[i] = skeleton.worldAngleDeg(bones.get(i));
            limit = Math.max(1, Math.min(12, config.d("motion." + kind + ".max.deg", 6)));
            sim = new VerletChain(Math.max(2, Math.min(12, config.i("chain." + kind + ".segments", 2))) + 1,
                    Math.max(1, config.d("chain." + kind + ".seg.length", 12)),
                    Math.max(1, Math.min(80, config.d("chain." + kind + ".max.deg", 35))),
                    Math.max(0.1, config.d("chain." + kind + ".damping", 2.5)));
        }
    }
    private final Skeleton skeleton;
    private final List<Chain> chains = new ArrayList<>();
    private final Pendulum lantern;
    private final double gravity;
    private double previousGrip, previousVelocity;
    private boolean primed;
    private double maxMotion;

    public LayeredDynamics(LayeredRig rig, Config config) {
        skeleton = rig.skeleton();
        gravity = Math.max(1, config.d("chain.gravity", 900));
        for (String kind : new String[]{"hair", "sleeve", "coat"}) {
            for (String side : new String[]{"Near", "Far"}) {
                String prefix = kind + side;
                List<String> names = skeleton.names().stream().filter(n -> n.matches(prefix + "[0-9]+" )).toList();
                if (!names.isEmpty()) chains.add(new Chain(names, skeleton, config, kind));
            }
        }
        lantern = new Pendulum(Math.min(18, config.d("lantern.max.deg", 18)),
                config.d("lantern.length.device", 90) / 2, Math.max(1.5, config.d("lantern.damping", 1.5)));
    }

    public void update(double dt, double externalX, double rest) {
        maxMotion = 0;
        for (Chain c : chains) {
            Point2D.Double anchor = skeleton.start(c.bones.get(0));
            c.sim.setAnchor(anchor.x + externalX, anchor.y);
            c.sim.update(dt, gravity);
            for (int i = 0; i < c.bones.size(); i++) {
                // 可配置模拟节数与贴图骨节数独立：沿链长度采样，保持素材绑定不变。
                int node = 1 + (int) Math.round(i * (c.sim.nodeCount() - 2.0) / Math.max(1, c.bones.size() - 1));
                double delta = c.limit * Math.tanh((c.sim.worldAngleDeg(node) - 90) / c.limit);
                String name = c.bones.get(i);
                skeleton.aim(name, c.rest[i] + delta);
                maxMotion = Math.max(maxMotion, Math.abs(delta));
            }
        }
        double grip = skeleton.start("lantern").x + externalX;
        double velocity = primed ? (grip - previousGrip) / dt : 0;
        double acceleration = primed ? (velocity - previousVelocity) / dt : 0;
        previousGrip = grip; previousVelocity = velocity; primed = true;
        lantern.update(dt, acceleration * (1 - rest), gravity);
        skeleton.setDelta("lantern", Math.toDegrees(lantern.angle()) * (1 - rest));
        skeleton.update();
    }

    public double maxMotion() { return maxMotion; }
}
