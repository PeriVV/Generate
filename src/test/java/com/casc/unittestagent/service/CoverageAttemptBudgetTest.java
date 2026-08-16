package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Map;

public class CoverageAttemptBudgetTest extends TestCase {
    public void testRepairCallsDoNotConsumeExplorationBudget() {
        CoverageConfig config = new CoverageConfig();
        config.setMaxRounds(2);
        config.setMaxRepairRounds(1);
        CoverageReport report = new CoverageReport();
        UnitTestGenerationService.CoverageAttemptBudget budget =
                new UnitTestGenerationService.CoverageAttemptBudget();

        budget.consume(UnitTestGenerationService.CoverageAttemptKind.REPAIR, report);

        assertFalse(budget.canUse(UnitTestGenerationService.CoverageAttemptKind.REPAIR, config));
        assertTrue(budget.canUse(UnitTestGenerationService.CoverageAttemptKind.EXPLORATION, config));
        assertEquals(0, report.getExplorationRounds());
        assertEquals(1, report.getRepairRounds());
        assertEquals(1, report.getRounds());

        budget.consume(UnitTestGenerationService.CoverageAttemptKind.EXPLORATION, report);
        budget.consume(UnitTestGenerationService.CoverageAttemptKind.EXPLORATION, report);

        assertFalse(budget.canUse(UnitTestGenerationService.CoverageAttemptKind.EXPLORATION, config));
        assertEquals(2, report.getExplorationRounds());
        assertEquals(3, budget.totalCalls());
    }

    public void testHistoricalCandidateRetriesBeforeStoppingRepeatedTransition() {
        CoverageConfig config = new CoverageConfig();
        config.setMaxRepairRounds(5);
        UnitTestGenerationService.CoverageAttemptBudget budget =
                new UnitTestGenerationService.CoverageAttemptBudget();
        Map<String, Integer> transitions = new HashMap<>();

        int first = UnitTestGenerationService.rememberRepeatedTransition(transitions, "B", "A");
        assertTrue(UnitTestGenerationService.shouldRetryHistoricalRepair(first, budget, config));
        budget.consume(UnitTestGenerationService.CoverageAttemptKind.REPAIR, null);

        int second = UnitTestGenerationService.rememberRepeatedTransition(transitions, "B", "A");
        assertTrue(UnitTestGenerationService.shouldRetryHistoricalRepair(second, budget, config));
        budget.consume(UnitTestGenerationService.CoverageAttemptKind.REPAIR, null);

        int third = UnitTestGenerationService.rememberRepeatedTransition(transitions, "B", "A");
        assertFalse(UnitTestGenerationService.shouldRetryHistoricalRepair(third, budget, config));

        int differentRepair = UnitTestGenerationService.rememberRepeatedTransition(transitions, "B", "C");
        assertTrue(UnitTestGenerationService.shouldRetryHistoricalRepair(differentRepair, budget, config));
    }

    public void testBudgetExhaustedHistoricalMessageDoesNotClaimRetryHappened() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#fails()");
        diagnostic.setExceptionType("org.opentest4j.AssertionFailedError");
        diagnostic.setMessage("expected: <a> but was: <b>");

        String message = UnitTestGenerationService.repeatedRuntimeMessage(
                "class SubjectTest {}", "historical", java.util.List.of(diagnostic),
                1, true, "此前仍为断言失败");

        assertTrue(message.contains("预算已耗尽"));
        assertTrue(message.contains("未执行替代转换重试"));
        assertFalse(message.contains("重试仍无进展"));
        assertTrue(message.contains("此前仍为断言失败"));
    }

    public void testUnchangedResponseTelemetryDoesNotConsumeRepairBudget() {
        CoverageConfig config = new CoverageConfig();
        config.setMaxRepairRounds(1);
        CoverageReport report = new CoverageReport();
        UnitTestGenerationService.CoverageAttemptBudget budget =
                new UnitTestGenerationService.CoverageAttemptBudget();

        report.recordUnchangedResponse("MODEL_OUTPUT");
        report.recordUnchangedResponse("TEST_PROTECTION");
        assertFalse(budget.consumeChangedCandidate(
                UnitTestGenerationService.CoverageAttemptKind.REPAIR, report, false));

        assertTrue(budget.canUse(UnitTestGenerationService.CoverageAttemptKind.REPAIR, config));
        assertEquals(0, report.getRepairRounds());
        assertEquals(2, report.getUnchangedResponses());
        assertEquals(1, report.getModelUnchangedResponses());
        assertEquals(1, report.getDiscardedChanges());
        assertEquals("TEST_PROTECTION", report.getLastUnchangedStage());

        assertTrue(budget.consumeChangedCandidate(
                UnitTestGenerationService.CoverageAttemptKind.REPAIR, report, true));
        assertFalse(budget.canUse(UnitTestGenerationService.CoverageAttemptKind.REPAIR, config));
        assertEquals(1, report.getRepairRounds());
    }

    public void testUnchangedRepairMessageIdentifiesModelAndProtectionStages() {
        String model = UnitTestGenerationService.unchangedRepairMessage(
                "Runtime", "MODEL_OUTPUT", "Wanted 1 time, but was 2 times");
        String protectedChange = UnitTestGenerationService.unchangedRepairMessage(
                "Runtime", "TEST_PROTECTION", "failure");

        assertTrue(model.contains("模型返回的测试代码与当前代码相同"));
        assertTrue(model.contains("未扣减正式修复预算"));
        assertTrue(protectedChange.contains("当前模式允许的范围"));
        assertTrue(protectedChange.contains("候选稳定器"));
    }
}
