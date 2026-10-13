package pet;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.Path;

/**
 * 把一张整图拆成可动部件：躯干、提灯（杖＋灯笼）、左小腿、右小腿。
 *
 * 拆分依据是在 105×123 的原生精灵图上按坐标量出来的矩形区域；
 * 部件之间的重叠（例如握杖的手）从躯干层挖掉，跟着部件一起动，
 * 所以看起来就是"手握着杖在摆动"。
 */
public final class BodyRig {

    /** 提灯（含杖与手）在原生图中的区域与握点 */
    private static final int STAFF_X = 0;
    private static final int STAFF_Y = 5;
    private static final int STAFF_W = 33;
    private static final int STAFF_H = 118;
    private static final double GRIP_X = 19;
    private static final double GRIP_Y = 66;

    /** 两条小腿的区域与髋部枢轴 */
    private static final int LEG_Y = 88;
    private static final int LEG_H = 35;
    private static final int[] LEG_X = {33, 51};
    private static final int[] LEG_W = {21, 22};
    private static final double[] LEG_PIVOT_X = {44, 61};
    private static final double HIP_Y = 89;

    private final BufferedImage body;
    private final BufferedImage staff;
    private final BufferedImage[] legs;
    private final BufferedImage original;
    private final int width;
    private final int height;

    private BodyRig(BufferedImage body, BufferedImage staff, BufferedImage[] legs,
                    BufferedImage original, int width, int height) {
        this.body = body;
        this.staff = staff;
        this.legs = legs;
        this.original = original;
        this.width = width;
        this.height = height;
    }

    public static BodyRig load(Path png) throws IOException {
        BufferedImage src = ImageIO.read(png.toFile());
        if (src == null) {
            throw new IOException("无法读取精灵图: " + png);
        }
        int w = src.getWidth();
        int h = src.getHeight();

        BufferedImage staff = cut(src, STAFF_X, STAFF_Y, STAFF_W, STAFF_H);
        BufferedImage[] legs = new BufferedImage[LEG_X.length];
        for (int i = 0; i < LEG_X.length; i++) {
            legs[i] = cut(src, LEG_X[i], LEG_Y, LEG_W[i], LEG_H);
        }

        BufferedImage body = copy(src);
        erase(body, STAFF_X, STAFF_Y, STAFF_W, STAFF_H);
        for (int i = 0; i < LEG_X.length; i++) {
            erase(body, LEG_X[i], LEG_Y, LEG_W[i], LEG_H);
        }
        Log.info("拆件完成: 原生 " + w + "x" + h
                + " | 杖区=" + STAFF_W + "x" + STAFF_H + " 握点=(" + (int) GRIP_X + "," + (int) GRIP_Y + ")"
                + " | 腿区=" + LEG_W[0] + "," + LEG_W[1]);
        return new BodyRig(body, staff, legs, copy(src), w, h);
    }

    /** 未拆分的整图，用来生成窗口裁剪形状（必须包含杖和腿，否则它们会被裁掉）。 */
    public BufferedImage original() {
        return original;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage cut(BufferedImage src, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, w, h, x, y, x + w, y + h, null);
        g.dispose();
        return out;
    }

    private static void erase(BufferedImage img, int x, int y, int w, int h) {
        for (int yy = y; yy < y + h && yy < img.getHeight(); yy++) {
            for (int xx = x; xx < x + w && xx < img.getWidth(); xx++) {
                img.setRGB(xx, yy, 0);
            }
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public BufferedImage body() {
        return body;
    }

    public BufferedImage staff() {
        return staff;
    }

    public BufferedImage leg(int index) {
        return legs[index];
    }

    /** 提灯部件握点在原生坐标里的位置 */
    public Point.Double grip() {
        return new Point.Double(GRIP_X, GRIP_Y);
    }

    /** 第 index 条腿的髋部枢轴（原生坐标） */
    public Point.Double hip(int index) {
        return new Point.Double(LEG_PIVOT_X[index], HIP_Y);
    }

    /** 部件在原生坐标里的左上角，绘制时需要用它做偏移 */
    public Point staffOrigin() {
        return new Point(STAFF_X, STAFF_Y);
    }

    public Point legOrigin(int index) {
        return new Point(LEG_X[index], LEG_Y);
    }
}
