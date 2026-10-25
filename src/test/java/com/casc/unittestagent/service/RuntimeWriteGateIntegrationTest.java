package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.coverage.CoverageProgressListener;
import com.casc.unittestagent.llm.LlmClient;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.casc.unittestagent.writer.TestFileWriter;
import com.casc.unittestagent.writer.TestWriteMode;
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
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real JVM proofs for independent execution, guarded repair and the final merged-file gate. */
public class RuntimeWriteGateIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private java.nio.file.Path testRoot;
    private static final String SOURCE = """
            package sample;
            public class Subject {
                public String normalize(String text) {
                    if (text.isEmpty()) return "empty";
                    return text.trim();
                }
            }
            """;
    private static final String PASSING = """
            package sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class SubjectTest {
                @Test void trims() {
                    String input = " ok ";
                    String actual = new Subject().normalize(input);
                    assertEquals("ok", actual);
                    assertTrue(java.lang.management.ManagementFactory.getRuntimeMXBean()
                        .getInputArguments().stream().noneMatch(arg -> arg.contains("jacoco")));
                }
            }
            """;
    private static final LightProjectDescriptor REAL_JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() {
            return JavaSdk.getInstance().createJdk("RuntimeWriteGateJdk", System.getProperty("java.home"), false);
        }
    };

    @Override protected LightProjectDescriptor getProjectDescriptor() { return REAL_JDK; }

    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        Set<String> paths = new HashSet<>();
        for (Class<?> type : List.of(org.junit.jupiter.api.Test.class, org.junit.jupiter.api.Assertions.class,
                org.junit.platform.console.ConsoleLauncher.class, org.opentest4j.AssertionFailedError.class)) {
            String path = PathUtil.getJarPathForClass(type);
            if (path != null && paths.add(path)) {
                File jar = new File(path);
                PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "runtime-gate-" + paths.size(),
                        jar.getParent(), jar.getName());
            }
        }
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(false);
        LlmConfigManager.save(llm, null);
        CoverageConfig config = new CoverageConfig();
        config.setLineThreshold(1);
        config.setBranchThreshold(1);
        config.setMaxRounds(30);
        config.setMaxRepairRounds(3);
        CoverageConfigManager.save(config);
    }

    public void testRuntimePassNeedsNeitherCoverageNorClassModelNorJacocoAgent() throws Exception {
        PsiFile source = source();
        UnitTestGenerationService service = service(config -> {
            throw new AssertionError("coverage exploration must not run");
        });
        GenerationResult result = execute(service, source, candidate(PASSING), PASSING, false, () -> false);
        assertPassed(result, PASSING, source);
        assertEquals(CoverageStatus.NOT_RUN, result.getCoverageReport().getStatus());
        assertEquals(1, result.getRuntimeValidationReport().getFinalExecution().getSucceeded());
    }

    public void testCoverageMeasurementIsPublishedWhileLoopIsStillRunning() throws Exception {
        PsiFile source = source();
        String code = "package sample; class SubjectTest { @org.junit.jupiter.api.Test void trims() {"
                + "org.junit.jupiter.api.Assertions.assertEquals(\"ok\", new Subject().normalize(\" ok \")); } }";
        GenerationResult candidate = candidate(code);
        candidate.setMethodModel(new com.casc.unittestagent.parser.JavaSourceAnalyzer().analyze(SOURCE, "normalize"));
        var frames = new java.util.concurrent.CopyOnWriteArrayList<CoverageReport>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                () -> new UnitTestGenerationService().runCoverageLoop(getProject(), source, candidate, code, () -> false,
                        (text, fraction) -> frames.add(candidate.getCoverageReport().snapshotForDisplay())),
                new EmptyProgressIndicator())).get(90, TimeUnit.SECONDS);
        var live = frames.stream().filter(frame -> frame.getStatus() == CoverageStatus.RUNNING
                && frame.getGenerated().isAvailable()).findFirst();
        assertTrue(candidate.getCoverageReport().getMessage(), live.isPresent());
        assertTrue(live.get().getGenerated().getExecution().isFullySuccessful());
        assertTrue(live.get().getGenerated().getLine().getCovered() > 0);
        assertEquals(.5d, live.get().getGenerated().getBranch().getRate(), .0001d);
        assertFalse(CoverageStatus.RUNNING == candidate.getCoverageReport().getStatus());
        assertEquals(CoverageStatus.RUNNING, live.get().getStatus());
    }

    public void testChangedBuildConfigurationCannotReusePassingRuntimeEvidence() throws Exception {
        PsiFile source = source();
        var result = execute(service(config -> { throw new AssertionError("no model expected"); }),
                source, candidate(PASSING), PASSING, false, () -> false);
        assertPassed(result, PASSING, source);
        var evidence = result.getRuntimeValidationReport().getCandidateEvidence();
        assertNotNull(evidence);
        assertTrue(com.casc.unittestagent.coverage.CandidateEvidenceFactory.isCurrent(getProject(), evidence));
        var writer = new TestFileWriter();
        var prepared = writer.prepare(getProject(), source, result.getGeneratedTestFile(), PASSING, TestWriteMode.CREATE_NEW, "normalize");
        Files.writeString(testRoot.resolve("pom.xml"), "<project/>");
        assertFalse(com.casc.unittestagent.coverage.CandidateEvidenceFactory.isCurrent(getProject(), evidence));
        try {
            writer.commit(getProject(), prepared, result.getValidationResult(), result.getRuntimeValidationReport(), false);
            fail("changed build configuration must invalidate passing evidence");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("证据")); }
        assertFalse(prepared.getTargetFile().exists());
    }

    public void testChangedModuleLanguageLevelInvalidatesPassingRuntimeEvidence() throws Exception {
        PsiFile source = source();
        var result = execute(service(config -> { throw new AssertionError("no model expected"); }),
                source, candidate(PASSING), PASSING, false, () -> false);
        assertPassed(result, PASSING, source);
        var evidence = result.getRuntimeValidationReport().getCandidateEvidence();
        assertTrue(com.casc.unittestagent.coverage.CandidateEvidenceFactory.isCurrent(getProject(), evidence));
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_11, getTestRootDisposable());
        assertFalse(com.casc.unittestagent.coverage.CandidateEvidenceFactory.isCurrent(getProject(), evidence));
    }

    public void testUndiscoveredPrivateMethodCannotBorrowAnotherTestsPassingGate() throws Exception {
        PsiFile source = source();
        String code = PASSING.replace("class SubjectTest {", "class SubjectTest { @Test private void neverDiscovered() { fail(); }");
        var result = execute(service(config -> { throw new AssertionError("must not call model"); }),
                source, candidate(code), code, false, () -> false);
        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getRuntimeValidationReport().getStatus());
        assertTrue(result.getRuntimeValidationReport().getFinalExecution().getDiagnostics().stream()
                .anyMatch(d -> "JUnitUndiscoveredTests".equals(d.getExceptionType())));
        assertTrue(result.getRuntimeValidationReport().getFinalExecution().getEvidenceFailures().stream()
                .anyMatch(message -> message.contains("neverDiscovered")));
        assertFalse(result.getRuntimeValidationReport().isPassedFor(CodeFingerprint.sha256(code), CodeFingerprint.sha256(source.getText())));
    }

    public void testRuntimeFailureRepairsArrangeThenRunsFullRegression() throws Exception {
        verifyRepairEvidence(false);
    }

    public void testIncompleteRepairExecutionEvidenceCannotReplaceOriginalFailureOrTriggerMoreRepairs() throws Exception {
        verifyRepairEvidence(true);
    }

    private void verifyRepairEvidence(boolean incompleteEvidence) throws Exception {
        PsiFile source = source();
        String broken = PASSING.replace("String input = \" ok \";", "String input = null;");
        AtomicInteger repairs = new AtomicInteger();
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(true);
        LlmConfigManager.save(llm, null);
        LlmClient stub = new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) {
                throw new AssertionError("initial generation is not needed");
            }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) {
                repairs.incrementAndGet();
                assertFalse(request.getRuntimeDiagnostics().isEmpty());
                LlmGenerationResponse response = new LlmGenerationResponse();
                response.setRepairScope("METHOD_ONLY");
                response.setRepairDisposition("REPAIR_TEST");
                response.setDiagnosisRootCauseCategory("NULL_FIXTURE_FAILURE");
                response.setDiagnosisEvidence(List.of("Arrange passed null to text.trim()"));
                response.setOracleDecision("KEEP");
                response.setOracleAuthority("NONE");
                response.setOracleAuthorityRef("NONE");
                response.setTestCode(PASSING);
                return response;
            }
        };
        UnitTestGenerationService service = service(config -> stub);
        if (incompleteEvidence) {
            var field = UnitTestGenerationService.class.getDeclaredField("coverageExecutor"); field.setAccessible(true);
            field.set(service, new com.casc.unittestagent.coverage.JacocoCoverageExecutor() {
                @Override public com.casc.unittestagent.coverage.CoverageRunResult executeTestsOnly(
                        com.casc.unittestagent.coverage.CompiledTestArtifact artifact,
                        CoverageConfig config, BooleanSupplier cancelled) {
                    var run = super.executeTestsOnly(artifact, config, cancelled);
                    // The repaired candidate really passes, but its execution record is lost.
                    if (run.getSnapshot().getExecution().isFullySuccessful()) run.getSnapshot().getExecution().getTestCases().clear();
                    return run;
                }
            });
        }
        GenerationResult repaired = execute(service, source, candidate(broken), broken, false, () -> false);
        if (incompleteEvidence) {
            assertEquals(ClassJUnitStatus.TEST_FAILED, repaired.getRuntimeValidationReport().getStatus());
            assertEquals(broken, repaired.getGeneratedTestFile().getContent()); assertEquals(1, repairs.get());
            var attempt = repaired.getRuntimeValidationReport().getAttempts().get(0);
            assertFalse(attempt.isAccepted()); assertEquals(0, attempt.getExecution().getExitCode());
            assertFalse(attempt.getExecution().isFullySuccessful());
            assertTrue(attempt.getRejectionReason().contains("缺少完整运行证据"));
            return;
        }
        String repairedCode = repaired.getGeneratedTestFile().getContent();
        assertPassed(repaired, repairedCode, source);
        assertFalse(repaired.getRuntimeValidationReport().getInitialExecution().isSuccessful());
        assertEquals(1, repairs.get());
        GenerationResult regression = execute(service, source, candidate(repairedCode), repairedCode, true, () -> false);
        regression.getRuntimeValidationReport().includeRepairHistory(repaired.getRuntimeValidationReport());
        assertPassed(regression, repairedCode, source);
        assertEquals(1, regression.getRuntimeValidationReport().getRuntimeRepairRounds());
        assertFalse(regression.getRuntimeValidationReport().getInitialExecution().isSuccessful());
        assertEquals("final regression must never rewrite code", repairedCode,
                regression.getGeneratedTestFile().getContent());
        assertEquals(1, repairs.get());
    }

    public void testPassingCandidateCannotAuthorizeFailingMergedClass() throws Exception {
        assertMergedGate("""
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class SubjectTest {
                    @Test void existingFailure() { assertEquals("wrong", new Subject().normalize("x")); }
                }
                """, false);
    }

    public void testMergedSetupFailureIsNotAutomaticallyRewritten() throws Exception {
        assertMergedGate("""
                package sample;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                class SubjectTest {
                    @BeforeEach void existingSetup() { throw new IllegalStateException("existing setup"); }
                    @Test void existingTest() { }
                }
                """, false);
    }

    public void testPassingMergedClassExecutesExistingAndGeneratedTestsBeforeCommit() throws Exception {
        assertMergedGate("""
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class SubjectTest {
                    @Test void existingTest() { assertEquals("empty", new Subject().normalize("")); }
                }
                """, true);
    }

    public void testEmptyAndAllSkippedCandidatesNeverPassRuntimeGate() throws Exception {
        PsiFile source = source();
        UnitTestGenerationService service = new UnitTestGenerationService();
        for (String code : List.of("package sample; class SubjectTest {}",
                PASSING.replace("@Test void trims()", "@org.junit.jupiter.api.Disabled @Test void trims()"),
                PASSING.replace("@Test void trims()", "@org.junit.jupiter.api.Disabled @Test void skipped() {} @Test void trims()"))) {
            GenerationResult result = execute(service, source, candidate(code), code, true, () -> false);
            assertFalse(result.getRuntimeValidationReport().isPassedFor(
                    CodeFingerprint.sha256(code), CodeFingerprint.sha256(source.getText())));
            assertTrue(result.getRuntimeValidationReport().getMessage(),
                    result.getRuntimeValidationReport().getStatus() != ClassJUnitStatus.PASSED);
        }
    }

    public void testCancelledValidationDoesNotAuthorizeWrite() throws Exception {
        PsiFile source = source();
        GenerationResult result = execute(new UnitTestGenerationService(), source, candidate(PASSING),
                PASSING, false, () -> true);
        assertEquals(ClassJUnitStatus.CANCELLED, result.getRuntimeValidationReport().getStatus());
    }

    public void testInvalidProjectRuntimeConfigurationBlocksExecutionBeforeModelRepair() throws Exception {
        PsiFile source = source();
        java.nio.file.Path options = java.nio.file.Path.of(source.getVirtualFile().getPath()).getParent()
                .resolve(".unitmind/test-runtime.json");
        Files.createDirectories(options.getParent());
        Files.writeString(options, "{\"runtime\":{\"workingDirectory\":\"does-not-exist\"}}");
        GenerationResult result = execute(service(config -> { throw new AssertionError("environment failures must not request repairs"); }),
                source, candidate(PASSING), PASSING, false, () -> false);
        assertEquals(ClassJUnitStatus.ENVIRONMENT_ERROR, result.getRuntimeValidationReport().getStatus());
        assertTrue(result.getRuntimeValidationReport().getMessage().contains("运行配置"));
        assertEquals(0, result.getRuntimeValidationReport().getFinalExecution().getFound());
    }

    public void testTimeoutNeverPassesRuntimeGate() throws Exception {
        CoverageConfig config = CoverageConfigManager.load();
        config.setExecutionTimeoutSeconds(5);
        CoverageConfigManager.save(config);
        PsiFile source = source();
        String code = PASSING.replace("@Test void trims() {", "@Test void trims() throws Exception { Thread.sleep(30000);");
        GenerationResult result = execute(new UnitTestGenerationService(), source, candidate(code), code, true, () -> false);
        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getRuntimeValidationReport().getStatus());
        assertEquals(ClassJUnitStopReason.TIMEOUT, result.getRuntimeValidationReport().getStopReason());
        assertTrue(result.getRuntimeValidationReport().getFinalExecution().isTimedOut());
    }

    private void assertMergedGate(String existing, boolean shouldPass) throws Exception {
        PsiFile source = source();
        AtomicInteger modelRequests = new AtomicInteger();
        UnitTestGenerationService service = service(config -> {
            modelRequests.incrementAndGet();
            throw new AssertionError("final regression must not request model repairs");
        });
        GenerationResult generated = execute(service, source, candidate(PASSING), PASSING, false, () -> false);
        assertPassed(generated, PASSING, source);
        TestFileWriter writer = new TestFileWriter();
        java.nio.file.Path existingPath = writer.resolveTargetFile(source, generated.getGeneratedTestFile()).toPath();
        Files.createDirectories(existingPath.getParent());
        Files.writeString(existingPath, existing);
        TestFileWriter.PreparedWrite prepared = writer.prepare(getProject(), source,
                generated.getGeneratedTestFile(), PASSING, TestWriteMode.APPEND_TO_EXISTING, "normalize");
        prepared = writer.prepareRepaired(prepared, generated.getGeneratedTestFile().getContent());
        String finalCode = prepared.getContent();
        GenerationResult merged = execute(service, source, candidate(finalCode), finalCode, true, () -> false);
        assertEquals(finalCode, merged.getGeneratedTestFile().getContent());
        assertEquals(0, modelRequests.get());
        if (shouldPass) {
            assertPassed(merged, finalCode, source);
            assertEquals(2, merged.getRuntimeValidationReport().getFinalExecution().getSucceeded());
            writer.commit(getProject(), prepared, merged.getValidationResult(), merged.getRuntimeValidationReport(), false);
            assertEquals(finalCode, Files.readString(prepared.getTargetFile().toPath()));
        } else {
            assertEquals(ValidationStatus.PASSED, merged.getValidationResult().getStatus());
            assertTrue(merged.getRuntimeValidationReport().getFinalExecution().getFailed() > 0);
            try {
                writer.commit(getProject(), prepared, merged.getValidationResult(),
                        generated.getRuntimeValidationReport(), false);
                fail("standalone runtime proof must not authorize different merged text");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("运行验证"));
            }
            try {
                writer.commit(getProject(), prepared, merged.getValidationResult(), merged.getRuntimeValidationReport(), false);
                fail("failed merged regression must block commit");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("运行验证"));
            }
            assertEquals(existing, Files.readString(prepared.getTargetFile().toPath()));
        }
    }

    private PsiFile source() throws Exception {
        testRoot = Files.createTempDirectory("unitmind-runtime-write-");
        java.nio.file.Path sourcePath = testRoot.resolve("src/main/java/sample/Subject.java");
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, SOURCE);
        com.intellij.openapi.vfs.VirtualFile root = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(testRoot.resolve("src/main/java"));
        PsiTestUtil.addSourceRoot(getModule(), root);
        return com.intellij.psi.PsiManager.getInstance(getProject()).findFile(root.findFileByRelativePath("sample/Subject.java"));
    }

    @Override protected void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            if (testRoot != null) {
                try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(testRoot)) {
                    for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private GenerationResult candidate(String code) {
        MethodModel method = new MethodModel();
        method.setPackageName("sample");
        method.setClassName("Subject");
        method.setMethodName("normalize");
        method.setMethodId("sample.Subject#normalize(String)");
        method.setSignature("normalize(String): String");
        method.setReturnType("String");
        method.setMethodBodyText("public String normalize(String text) { if (text.isEmpty()) return \"empty\"; return text.trim(); }");
        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName("sample");
        file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java");
        file.setContent(code);
        GenerationResult result = new GenerationResult();
        result.setMethodModel(method);
        result.setGeneratedTestFile(file);
        return result;
    }

    private UnitTestGenerationService service(com.casc.unittestagent.llm.LlmClientFactory factory) {
        return new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), factory);
    }

    private GenerationResult execute(UnitTestGenerationService service, PsiFile source, GenerationResult result,
                                     String code, boolean finalRegression, BooleanSupplier cancelled) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> finalRegression
                        ? service.runFinalTestRegression(getProject(), source, result, code, cancelled, CoverageProgressListener.NONE)
                        : service.runRuntimeValidation(getProject(), source, result, code, cancelled, CoverageProgressListener.NONE),
                        new EmptyProgressIndicator())).get(90, TimeUnit.SECONDS);
    }

    private void assertPassed(GenerationResult result, String code, PsiFile source) {
        ClassJUnitRepairReport report = result.getRuntimeValidationReport();
        assertEquals(report.getMessage() + report.getAttempts().stream().map(ClassJUnitRepairAttempt::getRejectionReason).toList()
                + report.getFinalExecution().getOutput(), ClassJUnitStatus.PASSED, report.getStatus());
        assertTrue(report.isPassedFor(CodeFingerprint.sha256(code), CodeFingerprint.sha256(source.getText())));
        assertTrue(result.getValidationResult().isPassedFor(CodeFingerprint.sha256(code)));
    }
}
