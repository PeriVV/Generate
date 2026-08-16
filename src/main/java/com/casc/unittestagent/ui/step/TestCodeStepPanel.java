package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.ui.component.CoverageReportPanel;
import com.casc.unittestagent.ui.component.UnitMindEmptyStatePanel;
import com.casc.unittestagent.ui.component.UnitMindErrorPanel;
import com.casc.unittestagent.ui.component.UnitMindLoadingPanel;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

/**
 * 测试代码步骤面板。
 * 成功状态下使用 IntelliJ EditorTextField 提供 Java 高亮、可编辑和滚动能力。
 */
public class TestCodeStepPanel extends JBPanel<TestCodeStepPanel> {
    /** 代码编辑器。 */
    private EditorTextField editorTextField;
    /** 原始生成代码。 */
    private final String generatedCode;
    /** 用户补充要求输入框。 */
    private JTextArea feedbackArea;
    /** 编译诊断与覆盖率详情容器。 */
    private JPanel detailsPanel;
    /** 当前覆盖率可视化组件。 */
    private JComponent coverageComponent;

    /**
     * 创建测试代码页。
     *
     * @param project 当前项目
     * @param status 测试代码生成状态
     * @param generatedCode 最近一次生成成功的原始测试代码
     * @param currentCode 当前编辑器中的测试代码
     * @param error 错误详情
     * @param generationNote 测试代码来源提示
     * @param onRegenerate 带补充要求再次生成动作
     */
    public TestCodeStepPanel(Project project,
                             GenerationStatus status,
                             String generatedCode,
                             String currentCode,
                             String error,
                             String generationNote,
                             ValidationResult validationResult,
                             Consumer<String> onRegenerate,
                             Runnable onValidate,
                             Consumer<String> onCodeChanged) {
        this(project, status, generatedCode, currentCode, error, generationNote,
                validationResult, null, false, onRegenerate, onValidate, onCodeChanged,
                () -> { }, () -> { });
    }

    public TestCodeStepPanel(Project project,
                             GenerationStatus status,
                             String generatedCode,
                             String currentCode,
                             String error,
                             String generationNote,
                             ValidationResult validationResult,
                             CoverageReport coverageReport,
                             boolean coverageRunning,
                             Consumer<String> onRegenerate,
                             Runnable onValidate,
                             Consumer<String> onCodeChanged,
                             Runnable onRunCoverage,
                             Runnable onStopCoverage) {
        this(project, status, generatedCode, currentCode, error, generationNote, validationResult,
                coverageReport, coverageRunning, true, true, onRegenerate, onValidate,
                onCodeChanged, onRunCoverage, onStopCoverage);
    }

    public TestCodeStepPanel(Project project,
                             GenerationStatus status,
                             String generatedCode,
                             String currentCode,
                             String error,
                             String generationNote,
                             ValidationResult validationResult,
                             CoverageReport coverageReport,
                             boolean coverageRunning,
                             boolean regenerationAllowed,
                             boolean coverageAllowed,
                             Consumer<String> onRegenerate,
                             Runnable onValidate,
                             Consumer<String> onCodeChanged,
                             Runnable onRunCoverage,
                             Runnable onStopCoverage) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        this.generatedCode = generatedCode == null ? "" : generatedCode;
        if (status == GenerationStatus.IDLE) {
            add(new UnitMindEmptyStatePanel("尚未生成测试代码", "请先完成方法分析，或通过上一步修改场景后重新进入本步骤。", null, null), BorderLayout.CENTER);
            return;
        }
        if (status == GenerationStatus.RUNNING) {
            add(new UnitMindLoadingPanel("正在生成测试代码..."), BorderLayout.CENTER);
            return;
        }
        if (status == GenerationStatus.FAILED) {
            add(new UnitMindErrorPanel("测试代码生成失败", error == null ? "未知错误" : error), BorderLayout.CENTER);
            return;
        }

        JPanel statusPanel = new JPanel(new BorderLayout(0, 4));
        if (generationNote != null && !generationNote.isBlank()) {
            statusPanel.add(new JBLabel(generationNote), BorderLayout.NORTH);
        }
        JBLabel validationLabel = new JBLabel(validationText(validationResult));
        statusPanel.add(validationLabel, BorderLayout.CENTER);
        detailsPanel = new JPanel();
        detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
        JTextArea diagnostics = diagnosticArea(validationResult);
        if (diagnostics != null) detailsPanel.add(diagnostics);
        coverageComponent = coveragePanel(coverageReport);
        if (coverageComponent != null) detailsPanel.add(coverageComponent);
        if (detailsPanel.getComponentCount() > 0) {
            statusPanel.add(detailsPanel, BorderLayout.SOUTH);
        }
        statusPanel.setBorder(JBUI.Borders.emptyBottom(8));
        add(statusPanel, BorderLayout.NORTH);

        Document document = EditorFactory.getInstance().createDocument(currentCode == null ? "" : currentCode);
        editorTextField = new EditorTextField(document, project, JavaFileType.INSTANCE, false, false);
        editorTextField.setOneLineMode(false);
        editorTextField.setEnabled(!coverageRunning);
        editorTextField.addSettingsProvider(editor -> editor.getSettings().setLineNumbersShown(true));
        JScrollPane scrollPane = new JScrollPane(editorTextField);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scrollPane, BorderLayout.CENTER);
        document.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(DocumentEvent event) {
                validationLabel.setText("编译校验：代码已修改，状态失效；写入前必须重新校验。");
                if (onCodeChanged != null) {
                    onCodeChanged.accept(getCode());
                }
            }
        });

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        JPanel feedbackPanel = new JPanel(new BorderLayout(6, 0));
        feedbackArea = new JTextArea(2, 60);
        feedbackArea.setLineWrap(true);
        feedbackArea.setWrapStyleWord(true);
        feedbackArea.setEnabled(!coverageRunning);
        feedbackPanel.add(new JLabel("补充要求"), BorderLayout.WEST);
        feedbackPanel.add(new JScrollPane(feedbackArea), BorderLayout.CENTER);
        bottom.add(feedbackPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton copy = new JButton("复制代码");
        JButton restore = new JButton("恢复生成代码");
        JButton regenerate = new JButton(regenerationAllowed ? "再次生成" : "整类请重新分析后生成");
        JButton validate = new JButton("重新校验");
        JButton runCoverage = new JButton(!coverageAllowed ? "整类覆盖率首期不支持"
                : coverageRunning ? "覆盖率运行中..." : "运行覆盖率并自动补测");
        JButton stopCoverage = new JButton("停止");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(getCode()), null));
        restore.addActionListener(event -> setCode(generatedCode));
        regenerate.addActionListener(event -> onRegenerate.accept(feedbackArea.getText()));
        validate.addActionListener(event -> onValidate.run());
        restore.setEnabled(!coverageRunning);
        regenerate.setEnabled(!coverageRunning && regenerationAllowed);
        validate.setEnabled(!coverageRunning);
        runCoverage.setEnabled(!coverageRunning && coverageAllowed);
        runCoverage.addActionListener(event -> onRunCoverage.run());
        stopCoverage.setEnabled(coverageRunning);
        stopCoverage.addActionListener(event -> onStopCoverage.run());
        buttons.add(copy);
        buttons.add(restore);
        buttons.add(regenerate);
        buttons.add(validate);
        buttons.add(runCoverage);
        buttons.add(stopCoverage);
        bottom.add(buttons, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    /**
     * 读取当前编辑器文本。
     *
     * @return 用户编辑后的测试代码
     */
    public String getCode() {
        return editorTextField == null ? generatedCode : editorTextField.getText();
    }

    /**
     * 判断当前页面是否已创建可编辑代码组件。
     *
     * @return 存在编辑器时返回 true
     */
    public boolean hasEditor() {
        return editorTextField != null;
    }

    /**
     * 更新编辑器文本。
     *
     * @param code 新代码
     */
    public void setCode(String code) {
        if (editorTextField != null) {
            editorTextField.setText(code == null ? "" : code);
        }
    }

    /**
     * 原位刷新覆盖率进度，避免后台每个阶段都重建代码编辑器。
     */
    public void updateCoverageReport(CoverageReport report) {
        if (detailsPanel == null) return;
        if (coverageComponent != null) {
            detailsPanel.remove(coverageComponent);
        }
        coverageComponent = coveragePanel(report);
        if (coverageComponent != null) {
            detailsPanel.add(coverageComponent);
        }
        detailsPanel.revalidate();
        detailsPanel.repaint();
    }

    private String validationText(ValidationResult result) {
        if (result == null || result.getStatus() == ValidationStatus.NOT_RUN) {
            return "编译校验：尚未执行。";
        }
        if (result.getStatus() == ValidationStatus.RUNNING) {
            return "编译校验：正在使用当前项目 classpath 编译...";
        }
        if (result.getStatus() == ValidationStatus.PASSED) {
            return "编译校验：已通过"
                    + (result.getRepairAttempts() > 0 ? "（自动修复 " + result.getRepairAttempts() + " 轮）" : "")
                    + "。";
        }
        if (result.getStatus() == ValidationStatus.STALE) {
            return "编译校验：代码已修改，状态失效。";
        }
        String kind = result.getStatus() == ValidationStatus.ENVIRONMENT_ERROR ? "环境错误" : "未通过";
        return "编译校验：" + kind + "，共 " + result.getDiagnostics().size() + " 个错误。";
    }

    private JTextArea diagnosticArea(ValidationResult result) {
        if (result == null || result.getDiagnostics().isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        int count = Math.min(5, result.getDiagnostics().size());
        for (int index = 0; index < count; index++) {
            CompileDiagnostic diagnostic = result.getDiagnostics().get(index);
            if (index > 0) {
                text.append('\n');
            }
            text.append(diagnostic.getLine() > 0 ? "L" + diagnostic.getLine() + " " : "")
                    .append(diagnostic.getCode()).append(": ").append(diagnostic.getMessage());
        }
        if (result.getDiagnostics().size() > count) {
            text.append("\n其余 ").append(result.getDiagnostics().size() - count).append(" 个错误已省略。");
        }
        JTextArea area = new JTextArea(text.toString());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(Math.min(3, count));
        return area;
    }

    private JComponent coveragePanel(CoverageReport report) {
        if (report == null || report.getStatus() == com.casc.unittestagent.model.CoverageStatus.NOT_RUN) {
            return null;
        }
        return new CoverageReportPanel(report);
    }
}
