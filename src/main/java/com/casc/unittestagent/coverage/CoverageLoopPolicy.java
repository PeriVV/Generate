package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageSnapshot;

/** 覆盖率达标和增益判断，独立于执行器便于验证终止策略。 */
public final class CoverageLoopPolicy {
    private CoverageLoopPolicy() {}

    public static boolean reached(CoverageSnapshot snapshot, CoverageConfig config) {
        return snapshot != null && snapshot.isAvailable()
                && snapshot.getLine().getRate() >= config.getLineThreshold()
                && (!snapshot.getBranch().isApplicable()
                || snapshot.getBranch().getRate() >= config.getBranchThreshold());
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
