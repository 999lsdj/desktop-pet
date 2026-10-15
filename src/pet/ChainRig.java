package pet;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 把骨架上的几条柔性链接到 Verlet 链上：每帧用链第一节的起点当挂点推进模拟，
 * 再把每节算出来的世界方向写回对应骨头（{@link Skeleton#aim}）。
 *
 * 挂点取的是骨头自己的起点，而它由父骨决定——所以手一晃、头一摆，链就跟着甩，
 * 而且越靠末端滞后越大。提灯不在这里：它是单个刚体，继续用 {@link Pendulum}。
 *
 * 注意：链的骨头必须在摆姿势之前就加好（{@link Skeleton#from} 里做的事），
 * 否则静止变换会取到已经摆过的姿态。
 */
public final class ChainRig {

    /** 链名、挂在哪根骨头上、以及挂点在精灵坐标里的位置 */
    private static final String[] NAMES = {"hair", "sleeve", "coat"};
    private static final String[] ATTACH = {"head", "foreArm", "torso"};
    private static final double[] ANCHOR_X = {38.5, 36, 34.5};
    private static final double[] ANCHOR_Y = {8, 60, 89};
    private static final int[] DEFAULT_SEGMENTS = {3, 2, 2};
    private static final double[] DEFAULT_SEG_LEN = {12, 10, 12};

    private static final class Chain {
        final String name;
        final String[] bones;
        final VerletChain sim;

        Chain(String name, String[] bones, VerletChain sim) {
            this.name = name;
            this.bones = bones;
            this.sim = sim;
        }
    }

    private final Skeleton skeleton;
    private final List<Chain> chains = new ArrayList<>();
    private final double gravity;

    /**
     * 按配置生成各条链的骨头并建好模拟。调用时机必须在摆姿势之前
     * （链的骨头由这里追加，静止变换要在建骨当下快照）。
     */
    public ChainRig(Skeleton skeleton, Config config) {
        this.skeleton = skeleton;
        this.gravity = config.d("chain.gravity", 900);
        for (int c = 0; c < NAMES.length; c++) {
            String prefix = NAMES[c];
            int segments = config.i("chain." + prefix + ".segments", DEFAULT_SEGMENTS[c]);
            double segLen = config.d("chain." + prefix + ".seg.length", DEFAULT_SEG_LEN[c]);
            double maxDeg = config.d("chain." + prefix + ".max.deg", 35);
            double damping = config.d("chain." + prefix + ".damping", 2.5);
            if (segments < 1 || segLen <= 0) {
                throw new IllegalArgumentException("链 " + prefix + " 的节数与杆长必须为正");
            }
            String[] bones = new String[segments];
            for (int i = 0; i < segments; i++) {
                bones[i] = prefix + (i + 1);
                if (i == 0) {
                    Point2D.Double start = skeleton.start(ATTACH[c]);
                    skeleton.addBone(bones[i], ATTACH[c],
                            ANCHOR_X[c] - start.x, ANCHOR_Y[c] - start.y, 90, segLen);
                } else {
                    skeleton.addBone(bones[i], bones[i - 1], 0, segLen, 90, segLen);
                }
            }
            chains.add(new Chain(prefix, bones, new VerletChain(segments + 1, segLen, maxDeg, damping)));
        }
    }

    /** 链名，顺序与 chainCount() 一致。 */
    public String chainName(int chainIndex) {
        return chains.get(chainIndex).name;
    }

    /** 推进所有链一帧，并把结果写回骨架。调用前先保证骨架的正运动学是最新的。 */
    public void update(double dt) {
        for (Chain c : chains) {
            Point2D.Double anchor = skeleton.start(c.bones[0]);
            c.sim.setAnchor(anchor.x, anchor.y);
            c.sim.update(dt, gravity);
            for (int i = 0; i < c.bones.length; i++) {
                skeleton.aim(c.bones[i], c.sim.worldAngleDeg(i + 1));
            }
        }
    }

    /** 推一把（走路起步、被拎起来时用）。 */
    public void kick(double dxPerFrame, double dyPerFrame) {
        for (Chain c : chains) {
            c.sim.kick(dxPerFrame, dyPerFrame);
        }
    }

    public void setDamping(double damping) {
        for (Chain c : chains) {
            c.sim.setDamping(damping);
        }
    }

    public int chainCount() {
        return chains.size();
    }

    public int segmentCount(int chainIndex) {
        return chains.get(chainIndex).bones.length;
    }

    public double nodeX(int chainIndex, int node) {
        return chains.get(chainIndex).sim.nodeX(node);
    }

    public double nodeY(int chainIndex, int node) {
        return chains.get(chainIndex).sim.nodeY(node);
    }

    public double jointAngleDeg(int chainIndex, int segment) {
        return chains.get(chainIndex).sim.jointAngleDeg(segment);
    }

    /** 整条链上所有关节里最大的偏离角，判定"停住"时用。 */
    public double maxJointAngleDeg() {
        double worst = 0;
        for (int c = 0; c < chains.size(); c++) {
            worst = Math.max(worst, maxJointAngleDeg(c));
        }
        return worst;
    }

    /** 指定链上所有关节里最大的偏离角。 */
    public double maxJointAngleDeg(int chainIndex) {
        double worst = 0;
        for (int s = 1; s <= segmentCount(chainIndex); s++) {
            worst = Math.max(worst, Math.abs(jointAngleDeg(chainIndex, s)));
        }
        return worst;
    }
}
