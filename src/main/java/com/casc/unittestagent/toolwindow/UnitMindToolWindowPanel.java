package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.StepStatus;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.ui.TestDataRangeConfigDialog;
import com.casc.unittestagent.ui.component.UnitMindHeaderPanel;
import com.casc.unittestagent.ui.component.UnitMindStepperPanel;
import com.casc.unittestagent.ui.step.ConstraintStepPanel;
import com.casc.unittestagent.ui.step.MockStrategyStepPanel;
import com.casc.unittestagent.ui.step.ScenarioStepPanel;
import com.casc.unittestagent.ui.step.SummaryStepPanel;
import com.casc.unittestagent.ui.step.TestCodeStepPanel;
import com.casc.unittestagent.ui.step.WriteFileStepPanel;
import com.casc.unittestagent.writer.TestFileWriter;
import com.casc.unittestagent.writer.TestWriteMode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.Arrays;

/**
 * UnitMind 右侧工具窗口主面板。
 * 主面板只负责上下文、状态、步骤导航、分析和写入，具体页面交给 ui.step 下的步骤面板。
 */
public class UnitMindToolWindowPanel extends JPanel {
    /** 步骤名称。 */
    private static final String[] STEPS = {
            "方法概要", "参数约束", "测试场景", "Mock策略", "测试代码", "写入测试文件"
    };

    /** 当前项目。 */
    private final Project project;
    /** 生成服务。 */
    private final UnitTestGenerationService generationService = new UnitTestGenerationService();
    /** 写入服务。 */
    private final TestFileWriter testFileWriter = new TestFileWriter();
    /** 顶部 Header。 */
    private final UnitMindHeaderPanel headerPanel;
    /** 步骤条。 */
    private final UnitMindStepperPanel stepperPanel;
    /** 步骤内容容器。 */
    private final JPanel contentPanel = new JPanel(new CardLayout());
    /** 步骤状态。 */
    private final StepStatus[] stepStatuses = new StepStatus[STEPS.length];
    /** 上一步按钮。 */
    private final JButton previousButton = new JButton("上一步");
    /** 下一步按钮。 */
    private final JButton nextButton = new JButton("下一步");
    /** 重新分析按钮。 */
    private final JButton analyzeButton = new JButton("重新分析");
    /** 写入按钮。 */
    private final JButton writeButton = new JButton("写入测试文件");

    /** 当前编辑器。 */
    private Editor currentEditor;
    /** 当前 Java 文件。 */
    private PsiFile currentPsiFile;
    /** 生成结果。 */
    private GenerationResult generationResult;
    /** 当前步骤索引。 */
    private int currentStepIndex;
    /** 测试代码生成状态。 */
    private GenerationStatus generationStatus = GenerationStatus.IDLE;
    /** 原始生成代码。 */
    private String generatedCode = "";
    /** 用户当前编辑后的测试代码。 */
    private String editedCode = "";
    /** 最近一次错误详情。 */
    private String lastError = "";
    /** 当前测试代码步骤面板。 */
    private TestCodeStepPanel testCodeStepPanel;

    /**
     * 创建 UnitMind 工具窗口主面板。
     *
     * @param project 当前项目
     */
    public UnitMindToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        Arrays.fill(stepStatuses, StepStatus.NOT_STARTED);
        setBorder(JBUI.Borders.empty());

        headerPanel = new UnitMindHeaderPanel(
                () -> new TestDataRangeConfigDialog(project).show(),
                this::analyzeCurrentMethod
        );
        stepperPanel = new UnitMindStepperPanel(STEPS, this::switchStep);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(stepperPanel, BorderLayout.NORTH);
        centerPanel.add(contentPanel, BorderLayout.CENTER);

        add(headerPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(bottomPanel(), BorderLayout.SOUTH);

        detectCurrentEditorContext();
        rebuildSteps();
    }

    /**
     * 设置当前编辑器上下文。
     * 右键菜单打开工具窗口时会调用该方法，使窗口进入方法概要步骤。
     *
     * @param editor 当前编辑器
     * @param psiFile 当前 PSI 文件
     */
    public void setCurrentContext(Editor editor, PsiFile psiFile) {
        this.currentEditor = editor;
        this.currentPsiFile = psiFile;
        this.generationResult = null;
        this.generatedCode = "";
        this.editedCode = "";
        this.lastError = "";
        this.generationStatus = GenerationStatus.IDLE;
        Arrays.fill(stepStatuses, StepStatus.NOT_STARTED);
        currentStepIndex = 0;
        rebuildSteps();
    }

    /**
     * 构建底部操作区。
     */
    private JPanel bottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        previousButton.addActionListener(event -> switchStep(currentStepIndex - 1));
        nextButton.addActionListener(event -> switchStep(currentStepIndex + 1));
        analyzeButton.addActionListener(event -> analyzeCurrentMethod());
        writeButton.addActionListener(event -> writeTestFile(null));
        panel.add(previousButton);
        panel.add(nextButton);
        panel.add(analyzeButton);
        panel.add(writeButton);
        return panel;
    }

    /**
     * 自动识别当前编辑器上下文。
     */
    private void detectCurrentEditorContext() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        PsiFile psiFile = null;
        if (editor != null) {
            psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        }
        currentEditor = editor;
        currentPsiFile = psiFile;
    }

    /**
     * 分析当前方法并更新所有步骤状态。
     * 同步分析前后都会设置状态，避免 UI 长期停留在“进行中”。
     */
    private void analyzeCurrentMethod() {
        if (currentEditor == null || !(currentPsiFile instanceof PsiJavaFile)) {
            detectCurrentEditorContext();
        }
        if (currentEditor == null || !(currentPsiFile instanceof PsiJavaFile)) {
            lastError = "请在编辑器中将光标放到一个 Java 方法内部，或选中一个方法后重新分析。";
            stepStatuses[0] = StepStatus.FAILED;
            generationStatus = GenerationStatus.FAILED;
            rebuildSteps();
            return;
        }

        stepStatuses[0] = StepStatus.RUNNING;
        stepStatuses[1] = StepStatus.RUNNING;
        generationStatus = GenerationStatus.RUNNING;
        rebuildSteps();

        try {
            generationResult = generationService.generate(project, currentPsiFile, currentEditor);
            GeneratedTestFile file = generationResult.getGeneratedTestFile();
            generatedCode = file == null ? "" : file.getContent();
            editedCode = generatedCode;
            boolean hasWarnings = generationResult != null && !generationResult.getWarnings().isEmpty();
            boolean failed = generationResult == null || generationResult.getMethodModel() == null || file == null
                    || generatedCode == null || generatedCode.trim().isEmpty();
            if (failed) {
                lastError = generatedCode == null || generatedCode.trim().isEmpty()
                        ? "测试代码生成失败：生成结果为空。请检查测试代码生成器是否返回了有效代码。"
                        : warningText();
                setSteps(StepStatus.FAILED);
                generationStatus = GenerationStatus.FAILED;
                currentStepIndex = 4;
            } else {
                stepStatuses[0] = StepStatus.SUCCESS;
                stepStatuses[1] = hasWarnings ? StepStatus.WARNING : StepStatus.SUCCESS;
                stepStatuses[2] = hasWarnings ? StepStatus.WARNING : StepStatus.SUCCESS;
                stepStatuses[3] = StepStatus.SUCCESS;
                stepStatuses[4] = StepStatus.SUCCESS;
                stepStatuses[5] = StepStatus.NOT_STARTED;
                generationStatus = GenerationStatus.SUCCESS;
                lastError = "";
                currentStepIndex = 0;
            }
        } catch (Exception ex) {
            lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            setSteps(StepStatus.FAILED);
            generationStatus = GenerationStatus.FAILED;
            currentStepIndex = 0;
        }
        rebuildSteps();
    }

    /**
     * 统一设置步骤状态。
     */
    private void setSteps(StepStatus status) {
        Arrays.fill(stepStatuses, status);
    }

    /**
     * 重建步骤内容。
     */
    private void rebuildSteps() {
        if (testCodeStepPanel != null && testCodeStepPanel.hasEditor()) {
            editedCode = testCodeStepPanel.getCode();
        }
        updateHeader();
        stepperPanel.update(currentStepIndex, stepStatuses);
        contentPanel.removeAll();
        contentPanel.add(new SummaryStepPanel(generationResult, sourcePath()), STEPS[0]);
        contentPanel.add(new ConstraintStepPanel(generationResult), STEPS[1]);
        contentPanel.add(new ScenarioStepPanel(generationResult, this::rebuildSteps), STEPS[2]);
        contentPanel.add(new MockStrategyStepPanel(generationResult), STEPS[3]);
        testCodeStepPanel = new TestCodeStepPanel(project, generationStatus, currentCode(), lastError,
                () -> Messages.showInfoMessage(project, "格式化代码功能将在后续版本接入 IDEA CodeStyle。", "UnitMind"));
        contentPanel.add(testCodeStepPanel, STEPS[4]);
        contentPanel.add(new WriteFileStepPanel(generationResult, targetFile(), this::writeTestFile), STEPS[5]);
        showCurrentStep();
        revalidate();
        repaint();
    }

    /**
     * 更新 Header 上下文。
     */
    private void updateHeader() {
        MethodModel method = generationResult == null ? null : generationResult.getMethodModel();
        String methodText = method == null ? "未选择" : method.getClassName() + "#" + method.getMethodName() + "(" + parameterText(method) + ")";
        headerPanel.updateContext(methodText, sourcePath());
    }

    /**
     * 切换步骤。
     *
     * @param index 目标步骤索引
     */
    private void switchStep(int index) {
        if (index < 0 || index >= STEPS.length) {
            return;
        }
        currentStepIndex = index;
        showCurrentStep();
    }

    /**
     * 显示当前步骤并刷新按钮状态。
     */
    private void showCurrentStep() {
        ((CardLayout) contentPanel.getLayout()).show(contentPanel, STEPS[currentStepIndex]);
        stepperPanel.update(currentStepIndex, stepStatuses);
        previousButton.setEnabled(currentStepIndex > 0);
        nextButton.setEnabled(currentStepIndex < STEPS.length - 1);
        writeButton.setEnabled(generationResult != null
                && generationResult.getGeneratedTestFile() != null
                && generationStatus == GenerationStatus.SUCCESS
                && currentCode() != null
                && !currentCode().trim().isEmpty());
    }

    /**
     * 重新选择方法。
     */
    private void reselectMethod() {
        detectCurrentEditorContext();
        setCurrentContext(currentEditor, currentPsiFile);
    }

    /**
     * 写入测试文件。
     */
    private void writeTestFile(TestWriteMode mode) {
        if (mode == TestWriteMode.CANCEL) {
            return;
        }
        if (generationResult == null || generationResult.getGeneratedTestFile() == null) {
            Messages.showWarningDialog(project, "请先生成测试代码。", "UnitMind");
            return;
        }
        String code = currentCode();
        if (code == null || code.trim().isEmpty()) {
            Messages.showWarningDialog(project, "测试代码为空，不能写入测试文件。", "UnitMind");
            return;
        }
        try {
            testFileWriter.write(project, currentPsiFile, generationResult.getGeneratedTestFile(), code, mode,
                    generationResult.getMethodModel().getMethodName());
            stepStatuses[5] = StepStatus.SUCCESS;
            Messages.showInfoMessage(project, "测试文件写入完成。", "UnitMind");
            rebuildSteps();
        } catch (Exception ex) {
            stepStatuses[5] = StepStatus.FAILED;
            lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            Messages.showErrorDialog(project, lastError, "UnitMind 写入失败");
            rebuildSteps();
        }
    }

    /**
     * 获取当前编辑后的测试代码。
     */
    private String currentCode() {
        if (testCodeStepPanel != null && testCodeStepPanel.hasEditor()) {
            return testCodeStepPanel.getCode();
        }
        if (editedCode != null && !editedCode.isBlank()) {
            return editedCode;
        }
        return generatedCode == null ? "" : generatedCode;
    }

    /**
     * 获取源码路径。
     */
    private String sourcePath() {
        return currentPsiFile == null || currentPsiFile.getVirtualFile() == null ? "" : currentPsiFile.getVirtualFile().getPath();
    }

    /**
     * 获取目标测试文件。
     */
    private File targetFile() {
        if (currentPsiFile == null || generationResult == null || generationResult.getGeneratedTestFile() == null) {
            return null;
        }
        return testFileWriter.resolveTargetFile(currentPsiFile, generationResult.getGeneratedTestFile());
    }

    /**
     * 汇总警告信息。
     */
    private String warningText() {
        if (generationResult == null || generationResult.getWarnings().isEmpty()) {
            return lastError == null ? "" : lastError;
        }
        return String.join("\n", generationResult.getWarnings());
    }

    /**
     * 格式化参数。
     */
    private String parameterText(MethodModel method) {
        StringBuilder builder = new StringBuilder();
        for (ParameterModel parameter : method.getParameters()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(parameter.getType()).append(" ").append(parameter.getName());
        }
        return builder.toString();
    }
}
