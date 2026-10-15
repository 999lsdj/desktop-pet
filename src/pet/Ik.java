package pet;

/**
 * 双骨 IK：给定根点、两段的长度、目标点，直接解析求出两段的世界方向角。
 *
 * 目标点超出可伸范围时夹到最长伸展（不抛异常，也不抖），这样拖着走的时候
 * 手会自然伸直而不是失控；夹过之后返回的 reached 为 false，方便调用方判断。
 */
public final class Ik {

    public static final class Solution {
        public final double firstWorldDeg;
        public final double secondWorldDeg;
        public final boolean reached;

        Solution(double firstWorldDeg, double secondWorldDeg, boolean reached) {
            this.firstWorldDeg = firstWorldDeg;
            this.secondWorldDeg = secondWorldDeg;
            this.reached = reached;
        }
    }

    private Ik() {
    }

    /**
     * @param bendPositive true 时肘/膝往一侧弯，false 往另一侧；同一套骨长配不同方向会得到镜像姿势
     */
    public static Solution solve(double rootX, double rootY, double len1, double len2,
                                 double targetX, double targetY, boolean bendPositive) {
        double dx = targetX - rootX;
        double dy = targetY - rootY;
        double distance = Math.hypot(dx, dy);
        double maxReach = len1 + len2;
        double minReach = Math.abs(len1 - len2);
        boolean reached = distance <= maxReach && distance >= minReach;
        double d = Math.min(Math.max(distance, minReach + 1e-6), maxReach - 1e-6);

        double baseAngle = Math.atan2(dy, dx);
        double cosAlpha = (len1 * len1 + d * d - len2 * len2) / (2 * len1 * d);
        double alpha = Math.acos(Math.min(1, Math.max(-1, cosAlpha)));
        double first = baseAngle + (bendPositive ? -alpha : alpha);
        double elbowX = rootX + len1 * Math.cos(first);
        double elbowY = rootY + len1 * Math.sin(first);
        double second = Math.atan2(targetY - elbowY, targetX - elbowX);
        return new Solution(Math.toDegrees(first), Math.toDegrees(second), reached);
    }
}
