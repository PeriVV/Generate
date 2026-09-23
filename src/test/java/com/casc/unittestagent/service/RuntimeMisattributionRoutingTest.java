package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.RuntimeMisattributionCorpus;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.ClassJUnitStopReason;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验（终态路由维度）：运行时环境/结构类失败即使模型返回 INCONCLUSIVE，
 * 终态也不得是 ORACLE_INCONCLUSIVE + “Oracle 证据冲突或不足”——那会把依赖缺失、
 * 框架启动失败等问题归咎到 Oracle。只有 EVIDENCE_REQUIRED（真正的断言/契约问题）
 * 才允许停在 Oracle 待确认态。
 */
public class RuntimeMisattributionRoutingTest extends TestCase {
    public void testRuntimeFailuresAreNotRoutedToOracleInconclusive() {
        List<String> blamed = new ArrayList<>();
        for (RuntimeMisattributionCorpus.Case sample : RuntimeMisattributionCorpus.all()) {
            OracleAssessment assessment = assessment(sample.idealCategory);
            CoverageStatus status = UnitTestGenerationService.coverageStatusForOracle(assessment);
            CoverageStopReason reason = UnitTestGenerationService.coverageStopReasonForOracle(assessment);
            ClassJUnitStatus classStatus = UnitTestGenerationService.classStatusForOracle(assessment);
            ClassJUnitStopReason classReason = UnitTestGenerationService.classStopReasonForOracle(assessment);
            String message = UnitTestGenerationService.oracleStopMessage(assessment, List.of("subject"));

            boolean oracleBlamed = reason == CoverageStopReason.ORACLE_INCONCLUSIVE
                    && message.contains("Oracle 证据冲突或不足");
            if ("EVIDENCE_REQUIRED".equals(sample.strategy)) {
                if (oracleBlamed || status != CoverageStatus.NEEDS_REVIEW) {
                    blamed.add(sample.name + ": 证据类失败应保留 Oracle 待确认态，实际=" + status + "/" + reason);
                }
                continue;
            }
            if (reason == CoverageStopReason.ORACLE_INCONCLUSIVE
                    || classReason == ClassJUnitStopReason.ORACLE_INCONCLUSIVE
                    || message.contains("Oracle 证据冲突或不足")) {
                blamed.add(sample.name + ": 被 Oracle 归因（" + sample.idealCategory + "）status="
                        + status + " reason=" + reason + " message=" + message);
                continue;
            }
            if (status != CoverageStatus.TEST_FAILED || classStatus != ClassJUnitStatus.TEST_FAILED) {
                blamed.add(sample.name + ": 结构/环境失败应为 TEST_FAILED，实际=" + status + "/" + classStatus);
            }
        }
        assertTrue("终态误归因 " + blamed.size() + "/" + RuntimeMisattributionCorpus.all().size()
                        + " 条：\n" + String.join("\n", blamed),
                blamed.isEmpty());
    }

    public void testEnvironmentFailuresKeepProtectedWriteGate() {
        // 环境类终态用 TEST_FAILED：写入门禁保持“受保护失败”语义（普通写入阻断、
        // force 走写入原失败测试确认），不得降级成普通警告。写入门禁矩阵见
        // toolwindow.WriteGateActionTest，这里只断言状态侧不降级。
        OracleAssessment assessment = assessment("RUNTIME_DEPENDENCY_UNAVAILABLE");
        assertEquals(CoverageStatus.TEST_FAILED, UnitTestGenerationService.coverageStatusForOracle(assessment));
        assertNotSame(CoverageStatus.ENVIRONMENT_ERROR, UnitTestGenerationService.coverageStatusForOracle(assessment));
    }

    public void testSuspectedSutBugRoutingUnchanged() {
        OracleAssessment assessment = assessment("ASSERTION_VALUE_MISMATCH");
        assessment.setDisposition(RuntimeRepairDisposition.SUSPECTED_SUT_BUG);
        assertEquals(CoverageStatus.SUSPECTED_SUT_BUG,
                UnitTestGenerationService.coverageStatusForOracle(assessment));
        assertEquals(CoverageStopReason.SUSPECTED_SUT_BUG,
                UnitTestGenerationService.coverageStopReasonForOracle(assessment));
    }

    private static OracleAssessment assessment(String category) {
        OracleAssessment assessment = new OracleAssessment();
        assessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
        assessment.setRootCauseCategory(category);
        return assessment;
    }
}
