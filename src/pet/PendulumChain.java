package pet;

/**
 * 链式单摆：把若干节 {@link Pendulum} 串起来，每节的驱动量取它挂点的水平加速度。
 *
 * 第 0 节的挂点由调用方每帧给出（走路时就是握点、头顶这类会晃的位置），
 * 第 i 节的挂点就是第 i-1 节的末端，所以晃动一节节往下传，越靠末端滞后越大——
 * 头发、袖子、袍摆要的就是这个效果。
 *
 * 角度约定：每节自己的角度相对上一节累加；竖直向下为 90 度，正值朝屏幕下方右侧。
 */
public final class PendulumChain {

    private final Pendulum[] links;
    private final double[] lengthPx;
    private final double[] rootX;
    private final double[] rootY;
    private final double[] rootVX;
    /** 平滑后的挂点加速度：原始差分值太尖，直接用会把下一节抽到限位 */
    private final double[] rootAX;
    private final double[] tipX;
    private final double[] tipY;
    private final double driveGain;
    private final double accelSmooth;
    private double anchorX;
    private double anchorY;
    private boolean primed;

    /**
     * @param linkCount     节数
     * @param maxAngleDeg   每节的单摆限位角
     * @param linkLengthPx  每节长度（原生像素）
     * @param damping       阻尼，越大停得越快
     * @param driveGain     挂点加速度接到单摆上的增益
     * @param accelSmooth   加速度低通系数（0～1，越小越平滑）。挂点加速度是用位置差分
     *                      算出来的，单摆撞限位反弹时速度会突变，不平滑的话下一节会被
     *                      瞬时尖峰甩到限位，整条链永远停不下来。
     */
    public PendulumChain(int linkCount, double maxAngleDeg, double linkLengthPx,
                         double damping, double driveGain, double accelSmooth) {
        if (linkCount < 1) {
            throw new IllegalArgumentException("链至少要有一节");
        }
        if (accelSmooth <= 0 || accelSmooth > 1) {
            throw new IllegalArgumentException("低通系数要在 0～1 之间");
        }
        links = new Pendulum[linkCount];
        lengthPx = new double[linkCount];
        rootX = new double[linkCount];
        rootY = new double[linkCount];
        rootVX = new double[linkCount];
        rootAX = new double[linkCount];
        tipX = new double[linkCount];
        tipY = new double[linkCount];
        for (int i = 0; i < linkCount; i++) {
            links[i] = new Pendulum(maxAngleDeg, linkLengthPx, damping);
            lengthPx[i] = linkLengthPx;
        }
        this.driveGain = driveGain;
        this.accelSmooth = accelSmooth;
    }

    /** 挂点当前位置。走路时就是手臂末端或头顶。 */
    public void setAnchor(double x, double y) {
        anchorX = x;
        anchorY = y;
    }

    public void kick(double delta) {
        for (Pendulum link : links) {
            link.kick(delta);
        }
    }

    public void setDamping(double damping) {
        for (Pendulum link : links) {
            link.setDamping(damping);
        }
    }

    /** 推进一帧。挂点位置要先用 {@link #setAnchor} 更新好。 */
    public void update(double dt, double gravity) {
        for (int i = 0; i < links.length; i++) {
            double px = i == 0 ? anchorX : tipX[i - 1];
            double py = i == 0 ? anchorY : tipY[i - 1];
            double vx = primed ? (px - rootX[i]) / dt : 0;
            double rawAx = primed ? (vx - rootVX[i]) / dt : 0;
            rootAX[i] += (rawAx - rootAX[i]) * accelSmooth;
            rootX[i] = px;
            rootY[i] = py;
            rootVX[i] = vx;
            links[i].update(dt, rootAX[i] * driveGain, gravity);
            double rad = Math.toRadians(worldAngleDeg(i));
            tipX[i] = px + lengthPx[i] * Math.cos(rad);
            tipY[i] = py + lengthPx[i] * Math.sin(rad);
        }
        primed = true;
    }

    /** 第 i 节相对上一节的角度（度）。 */
    public double linkAngleDeg(int i) {
        return Math.toDegrees(links[i].angle());
    }

    /** 第 i 节在世界里的方向（度）：竖直向下 90 度，加上从根到本节的累计角度。 */
    public double worldAngleDeg(int i) {
        double total = 90;
        for (int j = 0; j <= i; j++) {
            total += Math.toDegrees(links[j].angle());
        }
        return total;
    }

    public double tipX(int i) {
        return tipX[i];
    }

    public double tipY(int i) {
        return tipY[i];
    }

    public int linkCount() {
        return links.length;
    }
}
