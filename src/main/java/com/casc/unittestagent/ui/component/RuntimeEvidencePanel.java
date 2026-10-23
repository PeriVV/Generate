package com.casc.unittestagent.ui.component;

import com.casc.unittestagent.model.TestExecutionSummary;
import javax.swing.*;
import java.awt.*;

/** One view for generation, final regression and coverage: raw evidence is never replaced by model prose. */
public final class RuntimeEvidencePanel extends JPanel {
    public RuntimeEvidencePanel(TestExecutionSummary execution) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        if (execution == null) return;
        StringBuilder summary = new StringBuilder();
        execution.getDiagnostics().stream().filter(d -> !d.isContextFailureSecondary()).limit(5).forEach(d -> {
            if (!d.getRootCauseType().isBlank()) summary.append("最内层原因：").append(d.getRootCauseType()).append(": ").append(d.getRootCauseMessage()).append('\n');
        });
        if (!execution.getEvidenceArchivePath().isBlank()) summary.append("完整运行证据：").append(execution.getEvidenceArchivePath());
        if (!execution.getEvidenceArchiveError().isBlank()) summary.append("\n运行证据归档失败：").append(execution.getEvidenceArchiveError());
        if (!summary.isEmpty()) add(area(summary.toString()));
        if (!execution.getDiagnostics().isEmpty()) {
            JButton button = new JButton("查看完整运行异常");
            var scroll = new JScrollPane(); scroll.setVisible(false); scroll.setPreferredSize(new Dimension(600, 220));
            button.addActionListener(event -> {
                boolean show = !scroll.isVisible();
                if (show && scroll.getViewport().getView() == null) scroll.setViewportView(area(fullText(execution)));
                scroll.setVisible(show); button.setText(show ? "收起完整运行异常" : "查看完整运行异常"); revalidate(); repaint();
            });
            add(button); add(scroll);
        }
    }
    private static JTextArea area(String text) {
        var area = new JTextArea(text); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); return area;
    }
    public static String fullText(TestExecutionSummary execution) {
        var text = new StringBuilder();
        if (!execution.getEvidenceArchivePath().isBlank()) text.append("完整原始 XML / 进程日志：").append(execution.getEvidenceArchivePath()).append("\n\n");
        for (var d : execution.getDiagnostics()) text.append(d.getTestId()).append(" [").append(d.getFailurePhase()).append("] ")
                .append(d.getCategory()).append("\n首次失败：").append(d.getPrimaryFailureTestId()).append("\n")
                .append(d.getExceptionType()).append(": ").append(d.getMessage()).append("\n")
                .append(d.getFailureDetails()).append("\n\n");
        if (execution.getDiagnostics().stream().allMatch(d -> d.getFailureDetails().isBlank()))
            text.append("控制台摘要（完整日志见归档）：\n").append(execution.getOutput());
        return text.toString();
    }
}
