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

/** The model emits a bad constructor fixture; generation fixes it before the first real test run. */
public class GenerationFixturePreflightIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("GenerationFixtureJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample;
            public class Subject {
                private final AuditService audit;
                public Subject(AuditService audit) { this.audit = audit; }
                public int run() { audit.record(); return 7; }
            }
            """;
    private static final String TEST = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.BeforeEach;
            import static org.junit.jupiter.api.Assertions.*;
            class SubjectTest {
                AuditService audit;
                Subject subject;
                @BeforeEach void setUp() { audit = new AuditService(null); subject = new Subject(audit); }
                @Test void records() { assertEquals(7, subject.run()); }
            }
            """;
    private Path root;
    private PsiFile source;
    private final AtomicInteger generations = new AtomicInteger();
    private final AtomicInteger compileRepairs = new AtomicInteger();
    private boolean generateCompileError;
    private UnitTestGenerationService service;

    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-generation-fixture-");
        Path sources = root.resolve("src/main/java");
        Files.createDirectories(sources.resolve("sample"));
        Files.writeString(sources.resolve("sample/Subject.java"), SOURCE);
        Files.writeString(sources.resolve("sample/Repository.java"), "package sample; public interface Repository { void save(String value); }");
        Files.writeString(sources.resolve("sample/AuditService.java"), """
                package sample;
                public class AuditService {
                    private final Repository repository;
                    public AuditService(Repository repository) { this.repository = repository; }
                    public void record() { repository.save("event"); }
                }
                """);
        var vfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources);
        PsiTestUtil.addSourceRoot(getModule(), vfs);
        source = PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/Subject.java"));
        LlmConfig llm = new LlmConfig(); llm.setEnabled(true); llm.setMaxCompileRepairRounds(2); LlmConfigManager.save(llm, null);
        service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) {
                generations.incrementAndGet();
                var response = new LlmGenerationResponse();
                response.setTestCode(generateCompileError ? TEST.replace("assertEquals(7, subject.run());", "assertEquals(7, subject.run()); subject.missing();") : TEST);
                return response;
            }
            @Override public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                compileRepairs.incrementAndGet();
                assertTrue("only a real compiler failure should need model repair", generateCompileError);
                var response = new LlmGenerationResponse(); response.setTestCode(TEST); return response;
            }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) { throw new AssertionError("fixture must be fixed before execution"); }
            @Override public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) { throw new AssertionError("no runtime repair needed"); }
        });
    }

    public void testGeneratedFixtureIsFilledBeforeFirstExecutionWithoutExtraModelRequest() throws Exception {
        var generated = background(() -> service.generateTestCode(getProject(), source, candidate(true)));
        assertPrepared(generated);
        assertEquals(1, generations.get()); assertEquals(0, compileRepairs.get());
        assertEquals(ClassJUnitStatus.NOT_RUN, generated.getRuntimeValidationReport().getStatus());
        var executed = background(() -> service.runFinalTestRegression(getProject(), source, generated,
                generated.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE));
        assertEquals(executed.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED,
                executed.getRuntimeValidationReport().getStatus());
        assertTrue(executed.getRuntimeValidationReport().getInitialExecution().isSuccessful());
        assertEquals(0, executed.getRuntimeValidationReport().getRuntimeRepairRounds());
    }

    public void testRegenerationUsesTheSameFixturePreflight() throws Exception {
        var generated = background(() -> service.generateTestCode(getProject(), source, candidate(true)));
        var regenerated = background(() -> service.regenerateWithLlmFeedback(getProject(), source, generated,
                generated.getGeneratedTestFile().getContent(), "keep original assertions"));
        assertPrepared(regenerated); assertEquals(2, generations.get()); assertEquals(0, compileRepairs.get());
    }

    public void testCompileRepairCannotReintroduceConstructorNull() throws Exception {
        generateCompileError = true;
        var generated = background(() -> service.generateTestCode(getProject(), source, candidate(true)));
        assertPrepared(generated);
        assertEquals(1, compileRepairs.get());
        assertEquals(1, generated.getValidationResult().getRepairAttempts());
    }

    public void testForbiddenMockitoIsReportedBeforeExecution() throws Exception {
        LlmConfig llm = LlmConfigManager.load(); llm.setMaxCompileRepairRounds(0); LlmConfigManager.save(llm, null);
        var generated = background(() -> service.generateTestCode(getProject(), source, candidate(false)));
        assertEquals(ValidationStatus.FAILED, generated.getValidationResult().getStatus());
        assertEquals("FIXTURE_CONSTRUCTOR_DEPENDENCY", generated.getValidationResult().getDiagnostics().get(0).getCode());
        assertTrue(generated.getValidationResult().getDiagnostics().get(0).getMessage().contains("禁止新增 Mockito"));
        assertEquals(ClassJUnitStatus.NOT_RUN, generated.getRuntimeValidationReport().getStatus());
        assertEquals(0, compileRepairs.get());
    }

    public void testFastCandidateIsAlsoPreparedWithoutClaimingItHasRun() throws Exception {
        var generated = background(() -> service.generateTestCodeCandidate(getProject(), source, candidate(true)));
        assertFalse(generated.getGeneratedTestFile().getContent().contains("new AuditService(null)"));
        assertEquals(ValidationStatus.NOT_RUN, generated.getValidationResult().getStatus());
        assertEquals(ClassJUnitStatus.NOT_RUN, generated.getRuntimeValidationReport().getStatus());
        assertEquals(1, generations.get()); assertEquals(0, compileRepairs.get());
    }

    private void assertPrepared(GenerationResult result) {
        assertEquals(result.getWarnings() + " " + result.getValidationResult().getDiagnostics(), ValidationStatus.PASSED, result.getValidationResult().getStatus());
        String code = result.getGeneratedTestFile().getContent();
        assertFalse(code, code.contains("new AuditService(null)"));
        assertTrue(code, code.contains("org.mockito.Mockito.mock(sample.Repository.class)"));
        assertTrue(code, code.contains("assertEquals(7, subject.run())"));
        assertTrue(result.getWarnings().toString(), result.getWarnings().stream().anyMatch(warning -> warning.contains("生成前检查已补齐")));
    }
    private GenerationResult candidate(boolean mocks) {
        var result = new GenerationResult(); result.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE, "run"));
        var strategy = new MockStrategy();
        if (mocks) strategy.setStrategyItems(List.of(new MockStrategyItem(true, "LOCAL", "Repository", "save", "constructor fixture", "localMock", "allow constructor fixture mock")));
        result.setMockStrategy(strategy); return result;
    }
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
            try { return action.call(); } catch (Exception error) { throw new RuntimeException(error); }
        }, new EmptyProgressIndicator())).get(120, TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() -> ModuleRootModificationUtil.updateModel(getModule(), model -> {
                if (root != null) for (var entry : model.getContentEntries())
                    if (entry.getUrl().contains(root.getFileName().toString())) model.removeContentEntry(entry);
            }));
            super.tearDown();
        } finally { if (root != null) try (var files = Files.walk(root)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } }
    }
}
