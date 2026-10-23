package com.casc.unittestagent.ui.component;

import com.casc.unittestagent.model.*;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;

/** Displays only measurements bound to the final file; candidate coverage is never substituted. */
public class FinalTestFileReportPanel extends JPanel {
    public FinalTestFileReportPanel(FinalTestFileReport report) {
        this(report, null);
    }
    public FinalTestFileReportPanel(FinalTestFileReport report, ClassJUnitRepairReport runtime) {
        super(new BorderLayout(0, 6));
        var coverage = report.getMethodCoverage();
        checkWrittenSnapshot(report);
        if (runtime != null && (coverage.getStatus() == CoverageStatus.PASSED || coverage.getStatus() == CoverageStatus.BELOW_THRESHOLD)
                && !runtime.isPassedFor(coverage.getCodeHash(), coverage.getSourceHash())) {
            report.markStale("运行证明与覆盖率文件指纹不一致，请重新验证最终文件。");
        }
        add(new JBLabel("最终文件验证快照覆盖率 · " + (report.isWritten() ? "已写入" : "尚未写入")
                + " · " + coverage.getStatus()), BorderLayout.NORTH);
        var model = new DefaultTableModel(new String[]{"统计范围", "行覆盖率", "分支覆盖率"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        boolean usable = coverage.getStatus() == CoverageStatus.PASSED || coverage.getStatus() == CoverageStatus.BELOW_THRESHOLD;
        addRow(model, "最终完整测试文件 → 所选业务方法", coverage.getGenerated(), usable);
        addRow(model, "最终完整测试文件 → 所选业务类", report.getClassCoverage(), usable);
        var table = new JBTable(model); var metrics = new JPanel(new BorderLayout());
        metrics.add(table.getTableHeader(), BorderLayout.NORTH); metrics.add(table, BorderLayout.CENTER);
        add(metrics, BorderLayout.CENTER);
        String execution = runtime == null ? "" : "\n完整 JUnit：" + runtime.getStatus()
                + "，通过 " + runtime.getFinalExecution().getSucceeded()
                + "，失败 " + runtime.getFinalExecution().getFailed()
                + "，跳过 " + runtime.getFinalExecution().getSkipped();
        String version = coverage.getCodeHash().isBlank() ? "" : "\n文件指纹：" + coverage.getCodeHash();
        var details = new JTextArea(report.getTargetPath() + execution + version + "\n" + coverage.getMessage()
                + "\n方法统计包含其 lambda；局部/匿名类的方法及普通被调用方法单独归属。仅包含最终文件内测试产生的数据，不代表整个项目覆盖率。");
        details.setEditable(false); details.setOpaque(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        add(details, BorderLayout.SOUTH);
    }
    /** A saved percentage cannot be presented as current after the file or an unsaved document changes. */
    private static void checkWrittenSnapshot(FinalTestFileReport report) {
        if (!report.isWritten()) return;
        try {
            String code = com.intellij.openapi.application.ReadAction.compute(() -> {
                var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(report.getTargetPath()));
                var document = file == null ? null : com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(file);
                return document != null && com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document)
                        ? document.getText() : null;
            });
            if (code == null) code = com.casc.unittestagent.util.ProjectFileText.read(java.nio.file.Path.of(report.getTargetPath()));
            if (!com.casc.unittestagent.util.CodeFingerprint.sha256(code).equals(report.getMethodCoverage().getCodeHash())) {
                report.markStale("写入后的测试文件或未保存文档已修改，请重新验证；旧覆盖率不代表当前文件。");
            }
        } catch (Exception unavailable) {
            report.markStale("无法读取已写入文件，不能确认覆盖率仍然有效，请重新验证。");
        }
    }

    private static void addRow(DefaultTableModel model, String label, CoverageSnapshot snapshot, boolean usable) {
        boolean available = usable && snapshot.isAvailable();
        model.addRow(new Object[]{label, metric(snapshot.getLine(), available), metric(snapshot.getBranch(), available)});
    }
    private static String metric(CoverageMetric metric, boolean available) {
        if (!available) return "不可用";
        if (!metric.isApplicable()) return "不适用";
        return com.casc.unittestagent.util.CoverageDisplay.percent(metric.getRate())
                + " (" + metric.getCovered() + "/" + metric.getTotal() + ")";
    }
}
