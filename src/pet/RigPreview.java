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
    }

    /**
     * 链式自检：给挂点一个横向阶跃，看整条链摆起来之后会不会收敛、会不会发散。
     * 判据是"后 1 秒的残余振幅远小于前 1 秒"，以及数值没有变成 NaN、角度没有越过限位。
     */
    private static void chainSelfCheck() {
        int linkCount = 4;
        double dt = 1.0 / 120;
        double gravity = 900;
        double maxAngleDeg = 40;
        PendulumChain chain = new PendulumChain(linkCount, maxAngleDeg, 12, 1.1, 5.0, 0.25);

        // 预热一秒，让有限差分的速度有基准，避免第一帧就把加速度算爆
        chain.setAnchor(0, 0);
        for (int i = 0; i < 120; i++) {
            chain.update(dt, gravity);
        }

        // 阶跃：挂点一帧内横向挪 30 像素，相当于甩了一下
        chain.setAnchor(30, 0);
        chain.update(dt, gravity);

        int frames = (int) Math.round(4 / dt);
        double firstSecond = 0;
        double lastSecond = 0;
        double maxAbs = 0;
        boolean finite = true;
        for (int f = 0; f < frames; f++) {
            chain.update(dt, gravity);
            for (int i = 0; i < linkCount; i++) {
                double a = chain.linkAngleDeg(i);
                if (!Double.isFinite(a)) {
                    finite = false;
                    continue;
                }
                maxAbs = Math.max(maxAbs, Math.abs(a));
                if (f < 120) {
                    firstSecond = Math.max(firstSecond, Math.abs(a));
                }
                if (f >= frames - 120) {
                    lastSecond = Math.max(lastSecond, Math.abs(a));
                }
            }
        }
        double ratio = lastSecond <= 1e-9 ? Double.POSITIVE_INFINITY : firstSecond / lastSecond;
        Log.info(String.format("链式自检 %d 节：阶跃后最大摆角 %.2f 度（限位 %.0f 度）；"
                        + "前 1 秒振幅 %.2f 度、后 1 秒残余 %.4f 度，衰减 %.0f 倍；数值%s",
                linkCount, maxAbs, maxAngleDeg, firstSecond, lastSecond, ratio,
                finite ? "正常" : "出现 NaN/Inf"));
        if (!finite || maxAbs > maxAngleDeg + 1e-6) {
            Log.error("链式自检不通过：数值异常或角度越过限位", null);
        }
        if (lastSecond > firstSecond * 0.2) {
            Log.error("链式自检不通过：4 秒后仍未收敛到 20% 以内", null);
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
