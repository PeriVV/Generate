package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageSnapshot;

/** 覆盖率达标和增益判断，独立于执行器便于验证终止策略。 */
public final class CoverageLoopPolicy {
    private CoverageLoopPolicy() {}

    public static boolean reached(CoverageSnapshot snapshot, CoverageConfig config) {
        return snapshot != null && snapshot.isAvailable() && snapshot.getLine().isApplicable()
                && snapshot.getLine().getRate() >= config.getLineThreshold()
                && (!snapshot.getBranch().isApplicable()
                || snapshot.getBranch().getRate() >= config.getBranchThreshold());
    }

    /** 达到配置阈值不是探索终点；只有所有适用计数器都没有遗漏时才算满覆盖。 */
    public static boolean perfect(CoverageSnapshot snapshot) {
        return snapshot != null && snapshot.isAvailable() && snapshot.getLine().isApplicable()
                && snapshot.getLine().getMissed() == 0
                && (!snapshot.getBranch().isApplicable()
                || snapshot.getBranch().getMissed() == 0);
    }

    /**
     * 只要还有可行动缺口和探索预算，就继续提升覆盖率；该判断有意不依赖“是否已达阈值”。
     */
    public static boolean shouldContinueImproving(CoverageSnapshot snapshot,
                                                  boolean hasActionableGaps,
                                                  boolean explorationAvailable) {
        return snapshot != null && snapshot.isAvailable() && snapshot.getLine().isApplicable()
                && !perfect(snapshot)
                && hasActionableGaps
                && explorationAvailable;
    }

    public static boolean improved(CoverageSnapshot previous, CoverageSnapshot current) {
        if (previous == null || current == null) return false;
        int previousLines = previous.getLine().getCovered();
        int currentLines = current.getLine().getCovered();
        int previousBranches = previous.getBranch().getCovered();
        int currentBranches = current.getBranch().getCovered();
        // A "best" checkpoint must be Pareto-monotonic. A candidate that gains one line by
        // deleting a test which covered several branches is a regression, not an improvement.
        return currentLines >= previousLines && currentBranches >= previousBranches
                && (currentLines > previousLines || currentBranches > previousBranches);
    }
}
