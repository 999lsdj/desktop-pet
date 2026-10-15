package pet;

/**
 * 链式柔性：Verlet 积分 ＋ 距离约束。
 *
 * 每帧先把节点按惯性推进并施加重力，再把根节点钉回挂点，然后从根开始
 * 逐节把相邻节点的距离拉回杆长（杆长严格保持），最后按每节的限位角夹一次方向，
 * 免得发丝翻到头顶上去。惯性来自 Verlet 的"上一帧位置"，所以整条链天然滞后于挂点，
 * 越靠末端滞后越大——头发、袖子、袍摆要的就是这个。
 *
 * 稳定性由构造保证：没有固有频率，也就不存在等长单摆串联那种逐级共振。
 * 角度约定：竖直向下为 90 度，与骨架、拆件那一套一致。
 */
public final class VerletChain {

    private final int nodeCount;
    private final double segLen;
    private final double maxAngleDeg;
    /** 每秒保留的速度比例的对数（1/秒），越大停得越快 */
    private double damping;
    private final double[] x;
    private final double[] y;
    private final double[] prevX;
    private final double[] prevY;
    private double anchorX;
    private double anchorY;
    private double lastDt = 1.0 / 120;
    private boolean primed;

    /**
     * @param nodeCount   节点数（含钉住的根节点，至少 2）
     * @param segLen      每节杆长（原生像素）
     * @param maxAngleDeg 每个关节相对上一节能弯的最大角度
     * @param damping     阻尼（1/秒），越大停得越快
     */
    public VerletChain(int nodeCount, double segLen, double maxAngleDeg, double damping) {
        if (nodeCount < 2) {
            throw new IllegalArgumentException("链至少要有两个节点");
        }
        if (segLen <= 0 || damping < 0 || maxAngleDeg <= 0) {
            throw new IllegalArgumentException("杆长、阻尼、限位角都要是正数");
        }
        this.nodeCount = nodeCount;
        this.segLen = segLen;
        this.maxAngleDeg = maxAngleDeg;
        this.damping = damping;
        x = new double[nodeCount];
        y = new double[nodeCount];
        prevX = new double[nodeCount];
        prevY = new double[nodeCount];
    }

    /** 挂点当前位置（走路时就是手臂末端或头顶）。 */
    public void setAnchor(double x, double y) {
        this.anchorX = x;
        this.anchorY = y;
    }

    /** 调阻尼（1/秒）。参数来自配置，可以在运行中改。 */
    public void setDamping(double damping) {
        if (damping < 0) {
            throw new IllegalArgumentException("阻尼不能是负数");
        }
        this.damping = damping;
    }

    /**
     * 推一把：把上一帧位置往回挪，等于给整条链一个瞬时速度（像素/帧）。
     * 走路起步、蒙皮抖动这类冲击用得上。
     */
    public void kick(double dxPerFrame, double dyPerFrame) {
        if (!primed) {
            return;
        }
        for (int i = 1; i < nodeCount; i++) {
            prevX[i] -= dxPerFrame;
            prevY[i] -= dyPerFrame;
        }
    }

    /** 推进一帧。第一帧只负责把链竖直挂在挂点上，建立速度基准。 */
    public void update(double dt, double gravityPx) {
        lastDt = dt;
        if (!primed) {
            for (int i = 0; i < nodeCount; i++) {
                x[i] = anchorX;
                y[i] = anchorY + i * segLen;
                prevX[i] = x[i];
                prevY[i] = y[i];
            }
            primed = true;
            return;
        }

        double keep = Math.exp(-damping * dt);
        double dt2 = dt * dt;
        for (int i = 1; i < nodeCount; i++) {
            double vx = (x[i] - prevX[i]) * keep;
            double vy = (y[i] - prevY[i]) * keep;
            prevX[i] = x[i];
            prevY[i] = y[i];
            x[i] += vx;
            y[i] += vy + gravityPx * dt2;
        }
        constrain();
    }

    /** 根节点钉在挂点上，其余节点依次被拉到上一节的杆长处，并受关节限位约束。 */
    private void constrain() {
        x[0] = anchorX;
        y[0] = anchorY;
        double prevAngleDeg = 90;
        for (int i = 1; i < nodeCount; i++) {
            double dx = x[i] - x[i - 1];
            double dy = y[i] - y[i - 1];
            double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
            if (!Double.isFinite(angleDeg)) {
                angleDeg = 90;
            }
            if (i > 1) {
                double relative = normalizeDeg(angleDeg - prevAngleDeg);
                relative = Math.max(-maxAngleDeg, Math.min(maxAngleDeg, relative));
                angleDeg = prevAngleDeg + relative;
            }
            double rad = Math.toRadians(angleDeg);
            x[i] = x[i - 1] + segLen * Math.cos(rad);
            y[i] = y[i - 1] + segLen * Math.sin(rad);
            prevAngleDeg = angleDeg;
        }
    }

    private static double normalizeDeg(double deg) {
        double d = deg % 360;
        if (d > 180) {
            d -= 360;
        }
        if (d < -180) {
            d += 360;
        }
        return d;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public double nodeX(int i) {
        return x[i];
    }

    public double nodeY(int i) {
        return y[i];
    }

    /** 第 i 节相对上一节弯了多少度（第 1 节相对竖直向下）。 */
    public double jointAngleDeg(int i) {
        double angle = Math.toDegrees(Math.atan2(y[i] - y[i - 1], x[i] - x[i - 1]));
        double base = i == 1
                ? 90
                : Math.toDegrees(Math.atan2(y[i - 1] - y[i - 2], x[i - 1] - x[i - 2]));
        return normalizeDeg(angle - base);
    }

    /** 第 i 节在世界里的方向（度）。 */
    public double worldAngleDeg(int i) {
        return Math.toDegrees(Math.atan2(y[i] - y[i - 1], x[i] - x[i - 1]));
    }

    /** 第 i 节当前长度，自检用来确认杆长没被拉长。 */
    public double segmentLength(int i) {
        return Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
    }

    public double segLen() {
        return segLen;
    }

    public double lastDt() {
        return lastDt;
    }
}
