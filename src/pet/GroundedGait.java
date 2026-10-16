package pet;

import java.awt.geom.Point2D;

/** 支撑半周匀速后移、收腿半周平滑回摆。相位由实际行进距离推进。 */
public final class GroundedGait {
    private final Skeleton skeleton;
    private final Point2D.Double[] ankle = new Point2D.Double[2];
    private final double[] hipX = new double[2];
    private final double[] thighRest = new double[2], shinRest = new double[2];
    private final double amplitude;
    private final double lift;

    public GroundedGait(Skeleton skeleton, double degrees, double lift) {
        this.skeleton = skeleton;
        // 原腿长约 27 像素；预留屈膝量后 21 像素摆臂使实际髋角半行程约 12°。
        amplitude = 21 * Math.sin(Math.toRadians(Math.max(1, Math.min(14, degrees))));
        this.lift = Math.max(0.1, Math.min(2.5, lift));
        for (int i = 0; i < 2; i++) {
            String side = side(i);
            ankle[i] = skeleton.start("foot" + side);
            hipX[i] = skeleton.start("thigh" + side).x;
            thighRest[i] = skeleton.worldAngleDeg("thigh" + side);
            shinRest[i] = skeleton.worldAngleDeg("shin" + side);
        }
    }

    public double stride() { return amplitude * 4; }
    public static String side(int i) { return i == 0 ? "Near" : "Far"; }
    public static double phase(double cycles, int leg) {
        double p = cycles + leg * 0.5;
        return p - Math.floor(p);
    }

    /** 一个周期内的足端位移。两个接触交界的速度相同，升降速度为零。 */
    public double offset(double p) {
        if (p <= 0.5) return amplitude * (1 - 4 * p);
        double u = (p - 0.5) * 2;
        return amplitude * (-1 - 2 * u + 12 * u * u - 8 * u * u * u);
    }

    public void apply(double cycles, double blend, double tilt) {
        skeleton.setTranslation(0, 0.8 * blend + tilt * tilt / (144 + tilt * tilt));
        skeleton.update();
        for (int i = 0; i < 2; i++) {
            String side = side(i);
            double p = phase(cycles, i);
            double up = p <= 0.5 ? 0 : lift * Math.pow(Math.sin((p - 0.5) * 2 * Math.PI), 2);
            double center = hipX[i] + (ankle[i].x - hipX[i]) * 0.3;
            double tx = ankle[i].x + blend * (center + offset(p) - ankle[i].x);
            double ty = ankle[i].y - blend * up;
            Point2D.Double hip = skeleton.start("thigh" + side);
            Ik.Solution solution = Ik.solve(hip.x, hip.y,
                    skeleton.bone("thigh" + side).lengthPx, skeleton.bone("shin" + side).lengthPx,
                    tx, ty, false);
            // 静止的直腿姿势与 IK 分支渐变，避免刚开始运动时突然换膝向。
            double amount = Math.min(1, blend * 3 + tilt * tilt / 9);
            amount = amount * amount * (3 - 2 * amount);
            skeleton.aim("thigh" + side, thighRest[i] + amount * (solution.firstWorldDeg - thighRest[i]));
            skeleton.aim("shin" + side, shinRest[i] + amount * (solution.secondWorldDeg - shinRest[i]));
            skeleton.aim("foot" + side, 90);
        }
    }
}
