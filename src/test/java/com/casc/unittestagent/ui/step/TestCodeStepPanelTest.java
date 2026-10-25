package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.model.CoverageGap;
import com.casc.unittestagent.model.CoverageMetric;
import com.casc.unittestagent.model.CoveragePhase;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.ClassJUnitRepairAttempt;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.SourceBugAnalysis;
import com.casc.unittestagent.model.SourceBugLocation;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.testFramework.LightPlatformTestCase;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.JTable;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

public class TestCodeStepPanelTest extends LightPlatformTestCase {
    public void testDependencyImportDisablesExecutionButKeepsCodeCopyAvailable() {
        var panel = panel("class T {}", "class T {}");
        panel.configureRunnableSelection(null, () -> {}, () -> {}, () -> {}, false);
        panel.setDependencyImportPending();
        assertFalse(findButton(panel, "运行覆盖率").isEnabled());
        assertFalse(findButton(panel, "重新校验").isEnabled());
        assertFalse(findButton(panel, "再次生成").isEnabled());
        assertFalse(findButton(panel, "保留可运行用例并测覆盖率").isEnabled());
        assertTrue(findButton(panel, "复制").isEnabled());
    }

    public void testLiveCoverageRefreshShowsMeasurementsBeforeLoopCompletes() {
        String code = "class T {}";
        var panel = panel(code, code);
        var report = CoverageReport.status(CoverageStatus.RUNNING);
        report.setPhase(CoveragePhase.IMPROVING);
        var measured = new CoverageSnapshot(); measured.setAvailable(true);
        measured.setLine(new CoverageMetric(3, 1)); measured.setBranch(new CoverageMetric(1, 1));
        report.setGenerated(measured);
        panel.updateCoverageReport(report.snapshotForDisplay());
        assertTrue(componentContainsText(panel, "75.0%  (3/4)"));
        assertTrue(componentContainsText(panel, "50.0%  (1/2)"));
        assertEquals(code, panel.getCode());
    }

    public void testFailedDraftStillOffersSubsetExitWithoutPassingCompilation() {
        String code="class T { @org.junit.jupiter.api.Test void test() {} }";
        var panel=new TestCodeStepPanel(getProject(),GenerationStatus.FAILED,code,code,"preflight rejected","",
                com.casc.unittestagent.model.ValidationResult.status(com.casc.unittestagent.model.ValidationStatus.FAILED),
                feedback -> {}, () -> {}, text -> {});
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        panel.configureRunnableSelection(null,calls::incrementAndGet,()->{},()->{},false);
        assertEquals(code,panel.getCode());
        assertTrue(componentContainsText(panel,"部分用例受阻"));
        var button=findButton(panel,"保留可运行用例并测覆盖率");
        assertTrue(button.isEnabled()); button.doClick(); assertEquals(1,calls.get());
    }
    public void testRepeatedSelectionDisplaysEarlierExclusionsEvenWhenCurrentBatchHasNone() {
        var prior = new com.casc.unittestagent.model.RunnableTestSelectionReport();
        prior.setStatus(com.casc.unittestagent.model.RunnableTestSelectionReport.Status.PASSED);
        prior.setOriginalCode("original"); prior.setSelectedCode("retained"); prior.exclude("T#bad()", "earlier failure");
        var next = new com.casc.unittestagent.model.RunnableTestSelectionReport();
        next.setStatus(com.casc.unittestagent.model.RunnableTestSelectionReport.Status.PASSED);
        next.inheritExclusions(prior.getActiveExclusionOrigins());
        var panel = panel("retained", "retained");
        panel.configureRunnableSelection(next, () -> {}, () -> {}, () -> {}, false);
        assertTrue(componentContainsText(panel, "T#bad()"));
        assertTrue(componentContainsText(panel, "earlier failure"));
    }
    public void testRunnableSelectionHasIndependentActionExclusionReportAndRestore() {
        String original = "class T { /* original full batch */ }";
        String retained = "class T { /* retained runnable tests */ }";
        var panel = panel(original, retained);
        var report = new com.casc.unittestagent.model.RunnableTestSelectionReport();
        report.setStatus(com.casc.unittestagent.model.RunnableTestSelectionReport.Status.PASSED);
        report.setOriginalCode(original); report.setSelectedCode(retained); report.setOriginalMethods(2);
        report.setRetainedTests(List.of("T#good()")); report.exclude("T#bad()", "运行未通过：expected 1 but was 2");
        report.setArchivePath("/test/OriginalTest.java.txt");
        var selected = new java.util.concurrent.atomic.AtomicInteger();
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        panel.configureRunnableSelection(report, selected::incrementAndGet, () -> panel.setCode(original), opened::incrementAndGet, false);
        findButton(panel, "保留可运行用例并测覆盖率").doClick();
        findButton(panel, "查看筛选前代码").doClick();
        assertEquals(1, selected.get()); assertEquals(1, opened.get());
        assertTrue(componentContainsText(panel, "T#bad()"));
        assertTrue(componentContainsText(panel, "expected 1 but was 2"));
        assertEquals(retained, panel.getCode());
        findButton(panel, "恢复筛选前代码").doClick(); assertEquals(original, panel.getCode());
    }

    public void testRunnableSelectionCannotStartOrRestoreDuringExecution() {
        var panel = panel("original", "retained");
        var report = new com.casc.unittestagent.model.RunnableTestSelectionReport(); report.setOriginalCode("original");
        panel.configureRunnableSelection(report, () -> fail("selection is running"), () -> fail("selection is running"), () -> { }, true);
        assertFalse(findButton(panel, "保留可运行用例并测覆盖率").isEnabled());
        assertFalse(findButton(panel, "恢复筛选前代码").isEnabled());
    }

    public void testRestoreUsesOriginalGeneratedCodeAfterPanelRebuild() {
        String generated = "class SubjectTest { /* generated */ }";
        String edited = "class SubjectTest { /* edited */ }";
        TestCodeStepPanel panel = panel(generated, edited);

        assertEquals(edited, panel.getCode());
        JButton restore = findButton(panel, "还原");
        assertNotNull(restore);

        restore.doClick();

        assertEquals(generated, panel.getCode());
    }

    public void testLatestRegenerationBecomesNewRestoreBaseline() {
        String regenerated = "class SubjectTest { /* regenerated */ }";
        TestCodeStepPanel panel = panel(regenerated, "class SubjectTest { /* manual edit */ }");

        JButton restore = findButton(panel, "还原");
        assertNotNull(restore);
        restore.doClick();

        assertEquals(regenerated, panel.getCode());
    }

    public void testCodeEditorUsesThemeAwareDraggableSplitterWithoutControls() {
        TestCodeStepPanel panel = panel(
                "class SubjectTest { /* generated */ }",
                "class SubjectTest { /* generated */ }");

        OnePixelSplitter splitter = findComponent(panel, OnePixelSplitter.class);
        assertNotNull(splitter);
        assertTrue(splitter.isVertical());
        assertEquals(1, splitter.getDividerWidth());
        assertEquals(0, splitter.getDivider().getComponentCount());
        assertTrue(splitter.getSecondComponent().getMinimumSize().height > 0);
        assertNull(findButton(panel, "放大代码区"));
    }

    public void testCoverageCardShowsRuntimeDiagnosticForRepeatedRepair() {
        CoverageReport report = CoverageReport.status(CoverageStatus.TEST_FAILED);
        report.setStopReason(CoverageStopReason.REPEATED_CODE);
        report.setRounds(2);
        report.setMessage("运行修复未修改当前测试代码。");
        report.getGenerated().getExecution().setExitCode(1);
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#fails");
        diagnostic.setExceptionType("java.lang.AssertionError");
        diagnostic.setMessage("expected true but was false");
        report.getGenerated().getExecution().getDiagnostics().add(diagnostic);

        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, report, false, ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

        assertTrue(componentContainsText(panel, "sample.SubjectTest#fails"));
        assertTrue(componentContainsText(panel, "AssertionError"));
        assertTrue(componentContainsText(panel, "expected true but was false"));
        assertTrue(componentContainsText(panel, "补测 2 轮"));
        assertTrue(componentContainsText(panel, "返回了重复代码"));
    }

    public void testSuspectedBugKeepsUpperDiagnosticsVisibleAndExpandable() {
        CoverageReport report = CoverageReport.status(CoverageStatus.SUSPECTED_SUT_BUG);
        report.setStopReason(CoverageStopReason.SUSPECTED_SUT_BUG);
        SourceBugLocation location = new SourceBugLocation();
        location.setFile("Subject.java");
        location.setLine(42);
        location.setSymbol("calculate()");
        location.setReason("返回值与独立契约冲突");
        SourceBugAnalysis analysis = new SourceBugAnalysis();
        analysis.setAttempted(true);
        analysis.setAvailable(true);
        analysis.setConfidence("HIGH");
        analysis.setSummary("边界分支可能返回了错误结果");
        analysis.setSuspectedLocations(List.of(location));
        analysis.setEvidence(List.of("期望 2，实际 1"));
        analysis.setRecommendedChecks(List.of("核对第 42 行边界条件"));
        report.setSourceBugAnalysis(analysis);

        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, report, false, ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

        OnePixelSplitter splitter = findComponent(panel, OnePixelSplitter.class);
        assertNotNull(splitter);
        assertTrue("critical details should receive at least half of the view",
                splitter.getProportion() >= 0.50f);
        assertTrue(componentContainsText(panel, "疑似源码缺陷分析"));
        assertTrue(componentContainsText(panel, "Subject.java:42"));
        assertTrue(componentContainsText(panel, "边界分支可能返回了错误结果"));
        JButton expand = findButton(panel, "放大诊断区");
        assertNotNull(expand);
        expand.doClick();
        assertTrue(splitter.getProportion() >= 0.65f);
        assertNotNull(findButton(panel, "恢复诊断比例"));
    }

    public void testClassRepairShowsEachFocusedFailureSeparately() {
        ClassJUnitRepairReport report = ClassJUnitRepairReport.status(ClassJUnitStatus.NEEDS_REVIEW);
        ClassJUnitRepairAttempt repaired = new ClassJUnitRepairAttempt();
        repaired.setRound(1);
        repaired.setFocusedTestIds(List.of("returnsEarly"));
        repaired.setDiagnosis("MOCKITO_NO_INTERACTIONS_CONTRADICTION");
        repaired.setAccepted(true);
        ClassJUnitRepairAttempt kept = new ClassJUnitRepairAttempt();
        kept.setRound(2);
        kept.setFocusedTestIds(List.of("marksStopped"));
        kept.setDiagnosis("TEST_RUNTIME_FAILURE");
        kept.setRejectionReason("契约证据不足");
        report.setAttempts(List.of(repaired, kept));

        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, null, report, false, true, true, true,
                ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { }, feedback -> { }, 0);

        assertTrue(componentContainsText(panel, "分测试修复明细"));
        assertTrue(componentContainsText(panel, "returnsEarly"));
        assertTrue(componentContainsText(panel, "已接受"));
        assertTrue(componentContainsText(panel, "marksStopped"));
        assertTrue(componentContainsText(panel, "契约证据不足"));
    }

    public void testDiagnosticViewportTracksNarrowWindowWidthWithoutOuterHorizontalScroll() {
        CoverageReport report = CoverageReport.status(CoverageStatus.SUSPECTED_SUT_BUG);
        report.setMessage("/very/long/project/path/without/spaces/that/must/not/stretch/the/whole/tool/window/Subject.java");
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#veryLongDiagnosticNameThatMustStayInsideTheViewport");
        diagnostic.setMessage("x".repeat(600));
        report.getGenerated().getExecution().getDiagnostics().add(diagnostic);

        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, report, false, ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

        JScrollPane diagnosticScroll = findViewportTrackingScrollPane(panel);
        assertNotNull(diagnosticScroll);
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                diagnosticScroll.getHorizontalScrollBarPolicy());
        assertTrue(((Scrollable) diagnosticScroll.getViewport().getView())
                .getScrollableTracksViewportWidth());
    }

    public void testCoverageActionsRemainVisibleInNarrowToolWindow() {
        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, null, false,
                ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

        panel.setSize(new Dimension(320, 720));
        layoutRecursively(panel);

        JButton runCoverage = findButton(panel, "运行覆盖率");
        JButton stopCoverage = findButton(panel, "停止");
        assertNotNull(runCoverage);
        assertNotNull(stopCoverage);
        assertFullyInsideParent(runCoverage);
        assertFullyInsideParent(stopCoverage);
        assertTrue(SwingUtilities.convertRectangle(runCoverage.getParent(), runCoverage.getBounds(), panel).y
                < panel.getHeight());
    }

    public void testActionButtonsShareSingleWrappingRow() {
        TestCodeStepPanel panel = panel(
                "class SubjectTest { /* generated */ }",
                "class SubjectTest { /* generated */ }");

        JButton copy = findButton(panel, "复制");
        JButton stop = findButton(panel, "停止");
        assertNotNull(copy);
        assertNotNull(stop);
        assertSame("所有操作按钮必须在同一个自适应容器中，随窗口宽度整体换行",
                copy.getParent(), stop.getParent());

        panel.setSize(new Dimension(900, 640));
        layoutRecursively(panel);
        assertEquals("宽窗口下六个按钮应排成一行", 1, distinctRows(copy.getParent()));

        panel.setSize(new Dimension(320, 720));
        layoutRecursively(panel);
        assertTrue("窄窗口下按钮应自动换行", distinctRows(copy.getParent()) > 1);
        for (Component component : copy.getParent().getComponents()) {
            assertFullyInsideParent(component);
        }
    }

    private int distinctRows(Container rowParent) {
        return (int) java.util.Arrays.stream(rowParent.getComponents())
                .map(Component::getY)
                .distinct()
                .count();
    }

    public void testCoverageCardVisualizesMetricsAndGaps() {
        CoverageReport report = CoverageReport.status(CoverageStatus.BELOW_THRESHOLD);
        report.setStopReason(CoverageStopReason.NO_PROGRESS);
        report.setRounds(1);
        report.setDurationMillis(1250);
        report.setMessage("本轮未增加已覆盖行或分支。");

        CoverageSnapshot generated = new CoverageSnapshot();
        generated.setAvailable(true);
        generated.setLine(new CoverageMetric(9, 1));
        generated.setBranch(new CoverageMetric(3, 1));
        generated.getExecution().setFound(4);
        generated.getExecution().setSucceeded(4);
        generated.getExecution().setExitCode(0);
        report.setGenerated(generated);

        CoverageSnapshot initial = new CoverageSnapshot();
        initial.setAvailable(true);
        initial.setLine(new CoverageMetric(4, 6));
        initial.setBranch(new CoverageMetric(1, 3));
        initial.getExecution().setFound(2);
        initial.getExecution().setSucceeded(2);
        report.getHistory().add(initial);
        report.getHistory().add(generated);

        CoverageGap gap = new CoverageGap();
        gap.setKind(CoverageGap.Kind.BRANCH);
        gap.setLineNumber(42);
        gap.setMissedCount(1);
        gap.setTotalCount(2);
        gap.setSource("if (value == null) {");
        report.getGaps().add(gap);

        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, report, false, ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

        List<String> progressStrings = progressStrings(panel);
        assertContainsElements(progressStrings, "90.0%  (9/10)", "75.0%  (3/4)");
        assertTrue(componentContainsText(panel, "未达标"));
        assertTrue(componentContainsText(panel, "覆盖率无增益"));
        assertTrue(componentContainsText(panel, "L42"));
        assertTrue(componentContainsText(panel, "1/2"));
        assertTrue(componentContainsText(panel, "if (value == null) {"));
        assertTrue(componentContainsText(panel, "各轮覆盖率变化"));
        assertTrue(componentContainsText(panel, "补测 1"));
        assertContainsElements(progressStrings, "行 40.0%", "分支 25.0%", "行 90.0%", "分支 75.0%");
        assertTrue(componentContainsText(panel, "行 40.0%  ·  分支 25.0%  ·  测试 2/2 通过"));
        assertTrue(progressBars(panel).stream().noneMatch(JProgressBar::isStringPainted));
    }

    public void testCoverageCardShowsLiveCoveragePhase() {
        CoverageReport report = CoverageReport.status(CoverageStatus.RUNNING);
        report.updateProgress(CoveragePhase.IMPROVING, 2,
                "正在把未覆盖目标反馈给大模型...", 0.85d);

        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, report, true, ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

        assertTrue(componentContainsText(panel, "闭环过程"));
        assertTrue(componentContainsText(panel, "当前候选：第 2 轮"));
        assertTrue(componentContainsText(panel, "正在把未覆盖目标反馈给大模型..."));
        assertTrue(report.getVisitedPhases().contains(CoveragePhase.IMPROVING));
    }

    public void testCoverageRoundExplainsWhyMetricsAreUnavailable() {
        CoverageReport report = CoverageReport.status(CoverageStatus.RUNNING);
        CoverageSnapshot failed = new CoverageSnapshot();
        failed.setMessage("候选测试执行失败。");
        failed.getExecution().setFound(1);
        failed.getExecution().setFailed(1);
        failed.getExecution().setExitCode(1);
        report.getHistory().add(failed);

        TestCodeStepPanel panel = new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, report, true, ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

        assertTrue(componentContainsText(panel, "测试失败（0/1 通过），覆盖率不计入"));
    }

    private TestCodeStepPanel panel(String generatedCode, String currentCode) {
        return new TestCodeStepPanel(
                getProject(),
                GenerationStatus.SUCCESS,
                generatedCode,
                currentCode,
                "",
                "",
                null,
                feedback -> { },
                () -> { },
                code -> { });
    }

    public void testEvidenceResumeButtonHiddenWithoutOracleInconclusiveStop() {
        CoverageReport report = CoverageReport.status(CoverageStatus.TEST_FAILED);
        report.setStopReason(CoverageStopReason.REPEATED_CODE);

        TestCodeStepPanel panel = panelWithReport(report, false, feedback -> { });

        assertNull(findButton(panel, "补充证据并继续修复"));
        assertNull(findButton(panelWithReport(null, false, feedback -> { }), "补充证据并继续修复"));
    }

    public void testEvidenceResumeButtonVisibleForEvidenceRequiredStop() {
        TestCodeStepPanel panel = panelWithReport(evidenceRequiredReport(), false, feedback -> { });

        JButton resume = findButton(panel, "补充证据并继续修复");
        assertNotNull(resume);
        assertTrue(resume.isEnabled());
        assertTrue(componentContainsText(panel, "证据提示"));
        assertTrue(componentContainsText(panel, "真实期望值"));
        assertTrue(componentContainsText(panel, "shouldDiscount"));
    }

    public void testEvidenceResumeButtonSubmitsFeedbackAsEvidence() {
        List<String> submitted = new ArrayList<>();
        TestCodeStepPanel panel = panelWithReport(evidenceRequiredReport(), false, submitted::add);
        fillFeedback(panel, "期望值应为 95（PRD-3.2）");

        JButton resume = findButton(panel, "补充证据并继续修复");
        assertNotNull(resume);
        resume.doClick();

        assertEquals(List.of("期望值应为 95（PRD-3.2）"), submitted);
    }

    public void testEvidenceResumeButtonHiddenWhileCoverageRunning() {
        TestCodeStepPanel panel = panelWithReport(evidenceRequiredReport(), true, feedback -> { });

        assertNull(findButton(panel, "补充证据并继续修复"));
    }

    public void testEvidenceResumeButtonHiddenForStaleReport() {
        CoverageReport stale = evidenceRequiredReport();
        stale.setStatus(CoverageStatus.STALE);

        TestCodeStepPanel panel = panelWithReport(stale, false, feedback -> { });

        assertNull(findButton(panel, "补充证据并继续修复"));
    }

    public void testEvidenceResumeButtonHiddenForDeterministicCategory() {
        CoverageReport report = CoverageReport.status(CoverageStatus.NEEDS_REVIEW);
        report.setStopReason(CoverageStopReason.ORACLE_INCONCLUSIVE);
        report.setOracleAssessment(assessment("FIXTURE_DEPENDENCY_MISSING"));

        TestCodeStepPanel panel = panelWithReport(report, false, feedback -> { });

        assertNull(findButton(panel, "补充证据并继续修复"));
    }

    public void testEvidenceResumeButtonDisabledWhenQuotaExhausted() {
        // 额度耗尽（默认上限 2，已用 2）：按钮保留但置灰，证据提示仍显示（告知原因）。
        TestCodeStepPanel panel = panelWithReport(evidenceRequiredReport(), false, feedback -> { }, 2);

        JButton resume = findButton(panel, "补充证据并继续修复");
        assertNotNull(resume);
        assertFalse(resume.isEnabled());
        assertTrue(resume.getToolTipText(), resume.getToolTipText().contains("上限"));
        assertTrue(componentContainsText(panel, "证据提示"));

        // 额度内（已用 1）：按钮可用。
        assertTrue(findButton(panelWithReport(evidenceRequiredReport(), false, feedback -> { }, 1),
                "补充证据并继续修复").isEnabled());
    }

    private CoverageReport evidenceRequiredReport() {
        CoverageReport report = CoverageReport.status(CoverageStatus.NEEDS_REVIEW);
        report.setStopReason(CoverageStopReason.ORACLE_INCONCLUSIVE);
        report.setOracleAssessment(assessment("ASSERTION_VALUE_MISMATCH"));
        report.setFailingTestNamesAtStop(List.of("shouldDiscount"));
        return report;
    }

    private static com.casc.unittestagent.model.OracleAssessment assessment(String category) {
        com.casc.unittestagent.model.OracleAssessment value = new com.casc.unittestagent.model.OracleAssessment();
        value.setRootCauseCategory(category);
        return value;
    }

    private TestCodeStepPanel panelWithReport(CoverageReport report,
                                              boolean coverageRunning,
                                              java.util.function.Consumer<String> onResumeWithEvidence) {
        return panelWithReport(report, coverageRunning, onResumeWithEvidence, 0);
    }

    private TestCodeStepPanel panelWithReport(CoverageReport report,
                                              boolean coverageRunning,
                                              java.util.function.Consumer<String> onResumeWithEvidence,
                                              int evidenceResumeUsed) {
        return new TestCodeStepPanel(
                getProject(), GenerationStatus.SUCCESS, "class SubjectTest {}", "class SubjectTest {}",
                "", "", null, report, null, coverageRunning, true, true, false,
                ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { }, onResumeWithEvidence,
                evidenceResumeUsed);
    }

    private void fillFeedback(TestCodeStepPanel panel, String text) {
        // 证据提示区是只读 JTextArea；补充要求输入区是面板中唯一可编辑的多行文本组件。
        JTextComponent feedback = editableTextArea(panel);
        assertNotNull(feedback);
        feedback.setText(text);
    }

    private JTextComponent editableTextArea(Container container) {
        JTextComponent found = null;
        for (Component component : container.getComponents()) {
            if (component instanceof JTextComponent text && text.isEditable()) {
                found = text;
            }
            if (component instanceof Container nested) {
                JTextComponent nestedFound = editableTextArea(nested);
                if (nestedFound != null) found = nestedFound;
            }
        }
        return found;
    }

    private JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton found = findButton((Container) component, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private <T extends Component> T findComponent(Container container, Class<T> type) {
        for (Component component : container.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container) {
                T found = findComponent((Container) component, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container) layoutRecursively((Container) component);
        }
    }

    private void assertFullyInsideParent(Component component) {
        Rectangle bounds = component.getBounds();
        assertTrue(bounds.width > 0);
        assertTrue(bounds.height > 0);
        assertTrue(bounds.x >= 0);
        assertTrue(bounds.y >= 0);
        assertTrue(bounds.x + bounds.width <= component.getParent().getWidth());
        assertTrue(bounds.y + bounds.height <= component.getParent().getHeight());
    }

    private JScrollPane findViewportTrackingScrollPane(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JScrollPane scroll
                    && scroll.getViewport().getView() instanceof Scrollable view
                    && view.getScrollableTracksViewportWidth()
                    && scroll.getHorizontalScrollBarPolicy() == JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
                return scroll;
            }
            if (component instanceof Container) {
                JScrollPane found = findViewportTrackingScrollPane((Container) component);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean componentContainsText(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel && ((JLabel) component).getText() != null
                    && ((JLabel) component).getText().contains(text)) {
                return true;
            }
            if (component instanceof JTextComponent && ((JTextComponent) component).getText().contains(text)) {
                return true;
            }
            if (component instanceof JProgressBar && ((JProgressBar) component).getString() != null
                    && ((JProgressBar) component).getString().contains(text)) {
                return true;
            }
            if (component instanceof JTable) {
                JTable table = (JTable) component;
                for (int row = 0; row < table.getRowCount(); row++) {
                    for (int column = 0; column < table.getColumnCount(); column++) {
                        Object value = table.getValueAt(row, column);
                        if (value != null && value.toString().contains(text)) return true;
                    }
                }
            }
            if (component instanceof Container) {
                if (componentContainsText((Container) component, text)) return true;
            }
        }
        return false;
    }

    private List<String> progressStrings(Container container) {
        List<String> result = new ArrayList<>();
        collectProgressStrings(container, result);
        return result;
    }

    private List<JProgressBar> progressBars(Container container) {
        List<JProgressBar> result = new ArrayList<>();
        collectProgressBars(container, result);
        return result;
    }

    private void collectProgressBars(Container container, List<JProgressBar> result) {
        for (Component component : container.getComponents()) {
            if (component instanceof JProgressBar) result.add((JProgressBar) component);
            if (component instanceof Container) collectProgressBars((Container) component, result);
        }
    }

    private void collectProgressStrings(Container container, List<String> result) {
        for (Component component : container.getComponents()) {
            if (component instanceof JProgressBar) {
                result.add(((JProgressBar) component).getString());
            }
            if (component instanceof Container) {
                collectProgressStrings((Container) component, result);
            }
        }
    }
}
