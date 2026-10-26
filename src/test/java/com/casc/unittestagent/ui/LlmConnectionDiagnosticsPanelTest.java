package com.casc.unittestagent.ui;

import com.casc.unittestagent.llm.LlmConnectionDiagnostics;
import com.casc.unittestagent.llm.LlmDiagnosticReport;
import com.casc.unittestagent.ui.component.LlmConnectionDiagnosticsPanel;
import junit.framework.TestCase;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static com.casc.unittestagent.llm.LlmDiagnosticReport.*;

public class LlmConnectionDiagnosticsPanelTest extends TestCase {
    public void testRunningStopAndFinalReportRestoreControlsAndCopySnapshot() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var chosen = new AtomicReference<Mode>(); var stopped = new AtomicBoolean(); var copied = new AtomicReference<String>();
            var panel = new LlmConnectionDiagnosticsPanel(chosen::set, () -> stopped.set(true), copied::set);
            button(panel, "开始诊断").doClick(); assertEquals(Mode.GENERATION, chosen.get());
            panel.setRunning(true);
            assertFalse(button(panel, "开始诊断").isEnabled()); assertTrue(button(panel, "停止").isEnabled());
            assertFalse(button(panel, "复制报告（隐藏密钥）").isEnabled());
            button(panel, "停止").doClick(); assertTrue(stopped.get()); assertFalse(button(panel, "停止").isEnabled());
            panel.showReport(result());
            assertTrue(button(panel, "开始诊断").isEnabled()); assertFalse(button(panel, "停止").isEnabled());
            button(panel, "复制报告（隐藏密钥）").doClick(); assertEquals(panel.getReportText(), copied.get());
            assertTrue(copied.get().contains("未单独测得"));
        });
    }
    public void testIdleDisplayContinuesAdvancingWithoutNewNetworkEvents() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new LlmConnectionDiagnosticsPanel(mode -> {}, () -> {}, text -> {}); panel.setRunning(true);
            var progress = new LlmConnectionDiagnostics.Progress(1, 2, "等待响应头", 100, 100, 0, 0, 0, "partial report");
            panel.showProgress(progress.afterWaiting(2100));
            assertTrue(text(panel), text(panel).contains("已用 2 秒")); assertTrue(text(panel).contains("距数据 2 秒"));
            assertTrue(text(panel).contains("0 字节 / 0 事件 / 0 正文字符"));
        });
    }
    public void testClipboardFailureKeepsReportSelectable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new LlmConnectionDiagnosticsPanel(mode -> {}, () -> {}, text -> { throw new IllegalStateException(); });
            panel.showReport(result()); String before = panel.getReportText();
            button(panel, "复制报告（隐藏密钥）").doClick(); assertEquals(before, panel.getReportText());
            assertTrue(text(panel).contains("复制失败"));
        });
    }
    private LlmDiagnosticReport result() {
        var sample = new Sample(1, Probe.BASIC, Status.PASS, "LLM-OK", "短请求可用", "仅证明本次短请求", 2, 200, 1, 1,
                List.of(new Metric("TLS", "未单独测得")));
        return new LlmDiagnosticReport(Mode.BASIC, "http://fixture/v1", "fixture", true, true, false, 3, 24000, "UTC", List.of(sample), false, "");
    }
    private JButton button(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(label)) return button;
            if (child instanceof Container container) { JButton result = findButton(container, label); if (result != null) return result; }
        }
        throw new AssertionError("Missing button " + label);
    }
    private JButton findButton(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(label)) return button;
            if (child instanceof Container container) { var result = findButton(container, label); if (result != null) return result; }
        }
        return null;
    }
    private String text(Container parent) {
        StringBuilder out = new StringBuilder();
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label) out.append(label.getText());
            if (child instanceof Container container) out.append(text(container));
        }
        return out.toString();
    }
}
