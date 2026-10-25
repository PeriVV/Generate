package com.casc.unittestagent.ui.component;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.model.CoverageGap;
import com.casc.unittestagent.model.CoverageMetric;
import com.casc.unittestagent.model.CoveragePhase;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.FailureCategory;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestExecutionSummary;
import com.casc.unittestagent.model.SourceBugAnalysis;
import com.casc.unittestagent.model.SourceBugLocation;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Locale;

/**
 * 将覆盖率闭环结果展示为指标卡、进度条和结构化缺口表格。
 */
public class CoverageReportPanel extends JBPanel<CoverageReportPanel> {
    private static final Color SUCCESS = new JBColor(new Color(0x2E, 0x7D, 0x32), new Color(0x49, 0x8A, 0x52));
    private static final Color WARNING = new JBColor(new Color(0xC7, 0x78, 0x00), new Color(0xC7, 0x83, 0x24));
    private static final Color ERROR = new JBColor(new Color(0xC6, 0x28, 0x28), new Color(0xB8, 0x4B, 0x4B));
    private static final Color INFO = new JBColor(new Color(0x15, 0x65, 0xC0), new Color(0x4A, 0x83, 0xC6));
    private static final Color MUTED = new JBColor(new Color(0x75, 0x75, 0x75), new Color(0x82, 0x82, 0x82));

    private final CoverageReport report;
    private final CoverageConfig config;

    public CoverageReportPanel(CoverageReport report) {
        super();
        this.report = report;
        this.config = CoverageConfigManager.load();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)));
        setBackground(UIUtil.getPanelBackground());

        add(header());
        add(Box.createVerticalStrut(JBUI.scale(7)));
        add(processPanel());
        add(Box.createVerticalStrut(JBUI.scale(7)));
        add(metricCards());
        addOptionalDetails();
    }

    private JComponent header() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        title.setOpaque(false);
        JBLabel heading = new JBLabel("覆盖率反馈");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        title.add(heading);
        title.add(statusBadge());
        panel.add(title);

        StringBuilder summary = new StringBuilder("运行配置：")
                .append(report.getRuntimeConfigurationOrigin()).append("  ");
        if (report.getStopReason() != null && report.getStopReason() != CoverageStopReason.NONE) {
            summary.append("停止原因：").append(stopReasonText(report.getStopReason())).append("  ");
        }
        if (report.getExplorationRounds() + report.getRepairRounds() == 0 && report.getRounds() > 0) {
            summary.append("补测 ").append(report.getRounds()).append(" 轮");
        } else {
            summary.append("探索 ").append(report.getExplorationRounds())
                    .append(" 轮 / 修复 ").append(report.getRepairRounds()).append(" 轮");
        }
        if (report.getModelRequests() > 0) {
            summary.append("  ·  模型请求 ").append(report.getModelRequests());
        }
        if (!report.getCandidateRejections().isEmpty()) {
            summary.append(" / 不安全补丁拒绝 ").append(report.getCandidateRejections().size());
        }
        if (report.getUnchangedResponses() > 0) {
            summary.append(" / 无变化 ").append(report.getUnchangedResponses());
        }
        if (report.getRuntimeNoProgressCandidates() > 0) {
            summary.append(" / 运行无进展 ").append(report.getRuntimeNoProgressCandidates());
        }
        if (report.getFormatRetries() > 0 || report.getTransportRetries() > 0) {
            summary.append("  ·  重试 ").append(report.getFormatRetries())
                    .append("/").append(report.getTransportRetries()).append("（格式/网络）");
        }
        if (report.getDurationMillis() > 0) {
            summary.append("  ·  ").append(durationText(report.getDurationMillis()));
        }
        JTextArea detail = new JTextArea(summary.toString());
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setOpaque(false);
        detail.setRows(2);
        detail.setBorder(JBUI.Borders.emptyTop(3));
        detail.setForeground(UIUtil.getContextHelpForeground());
        detail.setMaximumSize(new Dimension(Integer.MAX_VALUE, detail.getPreferredSize().height));
        panel.add(detail);
        return panel;
    }

    private JComponent statusBadge() {
        JBLabel badge = new JBLabel(statusText(report.getStatus()));
        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setBackground(statusColor(report.getStatus()));
        badge.setBorder(JBUI.Borders.empty(2, 7));
        return badge;
    }

    private boolean usable(CoverageSnapshot snapshot) {
        return snapshot != null && snapshot.isAvailable()
                && (report.getStatus() == CoverageStatus.RUNNING || report.getStatus() == CoverageStatus.PASSED
                || report.getStatus() == CoverageStatus.BELOW_THRESHOLD);
    }

    private JComponent metricCards() {
        // 纵向卡片在窄工具窗口中不会互相挤压或制造整页横向滚动。
        JPanel cards = new JPanel(new java.awt.GridLayout(0, 1, 0, JBUI.scale(8)));
        cards.setOpaque(false);
        cards.add(metricCard("当前测试集合 → 所选方法", report.getGenerated()));
        return cards;
    }

    private JComponent processPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JBLabel title = new JBLabel("闭环过程");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        heading.add(title, BorderLayout.WEST);
        if (report.getCurrentCandidate() > 0 && report.getStatus() == CoverageStatus.RUNNING) {
            JBLabel candidate = new JBLabel("当前候选：第 " + report.getCurrentCandidate() + " 轮");
            candidate.setForeground(UIUtil.getContextHelpForeground());
            heading.add(candidate, BorderLayout.EAST);
        }
        panel.add(heading);
        panel.add(Box.createVerticalStrut(JBUI.scale(3)));
        panel.add(new CoverageProcessTrack(report));
        panel.add(Box.createVerticalStrut(JBUI.scale(3)));

        JProgressBar progress = new JProgressBar(0, 1000);
        progress.setStringPainted(false);
        progress.setValue((int) Math.round(report.getProgressFraction() * 1000.0d));
        String progressText = report.getProgressMessage();
        if (progressText.isBlank()) {
            progressText = report.getStatus() == CoverageStatus.RUNNING
                    ? "正在启动覆盖率闭环..." : statusText(report.getStatus());
        }
        progress.setString(progressText);
        progress.setForeground(statusColor(report.getStatus() == CoverageStatus.RUNNING
                ? CoverageStatus.RUNNING : report.getStatus()));
        progress.setPreferredSize(new Dimension(JBUI.scale(300), JBUI.scale(20)));
        if (report.getStatus() == CoverageStatus.RUNNING
                && report.getPhase() == CoveragePhase.NOT_STARTED) {
            progress.setIndeterminate(true);
        }
        JBLabel progressLabel = new JBLabel(progressText);
        progressLabel.setForeground(report.getStatus() == CoverageStatus.RUNNING
                ? UIUtil.getLabelForeground() : statusColor(report.getStatus()));
        panel.add(progressLabel);
        panel.add(Box.createVerticalStrut(JBUI.scale(2)));
        panel.add(progress);
        return panel;
    }

    private JComponent metricCard(String title, CoverageSnapshot snapshot) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(7, 9)));
        card.setBackground(UIUtil.getPanelBackground());

        JPanel headingRow = new JPanel(new BorderLayout());
        headingRow.setOpaque(false);
        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        headingRow.add(titleLabel, BorderLayout.WEST);
        headingRow.add(new JBLabel("目标：行 " + percent(config.getLineThreshold())
                + " / 分支 " + percent(config.getBranchThreshold())), BorderLayout.EAST);
        card.add(headingRow);
        card.add(Box.createVerticalStrut(JBUI.scale(5)));

        boolean running = report.getStatus() == CoverageStatus.RUNNING;
        if (running && usable(snapshot)) {
            JBLabel measured = new JBLabel("已完成候选的测量结果；补测仍在进行");
            measured.setForeground(UIUtil.getContextHelpForeground());
            card.add(measured);
            card.add(Box.createVerticalStrut(JBUI.scale(4)));
        }
        card.add(metricRow("行", snapshot == null ? null : snapshot.getLine(),
                config.getLineThreshold(), usable(snapshot), false, running));
        card.add(Box.createVerticalStrut(JBUI.scale(4)));
        card.add(metricRow("分支", snapshot == null ? null : snapshot.getBranch(),
                config.getBranchThreshold(), usable(snapshot), true, running));
        card.add(Box.createVerticalStrut(JBUI.scale(5)));
        card.add(executionSummary(snapshot));
        return card;
    }

    private JComponent metricRow(String name,
                                 CoverageMetric metric,
                                 double threshold,
                                 boolean available,
                                 boolean allowNotApplicable,
                                 boolean running) {
        JPanel row = new JPanel(new BorderLayout(7, 0));
        row.setOpaque(false);
        JBLabel label = new JBLabel(name);
        label.setPreferredSize(new Dimension(JBUI.scale(34), label.getPreferredSize().height));
        row.add(label, BorderLayout.WEST);

        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(false);
        bar.setPreferredSize(new Dimension(JBUI.scale(150), JBUI.scale(19)));
        if (running && !available) {
            bar.setIndeterminate(true);
            bar.setString("执行中...");
        } else if (!available || metric == null) {
            bar.setValue(0);
            bar.setString("不可用");
            bar.setForeground(MUTED);
        } else if (allowNotApplicable && !metric.isApplicable()) {
            bar.setValue(1000);
            bar.setString("N/A（无分支）");
            bar.setForeground(SUCCESS);
        } else {
            double rate = metric.getRate();
            bar.setValue((int) Math.round(rate * 1000.0d));
            bar.setString(percent(rate) + "  (" + metric.getCovered() + "/" + metric.getTotal() + ")");
            bar.setForeground(rate >= threshold ? SUCCESS : (rate > 0 ? WARNING : ERROR));
        }
        JPanel visualization = new JPanel(new BorderLayout(7, 0));
        visualization.setOpaque(false);
        visualization.add(bar, BorderLayout.CENTER);
        JBLabel value = new JBLabel(bar.getString());
        value.setHorizontalAlignment(JBLabel.RIGHT);
        value.setPreferredSize(new Dimension(JBUI.scale(105), value.getPreferredSize().height));
        visualization.add(value, BorderLayout.EAST);
        row.add(visualization, BorderLayout.CENTER);
        return row;
    }

    private JComponent executionSummary(CoverageSnapshot snapshot) {
        TestExecutionSummary execution = snapshot == null ? null : snapshot.getExecution();
        String text;
        if (report.getStatus() == CoverageStatus.STALE) {
            text = "运行证明已失效，请重新执行。";
        } else if (execution == null || execution.getFound() == 0) {
            text = "测试：尚无真实执行记录";
        } else {
            text = "测试：" + execution.getSucceeded() + "/" + execution.getFound() + " 通过";
            if (execution.getFailed() > 0) text += "  ·  " + execution.getFailed() + " 失败";
            if (execution.getSkipped() > 0) text += "  ·  " + execution.getSkipped() + " 跳过";
            if (execution.isTimedOut()) text += "  ·  已超时";
            if (execution.getExitCode() >= 0 && execution.getExitCode() != 0) {
                text += "  ·  退出码 " + execution.getExitCode();
            }
        }
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        if (snapshot != null && !snapshot.isAvailable() && !snapshot.getMessage().isBlank()) {
            label.setToolTipText(snapshot.getMessage());
        }
        return label;
    }

    private void addOptionalDetails() {
        if (report.getSourceBugAnalysis() != null
                && report.getSourceBugAnalysis().isAttempted()) {
            add(Box.createVerticalStrut(JBUI.scale(8)));
            add(sourceBugAnalysisPanel(report.getSourceBugAnalysis()));
        }
        if (!report.getHistory().isEmpty()) {
            add(Box.createVerticalStrut(JBUI.scale(7)));
            add(roundHistoryPanel());
        }
        if (!report.getMessage().isBlank()) {
            add(Box.createVerticalStrut(JBUI.scale(6)));
            add(messageArea(report.getMessage()));
        }

        List<RuntimeDiagnostic> diagnostics = report.getGenerated() == null
                || report.getGenerated().getExecution() == null
                ? List.of() : report.getGenerated().getExecution().getDiagnostics();
        if (!diagnostics.isEmpty()) {
            add(Box.createVerticalStrut(JBUI.scale(7)));
            add(sectionTitle("运行失败（" + diagnostics.size() + "）"));
            add(diagnosticTable(diagnostics));
            add(new RuntimeEvidencePanel(report.getGenerated().getExecution()));
        }
        if (!report.getGaps().isEmpty()) {
            add(Box.createVerticalStrut(JBUI.scale(7)));
            add(sectionTitle("未覆盖目标（" + report.getGaps().size() + "）"));
            add(gapTable(report.getGaps()));
        }
    }

    private JComponent sourceBugAnalysisPanel(SourceBugAnalysis analysis) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(ERROR, 1), JBUI.Borders.empty(7, 9)));
        panel.setBackground(UIUtil.getPanelBackground());
        JBLabel title = new JBLabel("疑似源码缺陷分析（只读假设，不是已确认事实）");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(ERROR);
        panel.add(title);
        panel.add(Box.createVerticalStrut(JBUI.scale(4)));

        JTextArea body = new JTextArea(sourceBugAnalysisText(analysis));
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        body.setRows(Math.min(12, Math.max(3, body.getText().split("\\R").length)));
        panel.add(body);
        return panel;
    }

    private String sourceBugAnalysisText(SourceBugAnalysis analysis) {
        if (!analysis.isAvailable()) {
            return analysis.getError().isBlank() ? "归因分析未返回结果。" : analysis.getError();
        }
        StringBuilder text = new StringBuilder("置信度：")
                .append(analysis.getConfidence()).append("\n结论：")
                .append(analysis.getSummary());
        if (!analysis.getSuspectedLocations().isEmpty()) {
            text.append("\n可能位置：");
            for (SourceBugLocation location : analysis.getSuspectedLocations()) {
                text.append("\n- ").append(location.getFile());
                if (location.getLine() > 0) text.append(":").append(location.getLine());
                if (!location.getSymbol().isBlank()) text.append("  ").append(location.getSymbol());
                if (!location.getReason().isBlank()) text.append("：").append(location.getReason());
            }
        }
        appendItems(text, "根因假设", analysis.getHypotheses());
        appendItems(text, "证据", analysis.getEvidence());
        appendItems(text, "建议核查", analysis.getRecommendedChecks());
        return text.toString();
    }

    private void appendItems(StringBuilder text, String heading, List<String> items) {
        if (items == null || items.isEmpty()) return;
        text.append('\n').append(heading).append('：');
        for (String item : items) text.append("\n- ").append(item);
    }

    private JComponent sectionTitle(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(JBUI.Borders.emptyBottom(3));
        return label;
    }

    private JComponent roundHistoryPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.add(sectionTitle("各轮覆盖率变化"));
        int count = Math.min(6, report.getHistory().size());
        for (int index = 0; index < count; index++) {
            CoverageSnapshot snapshot = report.getHistory().get(index);
            JPanel row = new JPanel(new BorderLayout(7, 0));
            row.setOpaque(false);
            JBLabel round = new JBLabel(index == 0 ? "初始" : "补测 " + index);
            round.setPreferredSize(new Dimension(JBUI.scale(52), round.getPreferredSize().height));
            row.add(round, BorderLayout.WEST);
            JPanel result = new JPanel();
            result.setLayout(new BoxLayout(result, BoxLayout.Y_AXIS));
            result.setOpaque(false);
            JBLabel outcome = new JBLabel(roundOutcome(snapshot));
            outcome.setForeground(usable(snapshot)
                    ? UIUtil.getContextHelpForeground() : ERROR);
            if (snapshot != null && !snapshot.getMessage().isBlank()) {
                outcome.setToolTipText(snapshot.getMessage());
            }
            result.add(outcome);
            result.add(Box.createVerticalStrut(JBUI.scale(2)));
            JPanel bars = new JPanel(new java.awt.GridLayout(1, 2, JBUI.scale(7), 0));
            bars.setOpaque(false);
            bars.add(historyBar("行", snapshot == null ? null : snapshot.getLine(),
                    usable(snapshot), false, config.getLineThreshold()));
            bars.add(historyBar("分支", snapshot == null ? null : snapshot.getBranch(),
                    usable(snapshot), true, config.getBranchThreshold()));
            result.add(bars);
            row.add(result, BorderLayout.CENTER);
            panel.add(row);
            if (index + 1 < count) panel.add(Box.createVerticalStrut(JBUI.scale(5)));
        }
        return panel;
    }

    private String roundOutcome(CoverageSnapshot snapshot) {
        if (report.getStatus() == CoverageStatus.STALE) return "历史结果已失效，请重新测量当前代码。";
        if (snapshot == null) return "未产生执行结果，覆盖率不计入";
        TestExecutionSummary execution = snapshot.getExecution();
        if (usable(snapshot)) {
            String branch = snapshot.getBranch().isApplicable()
                    ? percent(snapshot.getBranch().getRate()) : "N/A";
            return "行 " + percent(snapshot.getLine().getRate()) + "  ·  分支 " + branch
                    + "  ·  测试 " + execution.getSucceeded() + "/" + execution.getFound() + " 通过";
        }
        if (execution.isTimedOut()) return "测试超时，覆盖率不计入";
        if (execution.getFound() == 0) {
            return "未发现可执行测试（退出码 " + execution.getExitCode() + "），覆盖率不计入";
        }
        if (execution.getFailed() > 0 || execution.getExitCode() != 0) {
            return "测试失败（" + execution.getSucceeded() + "/" + execution.getFound()
                    + " 通过），覆盖率不计入";
        }
        String reason = snapshot.getMessage().isBlank() ? "JaCoCo 分析未完成" : snapshot.getMessage();
        return reason + "，覆盖率不计入";
    }

    private JComponent historyBar(String name,
                                  CoverageMetric metric,
                                  boolean available,
                                  boolean allowNotApplicable,
                                  double threshold) {
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(false);
        if (!available || metric == null) {
            bar.setString(name + " 不可用");
            bar.setForeground(MUTED);
        } else if (allowNotApplicable && !metric.isApplicable()) {
            bar.setValue(1000);
            bar.setString(name + " N/A");
            bar.setForeground(SUCCESS);
        } else {
            bar.setValue((int) Math.round(metric.getRate() * 1000.0d));
            bar.setString(name + " " + percent(metric.getRate()));
            bar.setForeground(metric.getRate() >= threshold ? SUCCESS
                    : (metric.getRate() > 0 ? WARNING : ERROR));
        }
        bar.setPreferredSize(new Dimension(JBUI.scale(90), JBUI.scale(18)));
        JPanel visualization = new JPanel(new BorderLayout(5, 0));
        visualization.setOpaque(false);
        visualization.add(bar, BorderLayout.CENTER);
        JBLabel value = new JBLabel(bar.getString());
        value.setHorizontalAlignment(JBLabel.RIGHT);
        value.setPreferredSize(new Dimension(JBUI.scale(78), value.getPreferredSize().height));
        visualization.add(value, BorderLayout.EAST);
        return visualization;
    }

    private JComponent messageArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(report.getStatus() == CoverageStatus.PASSED
                ? UIUtil.getLabelForeground() : statusColor(report.getStatus()));
        area.setBorder(JBUI.Borders.emptyLeft(2));
        area.setRows(Math.min(2, Math.max(1, text.split("\\R").length)));
        return area;
    }

    private JComponent diagnosticTable(List<RuntimeDiagnostic> diagnostics) {
        DefaultTableModel model = readOnlyModel(new String[]{"测试", "异常", "消息"});
        int count = Math.min(20, diagnostics.size());
        for (int index = 0; index < count; index++) {
            RuntimeDiagnostic diagnostic = diagnostics.get(index);
            String message = diagnostic.getMessage().isBlank() ? "未提供异常消息" : diagnostic.getMessage();
            if (diagnostic.getExpectedInvocationCount() >= 0
                    && diagnostic.getActualInvocationCount() >= 0) {
                message += "  |  verify 次数：期望 " + diagnostic.getExpectedInvocationCount()
                        + " / 实际 " + diagnostic.getActualInvocationCount();
            }
            if (diagnostic.getSourceLine() > 0) {
                message += "  |  L" + diagnostic.getSourceLine() + ": " + diagnostic.getSourceSnippet();
            }
            if (!diagnostic.getSuggestion().isBlank()) message += "  |  建议：" + diagnostic.getSuggestion();
            String usefulStack = usefulStack(diagnostic);
            if (!usefulStack.isBlank()) message += "  |  " + usefulStack;
            String exception = diagnostic.getCategory().isBlank()
                    ? diagnostic.getExceptionType()
                    : FailureCategory.fromCode(diagnostic.getCategory()).displayText()
                            + " / " + diagnostic.getExceptionType();
            if (diagnostic.getFailurePhase() != com.casc.unittestagent.model.RuntimeFailurePhase.UNKNOWN)
                exception = diagnostic.getFailurePhase().getLabelCn() + " / " + exception;
            model.addRow(new Object[]{diagnostic.getTestId(), exception, message});
        }
        JBTable table = table(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(190));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(420));
        return tableScroll(table, Math.min(4, count));
    }

    private JComponent gapTable(List<CoverageGap> gaps) {
        DefaultTableModel model = readOnlyModel(new String[]{"行", "类型", "遗漏", "源码"});
        int count = Math.min(50, gaps.size());
        for (int index = 0; index < count; index++) {
            CoverageGap gap = gaps.get(index);
            String type = gap.getKind() == CoverageGap.Kind.BRANCH ? "分支" : "行";
            String missed = gap.getKind() == CoverageGap.Kind.BRANCH
                    ? gap.getMissedCount() + "/" + gap.getTotalCount() : "未覆盖";
            model.addRow(new Object[]{"L" + gap.getLineNumber(), type, missed, gap.getSource()});
        }
        JBTable table = table(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(55));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(65));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(75));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(600));
        return tableScroll(table, Math.min(5, count));
    }

    private DefaultTableModel readOnlyModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private JBTable table(DefaultTableModel model) {
        JBTable table = new JBTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setShowGrid(false);
        table.setStriped(true);
        table.setRowSelectionAllowed(true);
        return table;
    }

    private JComponent tableScroll(JBTable table, int visibleRows) {
        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        int height = table.getTableHeader().getPreferredSize().height
                + table.getRowHeight() * Math.max(1, visibleRows) + JBUI.scale(3);
        scroll.setPreferredSize(new Dimension(JBUI.scale(760), height));
        return scroll;
    }

    private String usefulStack(RuntimeDiagnostic diagnostic) {
        if (diagnostic.getStackTrace().isEmpty()) return "";
        return diagnostic.getStackTrace().stream()
                .filter(line -> line.startsWith("Caused by:"))
                .reduce((first, second) -> second)
                .orElse(diagnostic.getStackTrace().get(0));
    }

    private String statusText(CoverageStatus status) {
        if (status == null) return "未运行";
        return switch (status) {
            case NOT_RUN -> "未运行";
            case RUNNING -> "执行中";
            case PASSED -> "已达标";
            case BELOW_THRESHOLD -> "未达标";
            case TEST_FAILED -> "测试失败";
            case SUSPECTED_SUT_BUG -> "疑似产品缺陷";
            case NEEDS_REVIEW -> "需人工确认";
            case ENVIRONMENT_ERROR -> "环境错误";
            case STALE -> "已失效";
            case CANCELLED -> "已取消";
        };
    }

    private Color statusColor(CoverageStatus status) {
        if (status == CoverageStatus.PASSED) return SUCCESS;
        if (status == CoverageStatus.BELOW_THRESHOLD || status == CoverageStatus.NEEDS_REVIEW) return WARNING;
        if (status == CoverageStatus.TEST_FAILED || status == CoverageStatus.SUSPECTED_SUT_BUG
                || status == CoverageStatus.ENVIRONMENT_ERROR) return ERROR;
        if (status == CoverageStatus.RUNNING) return INFO;
        return MUTED;
    }

    private String stopReasonText(CoverageStopReason reason) {
        if (reason == null) return "无";
        return switch (reason) {
            case NONE -> "无";
            case TARGET_REACHED -> "已达到目标";
            case MAX_ROUNDS -> "已达最大轮次";
            case NO_PROGRESS -> "覆盖率无增益";
            case REPEATED_CODE -> "返回了重复代码";
            case REPAIR_BUDGET_EXHAUSTED -> "修复预算已耗尽";
            case TEST_FAILURE -> "测试持续失败";
            case TEST_DESIGN_ERROR -> "测试设计错误";
            case RUNTIME_ENVIRONMENT_FAILURE -> "运行环境失败";
            case SUSPECTED_SUT_BUG -> "疑似生产代码缺陷";
            case ORACLE_INCONCLUSIVE -> "证据不足或无法判定";
            case MODEL_ERROR -> "大模型调用失败";
            case ENVIRONMENT_ERROR -> "覆盖率环境错误";
            case CANCELLED -> "用户取消";
        };
    }

    private String durationText(long millis) {
        if (millis < 1000) return millis + " ms";
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0d);
    }

    private String percent(double rate) {
        return com.casc.unittestagent.util.CoverageDisplay.percent(rate);
    }

    /** 紧凑的阶段轨道：蓝色表示当前，绿色表示已经真实经过，灰色表示未执行。 */
    private static final class CoverageProcessTrack extends JComponent {
        private static final CoveragePhase[] PHASES = {
                CoveragePhase.PREPARING, CoveragePhase.COMPILING, CoveragePhase.EXECUTING,
                CoveragePhase.ANALYZING, CoveragePhase.IMPROVING, CoveragePhase.DIAGNOSING_SUT_BUG,
                CoveragePhase.VALIDATING, CoveragePhase.COMPLETED
        };
        private static final String[] LABELS = {
                "准备", "编译", "执行", "分析", "补测", "归因", "校验", "完成"
        };
        private final CoverageReport report;

        private CoverageProcessTrack(CoverageReport report) {
            this.report = report;
            setFont(UIUtil.getLabelFont());
            setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(39)));
            setMinimumSize(new Dimension(0, JBUI.scale(39)));
            setToolTipText(report.getProgressMessage());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int radius = JBUI.scale(5);
                int left = radius + JBUI.scale(4);
                int right = getWidth() - radius - JBUI.scale(4);
                int y = JBUI.scale(9);
                int step = PHASES.length == 1 ? 0 : (right - left) / (PHASES.length - 1);
                g.setColor(JBColor.border());
                g.drawLine(left, y, right, y);
                Font labelFont = getFont().deriveFont(Math.max(10f, getFont().getSize2D() - 1f));
                g.setFont(labelFont);
                FontMetrics metrics = g.getFontMetrics();
                for (int index = 0; index < PHASES.length; index++) {
                    int x = left + step * index;
                    g.setColor(phaseColor(PHASES[index]));
                    g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
                    g.setColor(UIUtil.getLabelForeground());
                    int labelWidth = metrics.stringWidth(LABELS[index]);
                    g.drawString(LABELS[index], x - labelWidth / 2, y + radius + metrics.getAscent() + JBUI.scale(3));
                }
            } finally {
                g.dispose();
            }
        }

        private Color phaseColor(CoveragePhase phase) {
            if (phase == report.getPhase()) {
                if (report.getStatus() == CoverageStatus.RUNNING) return INFO;
                if (report.getStatus() == CoverageStatus.PASSED) return SUCCESS;
                if (report.getStatus() == CoverageStatus.BELOW_THRESHOLD
                        || report.getStatus() == CoverageStatus.CANCELLED
                        || report.getStatus() == CoverageStatus.STALE) return WARNING;
                return CoverageReportPanel.ERROR;
            }
            return report.getVisitedPhases().contains(phase) ? SUCCESS : MUTED;
        }
    }
}
