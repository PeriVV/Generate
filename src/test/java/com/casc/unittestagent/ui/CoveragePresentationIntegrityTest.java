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
