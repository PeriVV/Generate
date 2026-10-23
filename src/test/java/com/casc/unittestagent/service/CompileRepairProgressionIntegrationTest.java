package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CoverageProgressListener;
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
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Reproduces javac's 7 missing-annotation errors -> 8 previously masked checked exceptions. */
public class CompileRepairProgressionIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("CompileProgressJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample;
            public class Subject {
                public interface Client {
                    String upload(int index) throws Exception;
                    void delete(int index) throws Exception;
                    void send(String path, byte[] content, boolean overwrite) throws Exception;
                }
                public void send(Client client, String path, byte[] content) throws Exception { client.send(path, content, true); }
                public int run(Client client) {
                    try {
                        int total = 0;
                        for (int i = 0; i < 4; i++) { total += client.upload(i).length(); client.delete(i); }
                        return total;
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }
            }
            """;
    private static final String TEST = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.junit.jupiter.MockitoExtension;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.mockito.Mockito.*;
            @ExtendWith(MockitoExtension.class)
            class SubjectTest {
                @Mock Subject.Client first;
                @Mock Subject.Client second;
                @Mock Subject.Client third;
                @Mock Subject.Client fourth;
                @Mock Subject.Client fifth;
                @Mock Subject.Client sixth;
                @Mock Subject.Client seventh;
                @Test void uploadsAndDeletes() {
                    when(first.upload(0)).thenReturn("ok");
                    when(first.upload(1)).thenReturn("ok");
                    when(first.upload(2)).thenReturn("ok");
                    when(first.upload(3)).thenReturn("ok");
                    assertEquals(8, new Subject().run(first));
                    verify(first).delete(0);
                    verify(first).delete(1);
                    verify(first).delete(2);
                    verify(first).delete(3);
                }
            }
            """;
    private Path root;
    private PsiFile source;
    private final List<LlmGenerationRequest> repairs = new ArrayList<>();
    private boolean importOnlyFirstRepair;
    private UnitTestGenerationService service;

    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-compile-progress-");
        Path sources = root.resolve("src/main/java");
        Files.createDirectories(sources.resolve("sample"));
        Files.writeString(sources.resolve("sample/Subject.java"), SOURCE);
        var vfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources);
        PsiTestUtil.addSourceRoot(getModule(), vfs);
        source = PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/Subject.java"));
        LlmConfig config = new LlmConfig(); config.setEnabled(true); config.setMaxCompileRepairRounds(2); LlmConfigManager.save(config, null);
        service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), ignored -> new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) { return response(TEST); }
            @Override public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                repairs.add(request);
                String code = new GeneratedTestCodePostProcessor().process(TEST, null).getCode();
                if (!importOnlyFirstRepair || repairs.size() > 1)
                    code = code.replace("uploadsAndDeletes()", "uploadsAndDeletes() throws java.lang.Exception");
                return response(code);
            }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) { throw new AssertionError("no runtime repairs expected"); }
        });
    }

    public void testImportFixIsKeptWhenRealCompilerExposesEightCheckedExceptions() throws Exception {
        importOnlyFirstRepair = true;
        GenerationResult result = candidate(); result.setGeneratedByLlm(true);
        GeneratedTestFile file = file(TEST);
        var initial = background(() -> new ProjectCompileVerifier().validate(getProject(), source, file, TEST));
        assertEquals(initial.getDiagnostics().toString(), 7, initial.getDiagnostics().size());
        assertTrue(initial.getDiagnostics().stream().allMatch(d -> "CANNOT_FIND_SYMBOL".equals(d.getCode())));
        GeneratedTestFile compiled = background(() -> service.validateAndRepair(getProject(), source, result, file, LlmConfigManager.load()));
        result.setGeneratedTestFile(compiled);
        assertEquals(2, repairs.size());
        assertCheckedExceptionRequest(repairs.get(1));
        assertFalse(result.getWarnings().toString(), result.getWarnings().stream().anyMatch(w -> w.contains("已回滚")));
        assertCompilesAndRuns(result);
    }

    public void testGenerationRestoresImportBeforeFirstCompileAndOnlyRepairsThrows() throws Exception {
        var result = background(() -> service.generateTestCode(getProject(), source, candidate()));
        assertEquals(1, repairs.size());
        assertCheckedExceptionRequest(repairs.get(0));
        assertCompilesAndRuns(result);
    }

    public void testSharedPreparationFixesMixedMatchersBeforeRealExecutionWithoutModelRepair() throws Exception {
        String broken = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.mockito.Mockito.*;
                class SubjectTest {
                    @Test void sendsContent() throws Exception {
                        Subject.Client client = mock(Subject.Client.class);
                        String content = "a b";
                        new Subject().send(client, "file.yml", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        verify(client).send(anyString(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
                    }
                }
                """;
        var result = candidate(); result.setGeneratedTestFile(file(broken));
        var initial = background(() -> service.runFinalTestRegression(getProject(), source, result, broken, () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED, initial.getRuntimeValidationReport().getStatus());
        assertTrue(initial.getRuntimeValidationReport().getFinalExecution().getDiagnostics().toString(),
                initial.getRuntimeValidationReport().getFinalExecution().getDiagnostics().stream().anyMatch(d -> "MOCKITO_INVALID_MATCHER_USAGE".equals(d.getCategory())));
        var prepared = new GeneratedTestCodePostProcessor().process(broken, result.getMethodModel());
        assertFalse(prepared.getNormalizationEdits().isEmpty());
        result.setGeneratedTestFile(file(prepared.getCode()));
        var fixed = background(() -> service.runFinalTestRegression(getProject(), source, result, prepared.getCode(), () -> false, CoverageProgressListener.NONE));
        assertEquals(fixed.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED, fixed.getRuntimeValidationReport().getStatus());
        assertEquals(prepared.getCode(), fixed.getGeneratedTestFile().getContent());
        assertEquals(0, repairs.size());
    }

    private void assertCheckedExceptionRequest(LlmGenerationRequest request) {
        assertEquals(request.getCompilerDiagnostics().toString(), 8, request.getCompilerDiagnostics().size());
        assertTrue(request.getCompilerDiagnostics().stream().allMatch(d -> "UNREPORTED_EXCEPTION".equals(d.getCode())));
        assertTrue(request.getExistingTestCodeSummary().contains("import org.mockito.Mock;"));
    }
    private void assertCompilesAndRuns(GenerationResult result) throws Exception {
        assertEquals(result.getValidationResult().getDiagnostics().toString(), ValidationStatus.PASSED, result.getValidationResult().getStatus());
        assertTrue(result.getGeneratedTestFile().getContent().contains("assertEquals(8, new Subject().run(first))"));
        var executed = background(() -> service.runFinalTestRegression(getProject(), source, result,
                result.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE));
        assertEquals(executed.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED, executed.getRuntimeValidationReport().getStatus());
        assertEquals(0, executed.getRuntimeValidationReport().getRuntimeRepairRounds());
    }
    private GenerationResult candidate() {
        var result = new GenerationResult(); result.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE, "run"));
        var strategy = new MockStrategy();
        strategy.setStrategyItems(List.of(new MockStrategyItem(true, "LOCAL", "first", "upload", "client", "localMock", "use Client mock")));
        result.setMockStrategy(strategy); return result;
    }
    private static GeneratedTestFile file(String code) {
        var file = new GeneratedTestFile(); file.setPackageName("sample"); file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java"); file.setContent(code); return file;
    }
    private static LlmGenerationResponse response(String code) { var response = new LlmGenerationResponse(); response.setTestCode(code); return response; }
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
            try { return action.call(); } catch (Exception e) { throw new RuntimeException(e); }
        }, new EmptyProgressIndicator())).get(120, TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() -> ModuleRootModificationUtil.updateModel(getModule(), model -> {
                if (root != null) for (var entry : model.getContentEntries())
                    if (entry.getUrl().contains(root.getFileName().toString())) model.removeContentEntry(entry);
            }));
            super.tearDown();
        } finally { if (root != null) try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } }
    }
}
