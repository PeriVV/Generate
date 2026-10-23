package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TypeSymbol;
import com.casc.unittestagent.model.CoverageGap;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.CoverageMetric;
import com.casc.unittestagent.model.FailureCategory;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.OracleDecision;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class LlmPromptBuilderTest {
    @Test public void springContractsAndDeepestCauseReachEveryModelMode() {
        var request = new LlmGenerationRequest();
        request.setClassSourceCode("class Subject { @org.springframework.beans.factory.annotation.Value(\"${actual.key:default}\") String value; }");
        var diagnostic=new RuntimeDiagnostic();diagnostic.setCategory("FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE");
        diagnostic.setSharedContextFailure(true);diagnostic.setFailureDetails("wrapper".repeat(3000));
        diagnostic.setRootCauseType("sample.Deepest");diagnostic.setRootCauseMessage("required key at deepest cause");
        diagnostic.setCauseDetails(List.of("Caused by: sample.Deepest: required key at deepest cause"));
        request.setRuntimeDiagnostics(List.of(diagnostic));
        var builder=new LlmPromptBuilder();
        for(String prompt:List.of(builder.userPrompt(request),builder.repairPrompt(request),builder.classRepairPrompt(request),builder.coveragePrompt(request))) {
            assertTrue(prompt.contains("springPropertyBindings"));assertTrue(prompt.contains("actual.key"));
        }
        for(String prompt:List.of(builder.classRepairPrompt(request),builder.coveragePrompt(request))) {
            assertTrue(prompt.contains("required key at deepest cause")); assertTrue(prompt.contains("sharedContextFailure"));
        }
        for(String system:List.of(builder.systemPrompt(),builder.repairSystemPrompt(),builder.classRepairSystemPrompt(),builder.coverageSystemPrompt())) {
            assertTrue(system.contains("Spring 属性与生命周期规则")); assertTrue(system.contains("context.getBean"));
        }
    }

    @Test public void everyGenerationAndRepairModeSharesObjectGraphAndBehaviorContracts() {
        var builder=new LlmPromptBuilder();
        for(String prompt:List.of(builder.systemPrompt(),builder.repairSystemPrompt(),builder.classRepairSystemPrompt(),builder.coverageSystemPrompt())) {
            assertTrue(prompt.contains("mock 对象图"));
            assertTrue(prompt.contains("private final/static"));
            assertTrue(prompt.contains("公开入口"));
            assertTrue(prompt.contains("独立权威授权"));
        }
    }
    @Test
    public void generationAndRepairShareRealTestDoubleTypeContracts() {
        var builder = new LlmPromptBuilder();
        for (String prompt : java.util.List.of(builder.systemPrompt(), builder.repairSystemPrompt(),
                builder.coverageSystemPrompt(), builder.classRepairSystemPrompt())) {
            assertTrue(prompt.contains("implements 仅用于 interface"));
            assertTrue(prompt.contains("全部继承的抽象方法"));
            assertTrue(prompt.contains("API 不完整时"));
            assertTrue(prompt.contains("生产源码已独立通过"));
        }
    }
    @Test
    public void serializesMatcherOriginSeparatelyFromFailingJUnitTest() {
        var diagnostic = new RuntimeDiagnostic(); diagnostic.setTestId("T#detected()");
        diagnostic.setOriginLocations(List.of(new com.casc.unittestagent.model.RuntimeFailureOrigin(
                "T#origin()", 42, "when(Real.call(any()))", "-> at T.origin(T.java:42)")));
        var request = new LlmGenerationRequest(); request.setRuntimeDiagnostics(List.of(diagnostic));
        String prompt = new LlmPromptBuilder().coveragePrompt(request);
        assertTrue(prompt.contains("\"originLocations\"")); assertTrue(prompt.contains("T#origin()"));
        assertTrue(prompt.contains("T#detected()")); assertTrue(prompt.contains("不能改无关测试"));
    }
    @Test public void initializationRepairUsesPhaseAndSharedFixtureEvidenceWithoutRequestingBusinessOracle() {
        var diagnostic = new RuntimeDiagnostic(); diagnostic.setCategory("MOCKITO_FIXTURE_INITIALIZATION_FAILURE");
        diagnostic.setFailurePhase(com.casc.unittestagent.model.RuntimeFailurePhase.TEST_SETUP);
        diagnostic.setFixtureField("service"); diagnostic.setLifecycleEvidence(List.of("Mockito @Spy constructor failure"));
        var request = new LlmGenerationRequest(); request.setRuntimeDiagnostics(List.of(diagnostic));
        String prompt = new LlmPromptBuilder().classRepairPrompt(request);
        assertTrue(prompt.contains("SHARED_FIXTURE + REPAIR_TEST + KEEP + NONE"));
        assertTrue(prompt.contains("不需要业务 Oracle 证据或 NullPointerException"));
        assertTrue(prompt.contains("\"failurePhase\": \"TEST_SETUP\""));
        assertTrue(prompt.contains("\"fixtureField\": \"service\""));
        assertTrue(prompt.contains("Mockito @Spy constructor failure"));
    }
    @Test
    public void runtimeRepairIncludesCompleteClassConstantsForExceptionPathAdjudication() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setCategory("ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN");
        diagnostic.setExpectedExceptionType("java.lang.IllegalStateException");
        diagnostic.setMessage("Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.");
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setMethodSourceCode("void validateConcurrentLimit() { if (runningCount >= MAX_CONCURRENT_PROCESSES) throw new IllegalStateException(); }");
        request.setClassSourceCode("class ProcessService { private static final long MAX_CONCURRENT_PROCESSES = 20L; }");
        request.setExistingTestCodeSummary("when(repository.countRunningAssociations()).thenReturn(10L);");
        request.setRuntimeDiagnostics(List.of(diagnostic));

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(prompt.contains("\"classSourceCode\""));
        assertTrue(prompt.contains("MAX_CONCURRENT_PROCESSES = 20L"));
        assertTrue(prompt.contains("ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN"));
        assertTrue(prompt.contains("不是 Fixture 缺失"));
    }

    @Test
    public void sourceBugAnalysisPromptIsReadOnlyAndContainsFailureEvidence() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("SubjectTest#valueFollowsContract");
        diagnostic.setExpectedValue("2");
        diagnostic.setActualValue("1");
        diagnostic.setProductionFrame("at sample.Subject.value(Subject.java:3)");
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setClassSourceCode("class Subject { int value() { return 1; } }");
        request.setExistingTestCodeSummary("assertEquals(2, new Subject().value());");
        request.setRuntimeDiagnostics(List.of(diagnostic));
        OracleAssessment assessment = new OracleAssessment();
        assessment.setDisposition(RuntimeRepairDisposition.SUSPECTED_SUT_BUG);
        assessment.setDecision(OracleDecision.KEEP);
        assessment.setAuthority(OracleAuthority.CONTRACT_FACT);
        assessment.setAuthorityRef("CONTRACT_FACT:0");
        assessment.setEvidence(List.of("R-17 expects 2"));

        LlmPromptBuilder builder = new LlmPromptBuilder();
        String prompt = builder.sourceBugAnalysisPrompt(request, assessment);

        assertTrue(builder.sourceBugAnalysisSystemPrompt().contains("不得输出补丁"));
        assertTrue(prompt.contains("Subject.java:3"));
        assertTrue(prompt.contains("\"expectedValue\": \"2\""));
        assertTrue(prompt.contains("CONTRACT_FACT:0"));
        assertTrue(prompt.contains("只读"));
        assertTrue(prompt.contains("不得返回任何可应用的代码修改字段"));
    }

    @Test
    public void generationAndRepairPromptsRejectMockingSlf4jLogger() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setMethodSourceCode("@Slf4j class Subject { void run() { log.info(\"started\"); } }");

        LlmPromptBuilder builder = new LlmPromptBuilder();
        String generation = builder.systemPrompt() + builder.userPrompt(request);
        String coverage = builder.coveragePrompt(request);
        String classRepair = builder.classRepairPrompt(request);

        assertTrue(generation.contains("禁止创建 @Mock Logger"));
        assertTrue(generation.contains("verify(logger).info/error/warn/debug/trace"));
        assertTrue(coverage.contains("@Mock Logger 与生产 logger 不是同一实例"));
        assertTrue(classRepair.contains("MOCKITO_VERIFY_WRONG_INSTANCE"));
    }

    @Test
    public void runtimeRepairPromptEnumeratesClosedCategoryList() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("SubjectTest#t()");
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setRuntimeDiagnostics(List.of(diagnostic));
        request.setCoverageReport(new CoverageReport());
        request.setExistingTestCodeSummary("class SubjectTest { void t() {} }");

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        for (String code : FailureCategory.runtimeArbitrationCodes()) {
            assertTrue("封闭分类缺失：" + code, prompt.contains(code));
        }
        assertTrue(prompt.contains("不得发明新分类"));
        assertTrue(prompt.contains("MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET 表示 Mockito 拒绝 mockStatic 该目标"));
        assertTrue(prompt.contains("只对列出的失败测试有效"));
    }

    @Test
    public void classRepairPromptEnumeratesClosedCategoryList() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setMethodSourceCode("class Subject {}");

        String prompt = new LlmPromptBuilder().classRepairPrompt(request);

        for (String code : FailureCategory.runtimeArbitrationCodes()) {
            assertTrue("整类修复封闭分类缺失：" + code, prompt.contains(code));
        }
        assertTrue(prompt.contains("不得发明新分类"));
        assertTrue(prompt.contains("只对列出的失败测试有效"));
    }

    @Test
    public void generationPromptContainsResolvedCallableCatalog() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setClassName("OrderService");
        request.setMethodName("load");
        request.setSymbolSnapshot(snapshot());

        String prompt = new LlmPromptBuilder().userPrompt(request);

        assertTrue(prompt.contains("com.example.OrderRepository#findById(java.lang.Long)"));
        assertTrue(prompt.contains("\"parameterTypes\": ["));
        assertTrue(prompt.contains("projectSymbolSnapshot"));
    }

    @Test
    public void repairPromptContainsOnlyCandidateDiagnosticsAndSnapshot() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setSymbolSnapshot(snapshot());
        request.setExistingTestCodeSummary("class OrderServiceTest { void broken() {} }");
        request.setRepairAttempt(2);
        request.setCompilerDiagnostics(List.of(new CompileDiagnostic(
                "ERROR", "CANNOT_FIND_SYMBOL", "cannot find symbol: method missing()", 8, 12,
                "repository.missing()", true)));

        String prompt = new LlmPromptBuilder().repairPrompt(request);

        assertTrue(prompt.contains("\"repairAttempt\": 2"));
        assertTrue(prompt.contains("CANNOT_FIND_SYMBOL"));
        assertTrue(prompt.contains("repository.missing()"));
        assertTrue(prompt.contains("OrderServiceTest"));
    }

    @Test
    public void coveragePromptContainsStructuredGapsAndRuntimeDiagnostics() {
        CoverageGap gap = new CoverageGap();
        gap.setKind(CoverageGap.Kind.BRANCH);
        gap.setLineNumber(42);
        gap.setSource("if (amount > limit) {");
        gap.setMissedCount(1);
        gap.setTotalCount(2);
        CoverageSnapshot generated = new CoverageSnapshot();
        generated.setAvailable(true);
        generated.setLine(new CoverageMetric(9, 1));
        generated.setBranch(new CoverageMetric(1, 1));
        CoverageReport report = new CoverageReport();
        report.setGenerated(generated);
        report.setGaps(List.of(gap));
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("OrderServiceTest#shouldReject");
        diagnostic.setExceptionType("org.opentest4j.AssertionFailedError");
        diagnostic.setCategory("MOCKITO_MISSING_METHOD_INVOCATION");
        diagnostic.setExpectedExceptionType("java.lang.NullPointerException");
        diagnostic.setActualExceptionType("java.lang.reflect.InvocationTargetException");
        diagnostic.setRootCauseType("java.lang.NullPointerException");
        diagnostic.setCauseChain(List.of("java.lang.reflect.InvocationTargetException", "java.lang.NullPointerException"));
        diagnostic.setSourceLine(18);
        diagnostic.setSourceSnippet("when(subject.load()).thenReturn(value);");
        diagnostic.setSuggestion("删除对 @InjectMocks 被测对象的非法 when");
        diagnostic.setExpectedValue("expected-output");
        diagnostic.setActualValue("actual-output");
        diagnostic.setFailureDetails("full Mockito failure details");
        diagnostic.setTestFrame("at sample.OrderServiceTest.shouldReject(OrderServiceTest.java:18)");
        diagnostic.setProductionFrame("at sample.OrderService.reject(OrderService.java:42)");
        diagnostic.setRootCauseFrame("at sample.Repository.load(Repository.java:9)");

        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setCoverageReport(report);
        request.setRuntimeDiagnostics(List.of(diagnostic));
        request.setExistingTestCodeSummary("class OrderServiceTest {}");
        request.setFocusTestIds(List.of("OrderServiceTest#shouldReject"));
        request.setFailingTestIds(List.of("shouldReject", "shouldRejectBoundary"));
        request.setPassedTestIds(List.of("shouldLoad"));
        request.setMockitoStaticMockingSupported(false);
        request.setMockitoStaticMockingSupport("mockito-core-4.11.0.jar 未启用 inline MockMaker");

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(prompt.contains("\"kind\": \"BRANCH\""));
        assertTrue(prompt.contains("\"line\": 42"));
        assertTrue(prompt.contains("amount > limit"));
        assertTrue(prompt.contains("shouldReject"));
        assertTrue(prompt.contains("MOCKITO_MISSING_METHOD_INVOCATION"));
        assertTrue(prompt.contains("when(subject.load())"));
        assertTrue(prompt.contains("删除对 @InjectMocks"));
        assertTrue(prompt.contains("lineRate"));
        assertTrue(prompt.contains("\"staticMockingSupported\": false"));
        assertTrue(prompt.contains("mockito-core-4.11.0"));
        assertTrue(prompt.contains("\"taskMode\": \"RUNTIME_REPAIR\""));
        assertTrue(prompt.contains("意图仲裁"));
        assertTrue(prompt.contains("runtimeDiagnostics 只用于解释当前路径"));
        assertTrue(prompt.contains("禁止改成 assertThrows"));
        assertTrue(prompt.contains("确保测试输入、被测调用实参及 Mockito stub 的参数和值一致"));
        assertTrue(prompt.contains("返回前自检"));
        assertTrue(prompt.contains("expectedExceptionType"));
        assertTrue(prompt.contains("actualExceptionType"));
        assertTrue(prompt.contains("expected-output"));
        assertTrue(prompt.contains("actual-output"));
        assertTrue(prompt.contains("expectedTestClassName"));
        assertTrue(prompt.contains("POTENTIAL_SUT_BUG"));
        assertTrue(prompt.contains("rootCauseType"));
        assertTrue(prompt.contains("causeChain"));
        assertTrue(prompt.contains("ASSERT_THROWS_WRAPPED_BY_REFLECTION"));
        assertTrue(prompt.contains("full Mockito failure details"));
        assertTrue(prompt.contains("OrderService.java:42"));
        assertTrue(prompt.contains("shouldRejectBoundary"));
        assertTrue(prompt.contains("shouldLoad"));
        assertTrue(prompt.contains("methodPatches"));
        assertTrue(prompt.contains("不得返回 testCode"));
        assertTrue(prompt.contains("oracleEvidence"));
    }

    @Test
    public void compileRepairSuppressesStaleRuntimeDiagnostics() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setCompilerDiagnostics(List.of(new CompileDiagnostic(
                "ERROR", "CANNOT_FIND_SYMBOL", "cannot find symbol", 12, 8, "missingCall()", true)));
        RuntimeDiagnostic stale = new RuntimeDiagnostic();
        stale.setTestId("OrderServiceTest#staleRuntimeFailure");
        stale.setMessage("STALE_RUNTIME_MESSAGE");
        request.setRuntimeDiagnostics(List.of(stale));

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(prompt.contains("\"taskMode\": \"COMPILE_REPAIR\""));
        assertTrue(prompt.contains("CANNOT_FIND_SYMBOL"));
        assertFalse(prompt.contains("STALE_RUNTIME_MESSAGE"));
        assertFalse(prompt.contains("当前模式：RUNTIME_REPAIR"));
    }

    @Test
    public void runtimeRepairKeepsFullSourceAndDoesNotSpecialCaseExceptionMessage() {
        CoverageGap gap = gap(CoverageGap.Kind.BRANCH, 120, "if (invalid) throw failure;");
        CoverageReport report = new CoverageReport();
        report.setGaps(List.of(gap));
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("SubjectTest#shouldReturnNormallyWhenInputValid");
        diagnostic.setExceptionType("java.lang.IllegalArgumentException");
        diagnostic.setMessage("arbitrary domain failure");
        diagnostic.setStackTrace(List.of(
                "at sample.Subject.work(Subject.java:120)",
                "at sample.SubjectTest.shouldReturnNormallyWhenInputValid(SubjectTest.java:45)"));
        String fullSource = "FULL_SOURCE_PREFIX_MUST_REMAIN\n" + "x".repeat(4000);
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setCoverageReport(report);
        request.setRuntimeDiagnostics(List.of(diagnostic));
        request.setMethodSourceCode(fullSource);
        request.setExistingTestCodeSummary("class SubjectTest { void shouldReturnNormallyWhenInputValid() {} }");

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(prompt.contains("\"taskMode\": \"RUNTIME_REPAIR\""));
        assertTrue(prompt.contains("FULL_SOURCE_PREFIX_MUST_REMAIN"));
        assertFalse(prompt.contains("仅保留本轮未覆盖目标附近源码"));
        assertTrue(prompt.contains("arbitrary domain failure"));
        assertTrue(prompt.contains("实际异常只是本次执行的事实证据")
                || new LlmPromptBuilder().coverageSystemPrompt().contains("事实证据"));
        assertTrue(prompt.contains("成功/正常/有效输入场景意外抛异常时"));
    }

    @Test
    public void runtimeRepairPromptIncludesMockitoInvocationCountContract() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("DocumentTypeTest#shouldHandleBoundaryValues()");
        diagnostic.setCategory("MOCKITO_INVOCATION_COUNT_MISMATCH");
        diagnostic.setExpectedInvocationCount(1);
        diagnostic.setActualInvocationCount(2);
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setRuntimeDiagnostics(List.of(diagnostic));
        request.setCoverageReport(new CoverageReport());
        request.setExistingTestCodeSummary(
                "class DocumentTypeTest { void shouldHandleBoundaryValues() { verify(out, times(1)).append(\" \" ); } }");

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(prompt.contains("MOCKITO_INVOCATION_COUNT_MISMATCH"));
        assertTrue(prompt.contains("\"expectedInvocationCount\": 1"));
        assertTrue(prompt.contains("\"actualInvocationCount\": 2"));
        assertTrue(prompt.contains("times(n)/once/atLeast"));
        assertTrue(prompt.contains("不得用 atLeast(0)/anyTimes 伪造成功"));
    }

    @Test
    public void runtimeRepairPromptAssignsVerifiableAuthorityReferences() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("SubjectTest#value()");
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setRuntimeDiagnostics(List.of(diagnostic));
        request.setCoverageReport(new CoverageReport());
        ProjectSymbolSnapshot snapshot = new ProjectSymbolSnapshot();
        snapshot.setTestExamples(List.of("@Test void contractExample() { assertEquals(2, subject.value()); }"));
        snapshot.setContractFacts(List.of("CONTRACT: R-17 says value is 2"));
        request.setSymbolSnapshot(snapshot);
        TestScenario scenario = new TestScenario();
        scenario.setOracleEvidence("USER_REQUIREMENT");
        scenario.setExpectedAssertions(List.of("assertEquals(2, result)"));
        request.setScenarios(List.of(scenario));

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(prompt.contains("USER_REQUIREMENT:0"));
        assertTrue(prompt.contains("EXISTING_TEST:0"));
        assertTrue(prompt.contains("CONTRACT_FACT:0"));
        assertTrue(prompt.contains("oracleAuthorityRef"));
        assertTrue(prompt.contains("actualValue 和当前生产实现"));
    }

    @Test
    public void coveragePromptIncludesCurrentTestCodeOnlyOnce() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setExistingTestCodeSummary("class UniqueCoverageCandidateTest {}");
        request.setCoverageReport(new CoverageReport());

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertEquals(1, occurrences(prompt, "class UniqueCoverageCandidateTest {}"));
    }

    @Test
    public void coveragePromptFocusesLargeMethodsOnUncoveredSource() {
        CoverageGap gap = new CoverageGap();
        gap.setLineNumber(120);
        gap.setContext("119: prepare();\n120: if (missing) recover();\n121: finish();");
        CoverageReport report = new CoverageReport();
        report.setGaps(List.of(gap));
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setCoverageReport(report);
        request.setMethodSourceCode("METHOD_ENTRY_MUST_REMAIN\n" + "x".repeat(6000)
                + "OMITTED_MIDDLE_MARKER\n" + "y".repeat(7000) + "METHOD_TAIL_MUST_REMAIN");

        String prompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(prompt.contains("METHOD_ENTRY_MUST_REMAIN"));
        assertFalse(prompt.contains("OMITTED_MIDDLE_MARKER"));
        assertTrue(prompt.contains("METHOD_TAIL_MUST_REMAIN"));
        assertTrue(prompt.contains("if (missing) recover()"));
        assertTrue(prompt.contains("超长方法的路径切片"));
    }

    @Test
    public void coveragePromptLimitsTargetsAndRotatesAfterNoProgress() {
        CoverageGap first = gap(CoverageGap.Kind.BRANCH, 10, "if (first) work();");
        CoverageGap second = gap(CoverageGap.Kind.BRANCH, 20, "if (second) work();");
        CoverageReport report = new CoverageReport();
        report.setGaps(List.of(first, second,
                gap(CoverageGap.Kind.LINE, 30, "fallback();"),
                gap(CoverageGap.Kind.LINE, 40, "fallback2();"),
                gap(CoverageGap.Kind.LINE, 50, "fallback3();"),
                gap(CoverageGap.Kind.LINE, 60, "fallback4();")));
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setCoverageReport(report);

        String firstPrompt = new LlmPromptBuilder().coveragePrompt(request);
        report.incrementNoProgressCandidates();
        String secondPrompt = new LlmPromptBuilder().coveragePrompt(request);

        assertTrue(firstPrompt.matches("(?s).*\\{[^{}]*\\\"line\\\": 10[^{}]*\\\"primary\\\": true[^{}]*}.*")
                || firstPrompt.matches("(?s).*\\{[^{}]*\\\"primary\\\": true[^{}]*\\\"line\\\": 10[^{}]*}.*"));
        assertTrue(secondPrompt.matches("(?s).*\\{[^{}]*\\\"line\\\": 20[^{}]*\\\"primary\\\": true[^{}]*}.*")
                || secondPrompt.matches("(?s).*\\{[^{}]*\\\"primary\\\": true[^{}]*\\\"line\\\": 20[^{}]*}.*"));
        assertTrue(firstPrompt.contains("\"omittedGapCount\": 1"));
        assertTrue(firstPrompt.contains("coverage.gaps[0] 是唯一主目标"));
    }

    @Test
    public void generationPromptSerializesMockPolicyAndOracleConfidence() {
        TestScenario scenario = new TestScenario();
        scenario.setName("normalCase");
        scenario.setOracleKind("UNKNOWN");
        scenario.setOracleEvidence("STATIC_ANALYSIS");
        scenario.setOracleConfidence("LOW");
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setMockUsagePolicy("FORBIDDEN");
        request.setScenarios(List.of(scenario));

        String prompt = new LlmPromptBuilder().userPrompt(request);

        assertTrue(prompt.contains("\"mockUsagePolicy\": \"FORBIDDEN\""));
        assertTrue(prompt.contains("\"confidence\": \"LOW\""));
        assertTrue(prompt.contains("场景名只表示覆盖目标，不是精确 Oracle"));
        assertTrue(prompt.contains("FORBIDDEN 时禁止新建 mock/spy/verify"));
    }

    private CoverageGap gap(CoverageGap.Kind kind, int line, String source) {
        CoverageGap gap = new CoverageGap();
        gap.setKind(kind);
        gap.setLineNumber(line);
        gap.setSource(source);
        gap.setContext(line + ": " + source);
        gap.setMissedCount(1);
        gap.setTotalCount(kind == CoverageGap.Kind.BRANCH ? 2 : 1);
        return gap;
    }

    private int occurrences(String text, String value) {
        int count = 0;
        int cursor = 0;
        while ((cursor = text.indexOf(value, cursor)) >= 0) {
            count++;
            cursor += value.length();
        }
        return count;
    }

    private ProjectSymbolSnapshot snapshot() {
        CallableSymbol method = new CallableSymbol();
        method.setId("com.example.OrderRepository#findById(java.lang.Long)");
        method.setOwnerQualifiedName("com.example.OrderRepository");
        method.setName("findById");
        method.setReturnType("java.util.Optional<com.example.Order>");
        method.setParameterTypes(List.of("java.lang.Long"));

        TypeSymbol type = new TypeSymbol();
        type.setQualifiedName("com.example.OrderRepository");
        type.setKind("interface");
        type.setMethods(List.of(method));

        ProjectSymbolSnapshot snapshot = new ProjectSymbolSnapshot();
        snapshot.setTargetType("com.example.OrderService");
        snapshot.setTargetMethod(method);
        snapshot.setTypes(List.of(type));
        return snapshot;
    }
}
