package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.casc.unittestagent.writer.TestFileWriter;
import com.casc.unittestagent.writer.TestWriteMode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Real project fixture with only a JDK and production sources: no JUnit, Mockito or test roots. */
public class NoTestEnvironmentIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final String SOURCE = """
            package offline;
            public class Subject {
                public interface Gateway { int value(int input); }
                private final Gateway gateway;
                public Subject() { this(null); }
                public Subject(Gateway gateway) { this.gateway = gateway; }
                public int fetch(int input) { return gateway.value(input) + 1; }
                public static int increment(int value) {
                    if (value < 0) throw new IllegalArgumentException("negative");
                    return value + 1;
                }
            }
            """;
    private static final String TEST = """
            package offline;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class SubjectTest {
                @Test void increments() { assertEquals(2, Subject.increment(1)); }
                @Test void rejectsNegative() {
                    assertThrows(IllegalArgumentException.class, () -> Subject.increment(-1));
                }
            }
            """;
    private static final LightProjectDescriptor JDK_ONLY = new LightProjectDescriptor() {
        @Override public Sdk getSdk() {
            return JavaSdk.getInstance().createJdk("NoTestEnvironmentJdk", System.getProperty("java.home"), false);
        }
    };
    private Path root;
    private PsiFile source;

    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK_ONLY; }

    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-no-test-environment-");
        Path path = root.resolve("src/main/java/offline/Subject.java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, SOURCE);
        var sourceRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/main/java"));
        PsiTestUtil.addSourceRoot(getModule(), sourceRoot);
        source = PsiManager.getInstance(getProject()).findFile(sourceRoot.findFileByRelativePath("offline/Subject.java"));
        // A build export would fail. Missing-framework projects must run without Maven/Gradle or a repository.
        Files.writeString(root.resolve("pom.xml"), "<project>not configured for tests</project>");
        LlmConfig config = new LlmConfig();
        config.setEnabled(false);
        LlmConfigManager.save(config, null);
        CoverageConfig coverage = new CoverageConfig();
        coverage.setMaxRepairRounds(0);
        CoverageConfigManager.save(coverage);
        assertNull(JavaPsiFacade.getInstance(getProject()).findClass("org.junit.jupiter.api.Test",
                GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(getModule(), true)));
        assertNull(JavaPsiFacade.getInstance(getProject()).findClass("org.mockito.Mockito",
                GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(getModule(), true)));
        assertFalse(Files.exists(root.resolve("src/test")));
    }

    public void testCompilesAndExecutesFinalFileBeforeCreatingFirstTestDirectory() throws Exception {
        var before = ModuleRootManager.getInstance(getModule()).getOrderEntries();
        var service = new UnitTestGenerationService();
        GenerationResult result = background(() -> service.runFinalTestRegression(getProject(), source,
                candidate(TEST), TEST, () -> false, CoverageProgressListener.NONE));
        assertEquals(result.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED,
                result.getRuntimeValidationReport().getStatus());
        assertEquals(2, result.getRuntimeValidationReport().getFinalExecution().getSucceeded());
        assertTrue(result.getValidationResult().getTestEnvironmentNote().contains("内置 JUnit"));
        assertTrue(result.getRuntimeValidationReport().getRuntimeConfigurationOrigin().contains("内置离线沙箱"));
        TestFileWriter writer = new TestFileWriter();
        var prepared = writer.prepare(getProject(), source, result.getGeneratedTestFile(), TEST,
                TestWriteMode.CREATE_NEW, "increment");
        writer.commit(getProject(), prepared, result.getValidationResult(), result.getRuntimeValidationReport(), false);
        assertEquals(TEST, Files.readString(root.resolve("src/test/java/offline/SubjectTest.java")));
        assertEquals("<project>not configured for tests</project>", Files.readString(root.resolve("pom.xml")));
        assertEquals(before.length, ModuleRootManager.getInstance(getModule()).getOrderEntries().length);
    }

    public void testFailingAssertionStillBlocksOrdinaryWrite() throws Exception {
        String broken = TEST.replace("assertEquals(2,", "assertEquals(99,");
        var result = background(() -> new UnitTestGenerationService().runFinalTestRegression(getProject(), source,
                candidate(broken), broken, () -> false, CoverageProgressListener.NONE));
        assertEquals(ValidationStatus.PASSED, result.getValidationResult().getStatus());
        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getRuntimeValidationReport().getStatus());
        TestFileWriter writer = new TestFileWriter();
        var prepared = writer.prepare(getProject(), source, result.getGeneratedTestFile(), broken,
                TestWriteMode.CREATE_NEW, "increment");
        try { writer.commit(getProject(), prepared, result.getValidationResult(), result.getRuntimeValidationReport(), false); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("运行验证")); }
        assertFalse(Files.exists(root.resolve("src/test")));
    }

    public void testCoverageWorksWithoutProjectTestDependenciesOrBuildExports() throws Exception {
        ProjectTestEnvironment environment = background(() -> new ProjectTestEnvironmentResolver()
                .resolveForExecution(getProject(), source, () -> false));
        assertTrue(environment.getDiagnostics().toString(), environment.isUsable());
        assertTrue(environment.isBundledJunit());
        try (CompiledTestArtifact compiled = background(() -> new TestSandboxCompiler().compile(environment,
                candidate(TEST).getGeneratedTestFile(), TEST, "offline.Subject"))) {
            assertTrue(compiled.getDiagnostics().toString(), compiled.isSuccessful());
            CoverageRunResult run = background(() -> new JacocoCoverageExecutor().execute(compiled,
                    method(), SOURCE, new CoverageConfig(), () -> false));
            assertFalse(run.getSnapshot().getMessage(), run.isEnvironmentError());
            assertTrue(run.getSnapshot().getMessage() + run.getSnapshot().getExecution().getOutput(),
                    run.getSnapshot().getExecution().isSuccessful());
            assertTrue(run.getSnapshot().isAvailable());
            assertTrue(run.getSnapshot().getLine().getCovered() > 0);
        }
    }

    public void testRuleGeneratorProducesRunnableTestsWithAvailableOfflineMockito() throws Exception {
        var result = background(() -> new UnitTestGenerationService().generateTestCode(getProject(), source, candidate(TEST)));
        assertNotNull(result.getGeneratedTestFile());
        String code = result.getGeneratedTestFile().getContent();
        assertEquals(result.getValidationResult().getDiagnostics().toString(), ValidationStatus.PASSED,
                result.getValidationResult().getStatus());
        var checked = background(() -> new UnitTestGenerationService().runFinalTestRegression(getProject(), source,
                result, code, () -> false, CoverageProgressListener.NONE));
        assertEquals(checked.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED,
                checked.getRuntimeValidationReport().getStatus());
    }

    public void testOfflineMockitoExtensionInjectionStubbingAndVerification() throws Exception {
        String code = """
                package offline;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.InjectMocks;
                import org.mockito.junit.jupiter.MockitoExtension;
                import static org.mockito.Mockito.*;
                import static org.junit.jupiter.api.Assertions.*;
                @ExtendWith(MockitoExtension.class)
                class SubjectTest {
                    @Mock Subject.Gateway gateway;
                    @InjectMocks Subject subject;
                    @Test void stubsAndVerifiesBusinessDependency() {
                        when(gateway.value(3)).thenReturn(4);
                        assertEquals(5, subject.fetch(3));
                        verify(gateway).value(3);
                        assertEquals(1, java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                            .stream().filter(arg -> arg.startsWith("-javaagent:") && arg.contains("mockito-core")).count());
                    }
                    @Test void mocksStaticMethod() {
                        try (org.mockito.MockedStatic<Subject> mocked = mockStatic(Subject.class)) {
                            mocked.when(() -> Subject.increment(1)).thenReturn(7);
                            assertEquals(7, Subject.increment(1));
                            mocked.verify(() -> Subject.increment(1));
                        }
                    }
                }
                """;
        var result = background(() -> new UnitTestGenerationService().runFinalTestRegression(getProject(), source,
                candidate(code), code, () -> false, CoverageProgressListener.NONE));
        assertEquals(result.getRuntimeValidationReport().getMessage()
                        + result.getRuntimeValidationReport().getFinalExecution().getOutput(),
                ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
        assertEquals(2, result.getRuntimeValidationReport().getFinalExecution().getSucceeded());
        assertTrue(result.getValidationResult().getTestEnvironmentNote().contains("Mockito 5.18.0"));
        ProjectTestEnvironment environment = background(() -> new ProjectTestEnvironmentResolver()
                .resolveForExecution(getProject(), source, () -> false));
        try (var compiled = background(() -> new TestSandboxCompiler().compile(environment,
                candidate(code).getGeneratedTestFile(), code, "offline.Subject"))) {
            assertTrue(compiled.getDiagnostics().toString(), compiled.isSuccessful());
            var covered = background(() -> new JacocoCoverageExecutor().execute(compiled,
                    method(), SOURCE, new CoverageConfig(), () -> false));
            assertFalse(covered.getSnapshot().getMessage(), covered.isEnvironmentError());
            assertTrue(covered.getSnapshot().getMessage() + covered.getSnapshot().getExecution().getOutput(),
                    covered.getSnapshot().getExecution().isSuccessful());
            assertEquals(2, covered.getSnapshot().getExecution().getSucceeded());
        }
    }

    public void testMissingBusinessTypesRemainCompilationErrors() throws Exception {
        ValidationResult validation = background(() -> new ProjectCompileVerifier().validate(getProject(), source,
                candidate(TEST).getGeneratedTestFile(), TEST.replace("Subject.increment(1)", "MissingBusinessApi.call()")));
        assertFalse(validation.isPassedFor(CodeFingerprint.sha256(TEST)));
        assertEquals(ValidationStatus.FAILED, validation.getStatus());
        assertTrue(validation.getDiagnostics().stream().anyMatch(item -> item.getMessage().contains("MissingBusinessApi")));
    }

    public void testProjectMockitoStackIsUsedWithoutAddingAnotherVersion() throws Exception {
        for (Class<?> type : java.util.List.of(org.mockito.Mockito.class, org.mockito.junit.jupiter.MockitoExtension.class,
                net.bytebuddy.ByteBuddy.class, net.bytebuddy.agent.ByteBuddyAgent.class, org.objenesis.Objenesis.class)) {
            java.io.File jar = new java.io.File(com.intellij.util.PathUtil.getJarPathForClass(type));
            PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "project-" + jar.getName(),
                    jar.getParent(), jar.getName());
        }
        var environment = new ProjectTestEnvironmentResolver().resolve(getProject(), source);
        assertTrue(environment.getDiagnostics().toString(), environment.isUsable());
        assertFalse(environment.isBundledMockito());
        assertEquals(1L, environment.getRuntimeClasspath().stream()
                .filter(file -> file.getName().startsWith("mockito-core")).count());
        String projectJar = com.intellij.util.PathUtil.getJarPathForClass(org.mockito.Mockito.class);
        String code = """
                package offline;
                class SubjectTest {
                    @org.junit.jupiter.api.Test void usesProjectMockito() throws Exception {
                        Subject.Gateway gateway = org.mockito.Mockito.mock(Subject.Gateway.class);
                        org.mockito.Mockito.when(gateway.value(2)).thenReturn(9);
                        org.junit.jupiter.api.Assertions.assertEquals(10, new Subject(gateway).fetch(2));
                        org.junit.jupiter.api.Assertions.assertEquals(new java.io.File(%s).getCanonicalFile(),
                            new java.io.File(org.mockito.Mockito.class.getProtectionDomain().getCodeSource()
                                .getLocation().toURI()).getCanonicalFile());
                    }
                }
                """.formatted(org.json.JSONObject.quote(projectJar));
        var result = background(() -> new UnitTestGenerationService().runFinalTestRegression(getProject(), source,
                candidate(code), code, () -> false, CoverageProgressListener.NONE));
        assertEquals(result.getRuntimeValidationReport().getMessage()
                        + result.getRuntimeValidationReport().getFinalExecution().getOutput(),
                ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
    }

    public void testExistingMockitoStartupAgentIsNotDuplicated() throws Exception {
        var environment = background(() -> new ProjectTestEnvironmentResolver().resolveForExecution(getProject(), source, () -> false));
        var core = environment.getRuntimeClasspath().stream().filter(file -> file.getName().startsWith("mockito-core-5.")).findFirst().orElseThrow();
        Path options = root.resolve(".unitmind/test-runtime.json");
        Files.createDirectories(options.getParent());
        Files.writeString(options, new org.json.JSONObject().put("runtime", new org.json.JSONObject()
                .put("jvmArgs", java.util.List.of("-javaagent:" + core.getAbsolutePath()))
                .put("workingDirectory", ".")).toString());
        String code = """
                package offline;
                class SubjectTest {
                    @org.junit.jupiter.api.Test void oneAgent() {
                        org.junit.jupiter.api.Assertions.assertEquals(1,
                            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("mockito-core")).count());
                        Subject.Gateway gateway = org.mockito.Mockito.mock(Subject.Gateway.class);
                        org.mockito.Mockito.when(gateway.value(1)).thenReturn(4);
                        org.junit.jupiter.api.Assertions.assertEquals(5, new Subject(gateway).fetch(1));
                    }
                }
                """;
        var result = background(() -> new UnitTestGenerationService().runFinalTestRegression(getProject(), source,
                candidate(code), code, () -> false, CoverageProgressListener.NONE));
        assertEquals(result.getRuntimeValidationReport().getMessage() + result.getRuntimeValidationReport().getFinalExecution().getOutput(),
                ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
    }

    private MethodModel method() { return new JavaSourceAnalyzer().analyze(SOURCE, "increment"); }

    private GenerationResult candidate(String code) {
        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName("offline");
        file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java");
        file.setContent(code);
        GenerationResult result = new GenerationResult();
        result.setMethodModel(method());
        result.setGeneratedTestFile(file);
        return result;
    }

    private <T> T background(Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> {
            return ProgressManager.getInstance().runProcess(() -> {
                try { return action.call(); }
                catch (RuntimeException ex) { throw ex; }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }, new EmptyProgressIndicator());
        }).get(90, TimeUnit.SECONDS);
    }

    @Override protected void tearDown() throws Exception {
        try { super.tearDown(); }
        finally {
            if (root != null) try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
