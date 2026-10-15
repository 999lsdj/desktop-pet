package pet;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
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
