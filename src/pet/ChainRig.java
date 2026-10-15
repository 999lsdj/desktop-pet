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

    private static final String[][] CHAIN_BONES = {
            {"hair1", "hair2", "hair3"},
            {"sleeve1", "sleeve2"},
            {"coat1", "coat2"},
    };

    private static final class Chain {
        final String[] bones;
        final VerletChain sim;

        Chain(String[] bones, VerletChain sim) {
            this.bones = bones;
            this.sim = sim;
        }
    }

    private final Skeleton skeleton;
    private final List<Chain> chains = new ArrayList<>();
    private final double gravity;

    public ChainRig(Skeleton skeleton, double gravity) {
        this.skeleton = skeleton;
        this.gravity = gravity;
        for (String[] bones : CHAIN_BONES) {
            double segLen = skeleton.bone(bones[0]).lengthPx;
            VerletChain sim = new VerletChain(bones.length + 1, segLen, 35, 2.5);
            chains.add(new Chain(bones, sim));
        }
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
            for (int s = 1; s <= segmentCount(c); s++) {
                worst = Math.max(worst, Math.abs(jointAngleDeg(c, s)));
            }
        }
        return worst;
    }
}
