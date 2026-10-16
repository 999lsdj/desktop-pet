package pet;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.geom.Area;

/** 正式动作行为回归；不会创建窗口。 */
public final class ProductionAnimationSelfTest {
    public static void main(String[] args) throws Exception {
        LayeredRig rig = LayeredRig.load(Path.of(args[0]));
        Skeleton s = rig.skeleton();
        s.setDelta("thighNear", 12); rig.update();
        for (LayeredRig.Layer layer : rig.layers()) if (layer.name.equals("thighNear")) {
            MeshWarp mesh = layer.skin.mesh();
            for (int v = 0; v < mesh.vertexCount(); v++) {
                if (mesh.srcY(v) + layer.y <= 79 && mesh.srcX(v) + layer.x >= 40 && mesh.srcX(v) + layer.x <= 48)
                    require(Math.hypot(mesh.dstX(v) - mesh.srcX(v), mesh.dstY(v) - mesh.srcY(v)) < 1e-7,
                            "短裤顶行被大腿拉动");
            }
        }
        rig.resetPose();
        GroundedGait gait = new GroundedGait(s, 12, 0.6);
        double error = 0, footAngleError = 0;
        double[] minX = {1e9, 1e9}, maxX = {-1e9, -1e9};
        double[] minHip = {1e9, 1e9}, maxHip = {-1e9, -1e9};
        for (int i = 0; i <= 2160; i++) {
            double cycle = i / 720.0;
            gait.apply(cycle, 1, 0);
            for (int leg = 0; leg < 2; leg++) {
                String side = GroundedGait.side(leg);
                Point2D.Double foot = s.start("foot" + side);
                if (GroundedGait.phase(cycle, leg) <= 0.5)
                    error = Math.max(error, Math.abs(foot.y - 108));
                minX[leg] = Math.min(minX[leg], foot.x); maxX[leg] = Math.max(maxX[leg], foot.x);
                minHip[leg] = Math.min(minHip[leg], s.bone("thigh" + side).deltaDeg());
                maxHip[leg] = Math.max(maxHip[leg], s.bone("thigh" + side).deltaDeg());
                footAngleError = Math.max(footAngleError, Math.abs(s.worldAngleDeg("foot" + side) - 90));
            }
        }
        require(error < 0.01, "支撑脚离地，最大脚踝高度误差=" + error);
        require(footAngleError < 1e-7, "脚掌朝向发生折入");
        require(Math.abs((maxX[0] - minX[0]) - (maxX[1] - minX[1])) < 0.01, "左右步幅不对称");
        require(maxX[0] - minX[0] > 8, "左腿步幅不明显");
        // 接触点速度与角色行进速度抵消，而非只在Y轴锁定。
        for (double p : new double[]{0.05, 0.2, 0.45}) {
            gait.apply(p, 1, 0); double before = s.start("footNear").x + p * gait.stride();
            gait.apply(p + 0.001, 1, 0); double after = s.start("footNear").x + (p + 0.001) * gait.stride();
            require(Math.abs(after - before) < 1e-7, "支撑脚沿地面滑动");
        }
        // 循环、触地、离地交界的骨端点速度；覆盖三个周期。
        for (double p : new double[]{0, 0.5, 1, 1.5, 2, 2.5, 3}) {
            double e = 1e-5;
            gait.apply(p - e, 1, 0); Point2D.Double a = s.start("footNear");
            gait.apply(p, 1, 0); Point2D.Double b = s.start("footNear");
            gait.apply(p + e, 1, 0); Point2D.Double c = s.start("footNear");
            require(Math.hypot((c.x - 2*b.x + a.x) / e, (c.y - 2*b.y + a.y) / e) < 0.02,
                    "循环或触地速度不连续");
        }
        rig.resetPose();
        PetAnimation animation = new PetAnimation(rig, new Config(Path.of("config.properties")));
        double x = 0, maxFlex = 0;
        for (int i = 0; i < 360; i++) {
            x += 18.0 / 120;
            animation.update(1.0 / 120, true, 18, 0, 0, 0, x);
            maxFlex = Math.max(maxFlex, animation.flexibleMotion());
            require(rig.fits(PetAnimation.PADDING), "正式步态超出留边");
        }
        require(maxFlex > 0.5, "发丝袖摆没有惯性响应");
        for (int i = 0; i < 720; i++) animation.update(1.0 / 120, false, 0, 0, 0, 0, x);
        require(animation.flexibleMotion() < 0.25, "停步六秒后衣发仍未收敛: " + animation.flexibleMotion());
        String loops = loopContinuity(Path.of(args[0]));
        // 9.2：手臂抬出身体轮廓时（菜单姿态），逐像素命中仍与实际可见像素一致
        rig.resetPose();
        PetAnimation reach = new PetAnimation(rig, new Config(Path.of("config.properties")));
        for (int i = 0; i < 120; i++) reach.update(1.0 / 120, false, 0, 0, 0, 1, 0);
        BufferedImage reachFrame = reach.frame();
        int visiblePixels = 0, farX = -1, farY = -1;
        for (int y = 0; y < reachFrame.getHeight(); y++) {
            for (int ix = 0; ix < reachFrame.getWidth(); ix++) {
                if ((reachFrame.getRGB(ix, y) >>> 24) <= 8) continue;
                visiblePixels++;
                if (ix > farX) { farX = ix; farY = y; }
            }
        }
        require(visiblePixels > 2000, "菜单姿态没有可见像素");
        // 帧内像素 (px,py) 对应的地面相对坐标要减去画布原点偏移
        double frameOriginX = Math.ceil(rig.width() / 2.0) + PetAnimation.PADDING;
        double frameOriginY = rig.height() + PetAnimation.PADDING;
        double hitX = farX + 0.5 - frameOriginX, hitY = farY + 0.5 - frameOriginY;
        require(reach.hit(hitX, hitY, 0, 0, 1, false), "伸出去的部位未被判定命中");
        require(!reach.hit(hitX + 1, hitY, 0, 0, 1, false), "空白处被误判命中");
        System.out.println("PASS reach-pose hit: 手伸出轮廓外命中，旁边空白不命中");
        for (boolean mirror : new boolean[]{false, true}) {
            BufferedImage image = new BufferedImage(450, 450, BufferedImage.TYPE_INT_ARGB);
            var g = image.createGraphics(); animation.draw(g, 220, 360, 2, mirror); g.dispose();
            Area shape = animation.shape(220, 360, 2, mirror);
            for (int y = 0; y < 450; y++) for (int ix = 0; ix < 450; ix++) {
                boolean visible = (image.getRGB(ix, y) >>> 24) > 8;
                require(animation.hit(ix + 0.5, y + 0.5, 220, 360, 2, mirror) == visible, "命中偏离实际像素");
                require(shape.contains(ix + 0.5, y + 0.5) == visible, "窗口形状偏离实际像素");
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "PASS ground=%.9fpx feet=%.9fdeg stride=%.3f/%.3fpx hipHalfRange=%.3f/%.3fdeg flexPeak=%.3f settled=%.3f%n",
                error, footAngleError, maxX[0]-minX[0], maxX[1]-minX[1],
                (maxHip[0]-minHip[0])/2, (maxHip[1]-minHip[1])/2, maxFlex, animation.flexibleMotion());
        if (args.length > 1) {
            Path report = Path.of(args[1]); Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, String.format(java.util.Locale.ROOT,
                    "{\"status\":\"passed\",\"phase_samples\":2161,\"stable_support_height_error_px\":%.9f,"
                    + "\"foot_angle_error_deg\":%.9f,\"ankle_stride_px\":[%.6f,%.6f],\"hip_half_range_deg\":[%.6f,%.6f],"
                    + "\"flex_peak_deg\":%.6f,\"flex_after_six_seconds_deg\":%.6f,\"desktop_acceptance\":false,"
                    + "\"reach_hit_pose\":{\"visible_pixels\":%d,\"extended_pixel_hit\":true},"
                    + "\"loop_continuity\":{%s}}\n",
                    error, footAngleError, maxX[0]-minX[0], maxX[1]-minX[1],
                    (maxHip[0]-minHip[0])/2, (maxHip[1]-minHip[1])/2, maxFlex, animation.flexibleMotion(),
                    visiblePixels, loops),
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * 4.2：循环动作连播三轮。同一相位的姿势必须重复，交界那一帧的位移不得比轮内更大。
     * 每轮固定 120 帧，采样步长取周期的 1/120，所以三轮的相位点严格对齐。
     */
    private static String loopContinuity(Path bundle) throws Exception {
        String[] kinds = {"walk", "sway"};
        String[] tracked = {"torso", "head", "thighNear", "shinNear", "footNear", "thighFar",
                "shinFar", "footFar", "upperArm", "foreArm", "hand", "staff"};
        StringBuilder json = new StringBuilder();
        for (int k = 0; k < kinds.length; k++) {
            boolean walking = k == 0;
            Config config = new Config(Path.of("config.properties"));
            LayeredRig rig = LayeredRig.load(bundle);
            PetAnimation loop = new PetAnimation(rig, config);
            Skeleton s = rig.skeleton();
            double speed = walking ? 18 : 0;
            double period = walking
                    ? new GroundedGait(s, config.d("motion.hip.deg", 12),
                        config.d("motion.foot.lift.px", 0.6)).stride() / speed
                    : config.d("sway.period.sec", 1.5);
            double step = period / 120;
            double travel = 0, time = 0;
            for (int i = 0; i < 120; i++) {   // 起步过渡，不参与判定
                time += step;
                if (walking) travel += speed * step;
                loop.update(step, walking, speed, loopTilt(walking, time, period), 0, 0, travel);
            }
            double[] previous = pose(s, tracked);
            double maxInterior = 0, maxBoundary = 0;
            double[][] cycleEnd = new double[3][];
            for (int cycle = 0; cycle < 3; cycle++) {
                for (int i = 1; i <= 120; i++) {
                    time += step;
                    if (walking) travel += speed * step;
                    loop.update(step, walking, speed, loopTilt(walking, time, period), 0, 0, travel);
                    double[] current = pose(s, tracked);
                    double move = drift(previous, current);
                    previous = current;
                    // i == 120 的那一帧正好跨进下一轮，是唯一的交界帧
                    if (i < 120) maxInterior = Math.max(maxInterior, move);
                    else maxBoundary = Math.max(maxBoundary, move);
                }
                cycleEnd[cycle] = previous;
            }
            double repeat = Math.max(drift(cycleEnd[0], cycleEnd[1]), drift(cycleEnd[0], cycleEnd[2]));
            require(maxInterior > 0.02, kinds[k] + " 循环动作没有实际运动");
            require(maxBoundary <= maxInterior * 1.5 + 0.01,
                    kinds[k] + " 交界处出现跳变: " + maxBoundary + " 对比轮内 " + maxInterior);
            require(repeat < 1e-3, kinds[k] + " 三轮同一相位姿势不一致: " + repeat);
            json.append(String.format(java.util.Locale.ROOT,
                    "%s\"%s\":{\"cycles\":3,\"frames_per_cycle\":120,\"max_interior_step_px\":%.6f,"
                    + "\"max_boundary_step_px\":%.6f,\"same_phase_repeat_error_px\":%.3e}",
                    json.length() == 0 ? "" : ",", kinds[k], maxInterior, maxBoundary, repeat));
            System.out.printf(java.util.Locale.ROOT,
                    "PASS loop %s 3 cycles interior=%.4fpx boundary=%.4fpx repeat=%.2e px%n",
                    kinds[k], maxInterior, maxBoundary, repeat);
        }
        return json.toString();
    }

    /** 微醺摇摆的幅度固定为 8°，与配置里的开关无关；判定的是求值器本身的循环连续性。 */
    private static double loopTilt(boolean walking, double time, double period) {
        return walking ? 0 : 8 * Math.sin(2 * Math.PI * time / period);
    }

    /** 追踪骨头起点与末端，作为这一帧姿势的签名。 */
    private static double[] pose(Skeleton skeleton, String[] names) {
        double[] out = new double[names.length * 4];
        for (int i = 0; i < names.length; i++) {
            Point2D.Double head = skeleton.start(names[i]), tip = skeleton.tip(names[i]);
            out[i * 4] = head.x; out[i * 4 + 1] = head.y;
            out[i * 4 + 2] = tip.x; out[i * 4 + 3] = tip.y;
        }
        return out;
    }

    private static double drift(double[] a, double[] b) {
        double max = 0;
        for (int i = 0; i + 3 < a.length; i += 4) {
            max = Math.max(max, Math.hypot(a[i] - b[i], a[i + 1] - b[i + 1]));
            max = Math.max(max, Math.hypot(a[i + 2] - b[i + 2], a[i + 3] - b[i + 3]));
        }
        return max;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
