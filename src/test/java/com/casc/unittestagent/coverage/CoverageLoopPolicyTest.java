package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageMetric;
import com.casc.unittestagent.model.CoverageSnapshot;
import junit.framework.TestCase;

public class CoverageLoopPolicyTest extends TestCase {
    public void testRequiresBothConfiguredThresholds() {
        CoverageConfig config = new CoverageConfig();
        CoverageSnapshot snapshot = snapshot(9, 1, 7, 3);
        assertFalse(CoverageLoopPolicy.reached(snapshot, config));

        snapshot.setBranch(new CoverageMetric(8, 2));
        assertTrue(CoverageLoopPolicy.reached(snapshot, config));
    }

    public void testNoBranchMethodUsesLineThresholdOnly() {
        assertTrue(CoverageLoopPolicy.reached(snapshot(9, 1, 0, 0), new CoverageConfig()));
    }

    public void testReachedThresholdStillContinuesWhileCoverageCanImprove() {
        CoverageSnapshot reachedButIncomplete = snapshot(9, 1, 8, 2);

        assertTrue(CoverageLoopPolicy.reached(reachedButIncomplete, new CoverageConfig()));
        assertFalse(CoverageLoopPolicy.perfect(reachedButIncomplete));
        assertTrue(CoverageLoopPolicy.shouldContinueImproving(
                reachedButIncomplete, true, true));
    }

    public void testStopsImprovingAtPerfectCoverageOrWithoutActionableBudget() {
        CoverageSnapshot perfect = snapshot(10, 0, 4, 0);
        CoverageSnapshot incomplete = snapshot(9, 1, 8, 2);

        assertTrue(CoverageLoopPolicy.perfect(perfect));
        assertFalse(CoverageLoopPolicy.shouldContinueImproving(perfect, true, true));
        assertFalse(CoverageLoopPolicy.shouldContinueImproving(incomplete, false, true));
        assertFalse(CoverageLoopPolicy.shouldContinueImproving(incomplete, true, false));
    }

    public void testDetectsBranchOrLineGainAndRejectsNoProgress() {
        CoverageSnapshot previous = snapshot(5, 5, 1, 3);
        assertTrue(CoverageLoopPolicy.improved(previous, snapshot(6, 4, 1, 3)));
        assertTrue(CoverageLoopPolicy.improved(previous, snapshot(5, 5, 2, 2)));
        assertFalse(CoverageLoopPolicy.improved(previous, snapshot(5, 5, 1, 3)));
        assertFalse(CoverageLoopPolicy.improved(previous, snapshot(6, 4, 0, 4)));
        assertFalse(CoverageLoopPolicy.improved(previous, snapshot(4, 6, 2, 2)));
    }

    private CoverageSnapshot snapshot(int lineCovered, int lineMissed, int branchCovered, int branchMissed) {
        CoverageSnapshot snapshot = new CoverageSnapshot();
        snapshot.setAvailable(true);
        snapshot.setLine(new CoverageMetric(lineCovered, lineMissed));
        snapshot.setBranch(new CoverageMetric(branchCovered, branchMissed));
        return snapshot;
    }
}
