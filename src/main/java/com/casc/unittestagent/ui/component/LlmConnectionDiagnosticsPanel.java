package com.casc.unittestagent.ui.component;

import com.casc.unittestagent.llm.LlmConnectionDiagnostics;
import com.casc.unittestagent.llm.LlmDiagnosticReport;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Display and controls only; request ownership and network execution belong to the dialog/service. */
public final class LlmConnectionDiagnosticsPanel extends JPanel {
    private final JComboBox<LlmDiagnosticReport.Mode> mode = new JComboBox<>(LlmDiagnosticReport.Mode.values());
    private final JTextArea description = new JTextArea(5, 65);
    private final JLabel progress = new JLabel("选择模式后开始；使用当前未保存设置的快照。");
    private final JTextArea report = new JTextArea(23, 85);
    private final JButton start = new JButton("开始诊断");
    private final JButton stop = new JButton("停止");
    private final JButton copy = new JButton("复制报告（隐藏密钥）");
    private int timeoutSeconds;

    public LlmConnectionDiagnosticsPanel(Consumer<LlmDiagnosticReport.Mode> begin, Runnable cancel, Consumer<String> clipboard) {
        super(new BorderLayout(0, 8));
        mode.setSelectedItem(LlmDiagnosticReport.Mode.GENERATION);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(mode); buttons.add(start); buttons.add(stop); buttons.add(copy);
        JPanel north = new JPanel(new BorderLayout(0, 6)); north.add(buttons, BorderLayout.NORTH);
        description.setLineWrap(true); description.setWrapStyleWord(true); description.setEditable(false);
        description.setOpaque(false); description.setFocusable(false);
        north.add(description, BorderLayout.CENTER); north.add(progress, BorderLayout.SOUTH); add(north, BorderLayout.NORTH);
        report.setEditable(false); report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(report), BorderLayout.CENTER);
        start.addActionListener(event -> begin.accept((LlmDiagnosticReport.Mode) mode.getSelectedItem()));
        stop.addActionListener(event -> { stop.setEnabled(false); progress.setText("正在停止，已完成观测会保留..."); cancel.run(); });
        copy.addActionListener(event -> {
            try { clipboard.accept(report.getText()); progress.setText("报告已复制（已隐藏密钥；仍含主机、模型和请求 ID）。"); }
            catch (RuntimeException unavailable) { progress.setText("复制失败，可在下方选择报告文本手动复制。"); }
        });
        mode.addActionListener(event -> describe());
        describe(); setRunning(false);
    }
    private void describe() {
        var selected = (LlmDiagnosticReport.Mode) mode.getSelectedItem();
        description.setText(selected.description() + (timeoutSeconds <= 0 ? "" : "\n网络等待预算：单次总上限 "
                + 3L * timeoutSeconds + " 秒，本模式请求合计至多 " + 3L * timeoutSeconds * selected.count()
                + " 秒（不含本地解析及间隔）。可停止。"));
    }
    public void setTimeoutSeconds(int seconds) { timeoutSeconds = Math.max(1, seconds); describe(); }
    public void setRunning(boolean running) {
        mode.setEnabled(!running); start.setEnabled(!running); stop.setEnabled(running);
        copy.setEnabled(!report.getText().isBlank());
        if (running) { progress.setText("开始诊断..."); report.setText(""); copy.setEnabled(false); }
    }
    public void showProgress(LlmConnectionDiagnostics.Progress value) {
        progress.setText("请求 " + value.request() + "/" + value.planned() + " · " + value.stage()
                + " · 已用 " + value.elapsedMs() / 1000 + " 秒 · 距数据 " + value.idleMs() / 1000
                + " 秒 · " + value.receivedBytes() + " 字节 / " + value.events() + " 事件 / " + value.contentChars() + " 正文字符");
        progress.setToolTipText(progress.getText());
        setText(value.completedReport());
    }
    public void showReport(LlmDiagnosticReport result) {
        setText(result.text()); setRunning(false);
        progress.setText(result.cancelled() ? "已停止，已完成观测已保留。" : "诊断结束，请查看每次请求的状态、错误码与建议。");
    }
    public void showFailure(String message) { setText(message); setRunning(false); progress.setText("诊断未完成。"); }
    private void setText(String text) {
        if (text.equals(report.getText())) return;
        int caret = report.getCaretPosition(); report.setText(text); report.setCaretPosition(Math.min(caret, text.length()));
        copy.setEnabled(!text.isBlank());
    }
    public String getReportText() { return report.getText(); }
}
