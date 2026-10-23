package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.generator.GeneratedTestCodePostProcessor;
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
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Production-import evidence, real javac/JUnit and a fake model; no business data/network. */
public class ImportBindingIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("ImportBindingJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample.app;
            import sample.api.Gateway;
            public class Subject {
                public String read(Gateway gateway) { return gateway.read(); }
            }
            """;
    private static final String TEST = """
            package sample.app;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.mockito.Mockito.mock;
            import static org.mockito.Mockito.when;
            class SubjectTest {
                @org.mockito.Mock Gateway gateway;
                @org.junit.jupiter.api.BeforeEach void setup() { org.mockito.MockitoAnnotations.openMocks(this); }
                @Test void reads() {
                    // additional mocks
                    when(gateway.read()).thenReturn("ok");
                    assertEquals("ok", new Subject().read(gateway));
                }
            }
            """.replace("// additional mocks", "mock(Object.class);\n".repeat(10));
    private Path root;
    private Path archive;
    private PsiFile source;
    private UnitTestGenerationService service;
    private final AtomicInteger repairs = new AtomicInteger();
    private final AtomicInteger validations = new AtomicInteger();
    private boolean missingFactoryImport;
    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-import-bindings-");
        var sources = Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(sources.resolve("sample/api")); Files.createDirectories(sources.resolve("sample/app"));
        Files.writeString(sources.resolve("sample/api/Gateway.java"), "package sample.api; public interface Gateway { String read(); }");
        Files.writeString(sources.resolve("sample/app/Subject.java"), SOURCE);
        var vfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources);
        PsiTestUtil.addSourceRoot(getModule(), vfs);
        source = PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/app/Subject.java"));
        var llm = new LlmConfig(); llm.setEnabled(true); llm.setMaxCompileRepairRounds(2); LlmConfigManager.save(llm, null);
        var verifier = new ProjectCompileVerifier();
        service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), (p, psi, file, code) -> {
            validations.incrementAndGet(); return verifier.validate(p, psi, file, code);
        }, ignored -> new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) {
                var response = new LlmGenerationResponse(); response.setTestCode(missingFactoryImport
                        ? TEST.replace("import static org.mockito.Mockito.mock;", "") : TEST); return response;
            }
            @Override public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                repairs.incrementAndGet();
                if (!missingFactoryImport) throw new AssertionError("source-proven missing import must be completed before asking a model");
                assertEquals(10, request.getCompilerDiagnostics().size());
                assertTrue(request.getExistingTestCodeSummary().contains("import sample.api.Gateway;"));
                var response = new LlmGenerationResponse();
                response.setTestCode(request.getExistingTestCodeSummary().replace("import static org.mockito.Mockito.when;",
                        "import static org.mockito.Mockito.mock;\nimport static org.mockito.Mockito.when;"));
                return response;
            }
            @Override public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) { throw new AssertionError("not coverage exploration"); }
        });
    }
    public void testGenerationCompletesImportBeforeFirstCompileAndRealRuntime() throws Exception {
        var result = background(() -> service.generateTestCode(getProject(), source, candidate(TEST)));
        assertTrue(result.getGeneratedTestFile().getContent().contains("import sample.api.Gateway;"));
        assertTrue(result.getGeneratedTestFile().getContent().contains("import static org.mockito.Mockito.mock;"));
        assertEquals(ValidationStatus.PASSED, result.getValidationResult().getStatus());
        assertEquals(1, validations.get()); assertEquals(0, repairs.get());
        background(() -> service.runFinalTestRegression(getProject(), source, result,
                result.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
    }
    public void testImportOnlyModelPatchDoesNotLoseMockitoStaticBinding() throws Exception {
        String proposed = TEST.replace("import static org.mockito.Mockito.mock;", "import sample.api.Gateway;");
        var failedPatch = background(() -> service.validateCode(getProject(), source, candidate(proposed).getGeneratedTestFile(), proposed));
        assertEquals(10L, failedPatch.getDiagnostics().stream().filter(d -> "ERROR".equals(d.getSeverity())).count());
        String fixed = new GeneratedTestCodePostProcessor().processRepair(proposed, candidate(TEST).getMethodModel(), TEST).getCode();
        assertTrue(fixed.contains("import static org.mockito.Mockito.mock;"));
        var result = candidate(fixed);
        background(() -> service.runFinalTestRegression(getProject(), source, result, fixed, () -> false, CoverageProgressListener.NONE));
        assertEquals(result.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
        assertEquals(0, repairs.get());
    }
    public void testTypePreflightExposesPreviouslyMaskedMethodErrorsBeforeRepairBudgetComparison() throws Exception {
        missingFactoryImport = true;
        var result = background(() -> service.generateTestCode(getProject(), source, candidate(TEST)));
        assertEquals(ValidationStatus.PASSED, result.getValidationResult().getStatus());
        assertEquals(1, repairs.get()); assertEquals(2, validations.get());
        background(() -> service.runFinalTestRegression(getProject(), source, result,
                result.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
    }
    public void testRetainEntryCompletesTypeImportWithoutDroppingValidTestsAndArchivesExactInput() throws Exception {
        var result = candidate(TEST);
        background(() -> service.retainRunnableTests(getProject(), source, result, TEST, () -> false, CoverageProgressListener.NONE));
        var report = result.getRunnableTestSelectionReport();
        if (!report.getArchivePath().isBlank()) archive = Path.of(report.getArchivePath()).getParent();
        assertEquals(report.getMessage(), RunnableTestSelectionReport.Status.PASSED, report.getStatus());
        assertEquals(TEST, report.getOriginalCode()); assertEquals(TEST, Files.readString(Path.of(report.getArchivePath())));
        assertEquals(1, report.getRetainedTests().size()); assertTrue(report.getExcludedTests().isEmpty());
        assertTrue(result.getGeneratedTestFile().getContent().contains("import sample.api.Gateway;"));
        assertTrue(result.getCoverageReport().getGenerated().isAvailable()); assertEquals(0, repairs.get());
    }
    public void testFinalRegressionNeverSilentlyCompletesMissingImports() throws Exception {
        var result = candidate(TEST);
        background(() -> service.runFinalTestRegression(getProject(), source, result, TEST, () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getRuntimeValidationReport().getStatus());
        assertEquals(1L, result.getValidationResult().getDiagnostics().stream().filter(d -> "ERROR".equals(d.getSeverity())).count());
        assertEquals(TEST, result.getGeneratedTestFile().getContent()); assertEquals(0, repairs.get());
    }
    private static GenerationResult candidate(String code) {
        var result = new GenerationResult(); result.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE, "read"));
        var file = new GeneratedTestFile(); file.setClassName("SubjectTest"); file.setFileName("SubjectTest.java");
        file.setPackageName("sample.app"); file.setContent(code); result.setGeneratedTestFile(file); return result;
    }
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
            try { return action.call(); } catch (Exception e) { throw new RuntimeException(e); }
        }, new EmptyProgressIndicator())).get(180, TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() -> ModuleRootModificationUtil.updateModel(getModule(), model -> {
                if (root != null) for (var entry : model.getContentEntries()) if (entry.getUrl().contains(root.getFileName().toString())) model.removeContentEntry(entry);
            }));
            super.tearDown();
        } finally { remove(root); remove(archive); }
    }
    private static void remove(Path path) throws Exception {
        if (path != null && Files.exists(path)) try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
