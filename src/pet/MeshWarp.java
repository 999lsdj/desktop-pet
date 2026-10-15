package pet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * 三角网格变形：把整张图切成 cols×rows 个格，每格拆成两个三角形，
 * 逐三角形设裁剪区、用仿射变换把源三角形映射到目标三角形。
 *
 * 顶点位移由调用方给出：现在只提供 {@link #bendAround} 这种沿一条折痕的弯曲，
 * 用来验证"关节能连续弯、不露缺口"；正式蒙皮时由骨骼权重给每个顶点算位移。
 *
 * 绘制期间关闭抗锯齿：相邻三角形共边，开着抗锯齿会在缝上留下半透明细线。
 */
public final class MeshWarp {

    private final BufferedImage image;
    private final int vcols;
    private final int vrows;
    private final double[] srcX;
    private final double[] srcY;
    private final double[] dstX;
    private final double[] dstY;
    private final int[] tris;
    private final Path2D.Double[] triPath;
    private final AffineTransform at = new AffineTransform();

    public MeshWarp(BufferedImage image, int cols, int rows) {
        this.image = image;
        this.vcols = cols + 1;
        this.vrows = rows + 1;
        int n = vcols * vrows;
        srcX = new double[n];
        srcY = new double[n];
        dstX = new double[n];
        dstY = new double[n];
        double cw = image.getWidth() / (double) cols;
        double ch = image.getHeight() / (double) rows;
        for (int vy = 0; vy < vrows; vy++) {
            for (int vx = 0; vx < vcols; vx++) {
                int i = vy * vcols + vx;
                srcX[i] = Math.min(vx * cw, image.getWidth());
                srcY[i] = Math.min(vy * ch, image.getHeight());
            }
        }
        reset();

        int[] active = new int[cols * rows * 6];
        int t = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int v00 = y * vcols + x;
                int v10 = v00 + 1;
                int v01 = v00 + vcols;
                int v11 = v01 + 1;
                if (hasCoverage(srcX[v00], srcY[v00], srcX[v11], srcY[v11], true)) {
                    active[t++] = v00; active[t++] = v10; active[t++] = v11;
                }
                if (hasCoverage(srcX[v00], srcY[v00], srcX[v11], srcY[v11], false)) {
                    active[t++] = v00; active[t++] = v11; active[t++] = v01;
                }
            }
        }
        tris = Arrays.copyOf(active, t);
        triPath = new Path2D.Double[t / 3];
        for (int i = 0; i < triPath.length; i++) {
            triPath[i] = new Path2D.Double();
        }
    }

    /**
     * Conservative pixel-square/triangle overlap, with a one-pixel sampling halo.
     * Java2D quantizes the destination clip separately from nearest-neighbor sampling;
     * a triangle can therefore sample just outside its ideal source edges.
     */
    private boolean hasCoverage(double x0, double y0, double x1, double y1, boolean upper) {
        double width = x1 - x0, height = y1 - y0;
        for (int y = Math.max(0, (int) Math.floor(y0) - 1); y < Math.ceil(y1) + 1 && y < image.getHeight(); y++) {
            for (int x = Math.max(0, (int) Math.floor(x0) - 1); x < Math.ceil(x1) + 1 && x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) continue;
                if (upper ? (x + 2 - x0) * height >= (y - 1 - y0) * width
                        : (y + 2 - y0) * width >= (x - 1 - x0) * height) return true;
            }
        }
        return false;
    }

    /** 目标顶点回到与源顶点重合，即不变形。 */
    public void reset() {
        System.arraycopy(srcX, 0, dstX, 0, srcX.length);
        System.arraycopy(srcY, 0, dstY, 0, srcY.length);
    }

    /**
     * 沿一条水平折痕弯曲：折痕以上不动，以下随 y 线性加大旋转角，绕 pivot 旋转。
     * 角度在 fromY 到 toY 之间从 0 线性增到 angleDeg，所以弯的是弧线而不是硬折角。
     */
    public void bendAround(double pivotX, double pivotY, double fromY, double toY,
                           double angleDeg) {
        double span = (toY - fromY) == 0 ? 1 : (toY - fromY);
        for (int i = 0; i < srcX.length; i++) {
            double t = (srcY[i] - fromY) / span;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            double rad = Math.toRadians(angleDeg) * t;
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double x = srcX[i] - pivotX;
            double y = srcY[i] - pivotY;
            dstX[i] = pivotX + x * cos - y * sin;
            dstY[i] = pivotY + x * sin + y * cos;
        }
    }

    public int triangleCount() {
        return tris.length / 3;
    }

    public int vertexCount() {
        return srcX.length;
    }

    /** 顶点在源图里的位置，蒙皮要拿它当静止位置用。 */
    public double srcX(int i) {
        return srcX[i];
    }

    public double srcY(int i) {
        return srcY[i];
    }

    /** 直接指定某个顶点的目标位置，供蒙皮写入。 */
    public void setVertex(int i, double x, double y) {
        dstX[i] = x;
        dstY[i] = y;
    }

    /** 读回顶点当前的目标位置，蒙皮自检和将来的命中判定要用。 */
    public double dstX(int i) {
        return dstX[i];
    }

    public double dstY(int i) {
        return dstY[i];
    }

    /** 把变形后的图按 (offsetX, offsetY) 平移到 g 上。 */
    public void draw(Graphics2D g, double offsetX, double offsetY) {
        g = (Graphics2D) g.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.translate(offsetX, offsetY);
        Shape callerClip = g.getClip();
        for (int t = 0; t < triPath.length; t++) {
            int a = tris[t * 3];
            int b = tris[t * 3 + 1];
            int c = tris[t * 3 + 2];
            if (affine(srcX[a], srcY[a], srcX[b], srcY[b], srcX[c], srcY[c],
                    dstX[a], dstY[a], dstX[b], dstY[b], dstX[c], dstY[c])) {
                Path2D.Double p = triPath[t];
                p.reset();
                p.moveTo(dstX[a], dstY[a]);
                p.lineTo(dstX[b], dstY[b]);
                p.lineTo(dstX[c], dstY[c]);
                p.closePath();
                if (callerClip == null) g.setClip(p);
                else { g.setClip(callerClip); g.clip(p); }
                g.drawImage(image, at, null);
            }
        }
        g.dispose();
    }

    /** 画出网格线，用来核对网格是怎么弯的。 */
    public void drawGrid(Graphics2D g, double offsetX, double offsetY, Color color) {
        g.setColor(color);
        for (int vy = 0; vy < vrows; vy++) {
            for (int vx = 0; vx < vcols; vx++) {
                int i = vy * vcols + vx;
                if (vx + 1 < vcols) {
                    int r = i + 1;
                    g.drawLine((int) Math.round(dstX[i] + offsetX), (int) Math.round(dstY[i] + offsetY),
                            (int) Math.round(dstX[r] + offsetX), (int) Math.round(dstY[r] + offsetY));
                }
                if (vy + 1 < vrows) {
                    int d = i + vcols;
                    g.drawLine((int) Math.round(dstX[i] + offsetX), (int) Math.round(dstY[i] + offsetY),
                            (int) Math.round(dstX[d] + offsetX), (int) Math.round(dstY[d] + offsetY));
                }
            }
        }
    }

    /** 求三个源点到三个目标点的仿射变换；三角形退化时返回 false。 */
    private boolean affine(double x0, double y0, double x1, double y1, double x2, double y2,
                           double u0, double v0, double u1, double v1, double u2, double v2) {
        double det = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        if (Math.abs(det) < 1e-9) {
            return false;
        }
        double a = ((u1 - u0) * (y2 - y0) - (y1 - y0) * (u2 - u0)) / det;
        double c = ((x1 - x0) * (u2 - u0) - (u1 - u0) * (x2 - x0)) / det;
        double b = ((v1 - v0) * (y2 - y0) - (y1 - y0) * (v2 - v0)) / det;
        double d = ((x1 - x0) * (v2 - v0) - (v1 - v0) * (x2 - x0)) / det;
        at.setTransform(a, b, c, d, u0 - a * x0 - c * y0, v0 - b * x0 - d * y0);
        return true;
    }
}
