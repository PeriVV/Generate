package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.ui.step.TestCodeStepPanel;
import com.casc.unittestagent.util.CodeFingerprint;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/** Exercises the controller with real editor documents, including callbacks and page reconstruction. */
public class TestCodeStateTest extends LightJavaCodeInsightFixtureTestCase {
    private static final String ORIGINAL = "class SubjectTest { void good() {} void bad() {} }";
    private static final String SELECTED = "class SubjectTest { void good() {} }";
    private UnitMindToolWindowPanel panel;
    private PsiFile source;
    private GenerationResult original;

    @Override protected void setUp() throws Exception {
        super.setUp();
        source = myFixture.configureByText("Subject.java", "class Subject { int value() { return 1; } }");
        panel = new UnitMindToolWindowPanel(getProject());
        panel.setCurrentContext(null, source);
        original = result(ORIGINAL);
        call("applyGenerationResult", new Class<?>[]{GenerationResult.class}, original);
    }

    public void testDisposedPanelCancelsIndicatorsAndRejectsQueuedResults() throws Exception {
        var coverage = new com.intellij.openapi.progress.EmptyProgressIndicator();
        var writing = new com.intellij.openapi.progress.EmptyProgressIndicator();
        for (var name : java.util.List.of("coverageIndicator", "writeIndicator")) {
            var field = UnitMindToolWindowPanel.class.getDeclaredField(name); field.setAccessible(true);
            field.set(panel, name.equals("coverageIndicator") ? coverage : writing);
        }
        var sequence = UnitMindToolWindowPanel.class.getDeclaredField("analysisSequence"); sequence.setAccessible(true);
        int request = sequence.getInt(panel);
        panel.dispose(); panel.dispose();
        assertTrue(coverage.isCanceled()); assertTrue(writing.isCanceled());
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        panel.queueCurrentRequestUpdate(request, source, original, () -> invoked.set(true));
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse(invoked.get());
    }

    public void testSelectedTextReportsAndWriteInputStayTogetherAcrossRebuilds() throws Exception {
        original.getFinalTestFileReport().setTargetPath("/previous/SubjectTest.java");
        GenerationResult selected = selected();
        call("applyRunnableSelectionResult", new Class<?>[]{GenerationResult.class, String.class, PsiFile.class},
                selected, ORIGINAL, source);
        assertText(SELECTED);
        assertTrue(original.getFinalTestFileReport().getTargetPath().isBlank());
        assertTrue(original.getRunnableTestSelectionReport().hasActiveExclusions());
        assertSame(selected.getRuntimeValidationReport(), original.getRuntimeValidationReport());
        assertSame(selected.getCoverageReport(), original.getCoverageReport());
        assertEquals(RunnableTestSelectionReport.Status.PASSED, original.getRunnableTestSelectionReport().getStatus());
        for (int i = 0; i < 3; i++) { call("rebuildSteps", new Class<?>[0]); assertText(SELECTED); }
        assertEquals(ORIGINAL, original.getRunnableTestSelectionReport().getOriginalCode());
        call("restoreBeforeRunnableSelection", new Class<?>[0]);
        assertText(ORIGINAL);
        assertFalse(original.getRunnableTestSelectionReport().hasActiveExclusions());
        assertEquals(ClassJUnitStatus.STALE, original.getRuntimeValidationReport().getStatus());
        assertEquals(CoverageStatus.STALE, original.getCoverageReport().getStatus());
        call("rebuildSteps", new Class<?>[0]); assertText(ORIGINAL);
    }

    public void testUserEditsAndEmptyTextSurviveRebuildAndInvalidateProof() throws Exception {
        original.getFinalTestFileReport().setTargetPath("/previous/SubjectTest.java");
        editor().setCode(SELECTED);
        assertEquals(CoverageStatus.STALE, original.getFinalTestFileReport().getMethodCoverage().getStatus());
        call("rebuildSteps", new Class<?>[0]); assertText(SELECTED);
        assertEquals(ValidationStatus.STALE, original.getValidationResult().getStatus());
        editor().setCode("");
        call("rebuildSteps", new Class<?>[0]); assertText("");
    }

    public void testNewGenerationCoverageAndContextResetCannotReadBackOldEditor() throws Exception {
        call("applyGenerationResult", new Class<?>[]{GenerationResult.class}, result(SELECTED));
        assertText(SELECTED);
        call("applyCoverageResult", new Class<?>[]{GenerationResult.class}, result(ORIGINAL));
        assertText(ORIGINAL);
        panel.setCurrentContext(null, source);
        assertEquals("", currentCode());
        call("applyGenerationResult", new Class<?>[]{GenerationResult.class}, result(SELECTED));
        assertText(SELECTED);
    }

    public void testEditedInputRejectsStaleSelectionWithoutReplacingText() throws Exception {
        editor().setCode("class SubjectTest { void manual() {} }");
        call("applyRunnableSelectionResult", new Class<?>[]{GenerationResult.class, String.class, PsiFile.class},
                selected(), ORIGINAL, source);
        assertText("class SubjectTest { void manual() {} }");
        assertEquals(RunnableTestSelectionReport.Status.STALE, original.getRunnableTestSelectionReport().getStatus());
        assertNotSame(ClassJUnitStatus.PASSED, original.getRuntimeValidationReport().getStatus());
    }

    public void testBackgroundCandidateCannotMutateCurrentReportsInputsOrExclusionIntent() {
        var selected = selected();
        selected.setEvidenceResumeCount(2); selected.setGeneratedByLlm(true);
        var scenario = new TestScenario(); scenario.setName("original");
        selected.setScenarios(new java.util.ArrayList<>(java.util.List.of(scenario)));
        var strategy = new MockStrategy(); strategy.setWhenRules(new java.util.ArrayList<>(java.util.List.of("original")));
        var item = new MockStrategyItem(); item.setRemark("original"); strategy.getStrategyItems().add(item);
        selected.setMockStrategy(strategy);
        var task = UnitMindToolWindowPanel.runtimeCandidate(selected, SELECTED);
        task.getScenarios().get(0).setName("background"); task.getMockStrategy().getWhenRules().clear();
        task.getMockStrategy().getStrategyItems().get(0).setRemark("background");
        task.getCoverageReport().setStatus(CoverageStatus.RUNNING);
        task.getRuntimeValidationReport().setStatus(ClassJUnitStatus.CANCELLED);
        task.getRunnableTestSelectionReport().releaseExclusions();
        assertEquals("original", scenario.getName()); assertEquals("original", item.getRemark());
        assertEquals(1, strategy.getWhenRules().size()); assertTrue(selected.getRunnableTestSelectionReport().hasActiveExclusions());
        assertEquals(CoverageStatus.BELOW_THRESHOLD, selected.getCoverageReport().getStatus());
        assertEquals(ClassJUnitStatus.PASSED, selected.getRuntimeValidationReport().getStatus());
        assertEquals(2, task.getEvidenceResumeCount()); assertTrue(task.isGeneratedByLlm());
    }

    public void testEditingRevokesTaskBeforeItsIndicatorIsAssigned() throws Exception {
        var running = UnitMindToolWindowPanel.class.getDeclaredField("coverageRunning"); running.setAccessible(true);
        var sequence = UnitMindToolWindowPanel.class.getDeclaredField("coverageSequence"); sequence.setAccessible(true);
        running.setBoolean(panel, true); int oldSequence = sequence.getInt(panel);
        call("markValidationStale", new Class<?>[]{String.class}, SELECTED);
        assertFalse(running.getBoolean(panel)); assertTrue(sequence.getInt(panel) > oldSequence);
        var detached = UnitMindToolWindowPanel.runtimeCandidate(original, ORIGINAL);
        detached.getCoverageReport().setStatus(CoverageStatus.PASSED);
        detached.getGeneratedTestFile().setContent("old task output");
        assertEquals(SELECTED, currentCode());
        assertEquals(ValidationStatus.STALE, original.getValidationResult().getStatus());
    }

    public void testStoppingQueuedTaskRevokesOwnershipAndPreservesOracleResumeEvidence() throws Exception {
        var running = UnitMindToolWindowPanel.class.getDeclaredField("coverageRunning"); running.setAccessible(true);
        var sequence = UnitMindToolWindowPanel.class.getDeclaredField("coverageSequence"); sequence.setAccessible(true);
        var oracle = original.getCoverageReport(); oracle.setStopReason(CoverageStopReason.ORACLE_INCONCLUSIVE);
        oracle.setMessage("original oracle evidence");
        original.getRunnableTestSelectionReport().setStatus(RunnableTestSelectionReport.Status.RUNNING);
        running.setBoolean(panel, true); int before = sequence.getInt(panel);
        call("stopCoverageLoop", new Class<?>[0]);
        assertFalse(running.getBoolean(panel)); assertTrue(sequence.getInt(panel) > before);
        assertSame(oracle, original.getCoverageReport()); assertEquals("original oracle evidence", oracle.getMessage());
        assertEquals(CoverageStopReason.ORACLE_INCONCLUSIVE, oracle.getStopReason());
        assertEquals(RunnableTestSelectionReport.Status.CANCELLED, original.getRunnableTestSelectionReport().getStatus());
        assertText(ORIGINAL);
        original.setCoverageReport(CoverageReport.status(CoverageStatus.RUNNING));
        running.setBoolean(panel, true);
        call("stopCoverageLoop", new Class<?>[0]);
        assertEquals(CoverageStatus.CANCELLED, original.getCoverageReport().getStatus());
        assertEquals(CoverageStopReason.CANCELLED, original.getCoverageReport().getStopReason());
    }

    public void testLateTaskCannotReplaceNewTaskCancellationHandle() throws Exception {
        for (boolean write : new boolean[]{false, true}) {
            var current = new com.intellij.openapi.progress.EmptyProgressIndicator();
            var obsolete = new com.intellij.openapi.progress.EmptyProgressIndicator();
            assertTrue(panel.registerExecutionIndicator(current, () -> true, write));
            assertFalse(panel.registerExecutionIndicator(obsolete, () -> false, write));
            assertTrue(obsolete.isCanceled()); assertFalse(current.isCanceled());
            var field = UnitMindToolWindowPanel.class.getDeclaredField(write ? "writeIndicator" : "coverageIndicator");
            field.setAccessible(true); assertSame(current, field.get(panel));
        }
    }

    public void testQueuedGenerationUpdateChecksOwnershipWhenItActuallyRuns() throws Exception {
        var sequence = UnitMindToolWindowPanel.class.getDeclaredField("analysisSequence");
        sequence.setAccessible(true);
        var called = new java.util.concurrent.atomic.AtomicInteger();
        panel.queueCurrentRequestUpdate(sequence.getInt(panel), source, original, called::incrementAndGet);
        panel.setCurrentContext(null, source);
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(0, called.get());
        panel.queueCurrentRequestUpdate(sequence.getInt(panel), source, null, called::incrementAndGet);
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(1, called.get());
    }

    public void testEditingGenerationInputsRevokesPendingResultWithoutReplacingTheSession() throws Exception {
        var sequence = UnitMindToolWindowPanel.class.getDeclaredField("analysisSequence");
        sequence.setAccessible(true);
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        panel.queueCurrentRequestUpdate(sequence.getInt(panel), source, original, () -> called.set(true));
        call("onGenerationInputsEdited", new Class<?>[0]);
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse(called.get());
        assertNull(original.getGeneratedTestFile());
        assertEquals("", currentCode());
    }

    public void testInitialGenerationAndRegenerationOwnTheirInputsAndFile() {
        var scenario = new TestScenario(); scenario.setName("current input");
        original.getScenarios().add(scenario);
        var task = UnitMindToolWindowPanel.generationCandidate(original);
        task.getGeneratedTestFile().setContent("background partial result");
        task.getScenarios().get(0).setName("background input");
        task.getWarnings().add("background warning");
        assertEquals(ORIGINAL, original.getGeneratedTestFile().getContent());
        assertEquals("current input", scenario.getName());
        assertTrue(original.getWarnings().isEmpty());
        original.setGeneratedTestFile(null);
        var initial = UnitMindToolWindowPanel.generationCandidate(original);
        assertNull(initial.getGeneratedTestFile());
        initial.getScenarios().clear();
        assertEquals(1, original.getScenarios().size());
    }

    private GenerationResult selected() {
        var result = result(SELECTED);
        var report = result.getRunnableTestSelectionReport();
        report.setStatus(RunnableTestSelectionReport.Status.PASSED);
        report.setOriginalCode(ORIGINAL); report.setSelectedCode(SELECTED);
        report.exclude("SubjectTest#bad()", "failure");
        report.setSourceHash(CodeFingerprint.sha256(source.getText()));
        result.setRuntimeValidationReport(ClassJUnitRepairReport.status(ClassJUnitStatus.PASSED));
        result.setCoverageReport(CoverageReport.status(CoverageStatus.BELOW_THRESHOLD));
        return result;
    }
    private GenerationResult result(String code) {
        var result = new GenerationResult();
        result.setMethodModel(new JavaSourceAnalyzer().analyze(source.getText(), "value"));
        var file = new GeneratedTestFile(); file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java"); file.setContent(code); result.setGeneratedTestFile(file);
        return result;
    }
    private void assertText(String expected) throws Exception {
        assertEquals(expected, currentCode()); assertEquals(expected, editor().getCode());
    }
    private String currentCode() throws Exception { return (String) call("currentCode", new Class<?>[0]); }
    private TestCodeStepPanel editor() throws Exception {
        var field = UnitMindToolWindowPanel.class.getDeclaredField("testCodeStepPanel");
        field.setAccessible(true); return (TestCodeStepPanel) field.get(panel);
    }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        var method = UnitMindToolWindowPanel.class.getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(panel, args);
    }
}
