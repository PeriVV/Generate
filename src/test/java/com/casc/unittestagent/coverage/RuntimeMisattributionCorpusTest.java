package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.FailureCategory;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import junit.framework.TestCase;

import java.util.List;
import java.util.ArrayList;

/**
 * 实验：依赖繁多的真实项目运行时失败样本必须被确定性分类到与 Oracle 无关的类别，
 * 而不是落进 TEST_RUNTIME_FAILURE 兜底（终态会被归咎为“Oracle 证据冲突或不足”）。
 * 运行失败信息即修复前基线的误归因名单与计数。
 */
public class RuntimeMisattributionCorpusTest extends TestCase {
    private static final String TEST_CODE = "class SubjectTest { @Test void subject() { new Subject().run(); } }";

    public void testNoRuntimeFailureFallsIntoOracleBlameBucket() {
        List<String> misattributed = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (RuntimeMisattributionCorpus.Case sample : RuntimeMisattributionCorpus.all()) {
            RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
            diagnostic.setTestId("sample.SubjectTest#subject()");
            diagnostic.setExceptionType(sample.exceptionType);
            diagnostic.setMessage(sample.message);
            diagnostic.setFailureDetails(sample.failureDetails);
            diagnostic.setRootCauseType(sample.rootCauseType);
            // 真实链路中 expected/actual 由 JUnit XML 解析预提取，语料同步携带。
            diagnostic.setExpectedValue(sample.expectedValue);
            diagnostic.setActualValue(sample.actualValue);
            MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), TEST_CODE);

            FailureCategory actual = FailureCategory.fromCode(diagnostic.getCategory());
            if (!sample.idealCategory.equals(actual.name())) {
                misattributed.add(sample.name + ": 期望=" + sample.idealCategory
                        + " 实际=" + actual.name() + "(" + diagnostic.getCategory() + ")");
                continue;
            }
            if (!sample.strategy.equals(actual.getRepairStrategy().name())) {
                unexpected.add(sample.name + ": 策略期望=" + sample.strategy
                        + " 实际=" + actual.getRepairStrategy());
            }
        }
        int total = RuntimeMisattributionCorpus.all().size();
        assertTrue("分类误归因 " + misattributed.size() + "/" + total + " 条：\n"
                        + String.join("\n", misattributed),
                misattributed.isEmpty());
        assertTrue("策略不符 " + unexpected.size() + " 条：\n" + String.join("\n", unexpected),
                unexpected.isEmpty());
    }

    public void testStaticInitNpeCauseIsNotUpgradedToFixtureMissing() {
        // cause 链污染专项：静态初始化失败不得被 MissingFixtureDependencyDetector 升级为
        // “可修的 FIXTURE_DEPENDENCY_MISSING”——那会把环境问题指挥成补 @Mock。
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#subject()");
        diagnostic.setExceptionType("java.lang.ExceptionInInitializerError");
        diagnostic.setMessage("");
        diagnostic.setFailureDetails("java.lang.ExceptionInInitializerError\n"
                + "Caused by: java.lang.NullPointerException: config path is null\n");
        diagnostic.setRootCauseType("java.lang.NullPointerException");
        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), TEST_CODE);

        assertEquals("RUNTIME_STATIC_INIT_FAILURE", diagnostic.getCategory());
        MissingFixtureDependencyDetector.Result result = MissingFixtureDependencyDetector.inspect(
                "class Subject { Subject(Config config) {} }", TEST_CODE, List.of(diagnostic));
        assertFalse("静态初始化失败不得升级为 FIXTURE_DEPENDENCY_MISSING", result.isProven());
        assertEquals("RUNTIME_STATIC_INIT_FAILURE", diagnostic.getCategory());
    }
}
