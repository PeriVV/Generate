package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.model.TestScenario;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class EvidenceResumeScenarioTest extends TestCase {
    public void testBuildEvidenceScenarioFields() {
        TestScenario scenario = UnitTestGenerationService.buildEvidenceScenario(
                "订单满 100 元应打 95 折（来源：PRD-3.2）", List.of("shouldDiscount"), 1);

        assertEquals("USER_EVIDENCE", scenario.getType());
        assertNotNull(scenario.getType());
        assertFalse(scenario.getType().endsWith("_REASON"));
        assertTrue(scenario.isEnabled());
        assertEquals("USER_REQUIREMENT", scenario.getOracleEvidence());
        assertEquals("HIGH", scenario.getOracleConfidence());
        assertEquals(1, scenario.getExpectedAssertions().size());
        assertTrue(scenario.getExpectedAssertions().get(0).contains("PRD-3.2"));
        assertTrue(scenario.getExpectedAssertions().get(0).contains("shouldDiscount"));
        assertTrue(scenario.getDisplayNameCn().contains("用户补充证据"));
    }

    public void testBuildEvidenceScenarioSurvivesExecutableScenariosFilter() {
        // executableScenarios 的过滤规则：type 非 null、不以 _REASON 结尾、enabled=true。
        TestScenario scenario = UnitTestGenerationService.buildEvidenceScenario("evidence", List.of(), 2);

        assertTrue(scenario.getType() != null && !scenario.getType().endsWith("_REASON") && scenario.isEnabled());
    }

    public void testBuildEvidenceScenarioWithoutFailingTestsOmitsBindingNote() {
        TestScenario scenario = UnitTestGenerationService.buildEvidenceScenario("evidence", List.of(), 1);

        assertFalse(scenario.getExpectedAssertions().get(0).contains("适用于失败测试"));
    }

    public void testEvidenceResumeAllowedRespectsCap() {
        CoverageConfig config = new CoverageConfig();
        config.setMaxEvidenceResumes(2);

        assertTrue(UnitTestGenerationService.evidenceResumeAllowed(0, config));
        assertTrue(UnitTestGenerationService.evidenceResumeAllowed(1, config));
        assertFalse(UnitTestGenerationService.evidenceResumeAllowed(2, config));
        assertFalse(UnitTestGenerationService.evidenceResumeAllowed(3, config));

        CoverageConfig disabled = new CoverageConfig();
        disabled.setMaxEvidenceResumes(0);
        assertFalse(UnitTestGenerationService.evidenceResumeAllowed(0, disabled));
        assertFalse(UnitTestGenerationService.evidenceResumeAllowed(0, null));
    }

    public void testScenarioAppendPreservesPositionalOrder() {
        // oracleAuthorityReferenceSupported 按 index 引用 USER_REQUIREMENT:k，
        // 证据场景只能追加到末尾，不得挪动既有场景的位置。
        List<TestScenario> scenarios = new ArrayList<>();
        scenarios.add(scenario("alpha"));
        scenarios.add(scenario("beta"));
        scenarios.add(scenario("gamma"));
        int[] originalIndexes = {scenarios.indexOf(scenarios.get(0)),
                scenarios.indexOf(scenarios.get(1)), scenarios.indexOf(scenarios.get(2))};

        scenarios.add(UnitTestGenerationService.buildEvidenceScenario("evidence", List.of("failing"), 1));

        assertEquals(4, scenarios.size());
        assertEquals("user_evidence_1", scenarios.get(3).getName());
        assertEquals(0, scenarios.indexOf(scenarios.get(0)));
        assertEquals(originalIndexes[1], scenarios.indexOf(scenarios.get(1)));
        assertEquals(originalIndexes[2], scenarios.indexOf(scenarios.get(2)));
    }

    private static TestScenario scenario(String name) {
        TestScenario scenario = new TestScenario();
        scenario.setName(name);
        scenario.setType("NORMAL");
        scenario.setEnabled(true);
        return scenario;
    }
}
