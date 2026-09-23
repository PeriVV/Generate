package com.casc.unittestagent.evaluation;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.coverage.CoverageProgressListener;
import com.casc.unittestagent.llm.LlmClient;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
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

/** Reproduces the real-002 environment error: sibling classes in the same case must resolve. */
public class MultiFilePipelineTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor REAL_JDK = new LightProjectDescriptor() {
        @Override public com.intellij.openapi.projectRoots.Sdk getSdk() {
            return com.intellij.openapi.projectRoots.JavaSdk.getInstance()
                    .createJdk("MultiFileJdk", System.getProperty("java.home"), false);
        }
    };
    @Override protected LightProjectDescriptor getProjectDescriptor() { return REAL_JDK; }

    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(),
                com.intellij.pom.java.LanguageLevel.JDK_17, getTestRootDisposable());
        Set<String> paths = new HashSet<>();
        for (String name : List.of("org.junit.jupiter.api.Test",
                "org.junit.jupiter.api.Assertions", "org.mockito.Mockito",
                "org.junit.platform.console.ConsoleLauncher")) {
            Class<?> type = Class.forName(name);
            String path = PathUtil.getJarPathForClass(type);
            if (path != null && paths.add(path)) {
                File root = new File(path);
                PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(),
                        "multifile-" + paths.size(), root.getParent(), root.getName());
            }
        }
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(true);
        llm.setApiUrl("https://offline.invalid/v1/chat/completions");
        llm.setModel("offline-stub");
        LlmConfigManager.save(llm, null);
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRounds(0);
        coverage.setMaxRepairRounds(0);
        CoverageConfigManager.save(coverage);
    }

    public void testSiblingCollaboratorsCompileFromEvalcasesLayout() throws Exception {
        // Mirrors the fixed UnitMindRealApiEvaluationTest.loadSelectedCases: the case
        // tree lives on real disk and src/main/java is a module source root, so the
        // compile sandbox can resolve same-package collaborators via javac sourcepath.
        java.nio.file.Path caseRoot = java.nio.file.Files.createTempDirectory("multifile-case-");
        java.nio.file.Path sourceRootPath = caseRoot.resolve("src/main/java");
        java.nio.file.Path helperPath = sourceRootPath.resolve("eval/oss/x/Helper.java");
        java.nio.file.Files.createDirectories(helperPath.getParent());
        java.nio.file.Files.writeString(helperPath, """
                package eval.oss.x;
                public class Helper {
                    public static boolean isBlank(String v) { return v == null || v.isBlank(); }
                }
                """);
        java.nio.file.Path targetPath = sourceRootPath.resolve("eval/oss/x/Target.java");
        java.nio.file.Files.writeString(targetPath, """
                package eval.oss.x;
                public class Target {
                    public int len(String v) { return Helper.isBlank(v) ? 0 : v.length(); }
                }
                """);
        com.intellij.openapi.vfs.VirtualFile sourceRoot = com.intellij.openapi.vfs.LocalFileSystem
                .getInstance().refreshAndFindFileByNioFile(sourceRootPath.toRealPath());
        assertNotNull("case source root must exist", sourceRoot);
        PsiTestUtil.addSourceContentToRoots(getModule(), sourceRoot);
        PsiFile target = com.intellij.psi.PsiManager.getInstance(getProject())
                .findFile(sourceRoot.findFileByRelativePath("eval/oss/x/Target.java"));
        assertNotNull("target PSI must resolve", target);
        myFixture.openFileInEditor(target.getVirtualFile());
        LlmClient stub = request -> {
            LlmGenerationResponse response = new LlmGenerationResponse();
            response.setTestCode("""
                    package eval.oss.x;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class TargetTest {
                        @Test void blank() { assertEquals(0, new Target().len(null)); }
                        @Test void text() { assertEquals(2, new Target().len("ab")); }
                    }
                    """);
            return response;
        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> stub);
        Future<GenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> service.generate(
                        getProject(), target, myFixture.getEditor()), new EmptyProgressIndicator()));
        GenerationResult result = future.get(2, TimeUnit.MINUTES);
        GeneratedTestFile file = result.getGeneratedTestFile();
        String detail = file == null ? "no file" : file.getContent()
                + "\nvalidation=" + result.getValidationResult().getStatus()
                + "\ndiagnostics=" + result.getValidationResult().getDiagnostics();
        assertEquals(detail, ValidationStatus.PASSED, result.getValidationResult().getStatus());
    }
}
