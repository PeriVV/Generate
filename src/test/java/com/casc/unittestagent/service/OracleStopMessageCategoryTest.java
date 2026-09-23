package com.casc.unittestagent.service;

import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import junit.framework.TestCase;

import java.util.List;

public class OracleStopMessageCategoryTest extends TestCase {
    public void testEvidenceRequiredMessageContainsCategoryAndHint() {
        OracleAssessment assessment = assessment(RuntimeRepairDisposition.INCONCLUSIVE, "ASSERTION_VALUE_MISMATCH");

        String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of("shouldDiscount"));

        assertTrue(message, message.contains("缺少可授权修改 Oracle 的独立证据"));
        assertTrue(message, message.contains("断言期望值不一致(ASSERTION_VALUE_MISMATCH)"));
        assertTrue(message, message.contains("可在下方补充证据后继续修复"));
        assertTrue(message, message.contains("shouldDiscount"));
        assertTrue(message, message.contains("权威来源=NONE"));
    }

    public void testDeterministicMessageWording() {
        OracleAssessment assessment = assessment(RuntimeRepairDisposition.INCONCLUSIVE,
                "MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET");

        String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of());

        assertTrue(message, message.contains("测试侧结构错误"));
        assertTrue(message, message.contains("静态方法禁止 mock"));
        assertFalse(message, message.contains("缺少可授权修改 Oracle 的独立证据"));
    }

    public void testEnvironmentMessageWording() {
        OracleAssessment assessment = assessment(RuntimeRepairDisposition.INCONCLUSIVE, "JUNIT5_NOT_FOUND");

        String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of());

        assertTrue(message, message.contains("测试执行环境不可用"));
        assertTrue(message, message.contains("JUnit 5 依赖缺失"));
    }

    public void testUnclassifiedDoesNotBlameOracle() {
        OracleAssessment assessment = assessment(RuntimeRepairDisposition.INCONCLUSIVE, "TEST_RUNTIME_FAILURE");

        String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of("subject"));

        // UNCLASSIFIED 不再宣称“Oracle 证据冲突或不足”：未归类运行失败与 Oracle 无关。
        assertFalse(message, message.contains("Oracle 证据冲突或不足"));
        assertTrue(message, message.contains("运行失败未能归类"));
        assertTrue(message, message.contains("未分类运行失败(TEST_RUNTIME_FAILURE)"));
        assertTrue(message, message.contains("可补充信息辅助归因"));
        assertTrue(message, message.contains("subject"));
    }

    public void testUnknownRawCategoryStillRendersWithoutCrash() {
        OracleAssessment assessment = assessment(RuntimeRepairDisposition.INCONCLUSIVE, "SOMETHING_OLD");

        String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of());

        assertTrue(message, message.contains("分类="));
        assertTrue(message, message.contains("未分类(UNKNOWN)"));
    }

    public void testSuspectedSutBugPrefixUnchanged() {
        OracleAssessment assessment = assessment(RuntimeRepairDisposition.SUSPECTED_SUT_BUG,
                "ASSERTION_VALUE_MISMATCH");

        String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of());

        assertTrue(message, message.startsWith("疑似生产代码缺陷，已停止自动修复并保留原失败测试。"));
        assertFalse(message, message.contains("可在下方补充证据后继续修复"));
    }

    public void testNullAssessmentKeepsLegacyFallback() {
        assertEquals("Oracle 证据不足，已保留原失败测试。",
                UnitTestGenerationService.oracleStopMessage(null));
    }

    public void testEvidenceTrailIsIncluded() {
        OracleAssessment assessment = assessment(RuntimeRepairDisposition.INCONCLUSIVE, "ASSERTION_FAILURE");
        assessment.setEvidence(List.of("模型证据一", "模型证据二"));

        String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of());

        assertTrue(message, message.contains("模型证据一；模型证据二"));
    }

    private static OracleAssessment assessment(RuntimeRepairDisposition disposition, String category) {
        OracleAssessment assessment = new OracleAssessment();
        assessment.setDisposition(disposition);
        assessment.setAuthority(OracleAuthority.NONE);
        assessment.setAuthorityRef("NONE");
        assessment.setRootCauseCategory(category);
        return assessment;
    }
}
