package com.casc.unittestagent.ui;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.ui.component.CoverageReportPanel;
import com.casc.unittestagent.util.CoverageDisplay;
import com.intellij.testFramework.LightPlatformTestCase;
import javax.swing.*;
import java.awt.*;

public class CoveragePresentationIntegrityTest extends LightPlatformTestCase {
    public void testInvalidatedReportsHideOldCountersInCardsAndHistory() {
        for (var status : new CoverageStatus[]{CoverageStatus.STALE, CoverageStatus.TEST_FAILED,
                CoverageStatus.CANCELLED, CoverageStatus.ENVIRONMENT_ERROR, CoverageStatus.NOT_RUN}) {
            var report = new CoverageReport(); report.setStatus(status);
            var snapshot = new CoverageSnapshot(); snapshot.setAvailable(true); snapshot.setLine(new CoverageMetric(26, 0));
            report.setGenerated(snapshot); report.setHistory(java.util.List.of(snapshot));
            String visible = text(new CoverageReportPanel(report));
            assertFalse(status + ": " + visible, visible.contains("100.0%  (") || visible.contains("行 100.0%"));
            assertFalse(status + ": " + visible, visible.contains("26/26"));
        }
    }
    public void testRoundingCannotTurnIncompleteCountersIntoOneHundredPercent() {
        assertEquals("<100.0%", CoverageDisplay.percent(99999d / 100000d));
        assertEquals("100.0%", CoverageDisplay.percent(1d));
        assertEquals("50.0%", CoverageDisplay.percent(.5d));
    }

    public void testRunningLoopDisplaysCompletedMeasurementsAndHistory() {
        var report = measuredReport();
        report.setHistory(java.util.List.of(report.getGenerated()));
        String visible = text(new CoverageReportPanel(report));
        assertTrue(visible, visible.contains("75.0%  (3/4)"));
        assertTrue(visible, visible.contains("50.0%  (1/2)"));
        assertTrue(visible, visible.contains("已完成候选的测量结果"));
        assertTrue(visible, visible.contains("行 75.0%"));
        assertFalse(visible, visible.contains("含 lambda") || visible.contains("含lambda"));
    }

    public void testRunningLoopWithoutMeasurementDoesNotInventZeroCoverage() {
        var report = measuredReport(); report.getGenerated().setAvailable(false);
        String visible = text(new CoverageReportPanel(report));
        assertFalse(visible, visible.contains("3/4") || visible.contains("1/2"));
        assertTrue(visible, visible.contains("执行中"));
    }

    public void testNoBranchesRemainsNotApplicableDuringLiveUpdates() {
        var report = measuredReport(); report.getGenerated().setBranch(new CoverageMetric(0, 0));
        String visible = text(new CoverageReportPanel(report));
        assertTrue(visible, visible.contains("N/A（无分支）"));
        assertTrue(visible, visible.contains("75.0%"));
    }

    public void testQueuedPresentationSnapshotSurvivesLaterCandidateMutations() {
        var report = measuredReport(); report.getHistory().add(report.getGenerated());
        var queued = report.snapshotForDisplay();
        report.setStatus(CoverageStatus.STALE);
        report.getGenerated().getLine().setCovered(99);
        report.getGenerated().getExecution().setFound(99);
        report.getHistory().clear();
        assertEquals(CoverageStatus.RUNNING, queued.getStatus());
        assertEquals(1, queued.getHistory().size());
        assertEquals(3, queued.getHistory().get(0).getLine().getCovered());
        assertEquals(4, queued.getGenerated().getExecution().getFound());
        String visible = text(new CoverageReportPanel(queued));
        assertTrue(visible, visible.contains("75.0%  (3/4)"));
        assertFalse(visible, visible.contains("99/"));
    }

    private CoverageReport measuredReport() {
        var report = CoverageReport.status(CoverageStatus.RUNNING);
        report.setPhase(CoveragePhase.IMPROVING);
        var snapshot = new CoverageSnapshot(); snapshot.setAvailable(true);
        snapshot.setLine(new CoverageMetric(3, 1)); snapshot.setBranch(new CoverageMetric(1, 1));
        snapshot.getExecution().setFound(4); snapshot.getExecution().setSucceeded(4);
        snapshot.getExecution().setExitCode(0); report.setGenerated(snapshot);
        return report;
    }
    private String text(Container parent) {
        StringBuilder result = new StringBuilder();
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && !String.valueOf(label.getText()).startsWith("目标：")) result.append(label.getText()).append('\n');
            if (child instanceof JTextArea area) result.append(area.getText()).append('\n');
            if (child instanceof Container container) result.append(text(container));
        }
        return result.toString();
    }
}
