package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Real compiler, fresh JVM execution and JaCoCo; never sends source to a model. */
public class RunnableTestSelectionIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("RunnableSelectionJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample;
            public class Subject {
                public int value(int input) {
                    if (input > 0) return 1;
                    return -1;
                }
            }
            """;
    private static final String TEST = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Disabled;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;
            import static org.junit.jupiter.api.Assertions.*;
            class SubjectTest {
                Subject subject;
                @BeforeEach void setup() { subject = new Subject(); }
                @Test void positive() { assertEquals(1, subject.value(1)); }
                @Test void wrongNegative() { assertEquals(99, subject.value(-1)); }
                @Disabled @Test void skippedNegative() { assertEquals(-1, subject.value(-1)); }
                @Test void missingSymbol() { assertEquals(1, UNKNOWN); }
                @ParameterizedTest @ValueSource(ints = {1, -1})
                void mixedParameters(int input) { assertEquals(1, subject.value(input)); }
            }
            """;
    private Path root;
    private Path archive;
    private final java.util.Set<Path> archives = new java.util.LinkedHashSet<>();
    private PsiFile source;
    private UnitTestGenerationService service;
    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-runnable-selection-");
        Path sources = Files.createDirectories(root.resolve("src/main/java/sample"));
        Files.writeString(sources.resolve("Subject.java"), SOURCE);
        var vfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources.getParent());
        PsiTestUtil.addSourceRoot(getModule(), vfs);
        source = PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/Subject.java"));
        var config = new CoverageConfig(); config.setLineThreshold(1); config.setBranchThreshold(1);
        CoverageConfigManager.save(config);
        service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(),
                ignored -> { throw new AssertionError("Subset selection must never create an LLM client"); });
    }

    public void testRealCompilationRuntimeAndParameterFailuresRetainGoodTestsWithActualSubsetCoverage() throws Exception {
        var result = run(TEST, () -> false);
        var report = result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(), RunnableTestSelectionReport.Status.PASSED, report.getStatus());
        assertEquals(5, report.getOriginalMethods()); assertEquals(1, report.getRetainedTests().size());
        assertEquals(4, report.getExcludedTests().size());
        String retained = result.getGeneratedTestFile().getContent();
        assertTrue(retained.contains("@Test void positive() { assertEquals(1, subject.value(1)); }"));
        assertTrue(retained.contains("@BeforeEach void setup() { subject = new Subject(); }"));
        assertFalse(retained.contains("void mixedParameters(")); assertFalse(retained.contains("void missingSymbol()"));
        assertEquals(TEST, report.getOriginalCode()); assertEquals(TEST, Files.readString(Path.of(report.getArchivePath())));
        String audit = Files.readString(archive.resolve("selection-summary.json"));
        assertTrue(audit.contains("CANNOT_FIND_SYMBOL"));
        assertEquals(0.5d, new org.json.JSONObject(audit).getJSONObject("coverage").getDouble("branchRate"), 0.0001);
        assertEquals(ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
        assertEquals(1, result.getRuntimeValidationReport().getFinalExecution().getSucceeded());
        assertEquals(CodeFingerprint.sha256(retained), result.getRuntimeValidationReport().getCodeHash());
        assertTrue(result.getValidationResult().isPassedFor(CodeFingerprint.sha256(retained)));
        assertEquals(CoverageStatus.BELOW_THRESHOLD, result.getCoverageReport().getStatus());
        assertEquals(CoveragePhase.COMPLETED, result.getCoverageReport().getPhase());
        assertEquals(1d, result.getCoverageReport().getProgressFraction(), 0d);
        assertEquals(result.getRuntimeValidationReport().getRuntimeConfigurationOrigin(), result.getCoverageReport().getRuntimeConfigurationOrigin());
        assertTrue(result.getCoverageReport().getGenerated().isAvailable());
        assertEquals(0.5d, result.getCoverageReport().getGenerated().getBranch().getRate(), 0.0001);
        assertEquals(CodeFingerprint.sha256(retained), result.getCoverageReport().getCodeHash());
        var finalRegression = background(() -> service.runFinalTestRegression(getProject(), source, result, retained,
                () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.PASSED, finalRegression.getRuntimeValidationReport().getStatus());
        assertEquals(retained, finalRegression.getGeneratedTestFile().getContent());
    }

    public void testPreflightFailureInsideTestDoesNotBlockOtherRunnableTests() throws Exception {
        String code=SIMPLE.replace("assertEquals(99, new Subject().value(-1));", "Subject.class.getDeclaredField(\"missing\");")
                .replace("void wrongNegative()", "void wrongNegative() throws Exception");
        var result=run(code,()->false);
        var report=result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(),RunnableTestSelectionReport.Status.PASSED,report.getStatus());
        assertEquals(1,report.getRetainedTests().size());
        assertTrue(report.getExcludedTests().get(0).reason().contains("REFLECTION_FIELD_ACCESS_UNSAFE"));
        assertEquals(0.5d,result.getCoverageReport().getGenerated().getBranch().getRate(),0.0001);
    }

    public void testPreflightHelperIsolationSurvivesMergeFinalMeasurementAndWrite() throws Exception {
        String code=SIMPLE.replace("assertEquals(99, new Subject().value(-1));", "blockedHelper();")
                .replace("void wrongNegative()", "void wrongNegative() throws Exception")
                .replace("class SubjectTest {", "class SubjectTest { private void blockedHelper() throws Exception { Subject.class.getDeclaredField(\"missing\"); }");
        var result=run(code,()->false);
        var report=result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(),RunnableTestSelectionReport.Status.PASSED,report.getStatus());
        assertEquals(1,report.getRetainedTests().size()); assertEquals(1,report.getRemovedHelpers().size());
        assertFalse(result.getGeneratedTestFile().getContent().contains("blockedHelper"));
        assertEquals(code,Files.readString(Path.of(report.getArchivePath())));
        assertTrue(Files.readString(archive.resolve("selection-summary.json")).contains("removedHelpers"));
        assertEquals(0.5d,result.getCoverageReport().getGenerated().getBranch().getRate(),0.0001);
        var writer=new com.casc.unittestagent.writer.TestFileWriter();
        var target=writer.resolveTargetFile(source,result.getGeneratedTestFile()).toPath();
        Files.createDirectories(target.getParent());
        String existing=code.replace("@Test void positive() { assertEquals(1, new Subject().value(1)); }", "@Test void existingNegative() { assertEquals(-1, new Subject().value(-1)); }");
        Files.writeString(target,existing);
        var prepared=writer.prepare(getProject(),source,result.getGeneratedTestFile(),result.getGeneratedTestFile().getContent(),
                com.casc.unittestagent.writer.TestWriteMode.APPEND_TO_EXISTING,
                com.casc.unittestagent.writer.GenerationTarget.method("value"),report);
        assertFalse(prepared.getContent().contains("blockedHelper")); assertFalse(prepared.getContent().contains("wrongNegative"));
        assertTrue(prepared.getContent().contains("existingNegative"));
        background(()->service.runFinalTestRegressionWithCoverage(getProject(),source,result,prepared.getContent(),target.toString(),()->false,CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.PASSED,result.getRuntimeValidationReport().getStatus());
        assertEquals(1d,result.getFinalTestFileReport().getMethodCoverage().getGenerated().getBranch().getRate(),0.0001);
        writer.commit(getProject(),prepared,result.getValidationResult(),result.getRuntimeValidationReport(),false);
        String written=Files.readString(target);
        assertFalse(written.contains("blockedHelper")); assertFalse(written.contains("wrongNegative"));
        assertEquals(CodeFingerprint.sha256(written),result.getFinalTestFileReport().getMethodCoverage().getCodeHash());
    }
    public void testUncompilablePrivateHelperOnlyBlocksItsCallingTestGroup() throws Exception {
        String code=SIMPLE.replace("assertEquals(99, new Subject().value(-1));","brokenHelper();")
                .replace("class SubjectTest {","class SubjectTest { private int brokenHelper() { return missing.Unavailable.value(); }");
        var result=run(code,()->false);
        var report=result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(),RunnableTestSelectionReport.Status.PASSED,report.getStatus());
        assertEquals(1,report.getRemovedHelpers().size());
        assertFalse(result.getGeneratedTestFile().getContent().contains("brokenHelper"));
        assertEquals(0.5d,result.getCoverageReport().getGenerated().getBranch().getRate(),0.0001);
    }
    public void testSharedPreflightFailureCannotBeHiddenByRemovingFixtureOrItsHelper() throws Exception {
        String code=SIMPLE.replace("class SubjectTest {", "class SubjectTest { @org.junit.jupiter.api.BeforeEach void setup() throws Exception { sharedHelper(); } private void sharedHelper() throws Exception { Subject.class.getDeclaredField(\"missing\"); }");
        var result=run(code,()->false);
        assertEquals(RunnableTestSelectionReport.Status.NO_RUNNABLE_TESTS,result.getRunnableTestSelectionReport().getStatus());
        assertEquals(code,result.getGeneratedTestFile().getContent());
        assertTrue(result.getRunnableTestSelectionReport().getRemovedHelpers().isEmpty());
        assertFalse(result.getCoverageReport().getGenerated().isAvailable());
    }

    public void testUndiscoveredPrivateTestIsNotRetainedJustBecauseOtherTestsPass() throws Exception {
        String code = SIMPLE.replace("@Test void wrongNegative()", "@Test private void wrongNegative()");
        var result = run(code, () -> false);
        assertEquals(result.getRunnableTestSelectionReport().getMessage(), RunnableTestSelectionReport.Status.PASSED,
                result.getRunnableTestSelectionReport().getStatus());
        assertEquals(1, result.getRunnableTestSelectionReport().getRetainedTests().size());
        assertFalse(result.getGeneratedTestFile().getContent().contains("void wrongNegative()"));
        assertTrue(result.getRunnableTestSelectionReport().getExcludedTests().get(0).reason().contains("未发现"));
    }

    public void testSharedSetupFailureKeepsOriginalAndDoesNotInventCoverage() throws Exception {
        String broken = TEST.replace("subject = new Subject();", "throw new IllegalStateException(\"shared setup\");")
                .replace("@Test void missingSymbol() { assertEquals(1, UNKNOWN); }", "");
        var result = run(broken, () -> false);
        var report = result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(), RunnableTestSelectionReport.Status.NO_RUNNABLE_TESTS, report.getStatus());
        assertEquals(broken, result.getGeneratedTestFile().getContent());
        assertEquals(broken, Files.readString(Path.of(report.getArchivePath())));
        assertFalse(result.getCoverageReport().getGenerated().isAvailable());
        assertNotSame(ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
    }

    public void testSelectedSubsetReachesEditorPreparedWriteRegressionAndDiskUnchanged() throws Exception {
        var selected = run(TEST, () -> false);
        String retained = selected.getGeneratedTestFile().getContent();
        var panel = new com.casc.unittestagent.toolwindow.UnitMindToolWindowPanel(getProject());
        panel.setCurrentContext(null, source);
        var original = new GenerationResult(); original.setMethodModel(selected.getMethodModel());
        var file = new GeneratedTestFile(); file.setClassName("SubjectTest"); file.setFileName("SubjectTest.java");
        file.setPackageName("sample"); file.setContent(TEST); original.setGeneratedTestFile(file);
        panelCall(panel, "applyGenerationResult", new Class<?>[]{GenerationResult.class}, original);
        panelCall(panel, "applyRunnableSelectionResult", new Class<?>[]{GenerationResult.class, String.class, PsiFile.class},
                selected, TEST, source);
        panelCall(panel, "rebuildSteps", new Class<?>[0]);
        String writeInput = (String) panelCall(panel, "currentCode", new Class<?>[0]);
        assertEquals(retained, writeInput);
        var writer = new com.casc.unittestagent.writer.TestFileWriter();
        var prepared = writer.prepare(getProject(), source, original.getGeneratedTestFile(), writeInput,
                com.casc.unittestagent.writer.TestWriteMode.CREATE_NEW, "value");
        assertEquals(retained, prepared.getContent());
        background(() -> service.runRuntimeValidation(getProject(), source, original, writeInput,
                () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.PASSED, original.getRuntimeValidationReport().getStatus());
        var finalWrite = writer.prepareRepaired(prepared, original.getGeneratedTestFile().getContent());
        background(() -> service.runFinalTestRegression(getProject(), source, original, finalWrite.getContent(),
                () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.PASSED, original.getRuntimeValidationReport().getStatus());
        assertEquals(1, original.getRuntimeValidationReport().getFinalExecution().getSucceeded());
        writer.commit(getProject(), finalWrite, original.getValidationResult(), original.getRuntimeValidationReport(), false);
        assertEquals(retained, Files.readString(finalWrite.getTargetFile().toPath()));
        assertEquals(TEST, Files.readString(Path.of(selected.getRunnableTestSelectionReport().getArchivePath())));
    }

    private Object panelCall(com.casc.unittestagent.toolwindow.UnitMindToolWindowPanel panel,
                             String name, Class<?>[] types, Object... args) throws Exception {
        var method = panel.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(panel, args);
    }

    private static final String SIMPLE = """
            package sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class SubjectTest {
                @Test void positive() { assertEquals(1, new Subject().value(1)); }
                @Test void wrongNegative() { assertEquals(99, new Subject().value(-1)); }
            }
            """;
    private static final String EXISTING = """
            package sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class SubjectTest {
                @Test void existingNegative() { assertEquals(-1, new Subject().value(-1)); }
                @Test void wrongNegative() { assertEquals(99, new Subject().value(-1)); }
            }
            """;

    public void testLegacyExistingExclusionCannotReturnDuringReMergeAndFinalCoverageIncludesExistingTests() throws Exception {
        var result = run(SIMPLE, () -> false);
        assertEquals(0.5d, result.getCoverageReport().getGenerated().getBranch().getRate(), 0.0001);
        var writer = new com.casc.unittestagent.writer.TestFileWriter();
        var target = writer.resolveTargetFile(source, result.getGeneratedTestFile()).toPath();
        Files.createDirectories(target.getParent()); Files.writeString(target, EXISTING);
        var prepared = writer.prepare(getProject(), source, result.getGeneratedTestFile(),
                result.getGeneratedTestFile().getContent(), com.casc.unittestagent.writer.TestWriteMode.APPEND_TO_EXISTING,
                com.casc.unittestagent.writer.GenerationTarget.method("sample.Subject#value(int)"), result.getRunnableTestSelectionReport());
        assertFalse(prepared.getContent().contains("wrongNegative"));
        assertTrue(prepared.getContent().contains("existingNegative"));
        assertEquals(EXISTING, Files.readString(target));
        var finalWrite = writer.prepareRepaired(prepared, result.getGeneratedTestFile().getContent());
        assertFalse(finalWrite.getContent().contains("wrongNegative"));
        background(() -> service.runFinalTestRegressionWithCoverage(getProject(), source, result, finalWrite.getContent(),
                target.toString(), () -> false, CoverageProgressListener.NONE));
        var coverage = result.getFinalTestFileReport().getMethodCoverage();
        assertEquals(coverage.getMessage(), ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
        assertEquals(2, result.getRuntimeValidationReport().getFinalExecution().getSucceeded());
        assertEquals(1d, coverage.getGenerated().getBranch().getRate(), 0.0001);
        assertEquals(1d, result.getFinalTestFileReport().getClassCoverage().getBranch().getRate(), 0.0001);
        assertEquals(1d, result.getFinalTestFileReport().getClassCoverage().getLine().getRate(), 0.0001);
        assertEquals(CodeFingerprint.sha256(finalWrite.getContent()), coverage.getCodeHash());
        assertEquals(0.5d, result.getCoverageReport().getGenerated().getBranch().getRate(), 0.0001);
        writer.commit(getProject(), finalWrite, result.getValidationResult(), result.getRuntimeValidationReport(), false);
        assertEquals(coverage.getCodeHash(), CodeFingerprint.sha256(Files.readString(target)));
        try { writer.prepareRepaired(prepared, SIMPLE); fail("repair must not restore excluded method"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("已排除")); }
    }

    public void testChangedExistingExcludedTestIsProtectedBeforeMerge() throws Exception {
        var result = run(SIMPLE, () -> false);
        var writer = new com.casc.unittestagent.writer.TestFileWriter();
        var target = writer.resolveTargetFile(source, result.getGeneratedTestFile()).toPath();
        String changed = EXISTING.replace("assertEquals(99", "assertEquals(98");
        Files.createDirectories(target.getParent()); Files.writeString(target, changed);
        try {
            writer.prepare(getProject(), source, result.getGeneratedTestFile(), result.getGeneratedTestFile().getContent(),
                    com.casc.unittestagent.writer.TestWriteMode.APPEND_TO_EXISTING,
                    com.casc.unittestagent.writer.GenerationTarget.method("value"), result.getRunnableTestSelectionReport());
            fail("cannot silently remove manual change");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("不能安全自动删除")); }
        assertEquals(changed, Files.readString(target));
    }

    public void testUnrelatedExistingFailureBlocksFinalWriteAndNeverReusesSubsetCoverage() throws Exception {
        var result = run(SIMPLE, () -> false);
        String failing = result.getGeneratedTestFile().getContent().replace("class SubjectTest {",
                "class SubjectTest { @Test void humanFailure() { assertEquals(42, new Subject().value(-1)); }");
        background(() -> service.runFinalTestRegressionWithCoverage(getProject(), source, result, failing,
                root.resolve("Final.java").toString(), () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getRuntimeValidationReport().getStatus());
        assertEquals(CoverageStatus.TEST_FAILED, result.getFinalTestFileReport().getMethodCoverage().getStatus());
        assertFalse(result.getFinalTestFileReport().getMethodCoverage().getGenerated().isAvailable());
        assertTrue(result.getCoverageReport().getGenerated().isAvailable());
        assertTrue(result.getGeneratedTestFile().getContent().contains("humanFailure"));
    }

    public void testFailureOnlyUnderCoverageBlocksFinalWrite() throws Exception {
        var result = run(SIMPLE, () -> false);
        String code = result.getGeneratedTestFile().getContent().replace("class SubjectTest {", """
                class SubjectTest {
                    @Test void instrumentedFailure() {
                        assertFalse(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                                .stream().anyMatch(arg -> arg.contains("jacocoagent")));
                    }
                """);
        background(() -> service.runFinalTestRegressionWithCoverage(getProject(), source, result, code,
                root.resolve("Final.java").toString(), () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED, result.getRuntimeValidationReport().getStatus());
        assertTrue(result.getRuntimeValidationReport().getInitialExecution().isSuccessful());
        assertFalse(result.getRuntimeValidationReport().getFinalExecution().isSuccessful());
        assertFalse(result.getFinalTestFileReport().getMethodCoverage().getGenerated().isAvailable());
    }

    public void testFinalMeasurementRejectsSameTotalWithDifferentParameterizedInvocations() throws Exception {
        var result = run(SIMPLE, () -> false);
        String code = """
                package sample;
                class SubjectTest {
                    static boolean instrumented() {
                        return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                                .stream().anyMatch(arg -> arg.contains("jacocoagent"));
                    }
                    static java.util.stream.Stream<Integer> left() {
                        return instrumented() ? java.util.stream.Stream.of(1) : java.util.stream.Stream.of(1, 2);
                    }
                    static java.util.stream.Stream<Integer> right() {
                        return instrumented() ? java.util.stream.Stream.of(1, 2) : java.util.stream.Stream.of(1);
                    }
                    @org.junit.jupiter.params.ParameterizedTest
                    @org.junit.jupiter.params.provider.MethodSource("left")
                    void leftCase(int i) { org.junit.jupiter.api.Assertions.assertEquals(1, new Subject().value(i)); }
                    @org.junit.jupiter.params.ParameterizedTest
                    @org.junit.jupiter.params.provider.MethodSource("right")
                    void rightCase(int i) { org.junit.jupiter.api.Assertions.assertEquals(1, new Subject().value(i)); }
                }
                """;
        background(() -> service.runFinalTestRegressionWithCoverage(getProject(), source, result, code,
                root.resolve("Final.java").toString(), () -> false, CoverageProgressListener.NONE));
        var runtime = result.getRuntimeValidationReport();
        assertEquals(runtime.getMessage(), ClassJUnitStatus.TEST_FAILED, runtime.getStatus());
        assertEquals(3, runtime.getInitialExecution().getFound()); assertEquals(3, runtime.getFinalExecution().getFound());
        assertEquals(0, runtime.getInitialExecution().getExitCode()); assertEquals(0, runtime.getFinalExecution().getExitCode());
        assertTrue(runtime.getFinalExecution().isSuccessful()); assertFalse(runtime.getFinalExecution().isFullySuccessful());
        assertTrue(runtime.getFinalExecution().getDiagnostics().stream().anyMatch(d -> "JUnitInventoryChanged".equals(d.getExceptionType())));
        assertFalse(result.getFinalTestFileReport().getMethodCoverage().getGenerated().isAvailable());
    }

    public void testUnavailableFinalCoverageDoesNotInventZeroOrLoseIndependentRuntimeProof() throws Exception {
        var result = run(SIMPLE, () -> false);
        var field = UnitTestGenerationService.class.getDeclaredField("coverageExecutor"); field.setAccessible(true);
        field.set(service, new JacocoCoverageExecutor() {
            @Override public CoverageRunResult execute(CompiledTestArtifact artifact, MethodModel method, String source,
                    CoverageConfig config, java.util.function.BooleanSupplier cancelled) {
                var unavailable = new CoverageRunResult(); unavailable.setEnvironmentError(true);
                unavailable.getSnapshot().setMessage("Synthetic unavailable agent"); return unavailable;
            }
        });
        background(() -> service.runFinalTestRegressionWithCoverage(getProject(), source, result,
                result.getGeneratedTestFile().getContent(), root.resolve("Final.java").toString(), () -> false, CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
        assertEquals(CoverageStatus.ENVIRONMENT_ERROR, result.getFinalTestFileReport().getMethodCoverage().getStatus());
        assertFalse(result.getFinalTestFileReport().getMethodCoverage().getGenerated().isAvailable());
        assertFalse(result.getFinalTestFileReport().getClassCoverage().isAvailable());
    }

    public void testCancellationBetweenIndependentRegressionAndMeasurementBlocksWrite() throws Exception {
        var result = run(SIMPLE, () -> false);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        background(() -> service.runFinalTestRegressionWithCoverage(getProject(), source, result,
                result.getGeneratedTestFile().getContent(), root.resolve("Final.java").toString(), cancelled::get,
                (text, fraction) -> { if (fraction >= 0.55) cancelled.set(true); }));
        assertEquals(ClassJUnitStatus.CANCELLED, result.getRuntimeValidationReport().getStatus());
        assertTrue(result.getRuntimeValidationReport().getInitialExecution().isSuccessful());
        assertEquals(CoverageStatus.CANCELLED, result.getFinalTestFileReport().getMethodCoverage().getStatus());
        assertFalse(result.getFinalTestFileReport().getMethodCoverage().getGenerated().isAvailable());
    }
    public void testRepeatedAndCancelledSelectionPreserveOldExclusionsForLegacyMerge() throws Exception {
        var result = run(SIMPLE, () -> false);
        String retained = result.getGeneratedTestFile().getContent();
        background(() -> service.retainRunnableTests(getProject(), source, result, retained, () -> false, CoverageProgressListener.NONE));
        archive = Path.of(result.getRunnableTestSelectionReport().getArchivePath()).getParent();
        archives.add(archive);
        assertEquals(0, result.getRunnableTestSelectionReport().getExcludedTests().size());
        assertTrue(result.getRunnableTestSelectionReport().hasActiveExclusions());
        background(() -> service.retainRunnableTests(getProject(), source, result, retained, () -> true, CoverageProgressListener.NONE));
        archive = Path.of(result.getRunnableTestSelectionReport().getArchivePath()).getParent();
        archives.add(archive);
        assertTrue(result.getRunnableTestSelectionReport().hasActiveExclusions());
        var writer = new com.casc.unittestagent.writer.TestFileWriter();
        var target = writer.resolveTargetFile(source, result.getGeneratedTestFile()).toPath();
        Files.createDirectories(target.getParent()); Files.writeString(target, EXISTING);
        var merged = writer.prepare(getProject(), source, result.getGeneratedTestFile(), retained,
                com.casc.unittestagent.writer.TestWriteMode.APPEND_TO_EXISTING,
                com.casc.unittestagent.writer.GenerationTarget.method("value"), result.getRunnableTestSelectionReport());
        assertFalse(merged.getContent().contains("wrongNegative"));
        assertTrue(merged.getContent().contains("existingNegative"));
    }

    public void testUnavailableImportUsedOnlyByExcludedMethodDoesNotBlockPassingSuite() throws Exception {
        String code = TEST.replace("package sample;", "package sample;\nimport unavailable.MissingOnlyForBrokenTest;")
                .replace("assertEquals(1, UNKNOWN);", "new MissingOnlyForBrokenTest();");
        var result = run(code, () -> false);
        var report = result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(), RunnableTestSelectionReport.Status.PASSED, report.getStatus());
        assertEquals(1, report.getRetainedTests().size());
        assertFalse(result.getGeneratedTestFile().getContent().contains("import unavailable.MissingOnlyForBrokenTest;"));
        assertEquals(code, Files.readString(Path.of(report.getArchivePath())));
        assertEquals(0.5d, result.getCoverageReport().getGenerated().getBranch().getRate(), 0.0001);
    }

    public void testCancelledSelectionRetainsOriginalArchiveAndNoPassedGate() throws Exception {
        var result = run(TEST, () -> true);
        var report = result.getRunnableTestSelectionReport();
        assertEquals(RunnableTestSelectionReport.Status.CANCELLED, report.getStatus());
        assertEquals(TEST, result.getGeneratedTestFile().getContent());
        assertFalse(result.getCoverageReport().getGenerated().isAvailable());
        assertNotSame(ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
    }

    private GenerationResult run(String code, java.util.function.BooleanSupplier cancelled) throws Exception {
        var result = new GenerationResult(); result.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE, "value"));
        var file = new GeneratedTestFile(); file.setClassName("SubjectTest"); file.setFileName("SubjectTest.java");
        file.setPackageName("sample"); file.setContent(code); result.setGeneratedTestFile(file);
        background(() -> service.retainRunnableTests(getProject(), source, result, code, cancelled, CoverageProgressListener.NONE));
        if (!result.getRunnableTestSelectionReport().getArchivePath().isBlank()) archive = Path.of(result.getRunnableTestSelectionReport().getArchivePath()).getParent();
        if (archive != null) archives.add(archive);
        return result;
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
        } finally { remove(root); remove(archive); for (var saved : archives) remove(saved); }
    }
    private static void remove(Path path) throws Exception {
        if (path != null && Files.exists(path)) try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
