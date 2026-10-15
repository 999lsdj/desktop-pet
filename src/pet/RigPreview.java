package pet;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

/**
 * 拆件自检：把躯干、提灯、两条腿按不同姿态渲染成一张对比图，
 * 用来核对拆件坐标与旋转枢轴是否正确。不参与桌宠运行。
 */
public final class RigPreview {

    private static final int SCALE = 2;
    private static final int TILE_W = 340;
    private static final int TILE_H = 380;
    /** 测试网格密度（格数）：12×14 是粗网格，24×28 用来对比加密后的代价 */
    private static final int[][] BENCH_GRIDS = {{12, 14}, {24, 28}};
    /** 折痕取髋线：折痕以上不动，往下 35 像素内线性加大旋转角 */
    private static final double BEND_PIVOT_X = 44 * SCALE;
    private static final double BEND_PIVOT_Y = 89 * SCALE;
    private static final double BEND_FROM_Y = 88 * SCALE;
    private static final double BEND_TO_Y = 123 * SCALE;
    /** 蒙皮权重的影响半径（原生像素）：越大过渡越软，越小越接近刚性跟随 */
    private static final double SKIN_FALLOFF_PX = 16;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Log.init(root.resolve("logs").resolve("pet.log"));

        Path spritePath = Paths.get("assets", "sprites", "pet-base.png").toAbsolutePath();
        BodyRig rig = BodyRig.load(spritePath);

        BufferedImage sheet = new BufferedImage(TILE_W * 3 + 560, TILE_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(0xF2F5FA));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

        drawTile(g, rig, 0, 0, 0, 0);
        drawTile(g, rig, TILE_W, 12, 0, 0);
        drawTile(g, rig, TILE_W * 2, 0, 30, 0);
        drawLying(g, rig, TILE_W * 3, 560);

        g.dispose();
        Path out = Paths.get("_tmp", "shots", "rig-preview.png").toAbsolutePath();
        java.nio.file.Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());
        System.out.println("rig preview -> " + out);

        BufferedImage sprite2x = scale2x(rig.original());
        Path meshOut = Paths.get("_tmp", "shots", "mesh-check.png").toAbsolutePath();
        ImageIO.write(meshCheckSheet(sprite2x), "png", meshOut.toFile());
        System.out.println("mesh check -> " + meshOut);
        meshBench(sprite2x);

        Path skelOut = Paths.get("_tmp", "shots", "skeleton-check.png").toAbsolutePath();
        ImageIO.write(skeletonCheckSheet(sprite2x, rig), "png", skelOut.toFile());
        System.out.println("skeleton check -> " + skelOut);
        skeletonSelfCheck(rig);
        ikSelfCheck(rig);

        Path skinOut = Paths.get("_tmp", "shots", "skin-check.png").toAbsolutePath();
        ImageIO.write(skinCheckSheet(rig), "png", skinOut.toFile());
        System.out.println("skin check -> " + skinOut);
        skinSelfCheck(rig);
        chainSelfCheck();

        Path chainOut = Paths.get("_tmp", "shots", "chain-check.png").toAbsolutePath();
        ImageIO.write(chainRigSheet(rig), "png", chainOut.toFile());
        System.out.println("chain check -> " + chainOut);
        chainRigCheck(rig);
    }

    /** 柔性链验收图：静止、摆动中、停下后三格，链画成折线叠在角色上。 */
    private static BufferedImage chainRigSheet(BodyRig rig) {
        int tileW = 300;
        int tileH = 340;
        BufferedImage sheet = new BufferedImage(tileW * 3, tileH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0xF2F5FA));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

        double dt = 1.0 / 120;
        Skeleton skel = Skeleton.from(rig);
        ChainRig chains = new ChainRig(skel, 900);
        // 先把链挂到位，否则第一格画出来的还是没推进过的原点
        for (int f = 0; f < 60; f++) {
            skel.update();
            chains.update(dt);
        }
        for (int tile = 0; tile < 3; tile++) {
            if (tile == 1) {
                // 摆动中：躯干左右摆一秒，链被带着甩起来
                for (int f = 0; f < 120; f++) {
                    skel.setDelta("torso", 10 * Math.sin(2 * Math.PI * 1.0 * f * dt));
                    skel.update();
                    chains.update(dt);
                }
            } else if (tile == 2) {
                // 停下后：躯干归零再跑几秒，链自己衰减
                for (int f = 0; f < 480; f++) {
                    skel.setDelta("torso", 0);
                    skel.update();
                    chains.update(dt);
                }
            }
            double offX = tile * tileW + (tileW - rig.width() * SCALE) / 2.0;
            double offY = (tileH - rig.height() * SCALE) / 2.0;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.setComposite(AlphaComposite.SrcOver.derive(0.45f));
            g2.drawImage(scale2x(rig.original()), (int) Math.round(offX), (int) Math.round(offY), null);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.setColor(new Color(200, 60, 150));
            for (int c = 0; c < chains.chainCount(); c++) {
                for (int n = 0; n < chains.segmentCount(c); n++) {
                    g2.drawLine(
                            (int) Math.round(offX + chains.nodeX(c, n) * SCALE),
                            (int) Math.round(offY + chains.nodeY(c, n) * SCALE),
                            (int) Math.round(offX + chains.nodeX(c, n + 1) * SCALE),
                            (int) Math.round(offY + chains.nodeY(c, n + 1) * SCALE));
                }
            }
            g2.dispose();
        }
        g.dispose();
        return sheet;
    }

    /**
     * 柔性链自检：让躯干以 1 Hz 左右摆，看末端节点是不是滞后于挂点；
     * 停摆之后看多久能停住。
     */
    private static void chainRigCheck(BodyRig rig) {
        double dt = 1.0 / 120;
        Skeleton skel = Skeleton.from(rig);
        ChainRig chains = new ChainRig(skel, 900);

        // 先静置一秒
        for (int f = 0; f < 120; f++) {
            skel.update();
            chains.update(dt);
        }

        // 起步：1 Hz 摆动，先跑两秒到稳态，再取一个周期量相位差
        for (int f = 0; f < 240; f++) {
            skel.setDelta("torso", 10 * Math.sin(2 * Math.PI * f * dt));
            skel.update();
            chains.update(dt);
        }
        double anchorPeakX = -1e9;
        double tipPeakX = -1e9;
        double anchorPeakSec = 0;
        double tipPeakSec = 0;
        for (int f = 0; f < 120; f++) {
            double t = (240 + f) * dt;
            skel.setDelta("torso", 10 * Math.sin(2 * Math.PI * t));
            skel.update();
            chains.update(dt);
            double ax = chains.nodeX(0, 0);
            double tx = chains.nodeX(0, chains.segmentCount(0));
            if (ax > anchorPeakX) {
                anchorPeakX = ax;
                anchorPeakSec = t;
            }
            if (tx > tipPeakX) {
                tipPeakX = tx;
                tipPeakSec = t;
            }
        }
        double lagSec = tipPeakSec - anchorPeakSec;

        // 停下：躯干归零，量多久能停住（所有关节落到 1 度以内）
        double secondsToRest = -1;
        int limit = (int) Math.round(15 / dt);
        for (int f = 0; f < limit; f++) {
            skel.setDelta("torso", 0);
            skel.update();
            chains.update(dt);
            if (f > 120 && chains.maxJointAngleDeg() < 1.0) {
                secondsToRest = f * dt;
                break;
            }
        }
        Log.info(String.format("柔性链自检 %d 条链：摆幅峰值 挂点 %.2f → 末端 %.2f 原生像素，"
                        + "末端滞后 %.3f 秒；停摆后 %.2f 秒内所有关节落到 1 度以内",
                chains.chainCount(), anchorPeakX, tipPeakX, lagSec, secondsToRest));
        if (lagSec <= 0.02) {
            Log.error("柔性链自检不通过：末端没有滞后于挂点（滞后 " + lagSec + " 秒）", null);
        }
        if (secondsToRest < 0) {
            Log.error("柔性链自检不通过：停摆 15 秒仍未静止", null);
        }
    }

    /**
     * 链式自检：挂点在 0.2 秒内平移 30 像素再松手，看整条链会不会收敛、会不会发散，
     * 同时确认每节杆长始终在容差内（约束求解没被拉长）。
     *
     * 输入不能用"一帧内瞬移"：那会把限幅一次打满，任何参数都表现为同一结果，测不出东西。
     */
    private static void chainSelfCheck() {
        int nodeCount = 5;
        double segLen = 12;
        double dt = 1.0 / 120;
        double gravity = 900;
        double maxAngleDeg = 30;
        VerletChain chain = new VerletChain(nodeCount, segLen, maxAngleDeg, 2.5);

        // 先静止一秒，让链竖直挂好
        chain.setAnchor(0, 0);
        for (int i = 0; i < 120; i++) {
            chain.update(dt, gravity);
        }

        int ramp = (int) Math.round(0.2 / dt);
        int frames = (int) Math.round(12 / dt);
        double shakePeak = 0;
        double lastSecond = 0;
        double worstLengthError = 0;
        boolean finite = true;
        for (int f = 0; f < frames; f++) {
            chain.setAnchor(f < ramp ? 30.0 * (f + 1) / ramp : 30, 0);
            chain.update(dt, gravity);
            for (int i = 1; i < nodeCount; i++) {
                double a = chain.jointAngleDeg(i);
                if (!Double.isFinite(a)) {
                    finite = false;
                    continue;
                }
                if (f >= ramp && f < ramp + 120) {
                    shakePeak = Math.max(shakePeak, Math.abs(a));
                }
                if (f >= frames - 120) {
                    lastSecond = Math.max(lastSecond, Math.abs(a));
                }
                worstLengthError = Math.max(worstLengthError,
                        Math.abs(chain.segmentLength(i) - segLen));
            }
        }
        Log.info(String.format("链式自检 %d 节点 %d 节（限位 %.0f 度）：松手后 1 秒峰值 %.2f 度、"
                        + "12 秒后末 1 秒峰值 %.4f 度；杆长最大偏差 %.6f 像素；数值%s",
                nodeCount, nodeCount - 1, maxAngleDeg, shakePeak, lastSecond,
                worstLengthError, finite ? "正常" : "出现 NaN/Inf"));
        if (!finite) {
            Log.error("链式自检不通过：数值出现 NaN/Inf", null);
        }
        if (worstLengthError > 0.01) {
            Log.error("链式自检不通过：杆长被拉长 " + worstLengthError + " 像素", null);
        }
        if (lastSecond > 1.0) {
            // 判据是"停住"而不是"变小"：暴力甩过之后，12 秒内所有关节都要落到 1 度以内
            Log.error("链式自检不通过：12 秒后仍未停住（末 1 秒峰值 " + lastSecond + " 度）", null);
        }
    }

    /** 蒙皮验收图：整张图按骨骼权重变形，看关节处是不是连续弯、有没有缺口。 */
    private static BufferedImage skinCheckSheet(BodyRig rig) {
        int tileW = 300;
        int tileH = 340;
        BufferedImage sheet = new BufferedImage(tileW * 3, tileH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0xF2F5FA));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

        for (int tile = 0; tile < 3; tile++) {
            Skeleton skel = Skeleton.from(rig);
            applyPose(skel, rig, tile);
            Skin skin = new Skin(rig.original(), skel, BENCH_GRIDS[0][0], BENCH_GRIDS[0][1],
                    SKIN_FALLOFF_PX);
            skin.update();

            double offX = tile * tileW + (tileW - rig.width() * SCALE) / 2.0;
            double offY = (tileH - rig.height() * SCALE) / 2.0;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // 蒙皮算出来的是原生坐标，这里放大到屏幕上同样的尺寸
            g2.translate(offX, offY);
            g2.scale(SCALE, SCALE);
            skin.mesh().draw(g2, 0, 0);
            g2.dispose();
        }
        g.dispose();
        return sheet;
    }

    /** 蒙皮自检：静止姿态下顶点不该动；顺带量一下每帧重算的耗时。 */
    private static void skinSelfCheck(BodyRig rig) {
        Skeleton rest = Skeleton.from(rig);
        Skin skin = new Skin(rig.original(), rest, BENCH_GRIDS[0][0], BENCH_GRIDS[0][1],
                SKIN_FALLOFF_PX);
        skin.update();
        double worst = 0;
        for (int i = 0; i < skin.mesh().vertexCount(); i++) {
            worst = Math.max(worst, Math.hypot(skin.mesh().dstX(i) - skin.mesh().srcX(i),
                    skin.mesh().dstY(i) - skin.mesh().srcY(i)));
        }

        Skeleton posed = Skeleton.from(rig);
        Skin moving = new Skin(rig.original(), posed, BENCH_GRIDS[0][0], BENCH_GRIDS[0][1],
                SKIN_FALLOFF_PX);
        int frames = 240;
        int warmup = 60;
        long[] ns = new long[frames - warmup];
        for (int f = 0; f < frames; f++) {
            posed.setDelta("thighNear", 20 * Math.sin(f * 0.05));
            posed.setDelta("thighFar", -20 * Math.sin(f * 0.05));
            posed.setDelta("staff", 15 * Math.sin(f * 0.07));
            posed.update();
            long t0 = System.nanoTime();
            moving.update();
            long t1 = System.nanoTime();
            if (f >= warmup) {
                ns[f - warmup] = t1 - t0;
            }
        }
        Log.info("蒙皮自检 静止姿态顶点最大位移 " + String.format("%.9f", worst)
                + " 原生像素 | 顶点 " + skin.skinnedVertexCount() + " 个 | 每帧重算 "
                + stats(ns));
    }

    /** 骨架验收图：静止姿态、摆过腿和杖的姿态、以及用 IK 够到目标点的姿态。 */
    private static BufferedImage skeletonCheckSheet(BufferedImage sprite, BodyRig rig) {
        int tileW = 300;
        int tileH = 340;
        BufferedImage sheet = new BufferedImage(tileW * 3, tileH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(0xF2F5FA));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

        for (int tile = 0; tile < 3; tile++) {
            Skeleton skel = Skeleton.from(rig);
            Point2D.Double target = applyPose(skel, rig, tile);

            double offX = tile * tileW + (tileW - sprite.getWidth()) / 2.0;
            double offY = (tileH - sprite.getHeight()) / 2.0;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // 角色画淡一点，否则骨线压在花纹上根本看不清
            g2.setComposite(AlphaComposite.SrcOver.derive(0.4f));
            g2.drawImage(sprite, (int) Math.round(offX), (int) Math.round(offY), null);
            g2.setComposite(AlphaComposite.SrcOver);
            drawSkeleton(g2, skel, offX, offY);
            if (target != null) {
                g2.setColor(new Color(20, 140, 60));
                double tx = offX + target.x * SCALE;
                double ty = offY + target.y * SCALE;
                g2.drawLine((int) tx - 7, (int) ty, (int) tx + 7, (int) ty);
                g2.drawLine((int) tx, (int) ty - 7, (int) tx, (int) ty + 7);
            }
            g2.dispose();
        }
        g.dispose();
        return sheet;
    }

    /** 验收图三格的姿态：0 静止，1 摆腿甩灯，2 用 IK 够目标点。返回目标点或 null。 */
    private static Point2D.Double applyPose(Skeleton skel, BodyRig rig, int tile) {
        if (tile == 1) {
            // 摆腿与甩灯：验证父子级联——小腿跟着大腿动，提灯跟着手走
            skel.setDelta("thighNear", 25);
            skel.setDelta("shinNear", -35);
            skel.setDelta("thighFar", -20);
            skel.setDelta("torso", 6);
            skel.update();
            skel.setDelta("staff", 18);
            skel.update();
            return null;
        }
        if (tile == 2) {
            // 用 IK 让手够一个目标点，提灯作为子骨会自动跟过去
            Point2D.Double shoulder = skel.start("upperArm");
            Point2D.Double target = new Point2D.Double(rig.grip().x + 26, rig.grip().y - 30);
            Ik.Solution sol = Ik.solve(shoulder.x, shoulder.y,
                    skel.bone("upperArm").lengthPx, skel.bone("foreArm").lengthPx,
                    target.x, target.y, true);
            skel.aim("upperArm", sol.firstWorldDeg);
            skel.aim("foreArm", sol.secondWorldDeg);
            return target;
        }
        return null;
    }

    /**
     * 把骨架画在角色上面：骨头是蓝线，关节是橙点，根节点大一点。
     * 骨架坐标是原生像素（与 BodyRig 一致），这里按 SPRITE_SCALE 放大到和精灵图同一尺度。
     */
    private static void drawSkeleton(Graphics2D g, Skeleton skel, double offX, double offY) {
        g.setStroke(new java.awt.BasicStroke(2f));
        for (String name : skel.names()) {
            Point2D.Double a = skel.start(name);
            Point2D.Double b = skel.tip(name);
            boolean root = skel.bone(name).parent == null;
            g.setColor(root ? new Color(150, 60, 200) : new Color(40, 90, 200));
            g.drawLine((int) Math.round(offX + a.x * SCALE), (int) Math.round(offY + a.y * SCALE),
                    (int) Math.round(offX + b.x * SCALE), (int) Math.round(offY + b.y * SCALE));
            if (!root) {
                g.setColor(new Color(240, 140, 30));
                g.fillOval((int) Math.round(offX + a.x * SCALE) - 3,
                        (int) Math.round(offY + a.y * SCALE) - 3, 6, 6);
            }
        }
        Point2D.Double rootStart = skel.start("root");
        g.setColor(new Color(150, 60, 200));
        g.fillOval((int) Math.round(offX + rootStart.x * SCALE) - 5,
                (int) Math.round(offY + rootStart.y * SCALE) - 5, 10, 10);
    }

    /** 骨架自检：静止姿态下，髋部、握点、杖底必须与拆件坐标重合。 */
    private static void skeletonSelfCheck(BodyRig rig) {
        Skeleton skel = Skeleton.from(rig);
        double worst = 0;
        worst = Math.max(worst, gap(skel.start("thighNear"), rig.hip(0), "髋(近)"));
        worst = Math.max(worst, gap(skel.start("thighFar"), rig.hip(1), "髋(远)"));
        worst = Math.max(worst, gap(skel.tip("foreArm"), rig.grip(), "握点"));
        worst = Math.max(worst, gap(skel.tip("staff"), rig.staffFoot(), "杖底"));
        Log.info(String.format("骨架自检 关节与拆件坐标最大偏差 %.6f 原生像素，骨头 %d 根",
                worst, skel.names().size()));
    }

    private static double gap(Point2D.Double a, Point2D b, String label) {
        double d = a.distance(b);
        if (d > 0.001) {
            Log.error("骨架自检 关节对不上: " + label + " 偏差 " + d, null);
        }
        return d;
    }

    /** IK 自检：随机目标点，解出来再正算一遍，看末端误差；另外验证超出范围时夹到最长伸展。 */
    private static void ikSelfCheck(BodyRig rig) {
        Skeleton skel = Skeleton.from(rig);
        double len1 = skel.bone("upperArm").lengthPx;
        double len2 = skel.bone("foreArm").lengthPx;
        Point2D.Double shoulder = skel.start("upperArm");
        java.util.Random random = new java.util.Random(20261015L);
        double worst = 0;
        int reached = 0;
        for (int i = 0; i < 200; i++) {
            double targetX = shoulder.x + (random.nextDouble() * 2 - 1) * 30;
            double targetY = shoulder.y + (random.nextDouble() * 2 - 1) * 30;
            Ik.Solution sol = Ik.solve(shoulder.x, shoulder.y, len1, len2, targetX, targetY,
                    random.nextBoolean());
            skel.aim("upperArm", sol.firstWorldDeg);
            skel.aim("foreArm", sol.secondWorldDeg);
            if (sol.reached) {
                reached++;
                worst = Math.max(worst, skel.tip("foreArm").distance(targetX, targetY));
            }
        }
        Ik.Solution far = Ik.solve(shoulder.x, shoulder.y, len1, len2,
                shoulder.x + 500, shoulder.y, true);
        skel.aim("upperArm", far.firstWorldDeg);
        skel.aim("foreArm", far.secondWorldDeg);
        double farReach = skel.tip("foreArm").distance(shoulder);
        Log.info(String.format("IK 自检 %d 组目标（%d 组可达）末端最大误差 %.6f 设备像素；"
                        + "超出范围时夹到 %.3f 像素，理论最长伸展 %.3f",
                reached, reached, worst, farReach, len1 + len2));
    }

    /** 与桌宠一致的整数倍最近邻放大，让变形验收和屏幕上看到的是同一尺寸。 */
    private static BufferedImage scale2x(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth() * SCALE, src.getHeight() * SCALE,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
    }

    /**
     * 网格变形验收图：第一行是弯曲结果，第二行叠上网格线（看网格怎么弯、有没有露缺口）。
     * 四列的角度依次加大，红点是折痕所在的关节枢轴。
     */
    private static BufferedImage meshCheckSheet(BufferedImage sprite) {
        double[] angles = {0, 15, 30, 45};
        int tileW = 260;
        int tileH = 300;
        BufferedImage sheet = new BufferedImage(tileW * angles.length, tileH * 2,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(0xF2F5FA));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

        for (int i = 0; i < angles.length; i++) {
            for (int row = 0; row < 2; row++) {
                int tileX = i * tileW;
                int tileY = row * tileH;
                double offX = tileX + (tileW - sprite.getWidth()) / 2.0;
                double offY = tileY + (tileH - sprite.getHeight()) / 2.0;

                MeshWarp warp = new MeshWarp(sprite, BENCH_GRIDS[0][0], BENCH_GRIDS[0][1]);
                warp.bendAround(BEND_PIVOT_X, BEND_PIVOT_Y, BEND_FROM_Y, BEND_TO_Y, angles[i]);
                warp.draw(g, offX, offY);
                if (row == 1) {
                    warp.drawGrid(g, offX, offY, new Color(40, 90, 190, 110));
                } else {
                    g.setColor(new Color(255, 60, 60));
                    g.fillOval((int) (offX + BEND_PIVOT_X) - 3, (int) (offY + BEND_PIVOT_Y) - 3, 6, 6);
                }
            }
        }
        g.dispose();
        return sheet;
    }

    /**
     * 逐帧耗时：求值（顶点位移）与绘制（网格贴图）分开计时，并和"整图直贴"对照，
     * 看 105×123 母图、2 倍显示下能不能留在 120 Hz 的每帧预算里。
     */
    private static void meshBench(BufferedImage sprite) {
        int frames = 240;
        int warmup = 60;
        BufferedImage buf = new BufferedImage(340, 380, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buf.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        double offX = 65;
        double offY = 67;

        for (int[] grid : BENCH_GRIDS) {
            MeshWarp warp = new MeshWarp(sprite, grid[0], grid[1]);
            long[] eval = new long[frames - warmup];
            long[] paint = new long[frames - warmup];
            for (int f = 0; f < frames; f++) {
                clear(g, buf);
                long t0 = System.nanoTime();
                warp.reset();
                warp.bendAround(BEND_PIVOT_X, BEND_PIVOT_Y, BEND_FROM_Y, BEND_TO_Y,
                        30 * Math.sin(f * 0.05));
                long t1 = System.nanoTime();
                warp.draw(g, offX, offY);
                long t2 = System.nanoTime();
                if (f >= warmup) {
                    eval[f - warmup] = t1 - t0;
                    paint[f - warmup] = t2 - t1;
                }
            }
            report("网格 " + grid[0] + "x" + grid[1] + "（三角形 " + warp.triangleCount() + "）",
                    eval, paint);
        }

        long[] rigid = new long[frames - warmup];
        for (int f = 0; f < frames; f++) {
            clear(g, buf);
            long t0 = System.nanoTime();
            g.drawImage(sprite, (int) offX, (int) offY, null);
            long t1 = System.nanoTime();
            if (f >= warmup) {
                rigid[f - warmup] = t1 - t0;
            }
        }
        report("对照：整图直贴（现在桌宠的做法）", null, rigid);
        g.dispose();
    }

    private static void clear(Graphics2D g, BufferedImage buf) {
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, buf.getWidth(), buf.getHeight());
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void report(String label, long[] eval, long[] paint) {
        StringBuilder sb = new StringBuilder(label);
        if (eval != null) {
            sb.append(" | 求值 ").append(stats(eval));
        }
        sb.append(" | 绘制 ").append(stats(paint));
        Log.info("蒙皮耗时 " + sb);
    }

    private static String stats(long[] ns) {
        long[] copy = ns.clone();
        java.util.Arrays.sort(copy);
        double avg = 0;
        for (long v : copy) {
            avg += v;
        }
        avg /= copy.length;
        return String.format("平均 %.3f ms P95 %.3f ms 最大 %.3f ms",
                avg / 1e6, copy[(int) (copy.length * 0.95)] / 1e6, copy[copy.length - 1] / 1e6);
    }

    private static void drawTile(Graphics2D g, BodyRig rig, int tileX, double tilt,
                                 double staffAngle, double lieAngle) {
        int groundX = tileX + TILE_W / 2;
        int groundY = TILE_H - 30;
        int spriteW = rig.width() * SCALE;
        int spriteH = rig.height() * SCALE;

        g.setColor(new Color(0xC9D3E4));
        g.fillRect(tileX, groundY, TILE_W, 2);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(groundX, groundY);
        g2.rotate(Math.toRadians(tilt + lieAngle));

        // 腿
        for (int i = 0; i < 2; i++) {
            Point origin = rig.legOrigin(i);
            double lx = (origin.x - rig.width() / 2.0) * SCALE;
            double ly = (origin.y - rig.height()) * SCALE;
            double px = (rig.hip(i).x - rig.width() / 2.0) * SCALE;
            double py = (rig.hip(i).y - rig.height()) * SCALE;
            Graphics2D g3 = (Graphics2D) g2.create();
            g3.rotate(Math.toRadians(i == 0 ? 8 : -8), px, py);
            g3.drawImage(rig.leg(i), (int) lx, (int) ly,
                    rig.leg(i).getWidth() * SCALE, rig.leg(i).getHeight() * SCALE, null);
            g3.dispose();
        }

        // 躯干
        g2.drawImage(rig.body(), -spriteW / 2, -spriteH, spriteW, spriteH, null);

        // 提灯
        Point origin = rig.staffOrigin();
        double lx = (origin.x - rig.width() / 2.0) * SCALE;
        double ly = (origin.y - rig.height()) * SCALE;
        double px = (rig.grip().x - rig.width() / 2.0) * SCALE;
        double py = (rig.grip().y - rig.height()) * SCALE;
        Graphics2D g4 = (Graphics2D) g2.create();
        g4.rotate(Math.toRadians(staffAngle), px, py);
        g4.drawImage(rig.staff(), (int) lx, (int) ly,
                rig.staff().getWidth() * SCALE, rig.staff().getHeight() * SCALE, null);
        g4.dispose();

        // 枢轴标记（红点＝握点，蓝点＝髋部）
        g2.setColor(new Color(255, 60, 60));
        g2.fillOval((int) px - 3, (int) py - 3, 6, 6);
        g2.setColor(new Color(60, 120, 255));
        g2.fillOval((int) ((rig.hip(0).x - rig.width() / 2.0) * SCALE) - 3,
                (int) ((rig.hip(0).y - rig.height()) * SCALE) - 3, 6, 6);
        g2.dispose();
    }

    /** 躺下姿态：地面点靠右，身体向左伸出 */
    private static void drawLying(Graphics2D g, BodyRig rig, int tileX, int tileWidth) {
        int groundX = tileX + tileWidth - 150;
        int groundY = TILE_H - 30;
        int spriteW = rig.width() * SCALE;
        int spriteH = rig.height() * SCALE;

        g.setColor(new Color(0xC9D3E4));
        g.fillRect(tileX, groundY, tileWidth, 2);
        g.setColor(new Color(20, 25, 45, 62));
        g.fill(new java.awt.geom.Ellipse2D.Double(groundX - spriteH, groundY - 6, spriteH * 1.2, 12));

        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(groundX, groundY);
        g2.rotate(Math.toRadians(72));

        for (int i = 0; i < 2; i++) {
            Point origin = rig.legOrigin(i);
            g2.drawImage(rig.leg(i),
                    (int) ((origin.x - rig.width() / 2.0) * SCALE),
                    (int) ((origin.y - rig.height()) * SCALE),
                    rig.leg(i).getWidth() * SCALE, rig.leg(i).getHeight() * SCALE, null);
        }
        g2.drawImage(rig.body(), -spriteW / 2, -spriteH, spriteW, spriteH, null);

        Point origin = rig.staffOrigin();
        double px = (rig.grip().x - rig.width() / 2.0) * SCALE;
        double py = (rig.grip().y - rig.height()) * SCALE;
        Graphics2D g4 = (Graphics2D) g2.create();
        g4.rotate(Math.toRadians(-20), px, py);
        g4.drawImage(rig.staff(), (int) ((origin.x - rig.width() / 2.0) * SCALE),
                (int) ((origin.y - rig.height()) * SCALE),
                rig.staff().getWidth() * SCALE, rig.staff().getHeight() * SCALE, null);
        g4.dispose();
        g2.dispose();
    }
}
