package com.casc.unittestagent.service;

import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import com.casc.unittestagent.model.TestScenario;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

/** 用户补充证据的作用域校验：名单外的失败测试不得引用该证据授权 CORRECT。 */
public class EvidenceScopeAuthorityTest extends TestCase {
    public void testScopedEvidenceRejectedWhenFailingTestsOutsideScope() {
        LlmGenerationRequest request = requestWithScopedEvidence(List.of("shouldDiscountA", "shouldDiscountB"),
                List.of("unrelatedNewTest"));

        assertFalse(UnitTestGenerationService.oracleAuthorityReferenceSupported(
                request, assessment("USER_REQUIREMENT:1")));
        String note = UnitTestGenerationService.evidenceScopeRejectionNote(request, assessment("USER_REQUIREMENT:1"));
        assertTrue(note, note.contains("shouldDiscountA"));
        assertTrue(note, note.contains("不包含本轮失败测试"));
    }

    public void testScopedEvidenceAcceptedWhenFailingTestWithinScope() {
        LlmGenerationRequest request = requestWithScopedEvidence(List.of("shouldDiscountA"),
                List.of("shouldDiscountA", "other"));

        assertTrue(UnitTestGenerationService.oracleAuthorityReferenceSupported(
                request, assessment("USER_REQUIREMENT:1")));
        assertEquals("", UnitTestGenerationService.evidenceScopeRejectionNote(request, assessment("USER_REQUIREMENT:1")));
    }

    public void testUnscopedEvidenceAppliesGlobally() {
        LlmGenerationRequest request = requestWithScopedEvidence(List.of(), List.of("anyTest"));

        assertTrue(UnitTestGenerationService.oracleAuthorityReferenceSupported(
                request, assessment("USER_REQUIREMENT:1")));
    }

    public void testScopeCheckSkippedWhenNoFailingTestIdsGiven() {
        LlmGenerationRequest request = requestWithScopedEvidence(List.of("shouldDiscountA"), List.of());

        assertTrue(UnitTestGenerationService.oracleAuthorityReferenceSupported(
                request, assessment("USER_REQUIREMENT:1")));
    }

    public void testBuildEvidenceScenarioCarriesStructuredScope() {
        TestScenario scenario = UnitTestGenerationService.buildEvidenceScenario(
                "满 100 打 95 折", List.of("testA", "testB"), 1);

        assertEquals(List.of("testA", "testB"), scenario.getEvidenceScopedFailingTests());
        // 空名单表示未限定作用域（全局用户意图）。
        assertTrue(UnitTestGenerationService.buildEvidenceScenario("全局规则", List.of(), 2)
                .getEvidenceScopedFailingTests().isEmpty());
    }

    private static LlmGenerationRequest requestWithScopedEvidence(List<String> scopedTests,
                                                                  List<String> failingTestIds) {
        LlmGenerationRequest request = new LlmGenerationRequest();
        List<TestScenario> scenarios = new ArrayList<>();
        scenarios.add(plainScenario("normal"));
        scenarios.add(UnitTestGenerationService.buildEvidenceScenario(
                "满 100 打 95 折", scopedTests, 1));
        request.setScenarios(scenarios);
        request.setFailingTestIds(failingTestIds);
        return request;
    }

    private static TestScenario plainScenario(String name) {
        TestScenario scenario = new TestScenario();
        scenario.setName(name);
        scenario.setType("NORMAL");
        scenario.setEnabled(true);
        return scenario;
    }

    private static OracleAssessment assessment(String authorityRef) {
        OracleAssessment assessment = new OracleAssessment();
        assessment.setDisposition(RuntimeRepairDisposition.REPAIR_TEST);
        assessment.setAuthority(OracleAuthority.USER_REQUIREMENT);
        assessment.setAuthorityRef(authorityRef);
        return assessment;
    }
}
