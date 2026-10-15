package pet;

import java.awt.geom.AffineTransform;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 线性混合蒙皮：按"顶点离哪根骨头近"分权重，再用每根骨头「静止 → 当前」的变换把顶点搬过去。
 *
 * 权重只在构造时算一次（静止姿态是固定的），每帧只做加权矩阵运算，所以帧内开销很小。
 * 离所有骨头都超出 falloffPx 的顶点退化成"跟着最近的骨头刚性走"，
 * 免得出现没有任何骨头管的游离顶点。
 */
public final class Skin {

    /** 权重小于这个值的参与项直接丢掉，省下每帧的乘法 */
    private static final double WEIGHT_EPS = 0.02;

    private final Skeleton skeleton;
    private final BufferedImage image;
    private final MeshWarp mesh;
    private final String[] boneNames;
    private final AffineTransform[] transfer;
    private final AffineTransform[] restInverse;
    private final double[] srcX;
    private final double[] srcY;
    private final double originX;
    private final double originY;
    private final int[][] vertexBones;
    private final double[][] vertexWeights;
    private final boolean jointChain;
    private final Point2D.Double[] jointPivots;
    private double lastUpdateMs;

    public Skin(BufferedImage image, Skeleton skeleton, int cols, int rows, double falloffPx) {
        this(image, skeleton, cols, rows, falloffPx, skeleton.names(), 0, 0);
    }

    /** Bind a cropped layer only to its material's bones, in shared native coordinates. */
    public Skin(BufferedImage image, Skeleton skeleton, int cols, int rows, double falloffPx,
                List<String> allowedBones, double originX, double originY) {
        this(image, skeleton, cols, rows, falloffPx, allowedBones, originX, originY, false);
    }

    public Skin(BufferedImage image, Skeleton skeleton, int cols, int rows, double falloffPx,
                List<String> allowedBones, double originX, double originY, boolean jointChain) {
        if (allowedBones.isEmpty() || allowedBones.stream().distinct().count() != allowedBones.size()) {
            throw new IllegalArgumentException("Allowed bones must be nonempty and unique");
        }
        if (!(falloffPx > 0) || !Double.isFinite(falloffPx) || cols < 1 || rows < 1) {
            throw new IllegalArgumentException("Invalid skin grid or falloff");
        }
        this.skeleton = skeleton;
        this.jointChain = jointChain;
        this.image = image;
        this.originX = originX;
        this.originY = originY;
        this.mesh = new MeshWarp(image, cols, rows);
        int n = mesh.vertexCount();
        srcX = new double[n];
        srcY = new double[n];
        for (int i = 0; i < n; i++) {
            srcX[i] = mesh.srcX(i) + originX;
            srcY[i] = mesh.srcY(i) + originY;
        }
        boneNames = allowedBones.toArray(new String[0]);
        transfer = new AffineTransform[boneNames.length];
        restInverse = new AffineTransform[boneNames.length];
        for (int b = 0; b < boneNames.length; b++) {
            transfer[b] = new AffineTransform();
            try {
                restInverse[b] = skeleton.bone(boneNames[b]).restWorld().createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                throw new IllegalStateException("静止变换不可逆: " + boneNames[b], e);
            }
        }
        vertexBones = new int[n][];
        vertexWeights = new double[n][];
        jointPivots = new Point2D.Double[n];
        if (jointChain) assignJointWeights();
        else assignWeights(falloffPx);
    }

    /** 腿部只在相邻关节附近混合，防止短小腿把脚踝的影响传回膝盖。 */
    private void assignJointWeights() {
        for (int b = 1; b < boneNames.length; b++) {
            if (!boneNames[b - 1].equals(skeleton.bone(boneNames[b]).parent)
                    || restTip(b - 1).distance(restStart(b)) > 1e-8)
                throw new IllegalArgumentException("Joint skin requires a connected, ordered bone chain");
        }
        for (String name : boneNames) {
            if (!(skeleton.bone(name).lengthPx > 0))
                throw new IllegalArgumentException("Joint skin requires positive bone lengths");
        }
        for (int i = 0; i < srcX.length; i++) {
            int closest = 0;
            double best = Double.POSITIVE_INFINITY;
            for (int b = 0; b < boneNames.length; b++) {
                Point2D.Double a = restStart(b), z = restTip(b);
                double distance = distanceToSegment(srcX[i], srcY[i], a.x, a.y, z.x, z.y);
                if (distance < best) { best = distance; closest = b; }
            }
            vertexBones[i] = new int[]{closest};
            vertexWeights[i] = new double[]{1};
            for (int b = Math.max(1, closest); b <= Math.min(boneNames.length - 1, closest + 1); b++) {
                Point2D.Double a = restStart(b - 1), pivot = restStart(b), z = restTip(b);
                double before = a.distance(pivot), after = pivot.distance(z);
                double tx = (pivot.x - a.x) / before + (z.x - pivot.x) / after;
                double ty = (pivot.y - a.y) / before + (z.y - pivot.y) / after;
                double length = Math.hypot(tx, ty);
                if (length < 1e-8) throw new IllegalArgumentException("Joint skin cannot bind a folded rest chain");
                double along = ((srcX[i] - pivot.x) * tx + (srcY[i] - pivot.y) * ty) / length;
                double radius = Math.min(4, Math.min(before, after) * 0.45);
                if (Math.abs(along) >= radius) continue;
                double blend = (along + radius) / (2 * radius);
                blend = blend * blend * (3 - 2 * blend);
                vertexBones[i] = new int[]{b - 1, b};
                vertexWeights[i] = new double[]{1 - blend, blend};
                jointPivots[i] = pivot;
                break;
            }
        }
    }

    private void assignWeights(double falloffPx) {
        for (int i = 0; i < srcX.length; i++) {
            List<Integer> ids = new ArrayList<>();
            List<Double> ws = new ArrayList<>();
            double best = Double.MAX_VALUE;
            int bestBone = 0;
            double sum = 0;
            for (int b = 0; b < boneNames.length; b++) {
                Point2D.Double a = restStart(b);
                Point2D.Double t = restTip(b);
                double d = distanceToSegment(srcX[i], srcY[i], a.x, a.y, t.x, t.y);
                if (d < best) {
                    best = d;
                    bestBone = b;
                }
                double f = 1 - Math.min(1, d / falloffPx);
                double w = f * f;
                if (w > 0) {
                    ids.add(b);
                    ws.add(w);
                    sum += w;
                }
            }
            if (sum <= 0) {
                // 离任何骨头都太远：退化成跟着最近的骨头刚性走
                vertexBones[i] = new int[]{bestBone};
                vertexWeights[i] = new double[]{1};
                continue;
            }
            int kept = 0;
            for (double w : ws) {
                if (w / sum >= WEIGHT_EPS) {
                    kept++;
                }
            }
            int[] ids2 = new int[kept];
            double[] ws2 = new double[kept];
            int k = 0;
            double sum2 = 0;
            for (int j = 0; j < ids.size(); j++) {
                double w = ws.get(j) / sum;
                if (w >= WEIGHT_EPS) {
                    ids2[k] = ids.get(j);
                    ws2[k] = w;
                    sum2 += w;
                    k++;
                }
            }
            for (int j = 0; j < kept; j++) {
                ws2[j] /= sum2;
            }
            vertexBones[i] = ids2;
            vertexWeights[i] = ws2;
        }
    }

    private Point2D.Double restStart(int boneIndex) {
        return transform(skeleton.bone(boneNames[boneIndex]).restWorld(), 0);
    }

    private Point2D.Double restTip(int boneIndex) {
        Skeleton.Bone b = skeleton.bone(boneNames[boneIndex]);
        return transform(b.restWorld(), b.lengthPx);
    }

    private static Point2D.Double transform(AffineTransform t, double localX) {
        Point2D.Double p = new Point2D.Double();
        t.transform(new Point2D.Double(localX, 0), p);
        return p;
    }

    private static double distanceToSegment(double px, double py,
                                            double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.min(1, Math.max(0, t));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** 按当前姿态重算所有顶点的位置。每帧调用一次。 */
    public void update() {
        long t0 = System.nanoTime();
        for (int b = 0; b < boneNames.length; b++) {
            transfer[b].setTransform(skeleton.bone(boneNames[b]).world());
            transfer[b].concatenate(restInverse[b]);
        }
        for (int i = 0; i < srcX.length; i++) {
            double x = srcX[i];
            double y = srcY[i];
            double sx = 0;
            double sy = 0;
            int[] bones = vertexBones[i];
            double[] weights = vertexWeights[i];
            if (jointChain && bones.length == 2) {
                // 围绕共同枢轴插值旋转角，不把两个旋转矩阵相加而压瘪膝盖。
                AffineTransform a = transfer[bones[0]], b = transfer[bones[1]];
                double angle = Math.atan2(a.getShearY(), a.getScaleX());
                double delta = Math.atan2(b.getShearY(), b.getScaleX()) - angle;
                angle += weights[1] * Math.atan2(Math.sin(delta), Math.cos(delta));
                Point2D.Double pivot = jointPivots[i];
                double px = a.getScaleX() * pivot.x + a.getShearX() * pivot.y + a.getTranslateX();
                double py = a.getShearY() * pivot.x + a.getScaleY() * pivot.y + a.getTranslateY();
                double cos = Math.cos(angle), sin = Math.sin(angle);
                mesh.setVertex(i, px + cos * (x - pivot.x) - sin * (y - pivot.y) - originX,
                        py + sin * (x - pivot.x) + cos * (y - pivot.y) - originY);
                continue;
            }
            for (int k = 0; k < bones.length; k++) {
                AffineTransform t = transfer[bones[k]];
                double w = weights[k];
                sx += w * (t.getScaleX() * x + t.getShearX() * y + t.getTranslateX());
                sy += w * (t.getShearY() * x + t.getScaleY() * y + t.getTranslateY());
            }
            mesh.setVertex(i, sx - originX, sy - originY);
        }
        lastUpdateMs = (System.nanoTime() - t0) / 1e6;
    }

    public MeshWarp mesh() {
        return mesh;
    }

    /** Single-bone materials are exactly rigid; one image transform avoids needless triangle clips. */
    public void draw(Graphics2D g, double offsetX, double offsetY) {
        if (boneNames.length == 1) {
            AffineTransform placement = AffineTransform.getTranslateInstance(offsetX - originX, offsetY - originY);
            placement.concatenate(transfer[0]);
            placement.translate(originX, originY);
            g.drawImage(image, placement, null);
        } else {
            mesh.draw(g, offsetX, offsetY);
        }
    }

    public double lastUpdateMs() {
        return lastUpdateMs;
    }

    /** 参与蒙皮的顶点数，用来判断权重表有没有漏掉顶点。 */
    public int skinnedVertexCount() {
        return vertexBones.length;
    }
}
