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
    public void testRestoreUsesOriginalGeneratedCodeAfterPanelRebuild() {
        String generated = "class SubjectTest { /* generated */ }";
        String edited = "class SubjectTest { /* edited */ }";
        TestCodeStepPanel panel = panel(generated, edited);

        assertEquals(edited, panel.getCode());
        JButton restore = findButton(panel, "恢复生成代码");
        assertNotNull(restore);

        restore.doClick();

        assertEquals(generated, panel.getCode());
    }

    public void testLatestRegenerationBecomesNewRestoreBaseline() {
        String regenerated = "class SubjectTest { /* regenerated */ }";
        TestCodeStepPanel panel = panel(regenerated, "class SubjectTest { /* manual edit */ }");

        JButton restore = findButton(panel, "恢复生成代码");
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
                ignored -> { }, () -> { }, ignored -> { }, () -> { }, () -> { });

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

        JButton runCoverage = findButton(panel, "运行覆盖率并自动补测");
        JButton stopCoverage = findButton(panel, "停止");
        assertNotNull(runCoverage);
        assertNotNull(stopCoverage);
        assertFullyInsideParent(runCoverage);
        assertFullyInsideParent(stopCoverage);
        assertTrue(SwingUtilities.convertRectangle(runCoverage.getParent(), runCoverage.getBounds(), panel).y
                < panel.getHeight());
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

        CoverageSnapshot existing = new CoverageSnapshot();
        existing.setAvailable(true);
        existing.setLine(new CoverageMetric(5, 5));
        existing.setBranch(new CoverageMetric());
        existing.getExecution().setFound(2);
        existing.getExecution().setSucceeded(2);
        report.setExisting(existing);

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
        assertContainsElements(progressStrings, "90.0%  (9/10)", "75.0%  (3/4)", "50.0%  (5/10)", "N/A（无分支）");
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
