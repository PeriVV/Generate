package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.StepStatus;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.ui.UnitMindSettingsDialog;
import com.casc.unittestagent.ui.component.UnitMindHeaderPanel;
import com.casc.unittestagent.ui.component.UnitMindStepperPanel;
import com.casc.unittestagent.ui.step.MockStrategyStepPanel;
import com.casc.unittestagent.ui.step.ScenarioStepPanel;
import com.casc.unittestagent.ui.step.StaticAnalysisStepPanel;
import com.casc.unittestagent.ui.step.TestCodeStepPanel;
import com.casc.unittestagent.ui.step.WriteFileStepPanel;
import com.casc.unittestagent.writer.TestFileWriter;
import com.casc.unittestagent.writer.TestWriteMode;
import com.casc.unittestagent.util.CodeFingerprint;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

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
            "静态分析结果", "测试场景", "Mock策略", "测试代码", "写入测试文件"
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
    /** 分析任务序号，用于忽略过期后台任务结果。 */
    private int analysisSequence;
    /** 是否正在调用大模型生成候选 Mock 策略。 */
    private boolean mockStrategyLlmRunning;

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
                () -> new UnitMindSettingsDialog(project).show(),
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
        analysisSequence++;
        this.currentEditor = editor;
        this.currentPsiFile = psiFile;
        this.generationResult = null;
        this.generatedCode = "";
        this.editedCode = "";
        this.lastError = "";
        this.generationStatus = GenerationStatus.IDLE;
        this.mockStrategyLlmRunning = false;
        Arrays.fill(stepStatuses, StepStatus.NOT_STARTED);
        currentStepIndex = 0;
        rebuildSteps();
    }

    /**
     * 设置当前编辑器上下文并立即开始分析。
     * 右键菜单入口使用该方法，避免用户打开 UnitMind 后还需要再点击“重新分析”。
     *
     * @param editor 当前编辑器
     * @param psiFile 当前 PSI 文件
     */
    public void setCurrentContextAndAnalyze(Editor editor, PsiFile psiFile) {
        setCurrentContext(editor, psiFile);
        analyzeCurrentMethod();
    }

    /**
     * 构建底部操作区。
     */
    private JPanel bottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        previousButton.addActionListener(event -> switchStep(currentStepIndex - 1));
        nextButton.addActionListener(event -> switchStep(currentStepIndex + 1));
        analyzeButton.addActionListener(event -> analyzeCurrentMethod());
        writeButton.addActionListener(event -> writeTestFile(null, false));
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
        if (generationStatus == GenerationStatus.RUNNING) {
            return;
        }
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
        stepStatuses[1] = StepStatus.NOT_STARTED;
        stepStatuses[2] = StepStatus.NOT_STARTED;
        stepStatuses[3] = StepStatus.NOT_STARTED;
        stepStatuses[4] = StepStatus.NOT_STARTED;
        generationResult = null;
        generatedCode = "";
        editedCode = "";
        lastError = "";
        mockStrategyLlmRunning = false;
        generationStatus = GenerationStatus.RUNNING;
        rebuildSteps();

        int requestId = ++analysisSequence;
        Editor editor = currentEditor;
        PsiFile psiFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在分析方法", false) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = generationService.analyzeMethod(project, psiFile, editor);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (requestId != analysisSequence) {
                    return;
                }
                if (error != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        lastError = error;
                        setSteps(StepStatus.FAILED);
                        generationStatus = GenerationStatus.FAILED;
                        currentStepIndex = 0;
                        rebuildSteps();
                    });
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> applyAnalysisResult(result));
            }
        }.queue();
    }

    /**
     * 应用静态分析结果，只更新方法概要和参数约束步骤。
     */
    private void applyAnalysisResult(GenerationResult result) {
        generationResult = result;
        generatedCode = "";
        editedCode = "";
        boolean failed = generationResult == null || generationResult.getMethodModel() == null;
        if (failed) {
            lastError = warningText();
            stepStatuses[0] = StepStatus.FAILED;
            stepStatuses[1] = StepStatus.FAILED;
            stepStatuses[2] = StepStatus.NOT_STARTED;
            stepStatuses[3] = StepStatus.NOT_STARTED;
            stepStatuses[4] = StepStatus.NOT_STARTED;
            generationStatus = GenerationStatus.FAILED;
        } else {
            stepStatuses[0] = StepStatus.SUCCESS;
            stepStatuses[1] = StepStatus.NOT_STARTED;
            stepStatuses[2] = StepStatus.NOT_STARTED;
            stepStatuses[3] = StepStatus.NOT_STARTED;
            stepStatuses[4] = StepStatus.NOT_STARTED;
            generationStatus = GenerationStatus.IDLE;
            lastError = "";
            currentStepIndex = 0;
        }
        rebuildSteps();
    }

    /**
     * 按需规划测试场景，不调用大模型。
     */
    private void planScenariosIfNeeded() {
        if (generationResult == null || generationResult.getMethodModel() == null) {
            return;
        }
        if (!generationResult.getScenarios().isEmpty()) {
            return;
        }
        generationResult = generationService.planScenarios(generationResult);
        stepStatuses[1] = StepStatus.SUCCESS;
        rebuildSteps();
    }

    /**
     * 按需规划 Mock 策略，不调用大模型。
     */
    private void planMockStrategyIfNeeded() {
        if (generationResult == null || generationResult.getMethodModel() == null) {
            return;
        }
        planScenariosIfNeeded();
        if (generationResult.getMockStrategy() != null) {
            return;
        }
        generationResult = generationService.planMockStrategy(generationResult);
        stepStatuses[2] = StepStatus.SUCCESS;
        rebuildSteps();
    }

    /**
     * 按需启动测试代码生成。只有该阶段允许调用大模型。
     */
    private void generateTestCodeIfNeeded() {
        if (generationResult == null || generationResult.getMethodModel() == null) {
            return;
        }
        planMockStrategyIfNeeded();
        if (generationResult.getGeneratedTestFile() != null || generationStatus == GenerationStatus.RUNNING) {
            return;
        }

        stepStatuses[3] = StepStatus.RUNNING;
        generationStatus = GenerationStatus.RUNNING;
        rebuildSteps();

        int requestId = analysisSequence;
        GenerationResult currentResult = generationResult;
        new Task.Backgroundable(project, "UnitMind 正在生成测试代码", false) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = generationService.generateTestCode(project, currentPsiFile, currentResult);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (requestId != analysisSequence) {
                    return;
                }
                if (error != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        lastError = error;
                        stepStatuses[3] = StepStatus.FAILED;
                        generationStatus = GenerationStatus.FAILED;
                        rebuildSteps();
                    });
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> applyGenerationResult(result));
            }
        }.queue();
    }

    /**
     * 应用后台生成结果并刷新步骤状态。
     */
    private void applyGenerationResult(GenerationResult result) {
        generationResult = result;
        GeneratedTestFile file = generationResult == null ? null : generationResult.getGeneratedTestFile();
        generatedCode = file == null ? "" : file.getContent();
        editedCode = generatedCode;
        boolean failed = generationResult == null || generationResult.getMethodModel() == null || file == null
                || generatedCode == null || generatedCode.trim().isEmpty();
        if (failed) {
            lastError = generatedCode == null || generatedCode.trim().isEmpty()
                    ? "测试代码生成失败：生成结果为空。请检查测试代码生成器是否返回了有效代码。"
                    : warningText();
            setSteps(StepStatus.FAILED);
            generationStatus = GenerationStatus.FAILED;
            currentStepIndex = 3;
        } else {
            stepStatuses[0] = StepStatus.SUCCESS;
            stepStatuses[1] = StepStatus.SUCCESS;
            stepStatuses[2] = StepStatus.SUCCESS;
            ValidationResult validation = generationResult.getValidationResult();
            stepStatuses[3] = validation != null && validation.getStatus() == ValidationStatus.PASSED
                    ? StepStatus.SUCCESS : StepStatus.WARNING;
            stepStatuses[4] = StepStatus.NOT_STARTED;
            generationStatus = GenerationStatus.SUCCESS;
            lastError = "";
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
        contentPanel.add(new StaticAnalysisStepPanel(generationResult, sourcePath()), STEPS[0]);
        contentPanel.add(new ScenarioStepPanel(generationResult, this::rebuildSteps), STEPS[1]);
        contentPanel.add(new MockStrategyStepPanel(
                generationResult,
                mockStrategyLlmRunning,
                this::generateLlmMockStrategy,
                this::useStaticMockStrategy,
                this::useLlmMockStrategy), STEPS[2]);
        testCodeStepPanel = new TestCodeStepPanel(
                project,
                generationStatus,
                generatedCode,
                currentCode(),
                lastError,
                generationNote(),
                generationResult == null ? null : generationResult.getValidationResult(),
                this::regenerateTestCode,
                this::validateCurrentCode,
                this::markValidationStale);
        contentPanel.add(testCodeStepPanel, STEPS[3]);
        contentPanel.add(new WriteFileStepPanel(generationResult, targetFile(), this::writeTestFile), STEPS[4]);
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
        if (index == 1) {
            planScenariosIfNeeded();
        } else if (index == 2) {
            planMockStrategyIfNeeded();
        } else if (index >= 3) {
            generateTestCodeIfNeeded();
        }
    }

    /**
     * 显示当前步骤并刷新按钮状态。
     */
    private void showCurrentStep() {
        ((CardLayout) contentPanel.getLayout()).show(contentPanel, STEPS[currentStepIndex]);
        stepperPanel.update(currentStepIndex, stepStatuses);
        previousButton.setEnabled(currentStepIndex > 0);
        nextButton.setEnabled(currentStepIndex < STEPS.length - 1);
        analyzeButton.setEnabled(generationStatus != GenerationStatus.RUNNING);
        writeButton.setEnabled(generationResult != null
                && generationResult.getGeneratedTestFile() != null
                && generationStatus == GenerationStatus.SUCCESS
                && currentCode() != null
                && !currentCode().trim().isEmpty());
    }

    /**
     * 手动调用大模型生成候选 Mock 策略，不覆盖静态策略。
     */
    private void generateLlmMockStrategy() {
        if (generationResult == null || generationResult.getMethodModel() == null || mockStrategyLlmRunning) {
            return;
        }
        planMockStrategyIfNeeded();
        mockStrategyLlmRunning = true;
        rebuildSteps();
        int requestId = analysisSequence;
        GenerationResult currentResult = generationResult;
        new Task.Backgroundable(project, "UnitMind 正在生成大模型 Mock 策略", false) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = generationService.generateLlmMockStrategy(currentResult);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (requestId != analysisSequence) {
                    return;
                }
                mockStrategyLlmRunning = false;
                if (error != null) {
                    lastError = error;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(project, error, "UnitMind 大模型 Mock 策略生成失败");
                        rebuildSteps();
                    });
                    return;
                }
                generationResult = result;
                currentStepIndex = 2;
                ApplicationManager.getApplication().invokeLater(() -> rebuildSteps());
            }
        }.queue();
    }

    /**
     * 选择静态 Mock 策略用于后续测试代码生成。
     */
    private void useStaticMockStrategy() {
        if (generationResult == null) {
            return;
        }
        generationResult.setUseLlmMockStrategy(false);
        resetGeneratedTestCode();
        rebuildSteps();
    }

    /**
     * 选择大模型候选 Mock 策略用于后续测试代码生成。
     */
    private void useLlmMockStrategy() {
        if (generationResult == null || generationResult.getLlmMockStrategy() == null) {
            return;
        }
        generationResult.setUseLlmMockStrategy(true);
        resetGeneratedTestCode();
        rebuildSteps();
    }

    /**
     * Mock 策略选择变化后清空旧测试代码，避免策略和代码不一致。
     */
    private void resetGeneratedTestCode() {
        generationResult.setGeneratedTestFile(null);
        generationStatus = GenerationStatus.IDLE;
        generatedCode = "";
        editedCode = "";
        stepStatuses[3] = StepStatus.NOT_STARTED;
        stepStatuses[4] = StepStatus.NOT_STARTED;
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
    private void writeTestFile(TestWriteMode mode, boolean force) {
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
        TestFileWriter.PreparedWrite preparedWrite;
        try {
            preparedWrite = testFileWriter.prepare(
                    project,
                    currentPsiFile,
                    generationResult.getGeneratedTestFile(),
                    code,
                    mode,
                    generationResult.getMethodModel().getMethodName());
        } catch (Exception ex) {
            stepStatuses[4] = StepStatus.FAILED;
            lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            Messages.showErrorDialog(project, lastError, "UnitMind 写入失败");
            rebuildSteps();
            return;
        }
        if (preparedWrite.isCancelled()) {
            return;
        }

        generationResult.setValidationResult(ValidationResult.status(ValidationStatus.RUNNING));
        stepStatuses[4] = StepStatus.RUNNING;
        rebuildSteps();
        GenerationResult currentResult = generationResult;
        new Task.Backgroundable(project, "UnitMind 正在校验最终测试文件", false) {
            private ValidationResult validation;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    validation = generationService.validateCode(
                            project,
                            currentPsiFile,
                            currentResult.getGeneratedTestFile(),
                            preparedWrite.getContent());
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (error != null) {
                    stepStatuses[4] = StepStatus.FAILED;
                    lastError = error;
                    Messages.showErrorDialog(project, error, "UnitMind 最终文件校验失败");
                    rebuildSteps();
                    return;
                }
                currentResult.setValidationResult(validation);
                boolean passed = validation.getStatus() == ValidationStatus.PASSED
                        && validation.isPassedFor(CodeFingerprint.sha256(preparedWrite.getContent()));
                if (!passed && !force) {
                    stepStatuses[4] = StepStatus.WARNING;
                    lastError = validationSummary(validation);
                    Messages.showErrorDialog(project,
                            lastError + "\n\n正常写入已阻止。如确需保留草稿，请使用“强制写入草稿”。",
                            "UnitMind 编译未通过");
                    rebuildSteps();
                    return;
                }
                if (!passed) {
                    int answer = Messages.showYesNoDialog(
                            project,
                            validationSummary(validation) + "\n\n该文件可能无法编译，确定仍要写入草稿吗？",
                            "强制写入未验证草稿",
                            "仍然写入草稿",
                            "取消",
                            Messages.getWarningIcon());
                    if (answer != Messages.YES) {
                        stepStatuses[4] = StepStatus.WARNING;
                        rebuildSteps();
                        return;
                    }
                }
                try {
                    testFileWriter.commit(project, preparedWrite, validation, !passed);
                    stepStatuses[4] = StepStatus.SUCCESS;
                    lastError = "";
                    Messages.showInfoMessage(project,
                            passed ? "测试文件写入完成。" : "未验证草稿已强制写入。",
                            "UnitMind");
                } catch (Exception ex) {
                    stepStatuses[4] = StepStatus.FAILED;
                    lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    Messages.showErrorDialog(project, lastError, "UnitMind 写入失败");
                }
                rebuildSteps();
            }
        }.queue();
    }

    private void markValidationStale(String code) {
        editedCode = code == null ? "" : code;
        if (generationResult == null) {
            return;
        }
        ValidationResult stale = ValidationResult.status(ValidationStatus.STALE);
        stale.setCodeHash(CodeFingerprint.sha256(editedCode));
        generationResult.setValidationResult(stale);
        stepStatuses[3] = StepStatus.WARNING;
    }

    private void validateCurrentCode() {
        if (generationResult == null || generationResult.getGeneratedTestFile() == null) {
            return;
        }
        editedCode = currentCode();
        if (editedCode == null || editedCode.isBlank()) {
            Messages.showWarningDialog(project, "测试代码为空，无法校验。", "UnitMind");
            return;
        }
        String validatingCode = editedCode;
        String validatingHash = CodeFingerprint.sha256(validatingCode);
        generationResult.setValidationResult(ValidationResult.status(ValidationStatus.RUNNING));
        stepStatuses[3] = StepStatus.RUNNING;
        rebuildSteps();
        GenerationResult currentResult = generationResult;
        new Task.Backgroundable(project, "UnitMind 正在重新校验测试代码", false) {
            private ValidationResult validation;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                validation = generationService.validateCode(
                        project,
                        currentPsiFile,
                        currentResult.getGeneratedTestFile(),
                        validatingCode);
            }

            @Override
            public void onSuccess() {
                String latestCode = currentCode();
                if (!validatingHash.equals(CodeFingerprint.sha256(latestCode))) {
                    markValidationStale(latestCode);
                } else {
                    currentResult.setValidationResult(validation);
                    stepStatuses[3] = validation.getStatus() == ValidationStatus.PASSED
                            ? StepStatus.SUCCESS : StepStatus.WARNING;
                }
                rebuildSteps();
            }
        }.queue();
    }

    private String validationSummary(ValidationResult validation) {
        if (validation == null) {
            return "未获得编译校验结果。";
        }
        StringBuilder summary = new StringBuilder();
        summary.append(validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                ? "编译环境校验失败。" : "测试代码编译未通过。");
        int count = Math.min(5, validation.getDiagnostics().size());
        for (int index = 0; index < count; index++) {
            com.casc.unittestagent.model.CompileDiagnostic diagnostic = validation.getDiagnostics().get(index);
            summary.append("\n- ");
            if (diagnostic.getLine() > 0) {
                summary.append("第 ").append(diagnostic.getLine()).append(" 行：");
            }
            summary.append(diagnostic.getCode()).append(" - ").append(diagnostic.getMessage());
        }
        return summary.toString();
    }

    /**
     * 使用当前代码和用户补充要求再次请求大模型。
     */
    private void regenerateTestCode(String feedback) {
        if (generationResult == null || generationResult.getMethodModel() == null) {
            Messages.showWarningDialog(project, "请先完成方法分析。", "UnitMind");
            return;
        }
        editedCode = currentCode();
        generationStatus = GenerationStatus.RUNNING;
        stepStatuses[3] = StepStatus.RUNNING;
        currentStepIndex = 3;
        rebuildSteps();
        String currentCode = editedCode;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                GenerationResult regenerated = generationService.regenerateWithLlmFeedback(
                        project, currentPsiFile, generationResult, currentCode, feedback);
                ApplicationManager.getApplication().invokeLater(() -> {
                    generationResult = regenerated;
                    GeneratedTestFile file = generationResult.getGeneratedTestFile();
                    generatedCode = file == null ? "" : file.getContent();
                    editedCode = generatedCode;
                    generationStatus = GenerationStatus.SUCCESS;
                    stepStatuses[2] = StepStatus.SUCCESS;
                    stepStatuses[3] = generationResult.getValidationResult() != null
                            && generationResult.getValidationResult().getStatus() == ValidationStatus.PASSED
                            ? StepStatus.SUCCESS : StepStatus.WARNING;
                    lastError = "";
                    rebuildSteps();
                });
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                ApplicationManager.getApplication().invokeLater(() -> {
                    generationStatus = GenerationStatus.SUCCESS;
                    stepStatuses[3] = StepStatus.WARNING;
                    lastError = message;
                    Messages.showErrorDialog(project, message, "UnitMind 大模型重新生成失败");
                    rebuildSteps();
                });
            }
        });
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
        return ReadAction.compute(() -> currentPsiFile == null || currentPsiFile.getVirtualFile() == null
                ? "" : currentPsiFile.getVirtualFile().getPath());
    }

    /**
     * 获取目标测试文件。
     */
    private File targetFile() {
        return ReadAction.compute(() -> {
            if (currentPsiFile == null || generationResult == null || generationResult.getGeneratedTestFile() == null) {
                return null;
            }
            return testFileWriter.resolveTargetFile(currentPsiFile, generationResult.getGeneratedTestFile());
        });
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
     * 测试代码来源提示。
     */
    private String generationNote() {
        if (generationResult == null) {
            return "";
        }
        if (generationResult.isGeneratedByLlm()) {
            return "当前测试代码由大模型根据静态分析结果生成，请检查后再写入测试文件。";
        }
        if (generationResult.isRuleBasedFallback()) {
            if (generationResult.getLlmMessage() != null && generationResult.getLlmMessage().startsWith("大模型生成失败")) {
                return generationResult.getLlmMessage() + " 当前显示规则兜底测试代码。";
            }
            return "当前测试代码为规则兜底生成，大模型生成失败或未启用。";
        }
        return generationResult.getLlmMessage() == null ? "" : generationResult.getLlmMessage();
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
