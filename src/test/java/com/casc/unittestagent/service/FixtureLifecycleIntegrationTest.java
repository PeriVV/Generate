package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.llm.*;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real MockitoExtension order, real compiler and real JUnit; no model/network requests. */
public class FixtureLifecycleIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("FixtureLifecycleJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample;
            public class Subject {
                private final Repository repository;
                public Subject(Repository repository) { this.repository = java.util.Objects.requireNonNull(repository); }
                public String lookup(String key) {
                    String value = repository.find(key);
                    if (value == null) throw new IllegalArgumentException("not found");
                    return value;
                }
            }
            """;
    private static final String TEST = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.junit.jupiter.MockitoExtension;
            import org.mockito.Mock;
            import org.mockito.Spy;
            import static org.mockito.Mockito.*;
            import static org.junit.jupiter.api.Assertions.*;
            @ExtendWith(MockitoExtension.class)
            class SubjectTest {
                @Mock Repository repository;
                @Spy Subject service;
                @BeforeEach void setup() { service = new Subject(repository); }
                @Test void absentThrows() {
                    when(repository.find("missing")).thenReturn(null);
                    assertThrows(IllegalArgumentException.class, () -> service.lookup("missing"));
                }
                @Test void presentReturns() {
                    when(repository.find("present")).thenReturn("ok");
                    assertEquals("ok", service.lookup("present"));
                }
            }
            """;
    private static final String FIXED = TEST.replace("@Spy Subject service;", "Subject service;")
            .replace("service = new Subject(repository);", "service = org.mockito.Mockito.spy(new Subject(repository));");
    private Path root;
    private PsiFile source;
    private final AtomicInteger compileRepairs = new AtomicInteger();
    private final AtomicInteger classRepairs = new AtomicInteger();
    private final AtomicInteger validations = new AtomicInteger();
    private boolean changeTestBody;
    private boolean mislabelAndWrongScope;
    private UnitTestGenerationService service;

    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-fixture-lifecycle-");
        Path sources = root.resolve("src/main/java"); Files.createDirectories(sources.resolve("sample"));
        Files.writeString(sources.resolve("sample/Subject.java"), SOURCE);
        Files.writeString(sources.resolve("sample/Repository.java"), "package sample; public interface Repository { String find(String key); }");
        var vfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources); PsiTestUtil.addSourceRoot(getModule(), vfs);
        source = PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/Subject.java"));
        var llm = new LlmConfig(); llm.setEnabled(true); llm.setMaxCompileRepairRounds(2); LlmConfigManager.save(llm, null);
        var config = new CoverageConfig(); config.setMaxRounds(1); config.setMaxRepairRounds(1); config.setLineThreshold(1); config.setBranchThreshold(1); CoverageConfigManager.save(config);
        var validator = new ProjectCompileVerifier();
        service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), (p, psi, file, code) -> {
            validations.incrementAndGet(); return validator.validate(p, psi, file, code);
        }, ignored -> new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) { return code(TEST); }
            @Override public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                compileRepairs.incrementAndGet();
                assertTrue(request.getCompilerDiagnostics().toString(), request.getCompilerDiagnostics().stream().anyMatch(d -> "FIXTURE_INITIALIZATION_CONFLICT".equals(d.getCode())));
                return code(FIXED);
            }
            @Override public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) { throw new AssertionError("initialization must use the shared class runtime engine"); }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) {
                classRepairs.incrementAndGet();
                assertTrue(request.getRuntimeDiagnostics().toString(), request.getRuntimeDiagnostics().stream().allMatch(d ->
                        FixtureLifecycleAnalyzer.CATEGORY.equals(d.getCategory()) && d.getFailurePhase() == RuntimeFailurePhase.TEST_SETUP));
                assertTrue(request.getRuntimeDiagnostics().get(0).getLifecycleEvidence().size() > 0);
                var response = code(changeTestBody ? FIXED.replace("void absentThrows() {", "void absentThrows() { String unrelated = \"changed\";") : FIXED);
                response.setRepairScope("SHARED_FIXTURE"); response.setRepairDisposition("REPAIR_TEST"); response.setOracleDecision("KEEP");
                response.setOracleAuthority("NONE"); response.setOracleAuthorityRef("NONE");
                response.setDiagnosisRootCauseCategory(FixtureLifecycleAnalyzer.CATEGORY); response.setDiagnosisEvidence(List.of("Mockito initialization failed before test body"));
                if (mislabelAndWrongScope) {
                    response.setRepairScope("METHOD_ONLY"); response.setDiagnosisRootCauseCategory("FIXTURE_DEPENDENCY_MISSING");
                    response.setOracleAuthority("EXISTING_TEST"); response.setOracleAuthorityRef("EXISTING_TEST:999");
                }
                return response;
            }
        });
    }

    public void testGenerationRepairsLifecycleBeforeFirstCompilerOrTestExecution() throws Exception {
        var result = background(() -> service.generateTestCode(getProject(), source, candidate()));
        assertEquals(result.getValidationResult().getDiagnostics().toString(), ValidationStatus.PASSED, result.getValidationResult().getStatus());
        assertEquals(1, compileRepairs.get()); assertEquals(1, validations.get()); assertEquals(0, classRepairs.get());
        assertEquals(ClassJUnitStatus.NOT_RUN, result.getRuntimeValidationReport().getStatus());
        assertTrue(result.getGeneratedTestFile().getContent().contains("org.mockito.Mockito.spy(new Subject(repository))"));
        var regression = background(() -> service.runFinalTestRegression(getProject(), source, result, result.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE));
        assertEquals(regression.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED, regression.getRuntimeValidationReport().getStatus());
    }

    public void testRuntimeRepairUsesSharedFixtureWithoutChangingAnyTestBody() throws Exception {
        var result = background(() -> service.runRuntimeValidation(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        var report = result.getRuntimeValidationReport();
        assertEquals(report.getMessage(), ClassJUnitStatus.PASSED, report.getStatus());
        assertEquals(1, classRepairs.get()); assertEquals(1, report.getFixtureRepairRounds());
        assertEquals(2, report.getInitialExecution().getFailed()); assertEquals(2, report.getFinalExecution().getSucceeded());
        assertEquals(FixtureLifecycleAnalyzer.CATEGORY, report.getInitialExecution().getDiagnostics().get(0).getCategory());
    }

    public void testCoverageDelegatesInitializationRepairAndUsesRemainingBudget() throws Exception {
        var result = background(() -> service.runCoverageLoop(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        var report = result.getCoverageReport();
        assertEquals(report.getMessage(), CoverageStatus.PASSED, report.getStatus());
        assertEquals(1, classRepairs.get()); assertEquals(1, report.getRepairRounds()); assertEquals(1, report.getModelRequests());
        assertEquals(1, result.getRuntimeValidationReport().getFixtureRepairRounds());
        assertTrue(report.getGenerated().getExecution().isSuccessful());
    }

    public void testSharedFixtureAuthorizationDoesNotPermitArrangeEditsInsideTestBody() throws Exception {
        changeTestBody = true;
        var result = background(() -> service.runRuntimeValidation(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        var report = result.getRuntimeValidationReport();
        assertEquals(ClassJUnitStatus.TEST_FAILED, report.getStatus()); assertEquals(1, classRepairs.get());
        assertEquals(TEST, result.getGeneratedTestFile().getContent());
        assertEquals("REPAIR_SCOPE", report.getCandidateRejections().get(0).stage());
    }

    public void testRejectedSharedRepairDoesNotResetCoverageBudget() throws Exception {
        changeTestBody = true;
        var result = background(() -> service.runCoverageLoop(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        assertEquals(CoverageStatus.TEST_FAILED, result.getCoverageReport().getStatus()); assertEquals(1, classRepairs.get());
        assertEquals(1, result.getCoverageReport().getRepairRounds()); assertEquals(1, result.getCoverageReport().getCandidateRejections().size());
        assertEquals(FixtureLifecycleAnalyzer.CATEGORY, result.getCoverageReport().getOracleAssessment().getRootCauseCategory());
        assertEquals(TEST, result.getGeneratedTestFile().getContent());
    }

    public void testFinalMergedRegressionKeepsExactCodeAndDoesNotRepair() throws Exception {
        var result = background(() -> service.runFinalTestRegression(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getRuntimeValidationReport().getStatus());
        assertEquals(TEST, result.getGeneratedTestFile().getContent()); assertEquals(0, classRepairs.get());
    }

    public void testRunnerControlsInitializationScopeAndEvidenceDespiteModelMisclassification() throws Exception {
        mislabelAndWrongScope = true;
        var result = background(() -> service.runCoverageLoop(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        assertEquals(result.getCoverageReport().getMessage(), CoverageStatus.PASSED, result.getCoverageReport().getStatus());
        var assessment = result.getRuntimeValidationReport().getOracleAssessment();
        assertEquals(FixtureLifecycleAnalyzer.CATEGORY, assessment.getRootCauseCategory());
        assertEquals(OracleAuthority.NONE, assessment.getAuthority()); assertEquals("NONE", assessment.getAuthorityRef());
        assertEquals(1, classRepairs.get()); assertEquals(1, result.getRuntimeValidationReport().getFixtureRepairRounds());
    }

    private GenerationResult candidate() {
        var result = new GenerationResult(); result.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE, "lookup"));
        var strategy = new MockStrategy(); strategy.setStrategyItems(List.of(new MockStrategyItem(true, "FIELD", "repository", "find", "repository", "mock", "selected"))); result.setMockStrategy(strategy);
        var file = new GeneratedTestFile(); file.setClassName("SubjectTest"); file.setFileName("SubjectTest.java"); file.setPackageName("sample"); file.setContent(TEST); result.setGeneratedTestFile(file); return result;
    }
    private static LlmGenerationResponse code(String code) { var response = new LlmGenerationResponse(); response.setTestCode(code); return response; }
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
            try { return action.call(); } catch (Exception e) { throw new RuntimeException(e); }
        }, new EmptyProgressIndicator())).get(120, TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() -> ModuleRootModificationUtil.updateModel(getModule(), model -> {
                if (root != null) for (var entry : model.getContentEntries()) if (entry.getUrl().contains(root.getFileName().toString())) model.removeContentEntry(entry);
            }));
            super.tearDown();
        } finally { if (root != null) try (var files = Files.walk(root)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } }
    }
}
