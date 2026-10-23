package com.casc.unittestagent.ui.component;

import com.casc.unittestagent.model.RunnableTestSelectionReport;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public final class RunnableSelectionReportPanel extends JPanel {
    public RunnableSelectionReportPanel(RunnableTestSelectionReport report) {
        super(new BorderLayout(0, 5)); setBorder(JBUI.Borders.empty(6, 0));
        String state = switch (report.getStatus()) {
            case NOT_RUN -> "未运行"; case RUNNING -> "筛选中"; case PASSED -> "保留集合回归通过";
            case BLOCKED -> "筛选未完成"; case NO_RUNNABLE_TESTS -> "未获得可运行集合";
            case CANCELLED -> "已取消"; case STALE -> "代码或源码已变更，结果失效";
        };
        JPanel header = new JPanel(new BorderLayout(0, 3));
        var exclusions = new java.util.LinkedHashMap<String, RunnableTestSelectionReport.ExcludedTest>();
        report.getActiveExclusionOrigins().forEach(o -> o.tests().forEach(t -> exclusions.put(t.testId(), t)));
        report.getExcludedTests().forEach(t -> exclusions.put(t.testId(), t));
        header.add(new JBLabel(report.getStatus() == RunnableTestSelectionReport.Status.RUNNING
                ? "可运行用例筛选：正在编译、执行与筛选当前测试..."
                : "可运行用例筛选：" + state + "；原有 " + report.getOriginalMethods()
                        + " 个方法组，确认保留 " + report.getRetainedTests().size() + "，本次排除 " + report.getExcludedTests().size()
                        + "；累计排除记录 " + exclusions.size()), BorderLayout.NORTH);
        JTextArea message = new JTextArea(report.getMessage()
                + (report.getRuntimeEvidenceArchives().isEmpty() ? "" : "\n完整运行证据：" + String.join("\n", report.getRuntimeEvidenceArchives())) + (report.getRemovedHelpers().isEmpty() ? ""
                : "\n已清理仅供排除用例使用的私有辅助方法（原代码已备份）：" + String.join("、", report.getRemovedHelpers()))); message.setEditable(false);
        message.setLineWrap(true); message.setWrapStyleWord(true); message.setBackground(null);
        header.add(message, BorderLayout.CENTER); add(header, BorderLayout.NORTH);
        if (!exclusions.isEmpty()) {
            var model = new DefaultTableModel(new String[]{"排除的方法组", "未通过原因（完整代码已备份）"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            exclusions.values().forEach(item -> model.addRow(new Object[]{item.testId(), item.reason()}));
            var table = new JBTable(model);
            table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(220));
            table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(480));
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(0, JBUI.scale(Math.min(170, 48 + exclusions.size() * 22))));
            add(scroll, BorderLayout.CENTER);
        }
    }
}
