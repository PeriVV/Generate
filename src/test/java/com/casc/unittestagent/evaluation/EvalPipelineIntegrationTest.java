package com.casc.unittestagent.evaluation;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.coverage.CoverageProgressListener;
import com.casc.unittestagent.llm.LlmClient;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.ClassJUnitStopReason;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.SourceBugAnalysis;
import com.casc.unittestagent.model.SourceBugLocation;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.PathUtil;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline proof that the evaluation runner assumptions work against the real plugin pipeline. */
public class EvalPipelineIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor REAL_JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() {
            return JavaSdk.getInstance().createJdk("UnitMindEvalPipelineJdk",
                    System.getProperty("java.home"), false);
        }
    };

    @Override protected LightProjectDescriptor getProjectDescriptor() { return REAL_JDK; }

    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        attachRuntime();
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(true);
        llm.setApiUrl("https://offline.invalid/v1/chat/completions");
        llm.setModel("offline-stub");
        llm.setMaxCompileRepairRounds(5);
        llm.setFallbackToRuleBased(true);
        LlmConfigManager.save(llm, null);
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRounds(0);
        coverage.setMaxRepairRounds(0);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
    }

    public void testStubGeneratedCandidateCompilesRunsAndProducesCoverage() throws Exception {
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                public class Subject {
                    public String <caret>classify(int value) {
                        return value >= 0 ? "NON_NEGATIVE" : "NEGATIVE";
                    }
                }
                """);
        LlmClient stub = request -> {
            LlmGenerationResponse response = new LlmGenerationResponse();
            response.setTestCode("""
                    package sample;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class SubjectTest {
                        @Test void nonNegative() { assertEquals("NON_NEGATIVE", new Subject().classify(0)); }
                        @Test void negative() { assertEquals("NEGATIVE", new Subject().classify(-1)); }
                    }
                    """);
            return response;
        };
        EvalLlmTelemetry telemetry = new EvalLlmTelemetry(config -> stub);
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), telemetry);

        Future<GenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> {
            GenerationResult result = service.generate(getProject(), source, myFixture.getEditor());
            if (result.getValidationResult().getStatus() == ValidationStatus.PASSED) {
                service.runCoverageLoop(getProject(), source, result,
                        result.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE);
            }
            return result;
        }, new EmptyProgressIndicator()));
        GenerationResult result = future.get(2, TimeUnit.MINUTES);

        assertEquals(result.getWarnings().toString(), ValidationStatus.PASSED,
                result.getValidationResult().getStatus());
        assertTrue(result.getCoverageReport().getGenerated().getExecution().isSuccessful());
        assertTrue(result.getCoverageReport().getGenerated().isAvailable());
        assertEquals(1, telemetry.getRequests());
    }

    public void testFinalClassJUnitLoopRepairsFailureAndRecordsAtOne() throws Exception {
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRounds(0);
        coverage.setMaxRepairRounds(5);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                public class Subject {
                    public int <caret>value() { return 1; }
                }
                """);
        String initialCode = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    // UnitMind method: sample.Subject#value()
                    @Test void valueIsOne() { assertEquals(2, new Subject().value()); }
                }
                """;
        AtomicInteger repairs = new AtomicInteger();
        LlmClient stub = new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                throw new AssertionError("Initial generation must not be called");
            }

            @Override public LlmGenerationResponse repairClassTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                repairs.incrementAndGet();
                LlmGenerationResponse response = new LlmGenerationResponse();
                response.setRepairScope("METHOD_ONLY");
                response.setDiagnosisRootCauseCategory("ASSERTION_VALUE_MISMATCH");
                response.setDiagnosisEvidence(List.of("contract fact says value is one"));
                response.setOracleDecision("CORRECT");
                response.setOracleAuthority("CONTRACT_FACT");
                response.setOracleAuthorityRef("CONTRACT_FACT:0");
                response.setRepairDisposition("REPAIR_TEST");
                response.setTestCode(initialCode.replace("assertEquals(2", "assertEquals(1"));
                return response;
            }

        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> stub);
        ClassGenerationResult input = withContractFact(classResult(initialCode),
                "value() contract returns 1");

        Future<ClassGenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> service.runFinalClassJUnitRepair(
                        getProject(), source, input, initialCode, () -> false,
                        CoverageProgressListener.NONE), new EmptyProgressIndicator()));
        ClassGenerationResult result = future.get(2, TimeUnit.MINUTES);

        assertEquals(ClassJUnitStatus.PASSED, result.getClassJUnitRepairReport().getStatus());
        assertFalse(result.getClassJUnitRepairReport().getInitialExecution().isSuccessful());
        assertTrue(result.getClassJUnitRepairReport().getFinalExecution().isSuccessful());
        assertEquals(1, result.getClassJUnitRepairReport().getRuntimeRepairRounds());
        assertEquals(1, repairs.get());
        assertTrue(result.getGeneratedTestFile().getContent().contains("assertEquals(1"));
    }

    public void testFinalClassJUnitRepairsMechanicalFailureBeforeKeepingIndependentOracleConflict()
            throws Exception {
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRounds(0);
        coverage.setMaxRepairRounds(3);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                public class Subject {
                    public int <caret>value() { return 1; }
                }
                """);
        String initialCode = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                import static org.mockito.Mockito.*;
                class SubjectTest {
                    @Test void contradictoryInteraction() {
                        Runnable dependency = mock(Runnable.class);
                        dependency.run();
                        verify(dependency).run();
                        verifyNoInteractions(dependency);
                    }
                    @Test void contractFailure() {
                        assertEquals(2, new Subject().value());
                    }
                }
                """;
        String interactionRepaired = initialCode.replace(
                "verifyNoInteractions(dependency)", "verifyNoMoreInteractions(dependency)");
        AtomicInteger repairs = new AtomicInteger();
        LlmClient stub = new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                throw new AssertionError("Initial generation must not be called");
            }

            @Override public LlmGenerationResponse repairClassTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                int call = repairs.incrementAndGet();
                assertEquals(1, request.getFocusTestIds().size());
                assertEquals(1, request.getRuntimeDiagnostics().size());
                LlmGenerationResponse response = new LlmGenerationResponse();
                response.setRepairScope("METHOD_ONLY");
                response.setOracleDecision("KEEP");
                response.setOracleAuthority("NONE");
                response.setOracleAuthorityRef("NONE");
                if (call == 1) {
                    assertTrue(request.getFocusTestIds().get(0).contains("contradictoryInteraction"));
                    response.setDiagnosisRootCauseCategory("MOCKITO_NO_INTERACTIONS_CONTRADICTION");
                    response.setDiagnosisEvidence(List.of("verify followed by verifyNoInteractions on same mock"));
                    response.setRepairDisposition("REPAIR_TEST");
                    response.setTestCode(interactionRepaired);
                } else {
                    assertTrue(request.getFocusTestIds().get(0).contains("contractFailure"));
                    response.setDiagnosisRootCauseCategory("ASSERTION_VALUE_MISMATCH");
                    response.setDiagnosisEvidence(List.of("no independent contract authority"));
                    response.setRepairDisposition("INCONCLUSIVE");
                }
                return response;
            }
        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> stub);
        ClassGenerationResult input = classResult(
                initialCode.replace("class SubjectTest", "final class SubjectTest"));

        Future<ClassGenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> service.runFinalClassJUnitRepair(
                        getProject(), source, input, initialCode, () -> false,
                        CoverageProgressListener.NONE), new EmptyProgressIndicator()));
        ClassGenerationResult result = future.get(2, TimeUnit.MINUTES);

        assertEquals(result.getClassJUnitRepairReport().getStatus() + " / "
                + result.getClassJUnitRepairReport().getStopReason() + " / "
                + result.getClassJUnitRepairReport().getMessage(), 2, repairs.get());
        assertEquals(ClassJUnitStatus.NEEDS_REVIEW, result.getClassJUnitRepairReport().getStatus());
        assertEquals(2, result.getClassJUnitRepairReport().getAttempts().size());
        assertTrue(result.getClassJUnitRepairReport().getAttempts().get(0).isAccepted());
        assertEquals(List.of("contradictoryInteraction"),
                result.getClassJUnitRepairReport().getAttempts().get(0).getFocusedTestIds());
        assertEquals(List.of("contractFailure"),
                result.getClassJUnitRepairReport().getAttempts().get(1).getFocusedTestIds());
        assertTrue(result.getGeneratedTestFile().getContent().contains(
                "verifyNoMoreInteractions(dependency)"));
        assertTrue(result.getGeneratedTestFile().getContent().contains(
                "assertEquals(2, new Subject().value())"));
        assertEquals(1, result.getClassJUnitRepairReport().getFinalExecution().getFailed());
    }

    public void testFinalClassJUnitLoopPreservesTestWhenModelReportsSutBug() throws Exception {
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRounds(0);
        coverage.setMaxRepairRounds(3);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                public class Subject {
                    public int <caret>value() { return 1; }
                }
                """);
        String initialCode = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    // UnitMind method: sample.Subject#value()
                    @Test void valueFollowsContract() { assertEquals(2, new Subject().value()); }
                }
                """;
        LlmClient stub = new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                throw new AssertionError("Initial generation must not be called");
            }

            @Override public LlmGenerationResponse repairClassTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                LlmGenerationResponse response = new LlmGenerationResponse();
                response.setRepairScope("METHOD_ONLY");
                response.setDiagnosisRootCauseCategory("POTENTIAL_SUT_BUG");
                response.setDiagnosisEvidence(List.of("requirement R-17 requires value 2"));
                response.setOracleDecision("KEEP");
                response.setOracleAuthority("CONTRACT_FACT");
                response.setOracleAuthorityRef("CONTRACT_FACT:0");
                response.setRepairDisposition("SUSPECTED_SUT_BUG");
                return response;
            }

            @Override public SourceBugAnalysis analyzeSuspectedSutBug(
                    com.casc.unittestagent.llm.LlmGenerationRequest request,
                    OracleAssessment assessment) {
                return sourceBugAnalysis("Subject.java", 3, "value() returns 1");
            }
        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> stub);

        ClassGenerationResult input = withContractFact(classResult(initialCode),
                "requirement R-17 requires value 2");
        Future<ClassGenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> service.runFinalClassJUnitRepair(
                        getProject(), source, input, initialCode, () -> false,
                        CoverageProgressListener.NONE), new EmptyProgressIndicator()));
        ClassGenerationResult result = future.get(2, TimeUnit.MINUTES);

        assertEquals(ClassJUnitStatus.SUSPECTED_SUT_BUG,
                result.getClassJUnitRepairReport().getStatus());
        assertEquals(ClassJUnitStopReason.SUSPECTED_SUT_BUG,
                result.getClassJUnitRepairReport().getStopReason());
        assertTrue(result.getGeneratedTestFile().getContent().contains("assertEquals(2"));
        assertFalse(result.getClassJUnitRepairReport().getFinalExecution().isSuccessful());
        assertTrue(result.getClassJUnitRepairReport().getSourceBugAnalysis().isAvailable());
        assertEquals("Subject.java",
                result.getClassJUnitRepairReport().getSourceBugAnalysis()
                        .getSuspectedLocations().get(0).getFile());
    }

    public void testClassPreflightRemovesUnreachableStaticLoggerVerifyWhenBusinessOracleRemains()
            throws Exception {
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRepairRounds(2);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                import java.util.logging.Logger;
                public class Subject {
                    private static final Logger log = Logger.getLogger(Subject.class.getName());
                    public int <caret>value() { log.info("value"); return 1; }
                }
                """);
        String initialCode = """
                package sample;
                import java.util.logging.Logger;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.mockito.Mockito.verify;
                @ExtendWith(MockitoExtension.class)
                class SubjectTest {
                    @Mock Logger logger;
                    @Test void valueHasBusinessResult() {
                        assertEquals(1, new Subject().value());
                        verify(logger).info("value");
                    }
                }
                """;
        LlmClient mustNotRun = request -> {
            throw new AssertionError("deterministic logger wiring repair must run before LLM");
        };
        ClassGenerationResult input = classResult(initialCode);

        ClassGenerationResult result = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> new UnitTestGenerationService(
                        new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(),
                        config -> mustNotRun).runFinalClassJUnitRepair(
                        getProject(), source, input, initialCode, () -> false,
                        CoverageProgressListener.NONE), new EmptyProgressIndicator()))
                .get(2, TimeUnit.MINUTES);

        assertEquals(ClassJUnitStatus.PASSED, result.getClassJUnitRepairReport().getStatus());
        assertEquals(0, result.getClassJUnitRepairReport().getModelRequests());
        assertFalse(result.getGeneratedTestFile().getContent().contains("verify(logger)"));
        assertTrue(result.getGeneratedTestFile().getContent().contains("assertEquals(1"));
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("不是同一实例")
                || value.contains("无法观察")));
    }

    public void testClassLoopStopsAsTestDesignErrorWhenStaticLoggerVerifyIsOnlyOracle()
            throws Exception {
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRepairRounds(2);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                import java.util.logging.Logger;
                public class Subject {
                    private static final Logger log = Logger.getLogger(Subject.class.getName());
                    public int <caret>value() { log.info("value"); return 1; }
                }
                """);
        String initialCode = """
                package sample;
                import java.util.logging.Logger;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import static org.mockito.Mockito.verify;
                @ExtendWith(MockitoExtension.class)
                class SubjectTest {
                    @Mock Logger logger;
                    @Test void logsValue() {
                        new Subject().value();
                        verify(logger).info("value");
                    }
                }
                """;
        LlmClient mustNotRun = request -> {
            throw new AssertionError("test design error must stop before LLM");
        };

        ClassGenerationResult result = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> new UnitTestGenerationService(
                        new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(),
                        config -> mustNotRun).runFinalClassJUnitRepair(
                        getProject(), source, classResult(initialCode), initialCode, () -> false,
                        CoverageProgressListener.NONE), new EmptyProgressIndicator()))
                .get(2, TimeUnit.MINUTES);

        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getClassJUnitRepairReport().getStatus());
        assertEquals(ClassJUnitStopReason.TEST_DESIGN_ERROR,
                result.getClassJUnitRepairReport().getStopReason());
        assertEquals(0, result.getClassJUnitRepairReport().getModelRequests());
        assertTrue(result.getGeneratedTestFile().getContent().contains("verify(logger)"));
        assertTrue(result.getClassJUnitRepairReport().getMessage().contains("不是同一实例"));
        assertEquals("MOCKITO_VERIFY_WRONG_INSTANCE",
                result.getClassJUnitRepairReport().getFinalExecution()
                        .getDiagnostics().get(0).getCategory());
    }

    public void testCoverageLoopPreservesFailingCandidateWhenModelReportsSutBug() throws Exception {
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRounds(0);
        coverage.setMaxRepairRounds(3);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                public class Subject {
                    public int <caret>value() { return 1; }
                }
                """);
        String initialCode = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    @Test void valueFollowsContract() { assertEquals(2, new Subject().value()); }
                }
                """;
        AtomicInteger repairs = new AtomicInteger();
        LlmClient stub = new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                throw new AssertionError("Initial generation must not be called");
            }

            @Override public LlmGenerationResponse improveCoverage(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                repairs.incrementAndGet();
                LlmGenerationResponse response = new LlmGenerationResponse();
                response.setDiagnosisRootCauseCategory("POTENTIAL_SUT_BUG");
                response.setDiagnosisEvidence(List.of("existing contract test requires value 2"));
                response.setOracleDecision("KEEP");
                response.setOracleAuthority("CONTRACT_FACT");
                response.setOracleAuthorityRef("CONTRACT_FACT:0");
                response.setRepairDisposition("SUSPECTED_SUT_BUG");
                return response;
            }

            @Override public SourceBugAnalysis analyzeSuspectedSutBug(
                    com.casc.unittestagent.llm.LlmGenerationRequest request,
                    OracleAssessment assessment) {
                return sourceBugAnalysis("Subject.java", 3, "contract mismatch");
            }
        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> stub);
        ClassGenerationResult input = withContractFact(classResult(initialCode),
                "existing contract requires value 2");

        Future<GenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> service.runCoverageLoop(
                        getProject(), source, input, initialCode, () -> false,
                        CoverageProgressListener.NONE), new EmptyProgressIndicator()));
        GenerationResult result = future.get(2, TimeUnit.MINUTES);

        assertEquals(CoverageStatus.SUSPECTED_SUT_BUG, result.getCoverageReport().getStatus());
        assertEquals(CoverageStopReason.SUSPECTED_SUT_BUG,
                result.getCoverageReport().getStopReason());
        assertEquals(1, repairs.get());
        assertTrue(result.getGeneratedTestFile().getContent().contains("assertEquals(2"));
        assertFalse(result.getCoverageReport().getGenerated().getExecution().isSuccessful());
        assertTrue(result.getCoverageReport().getSourceBugAnalysis().isAvailable());
        assertTrue(result.getCoverageReport().getVisitedPhases()
                .contains(com.casc.unittestagent.model.CoveragePhase.DIAGNOSING_SUT_BUG));
    }

    public void testClassLoopRejectsKeepRepairThatWouldChangeArrangeAfterAssertionMismatch() throws Exception {
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRepairRounds(2);
        coverage.setExistingBaselineEnabled(false);
        CoverageConfigManager.save(coverage);
        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                public class Subject { public int <caret>value() { return 1; } }
                """);
        String initialCode = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    @Test void valueForContractInput() { assertEquals(2, new Subject().value()); }
                }
                """;
        LlmClient stub = new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                throw new AssertionError("Initial generation must not be called");
            }

            @Override public LlmGenerationResponse repairClassTest(
                    com.casc.unittestagent.llm.LlmGenerationRequest request) {
                LlmGenerationResponse response = new LlmGenerationResponse();
                response.setRepairScope("METHOD_ONLY");
                response.setDiagnosisRootCauseCategory("TEST_INPUT");
                response.setDiagnosisEvidence(List.of("change input until actual becomes expected"));
                response.setOracleDecision("KEEP");
                response.setOracleAuthority("NONE");
                response.setOracleAuthorityRef("NONE");
                response.setRepairDisposition("REPAIR_TEST");
                response.setTestCode(initialCode.replace("new Subject().value()", "2"));
                return response;
            }
        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> stub);

        Future<ClassGenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> service.runFinalClassJUnitRepair(
                        getProject(), source, classResult(initialCode), initialCode, () -> false,
                        CoverageProgressListener.NONE), new EmptyProgressIndicator()));
        ClassGenerationResult result = future.get(2, TimeUnit.MINUTES);

        assertEquals(ClassJUnitStatus.NEEDS_REVIEW, result.getClassJUnitRepairReport().getStatus());
        assertEquals(ClassJUnitStopReason.ORACLE_INCONCLUSIVE,
                result.getClassJUnitRepairReport().getStopReason());
        assertTrue(result.getGeneratedTestFile().getContent().contains("new Subject().value()"));
        assertFalse(result.getGeneratedTestFile().getContent().contains("assertEquals(2, 2)"));
    }

    private ClassGenerationResult classResult(String code) {
        MethodModel method = new MethodModel();
        method.setMethodId("sample.Subject#value()");
        method.setSignature("value(): int");
        method.setPackageName("sample");
        method.setClassName("Subject");
        method.setMethodName("value");
        method.setReturnType("int");
        method.setMethodBodyText("public int value() { return 1; }");
        ClassModel model = new ClassModel();
        model.setPackageName("sample");
        model.setClassName("Subject");
        model.setQualifiedName("sample.Subject");
        model.setMethods(List.of(method));
        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName("sample");
        file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java");
        file.setContent(code);
        ClassGenerationResult result = new ClassGenerationResult();
        result.setClassModel(model);
        result.setMethodModel(method);
        result.setClassMethodSignatures(List.of(method.getSignature()));
        result.setGeneratedTestFile(file);
        result.setGeneratedByLlm(true);
        result.setComplete(true);
        result.setValidationResult(ValidationResult.status(ValidationStatus.PASSED));
        return result;
    }

    private static SourceBugAnalysis sourceBugAnalysis(String file, int line, String summary) {
        SourceBugLocation location = new SourceBugLocation();
        location.setFile(file);
        location.setLine(line);
        location.setSymbol("value()");
        location.setReason("runtime actual conflicts with the independent contract");
        SourceBugAnalysis analysis = new SourceBugAnalysis();
        analysis.setAttempted(true);
        analysis.setAvailable(true);
        analysis.setConfidence("HIGH");
        analysis.setSummary(summary);
        analysis.setSuspectedLocations(List.of(location));
        analysis.setEvidence(List.of("contract expects 2; runtime returned 1"));
        analysis.setRecommendedChecks(List.of("review the value() return expression"));
        return analysis;
    }

    private ClassGenerationResult withContractFact(ClassGenerationResult result, String fact) {
        ProjectSymbolSnapshot snapshot = new ProjectSymbolSnapshot();
        snapshot.setContractFacts(List.of("CONTRACT: " + fact));
        snapshot.setTargetType("sample.Subject");
        CallableSymbol target = new CallableSymbol();
        target.setId("sample.Subject#value()");
        target.setResolved(true);
        snapshot.setTargetMethod(target);
        result.setSymbolSnapshot(snapshot);
        return result;
    }

    private void attachRuntime() throws Exception {
        Set<String> paths = new HashSet<>();
        for (String name : List.of("org.junit.jupiter.api.Test", "org.junit.jupiter.api.Assertions",
                "org.junit.jupiter.api.extension.ExtendWith", "org.mockito.Mockito",
                "org.mockito.junit.jupiter.MockitoExtension", "org.junit.platform.console.ConsoleLauncher",
                "org.opentest4j.AssertionFailedError", "net.bytebuddy.ByteBuddy",
                "net.bytebuddy.agent.ByteBuddyAgent", "org.objenesis.Objenesis")) {
            String path = PathUtil.getJarPathForClass(Class.forName(name));
            if (path != null && paths.add(path)) {
                File root = new File(path);
                PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(),
                        "unitmind-eval-pipeline-" + paths.size(), root.getParent(), root.getName());
            }
        }
    }
}
