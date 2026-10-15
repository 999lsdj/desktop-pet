package pet;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 骨树与正运动学。
 *
 * 约定：每根骨头在自己的坐标系里沿 +X 伸出 lengthPx；建骨时给的 (dx, dy) 是这根骨头的起点
 * 相对父骨起点的位移，写在**精灵坐标系**里（和 BodyRig 量出来的坐标同一套），
 * 由 add() 内部换算成父骨局部系，读数时不用在脑子里叠旋转。
 * 世界变换 = 父世界变换 × 平移(offset) × 旋转(restAngle + delta)。
 * 角度用度，正方向与 AffineTransform.rotate 一致（屏幕坐标下顺时针为正）。
 *
 * 所有静止角度都按"世界方向"给，由 {@link #restFor} 换算成相对父骨的角度，
 * 这样读数时不用在脑子里叠父骨的旋转。
 */
public final class Skeleton {

    public static final class Bone {
        public final String name;
        public final String parent;
        public final double offsetX;
        public final double offsetY;
        public final double restAngleDeg;
        public final double lengthPx;
        private double deltaDeg;
        private final AffineTransform world = new AffineTransform();
        /** 静止姿态下的世界变换，蒙皮拿它算"这根骨头把顶点搬了多远"。 */
        private final AffineTransform restWorld = new AffineTransform();

        private Bone(String name, String parent, double offsetX, double offsetY,
                     double restAngleDeg, double lengthPx) {
            this.name = name;
            this.parent = parent;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.restAngleDeg = restAngleDeg;
            this.lengthPx = lengthPx;
        }

        /** 相对静止姿态的追加角度（度），动画就是改这个值。 */
        public double deltaDeg() {
            return deltaDeg;
        }

        public AffineTransform world() {
            return world;
        }

        public AffineTransform restWorld() {
            return restWorld;
        }
    }

    private final Map<String, Bone> bones = new LinkedHashMap<>();
    private final List<Bone> order = new ArrayList<>();

    private Skeleton() {
    }

    /** Empty skeleton for a separately validated, data-driven rig. */
    public static Skeleton empty() {
        return new Skeleton();
    }

    /**
     * 按现有拆件坐标搭出角色的骨架。
     *
     * 髋部、握点、杖底三个关节直接读 BodyRig 的值，保证"骨架与拆件对齐"是结构上的
     * 而不是巧合；肩与肘在现有扁平立绘上量不出来，先按估计值放，等第七组重新拆件后再校正。
     */
    public static Skeleton from(BodyRig rig) {
        Skeleton s = new Skeleton();
        double cx = rig.width() / 2.0;
        double bottom = rig.height();
        double hipLine = rig.hip(0).y;
        double hipNearX = rig.hip(0).x;
        double hipFarX = rig.hip(1).x;
        double gripX = rig.grip().x;
        double gripY = rig.grip().y;
        double footX = rig.staffFoot().x;
        double footY = rig.staffFoot().y;

        // 根：脚底中心，朝上指向髋线
        double rootLen = bottom - hipLine;
        s.add("root", null, cx, bottom, -90, rootLen);
        // 躯干：髋线到画布顶，头再往上留一截当参照
        s.add("torso", "root", 0, -rootLen, -90, hipLine);
        s.add("head", "torso", 0, -hipLine, -90, 24);

        // 两条腿：大腿 + 小腿，髋关节落在拆件用的枢轴上
        double legLen = bottom - hipLine;
        s.add("thighNear", "root", hipNearX - cx, -rootLen, 90, legLen / 2);
        s.add("shinNear", "thighNear", 0, legLen / 2, 90, legLen / 2);
        s.add("thighFar", "root", hipFarX - cx, -rootLen, 90, legLen / 2);
        s.add("shinFar", "thighFar", 0, legLen / 2, 90, legLen / 2);

        // 近侧手臂：肩到肘到握点。肩与肘是估计值，末端必须精确落在握点上。
        double shoulderX = 48;
        double shoulderY = 46;
        double elbowX = 36;
        double elbowY = 60;
        s.add("upperArm", "torso", shoulderX - cx, shoulderY - hipLine,
                angle(shoulderX, shoulderY, elbowX, elbowY), dist(shoulderX, shoulderY, elbowX, elbowY));
        s.add("foreArm", "upperArm", elbowX - shoulderX, elbowY - shoulderY,
                angle(elbowX, elbowY, gripX, gripY), dist(elbowX, elbowY, gripX, gripY));

        // 提灯：挂在握点上，从握点指向杖底
        s.add("staff", "foreArm", gripX - elbowX, gripY - elbowY,
                angle(gripX, gripY, footX, footY), dist(gripX, gripY, footX, footY));

        s.update();
        return s;
    }

    /**
     * 追加一根骨头，供柔性链按配置生成自己的骨头用。
     * 必须在摆姿势之前调用：静止变换是建骨当下快照的。
     */
    public void addBone(String name, String parent, double dx, double dy,
                        double desiredWorldDeg, double lengthPx) {
        add(name, parent, dx, dy, desiredWorldDeg, lengthPx);
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    private static double angle(double x1, double y1, double x2, double y2) {
        return Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
    }

    /**
     * 追加一根骨头。(dx, dy) 是起点相对父骨起点的位移，写在精灵坐标系里；
     * desiredWorldDeg 是静止时的世界方向，这里换算成相对父骨的角度。
     */
    private void add(String name, String parent, double dx, double dy,
                     double desiredWorldDeg, double lengthPx) {
        if (bones.containsKey(name)) {
            throw new IllegalArgumentException("骨头重名: " + name);
        }
        if (parent != null && !bones.containsKey(parent)) {
            throw new IllegalArgumentException("父骨不存在: " + parent);
        }
        double offsetX = dx;
        double offsetY = dy;
        if (parent != null) {
            Bone p = bones.get(parent);
            Point2D.Double parentStart = new Point2D.Double();
            p.world.transform(new Point2D.Double(0, 0), parentStart);
            Point2D.Double desired = new Point2D.Double(parentStart.x + dx, parentStart.y + dy);
            try {
                p.world.createInverse().transform(desired, desired);
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                throw new IllegalStateException("父骨变换不可逆: " + parent, e);
            }
            offsetX = desired.x;
            offsetY = desired.y;
        }
        double rest = restFor(parent, desiredWorldDeg);
        Bone bone = new Bone(name, parent, offsetX, offsetY, rest, lengthPx);
        bones.put(name, bone);
        order.add(bone);
        // 立刻算一次，后面的子骨要读父骨的世界变换
        update();
        // 刚建好的骨头还没有任何 delta，此刻的世界变换就是它的静止变换
        bone.restWorld.setTransform(bone.world);
    }

    /** 把世界方向换算成相对父骨的方向；建骨阶段父骨已经算过世界变换。 */
    private double restFor(String parent, double desiredWorldDeg) {
        if (parent == null) {
            return desiredWorldDeg;
        }
        return desiredWorldDeg - worldAngleDeg(parent);
    }

    /** 重算整棵树。骨头不多（十来根），每帧全算一遍比增量更新省事也更不容易错。 */
    public void update() {
        for (Bone b : order) {
            AffineTransform t = new AffineTransform();
            if (b.parent == null) {
                t.translate(b.offsetX, b.offsetY);
            } else {
                t.concatenate(bones.get(b.parent).world);
                t.translate(b.offsetX, b.offsetY);
            }
            t.rotate(Math.toRadians(b.restAngleDeg + b.deltaDeg));
            b.world.setTransform(t);
        }
    }

    public Bone bone(String name) {
        Bone b = bones.get(name);
        if (b == null) {
            throw new IllegalArgumentException("没有这根骨头: " + name);
        }
        return b;
    }

    public List<String> names() {
        return new ArrayList<>(bones.keySet());
    }

    /** 关节（骨头起点）在当前姿态下的位置。 */
    public Point2D.Double start(String name) {
        return point(name, 0);
    }

    /** 骨头末端在当前姿态下的位置；长度 0 的骨头末端等于起点。 */
    public Point2D.Double tip(String name) {
        return point(name, bone(name).lengthPx);
    }

    private Point2D.Double point(String name, double localX) {
        Bone b = bone(name);
        java.awt.geom.Point2D.Double p = new java.awt.geom.Point2D.Double();
        b.world.transform(new java.awt.geom.Point2D.Double(localX, 0), p);
        return p;
    }

    /** 骨头在屏幕坐标下的方向（度）。 */
    public double worldAngleDeg(String name) {
        Bone b = bone(name);
        double cos = b.world.getScaleX();
        double sin = b.world.getShearY();
        return Math.toDegrees(Math.atan2(sin, cos));
    }

    /** 设置某根骨头相对静止姿态的追加角度。 */
    public void setDelta(String name, double deg) {
        bone(name).deltaDeg = deg;
    }

    /** 把某根骨头摆到指定的世界方向（父骨要先摆好，按树的顺序调用）。 */
    public void aim(String name, double desiredWorldDeg) {
        Bone b = bone(name);
        double parentWorld = b.parent == null ? 0 : worldAngleDeg(b.parent);
        b.deltaDeg = desiredWorldDeg - parentWorld - b.restAngleDeg;
        update();
    }
}
