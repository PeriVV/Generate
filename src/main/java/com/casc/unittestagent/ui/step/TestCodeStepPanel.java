package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.model.RunnableTestSelectionReport;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.FailureCategory;
import com.casc.unittestagent.model.RepairStrategy;
import com.casc.unittestagent.model.SourceBugAnalysis;
import com.casc.unittestagent.model.SourceBugLocation;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.ui.component.CoverageReportPanel;
import com.casc.unittestagent.ui.component.UnitMindEmptyStatePanel;
import com.casc.unittestagent.ui.component.UnitMindErrorPanel;
import com.casc.unittestagent.ui.component.UnitMindLoadingPanel;
import com.casc.unittestagent.ui.component.UnitMindWrapLayout;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

/**
 * 测试代码步骤面板。
 * 成功状态下使用 IntelliJ EditorTextField 提供 Java 高亮、可编辑和滚动能力。
 */
public class TestCodeStepPanel extends JBPanel<TestCodeStepPanel> {
    private static final int MIN_CODE_AREA_HEIGHT = 180;
    private static final float NORMAL_DETAILS_PROPORTION = 0.34f;
    private static final float CRITICAL_DETAILS_PROPORTION = 0.52f;
    private static final float EXPANDED_DETAILS_PROPORTION = 0.68f;

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
    private JComponent classJUnitComponent;
    private JPanel actionsPanel;
    private JComponent runnableSelectionComponent;
    /** 验证详情和代码编辑器的可调整分栏。 */
    private OnePixelSplitter validationCodeSplitter;
    private JButton detailsSizeButton;
    private float defaultDetailsProportion = NORMAL_DETAILS_PROPORTION;
    private boolean detailsExpanded;

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
        this(project, status, generatedCode, currentCode, error, generationNote, validationResult,
                coverageReport, null, coverageRunning, regenerationAllowed, coverageAllowed,
                false, onRegenerate, onValidate, onCodeChanged, onRunCoverage, onStopCoverage,
                feedback -> { }, 0);
    }

    public TestCodeStepPanel(Project project,
                             GenerationStatus status,
                             String generatedCode,
                             String currentCode,
                             String error,
                             String generationNote,
                             ValidationResult validationResult,
                             CoverageReport coverageReport,
                             ClassJUnitRepairReport classJUnitReport,
                             boolean coverageRunning,
                             boolean regenerationAllowed,
                             boolean coverageAllowed,
                             boolean classMode,
                             Consumer<String> onRegenerate,
                             Runnable onValidate,
                             Consumer<String> onCodeChanged,
                             Runnable onRunCoverage,
                             Runnable onStopCoverage,
                             Consumer<String> onResumeWithEvidence,
                             int evidenceResumeUsed) {
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
        boolean hasDraft = currentCode != null && !currentCode.isBlank() || !this.generatedCode.isBlank();
        if (status == GenerationStatus.FAILED && !hasDraft) {
            add(new UnitMindErrorPanel("测试代码生成失败", error == null ? "未知错误" : error), BorderLayout.CENTER);
            return;
        }

        JPanel statusPanel = new ViewportWidthPanel(new BorderLayout(0, 4));
        if (generationNote != null && !generationNote.isBlank()) {
            statusPanel.add(new JBLabel(generationNote), BorderLayout.NORTH);
        }
        JBLabel validationLabel = new JBLabel(validationText(validationResult));
        statusPanel.add(validationLabel, BorderLayout.CENTER);
        if (validationResult != null && validationResult.getStatus() == ValidationStatus.FAILED
                || status == GenerationStatus.FAILED) {
            JTextArea subsetHint = new JTextArea("部分用例受阻时，可点击“保留可运行用例并测覆盖率”，单独验证剩余用例。");
            subsetHint.setEditable(false); subsetHint.setLineWrap(true); subsetHint.setWrapStyleWord(true);
            subsetHint.setBackground(null); statusPanel.add(subsetHint, BorderLayout.SOUTH);
        }
        detailsPanel = new JPanel();
        detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
        JTextArea diagnostics = diagnosticArea(validationResult);
        if (diagnostics != null) detailsPanel.add(diagnostics);
        coverageComponent = coveragePanel(coverageReport);
        if (coverageComponent != null) detailsPanel.add(coverageComponent);
        classJUnitComponent = classJUnitPanel(classJUnitReport);
        if (classJUnitComponent != null) detailsPanel.add(classJUnitComponent);
        statusPanel.add(detailsPanel, BorderLayout.SOUTH);
        statusPanel.setBorder(JBUI.Borders.empty(0, 0, 8, 4));

        Document document = EditorFactory.getInstance().createDocument(currentCode == null ? "" : currentCode);
        editorTextField = new EditorTextField(document, project, JavaFileType.INSTANCE, false, false);
        editorTextField.setOneLineMode(false);
        editorTextField.setEnabled(!coverageRunning);
        editorTextField.addSettingsProvider(editor -> editor.getSettings().setLineNumbersShown(true));
        JScrollPane scrollPane = new JScrollPane(editorTextField);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel codePanel = new JPanel(new BorderLayout(0, 4));
        JPanel codeHeader = new JPanel(new BorderLayout());
        codeHeader.add(new JBLabel("测试代码（可编辑，拖动上方分隔线可调整高度）"), BorderLayout.WEST);
        detailsSizeButton = new JButton("放大诊断区");
        detailsSizeButton.setToolTipText("在诊断详情与测试代码之间切换显示比例");
        detailsSizeButton.addActionListener(event -> toggleDetailsSize());
        codeHeader.add(detailsSizeButton, BorderLayout.EAST);
        codePanel.add(codeHeader, BorderLayout.NORTH);
        codePanel.add(scrollPane, BorderLayout.CENTER);
        codePanel.setMinimumSize(new Dimension(0, JBUI.scale(MIN_CODE_AREA_HEIGHT)));

        JScrollPane detailsScrollPane = new JScrollPane(statusPanel);
        detailsScrollPane.setBorder(JBUI.Borders.empty());
        detailsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        // 诊断区始终跟随工具窗口宽度；表格如需横向浏览，只在表格自己的滚动容器内进行。
        detailsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        detailsScrollPane.setMinimumSize(new Dimension(0, JBUI.scale(96)));

        defaultDetailsProportion = isCriticalReport(coverageReport, classJUnitReport)
                ? CRITICAL_DETAILS_PROPORTION : NORMAL_DETAILS_PROPORTION;

        validationCodeSplitter = new OnePixelSplitter(true, defaultDetailsProportion);
        validationCodeSplitter.setBorder(JBUI.Borders.empty());
        // 小尺寸工具窗口中若同时尊重代码区最小高度，上方诊断会被挤到不可见。
        // 比例和显式放大按钮负责可用性，分隔线仍可由用户自由拖动。
        validationCodeSplitter.setHonorComponentsMinimumSize(false);
        validationCodeSplitter.setFirstComponent(detailsScrollPane);
        validationCodeSplitter.setSecondComponent(codePanel);
        add(validationCodeSplitter, BorderLayout.CENTER);
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
        boolean evidenceStopState = evidenceResumeStopState(coverageReport, coverageRunning);
        if (evidenceStopState) {
            JTextArea evidenceHint = new JTextArea(2, 60);
            evidenceHint.setLineWrap(true);
            evidenceHint.setWrapStyleWord(true);
            evidenceHint.setEditable(false);
            evidenceHint.setBackground(null);
            FailureCategory category = FailureCategory.fromCode(
                    coverageReport.getOracleAssessment().getRootCauseCategory());
            String hintText = "证据提示：" + category.formatRequiredUserInfo(
                    coverageReport.getFailingTestNamesAtStop());
            if (CoverageConfigManager.load().isStrictOracleGate()) {
                hintText += "（严格 Oracle 准出已开启：即使补充证据，修改断言的修复也不会自动应用，仍需人工确认后写入。）";
            }
            evidenceHint.setText(hintText);
            bottom.add(evidenceHint, BorderLayout.NORTH);
        }
        JPanel feedbackPanel = new JPanel(new BorderLayout(6, 0));
        feedbackArea = new JTextArea(2, 60);
        feedbackArea.setLineWrap(true);
        feedbackArea.setWrapStyleWord(true);
        feedbackArea.setEnabled(!coverageRunning);
        feedbackPanel.add(new JLabel("补充要求"), BorderLayout.WEST);
        feedbackPanel.add(new JScrollPane(feedbackArea), BorderLayout.CENTER);
        bottom.add(feedbackPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new UnitMindWrapLayout(FlowLayout.LEFT, 8, 4));
        actionsPanel = buttons;
        JButton copy = new JButton("复制");
        copy.putClientProperty("unitmind.readOnlyDuringImport", true);
        copy.setToolTipText("复制当前测试代码到剪贴板");
        JButton restore = new JButton("还原");
        restore.setToolTipText("丢弃手动修改，恢复为最近一次生成的测试代码");
        JButton regenerate = new JButton(regenerationAllowed ? "再次生成" : "重新分析后生成");
        JButton validate = new JButton("重新校验");
        JButton runCoverage = new JButton(!coverageAllowed ? "闭环不可用"
                : coverageRunning ? (classMode ? "修复中..." : "运行中...")
                : classMode ? "运行整类修复" : "运行覆盖率");
        runCoverage.setToolTipText("在隔离 JVM 中真实执行测试，按未覆盖的行/分支自动补测并修复");
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
        if (evidenceStopState) {
            JButton resumeEvidence = new JButton("补充证据并继续修复");
            int maxEvidenceResumes = CoverageConfigManager.load().getMaxEvidenceResumes();
            if (maxEvidenceResumes > 0 && evidenceResumeUsed < maxEvidenceResumes) {
                resumeEvidence.setToolTipText("将下方补充要求作为用户权威证据注入，从当前测试代码继续覆盖率修复");
            } else {
                // 额度耗尽/禁用时保留入口并置灰，让用户知道为什么不能续修，而不是按钮凭空消失。
                resumeEvidence.setEnabled(false);
                resumeEvidence.setToolTipText("补充证据续修次数已达上限（" + maxEvidenceResumes
                        + "）。可在设置中调整额度，或重新生成后再试。");
            }
            resumeEvidence.addActionListener(event -> onResumeWithEvidence.accept(feedbackArea.getText()));
            buttons.add(resumeEvidence);
        }
        buttons.add(stopCoverage);
        bottom.add(buttons, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    public void configureRunnableSelection(RunnableTestSelectionReport report,
                                           Runnable onSelect, Runnable onRestore, Runnable onOpenOriginal,
                                           boolean running) {
        if (actionsPanel == null || detailsPanel == null) return;
        JButton select = new JButton("保留可运行用例并测覆盖率");
        select.setToolTipText("支持预检、编译或运行失败：隔离问题方法组及其不再使用的私有辅助方法，完整回归并测量剩余集合的覆盖率");
        select.setEnabled(!running); select.addActionListener(e -> onSelect.run()); actionsPanel.add(select);
        if (report != null && !report.getOriginalCode().isBlank()) {
            JButton original = new JButton("查看筛选前代码");
            original.setEnabled(!report.getArchivePath().isBlank()); original.addActionListener(e -> onOpenOriginal.run());
            actionsPanel.add(original);
            JButton restore = new JButton("恢复筛选前代码");
            restore.setEnabled(!running && !report.getOriginalCode().equals(getCode()));
            restore.addActionListener(e -> onRestore.run()); actionsPanel.add(restore);
        }
        if (runnableSelectionComponent != null) detailsPanel.remove(runnableSelectionComponent);
        if (report != null && report.getStatus() != RunnableTestSelectionReport.Status.NOT_RUN) {
            runnableSelectionComponent = new com.casc.unittestagent.ui.component.RunnableSelectionReportPanel(report);
            detailsPanel.add(runnableSelectionComponent, 0);
        }
        revalidate(); repaint();
    }

    /**
     * 只有“覆盖率闭环因缺少 Oracle 证据停止”才提供证据续修入口：
     * 需要 NEEDS_REVIEW 状态（STALE 意味着代码或输入已被编辑，指纹前提失效）、
     * ORACLE_INCONCLUSIVE 停止原因，且失败类别确实属于 EVIDENCE_REQUIRED。
     * 额度是否可用（evidenceResumeUsed vs maxEvidenceResumes）由调用方另行判断。
     */
    static boolean evidenceResumeStopState(CoverageReport report, boolean coverageRunning) {
        return !coverageRunning
                && report != null
                && report.getStatus() == CoverageStatus.NEEDS_REVIEW
                && report.getStopReason() == CoverageStopReason.ORACLE_INCONCLUSIVE
                && report.getOracleAssessment() != null
                && FailureCategory.fromCode(report.getOracleAssessment().getRootCauseCategory())
                        .getRepairStrategy() == RepairStrategy.EVIDENCE_REQUIRED;
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
        ensureCriticalDetailsVisible(report != null
                && (report.getStatus() == CoverageStatus.SUSPECTED_SUT_BUG
                || report.getStatus() == CoverageStatus.NEEDS_REVIEW
                || report.getStatus() == CoverageStatus.TEST_FAILED));
    }

    public void setDependencyImportPending() {
        if (actionsPanel != null) for (var component : actionsPanel.getComponents()) {
            if (component instanceof JButton button
                    && !Boolean.TRUE.equals(button.getClientProperty("unitmind.readOnlyDuringImport"))) button.setEnabled(false);
        }
        if (editorTextField != null) editorTextField.setEnabled(false);
        if (feedbackArea != null) feedbackArea.setEnabled(false);
    }

    public void updateClassJUnitReport(ClassJUnitRepairReport report) {
        if (detailsPanel == null) return;
        if (classJUnitComponent != null) detailsPanel.remove(classJUnitComponent);
        classJUnitComponent = classJUnitPanel(report);
        if (classJUnitComponent != null) detailsPanel.add(classJUnitComponent);
        detailsPanel.revalidate();
        detailsPanel.repaint();
        ensureCriticalDetailsVisible(report != null
                && (report.getStatus() == ClassJUnitStatus.SUSPECTED_SUT_BUG
                || report.getStatus() == ClassJUnitStatus.NEEDS_REVIEW
                || report.getStatus() == ClassJUnitStatus.TEST_FAILED));
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
        if (result == null || result.getDiagnostics().isEmpty() && result.getTestEnvironmentNote().isBlank()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (!result.getTestEnvironmentNote().isBlank()) text.append(result.getTestEnvironmentNote()).append('\n');
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
        area.setRows(Math.max(2, Math.min(4, count + 2)));
        return area;
    }

    private JComponent coveragePanel(CoverageReport report) {
        if (report == null || report.getStatus() == com.casc.unittestagent.model.CoverageStatus.NOT_RUN) {
            return null;
        }
        return new CoverageReportPanel(report);
    }

    private JComponent classJUnitPanel(ClassJUnitRepairReport report) {
        if (report == null || report.getStatus() == ClassJUnitStatus.NOT_RUN) return null;
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JTextArea area = new JTextArea("完整 JUnit 运行验证：" + report.getStatus()
                + "；JUnit@1 " + executionText(report.getInitialExecution())
                + "；最终 " + executionText(report.getFinalExecution())
                + "；运行修复 " + report.getRuntimeRepairRounds() + " 轮"
                + (report.getAttempts().size() > 1 ? "（按失败测试独立仲裁）" : "")
                + "；运行配置 " + report.getRuntimeConfigurationOrigin()
                + "；停止原因 " + report.getStopReason()
                + (report.getMessage().isBlank() ? "" : "\n" + report.getMessage()));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(3);
        panel.add(area);
        if (!report.getFinalExecution().getDiagnostics().isEmpty()) {
            StringBuilder failures = new StringBuilder();
            report.getFinalExecution().getDiagnostics().stream().limit(5).forEach(item -> {
                if (failures.length() > 0) failures.append('\n');
                failures.append(item.getTestId()).append("：").append(item.getCategory())
                        .append(item.getFailurePhase() == com.casc.unittestagent.model.RuntimeFailurePhase.UNKNOWN ? "" : " [" + item.getFailurePhase().getLabelCn() + "]")
                        .append(" ").append(item.getExceptionType()).append(" ").append(item.getMessage());
            });
            JTextArea failureArea = new JTextArea(failures.toString());
            failureArea.setEditable(false);
            failureArea.setLineWrap(true);
            failureArea.setWrapStyleWord(true);
            failureArea.setRows(4);
            panel.add(failureArea);
            panel.add(new com.casc.unittestagent.ui.component.RuntimeEvidencePanel(report.getFinalExecution()));
        }
        if (!report.getAttempts().isEmpty()) {
            panel.add(new JBLabel("分测试修复明细"));
            StringBuilder attempts = new StringBuilder();
            report.getAttempts().stream().limit(8).forEach(attempt -> {
                if (attempts.length() > 0) attempts.append('\n');
                attempts.append("第 ").append(attempt.getRound()).append(" 轮 ")
                        .append(attempt.getFocusedTestIds().isEmpty()
                                ? "[未定位测试]" : attempt.getFocusedTestIds())
                        .append("：").append(attempt.getDiagnosis().isBlank()
                                ? "未分类" : attempt.getDiagnosis())
                        .append(attempt.isAccepted() ? " · 已接受" : " · 已保留/拒绝");
                if (!attempt.isAccepted() && !attempt.getRejectionReason().isBlank()) {
                    attempts.append(" · ").append(attempt.getRejectionReason());
                }
            });
            JTextArea attemptArea = new JTextArea(attempts.toString());
            attemptArea.setEditable(false);
            attemptArea.setLineWrap(true);
            attemptArea.setWrapStyleWord(true);
            attemptArea.setRows(Math.min(8, Math.max(2, report.getAttempts().size())));
            panel.add(attemptArea);
        }
        if (report.getSourceBugAnalysis() != null
                && report.getSourceBugAnalysis().isAttempted()) {
            panel.add(new JBLabel("疑似源码缺陷分析（只读假设，不是已确认事实）"));
            JTextArea analysis = new JTextArea(sourceBugAnalysisText(report.getSourceBugAnalysis()));
            analysis.setEditable(false);
            analysis.setLineWrap(true);
            analysis.setWrapStyleWord(true);
            analysis.setRows(8);
            panel.add(analysis);
        }
        return panel;
    }

    private boolean isCriticalReport(CoverageReport coverageReport,
                                     ClassJUnitRepairReport classJUnitReport) {
        return coverageReport != null && (coverageReport.getStatus() == CoverageStatus.SUSPECTED_SUT_BUG
                || coverageReport.getStatus() == CoverageStatus.NEEDS_REVIEW
                || coverageReport.getStatus() == CoverageStatus.TEST_FAILED)
                || classJUnitReport != null && (classJUnitReport.getStatus() == ClassJUnitStatus.SUSPECTED_SUT_BUG
                || classJUnitReport.getStatus() == ClassJUnitStatus.NEEDS_REVIEW
                || classJUnitReport.getStatus() == ClassJUnitStatus.TEST_FAILED);
    }

    private void toggleDetailsSize() {
        if (validationCodeSplitter == null) return;
        detailsExpanded = !detailsExpanded;
        validationCodeSplitter.setProportion(detailsExpanded
                ? EXPANDED_DETAILS_PROPORTION : defaultDetailsProportion);
        detailsSizeButton.setText(detailsExpanded ? "恢复诊断比例" : "放大诊断区");
        revalidate();
        repaint();
    }

    private void ensureCriticalDetailsVisible(boolean critical) {
        if (!critical || validationCodeSplitter == null || detailsExpanded) return;
        defaultDetailsProportion = CRITICAL_DETAILS_PROPORTION;
        validationCodeSplitter.setProportion(CRITICAL_DETAILS_PROPORTION);
    }

    private String sourceBugAnalysisText(SourceBugAnalysis analysis) {
        if (!analysis.isAvailable()) {
            return analysis.getError().isBlank() ? "归因分析未返回结果。" : analysis.getError();
        }
        StringBuilder text = new StringBuilder("置信度：")
                .append(analysis.getConfidence()).append('\n')
                .append("结论：").append(analysis.getSummary());
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

    private void appendItems(StringBuilder text, String heading, java.util.List<String> items) {
        if (items == null || items.isEmpty()) return;
        text.append('\n').append(heading).append('：');
        for (String item : items) text.append("\n- ").append(item);
    }

    /** 让外层诊断视图始终收敛到 viewport 宽度，避免长路径或表格撑宽整个工具窗口。 */
    private static final class ViewportWidthPanel extends JPanel implements Scrollable {
        private ViewportWidthPanel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(JBUI.scale(16), visibleRect.height - JBUI.scale(16));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private String executionText(com.casc.unittestagent.model.TestExecutionSummary execution) {
        if (execution == null) return "未执行";
        return execution.getSucceeded() + "/" + execution.getFound()
                + " 通过，失败 " + execution.getFailed();
    }
}
