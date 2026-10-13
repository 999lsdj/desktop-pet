package pet;

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

    public static void main(String[] args) throws Exception {
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
