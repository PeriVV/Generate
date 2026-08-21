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
import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
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
                response.setTestCode(initialCode.replace("assertEquals(2", "assertEquals(1"));
                return response;
            }
        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> stub);
        ClassGenerationResult input = classResult(initialCode);

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
