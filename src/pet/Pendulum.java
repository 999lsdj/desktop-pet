package pet;

/**
 * 提着灯的受迫单摆：重力给回复力，手的水平加速度耦合进来，阻尼让它最终停下。
 * 这是"提灯物理"的核心，参数少、不会失控。
 */
public final class Pendulum {

    private final double maxAngleRad;
    private final double lengthPx;
    private double damping;

    private double angle;
    private double angularVelocity;

    public Pendulum(double maxAngleDeg, double lengthPx, double damping) {
        this.maxAngleRad = Math.toRadians(maxAngleDeg);
        this.lengthPx = Math.max(1.0, lengthPx);
        this.damping = damping;
    }

    public void setDamping(double damping) {
        this.damping = damping;
    }

    /** 每帧推进；handAccelPx 是握点在本帧的水平加速度（像素/秒平方）。 */
    public void update(double dt, double handAccelPx, double gravity) {
        double clampedAccel = Math.max(-4000, Math.min(4000, handAccelPx));
        // 单摆方程：θ'' = −(g/L)·sinθ − c·θ' − (aₓ/L)·cosθ
        double angularAccel = -(gravity / lengthPx) * Math.sin(angle)
                - damping * angularVelocity
                - (clampedAccel / lengthPx) * Math.cos(angle);
        angularVelocity += angularAccel * dt;
        angle += angularVelocity * dt;

        if (angle > maxAngleRad) {
            angle = maxAngleRad;
            angularVelocity *= -0.35;
        } else if (angle < -maxAngleRad) {
            angle = -maxAngleRad;
            angularVelocity *= -0.35;
        }
    }

    /** 被人推了一把（例如开始走路、踉跄时）。 */
    public void kick(double delta) {
        angularVelocity += delta;
    }

    public double angle() {
        return angle;
    }

    public double velocity() {
        return angularVelocity;
    }
}
