package com.casc.unittestagent.service;

import com.casc.unittestagent.model.CoverageMetric;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.TestExecutionSummary;
import junit.framework.TestCase;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;

public class CoverageCandidatePolicyTest extends TestCase {
    public void testKeepsLastPassingCandidateWhenReplacementFails() {
        UnitTestGenerationService.AcceptedCoverageCandidate accepted =
                UnitTestGenerationService.chooseCoverageCandidate(null, "passing", passing(5, 5), List.of());

        UnitTestGenerationService.AcceptedCoverageCandidate selected =
                UnitTestGenerationService.chooseCoverageCandidate(accepted, "failing", failing(), List.of());

        assertSame(accepted, selected);
        assertEquals("passing", selected.getCode());
    }

    public void testReplacesCheckpointOnlyForRealCoverageGain() {
        UnitTestGenerationService.AcceptedCoverageCandidate initial =
                UnitTestGenerationService.chooseCoverageCandidate(null, "initial", passing(5, 5), List.of());
        UnitTestGenerationService.AcceptedCoverageCandidate unchanged =
                UnitTestGenerationService.chooseCoverageCandidate(initial, "same", passing(5, 5), List.of());
        UnitTestGenerationService.AcceptedCoverageCandidate improved =
                UnitTestGenerationService.chooseCoverageCandidate(unchanged, "improved", passing(6, 4), List.of());

        assertSame(initial, unchanged);
        assertNotSame(initial, improved);
        assertEquals("improved", improved.getCode());
    }

    public void testOnlyTransientTransportFailuresAreRetried() {
        assertTrue(UnitTestGenerationService.isRetryableCoverageFailure(new SocketTimeoutException("timeout")));
        assertTrue(UnitTestGenerationService.isRetryableCoverageFailure(
                new IOException("LLM request failed: 503 Service Unavailable")));
        assertFalse(UnitTestGenerationService.isRetryableCoverageFailure(
                new IOException("LLM request failed: 400 Bad Request")));
    }

    private CoverageSnapshot passing(int covered, int missed) {
        CoverageSnapshot snapshot = new CoverageSnapshot();
        snapshot.setAvailable(true);
        snapshot.setLine(new CoverageMetric(covered, missed));
        snapshot.setBranch(new CoverageMetric(0, 0));
        TestExecutionSummary execution = new TestExecutionSummary();
        execution.setFound(1);
        execution.setSucceeded(1);
        execution.setFailed(0);
        execution.setExitCode(0);
        execution.setTestCases(List.of(new com.casc.unittestagent.model.TestCaseExecution("T#exercise()", true, false)));
        snapshot.setExecution(execution);
        return snapshot;
    }

    private CoverageSnapshot failing() {
        CoverageSnapshot snapshot = passing(8, 2);
        snapshot.getExecution().setFailed(1);
        snapshot.getExecution().setSucceeded(0);
        snapshot.getExecution().setExitCode(1);
        return snapshot;
    }
}
