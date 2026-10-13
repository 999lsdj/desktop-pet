package pet;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** 精灵图：按整数倍最近邻放大，并生成 alpha 轮廓用于窗口裁剪。 */
public final class Sprite {

    private final BufferedImage image;
    private final int scale;

    private Sprite(BufferedImage image, int scale) {
        this.image = image;
        this.scale = scale;
    }

    public static Sprite load(Path png, int scale) throws IOException {
        BufferedImage src = ImageIO.read(png.toFile());
        if (src == null) {
            throw new IOException("无法读取精灵图: " + png);
        }
        int w = src.getWidth() * scale;
        int h = src.getHeight() * scale;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return new Sprite(out, scale);
    }

    public BufferedImage image() {
        return image;
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }

    public int scale() {
        return scale;
    }

    /**
     * 按 alpha 生成角色剪影。窗口用这个形状裁剪后，角色以外的区域既不绘制也不挡鼠标。
     * 逐行取连续不透明段，避免逐像素构造 Area 带来的开销。
     */
    public Shape silhouette(int offsetX, int offsetY, int alphaThreshold) {
        Area area = new Area();
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            int runStart = -1;
            for (int x = 0; x < w; x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                boolean solid = alpha > alphaThreshold;
                if (solid && runStart < 0) {
                    runStart = x;
                }
                if (runStart >= 0 && (!solid || x == w - 1)) {
                    int end = solid ? x : x - 1;
                    area.add(new Area(new Rectangle(
                            offsetX + runStart, offsetY + y, end - runStart + 1, 1)));
                    runStart = -1;
                }
            }
        }
        return area;
    }
}
