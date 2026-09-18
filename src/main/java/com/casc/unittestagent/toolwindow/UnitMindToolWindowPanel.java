package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.ClassJUnitStopReason;
import com.casc.unittestagent.model.GenerationScope;
import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.StepStatus;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.service.ClassUnitTestGenerationService;
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
import com.casc.unittestagent.writer.GenerationTarget;
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
    /** 整类测试生成编排服务。 */
    private final ClassUnitTestGenerationService classGenerationService = new ClassUnitTestGenerationService();
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
    /** 是否正在隔离执行覆盖率闭环。 */
    private boolean coverageRunning;
    /** 当前可取消的覆盖率后台进度。 */
    private ProgressIndicator coverageIndicator;
    /** 覆盖率任务序号，用于忽略切换方法后的过期回调。 */
    private int coverageSequence;
    /** 最近一次通过入口选择的生成粒度。 */
    private GenerationScope requestedScope = GenerationScope.METHOD;

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
                this::reanalyzeCurrentTarget
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
        coverageSequence++;
        if (coverageIndicator != null) coverageIndicator.cancel();
        coverageIndicator = null;
        coverageRunning = false;
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
        requestedScope = GenerationScope.METHOD;
        setCurrentContext(editor, psiFile);
        analyzeCurrentMethod();
    }

    /** 设置当前编辑器上下文并立即分析顶层类。 */
    public void setCurrentClassContextAndAnalyze(Editor editor, PsiFile psiFile) {
        requestedScope = GenerationScope.CLASS;
        setCurrentContext(editor, psiFile);
        analyzeCurrentClass();
    }

    private void reanalyzeCurrentTarget() {
        if (requestedScope == GenerationScope.CLASS) analyzeCurrentClass();
        else analyzeCurrentMethod();
    }

    private void analyzeCurrentClass() {
        if (generationStatus == GenerationStatus.RUNNING) return;
        if (currentEditor == null || !(currentPsiFile instanceof PsiJavaFile)) detectCurrentEditorContext();
        if (currentEditor == null || !(currentPsiFile instanceof PsiJavaFile)) {
            lastError = "请在编辑器中打开一个顶层 Java 具体类。";
            stepStatuses[0] = StepStatus.FAILED;
            generationStatus = GenerationStatus.FAILED;
            rebuildSteps();
            return;
        }
        stepStatuses[0] = StepStatus.RUNNING;
        for (int index = 1; index < stepStatuses.length; index++) stepStatuses[index] = StepStatus.NOT_STARTED;
        generationResult = null;
        generatedCode = "";
        editedCode = "";
        lastError = "";
        generationStatus = GenerationStatus.RUNNING;
        rebuildSteps();
        int requestId = ++analysisSequence;
        Editor editor = currentEditor;
        PsiFile psiFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在分析当前类", false) {
            private ClassGenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = classGenerationService.analyzeClass(project, psiFile, editor);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (requestId != analysisSequence) return;
                if (error != null) {
                    lastError = error;
                    stepStatuses[0] = StepStatus.FAILED;
                    generationStatus = GenerationStatus.FAILED;
                    rebuildSteps();
                    return;
                }
                applyAnalysisResult(result);
            }
        }.queue();
    }

    /**
     * 构建底部操作区。
     */
    private JPanel bottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        previousButton.addActionListener(event -> switchStep(currentStepIndex - 1));
        nextButton.addActionListener(event -> switchStep(currentStepIndex + 1));
        analyzeButton.addActionListener(event -> reanalyzeCurrentTarget());
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
        if (generationResult instanceof ClassGenerationResult) {
            generationResult = classGenerationService.planScenarios((ClassGenerationResult) generationResult);
            stepStatuses[1] = StepStatus.SUCCESS;
            rebuildSteps();
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
        if (generationResult instanceof ClassGenerationResult) {
            generationResult = classGenerationService.planMockStrategies((ClassGenerationResult) generationResult);
            stepStatuses[2] = StepStatus.SUCCESS;
            rebuildSteps();
            return;
        }
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
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在生成测试代码", currentResult instanceof ClassGenerationResult) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = currentResult instanceof ClassGenerationResult
                            ? classGenerationService.generateClassTest(project, requestFile,
                            (ClassGenerationResult) currentResult, indicator)
                            : generationService.generateTestCode(project, requestFile, currentResult);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (!isCurrentRequest(requestId, requestFile, currentResult)) {
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
                generationResult == null ? null : generationResult.getCoverageReport(),
                generationResult instanceof ClassGenerationResult
                        ? ((ClassGenerationResult) generationResult).getClassJUnitRepairReport() : null,
                coverageRunning,
                !(generationResult instanceof ClassGenerationResult),
                true,
                generationResult instanceof ClassGenerationResult,
                this::regenerateTestCode,
                this::validateCurrentCode,
                this::markValidationStale,
                this::runCoverageLoop,
                this::stopCoverageLoop);
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
        if (generationResult instanceof ClassGenerationResult) {
            ClassGenerationResult classResult = (ClassGenerationResult) generationResult;
            String text = classResult.getClassModel() == null ? "未选择类"
                    : classResult.getClassModel().getQualifiedName() + " ("
                    + classResult.getClassModel().getMethods().size() + " 个待测方法)";
            headerPanel.updateContext(text, sourcePath());
            return;
        }
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
                && (!(generationResult instanceof ClassGenerationResult)
                || ((ClassGenerationResult) generationResult).isComplete())
                && currentCode() != null
                && !currentCode().trim().isEmpty());
    }

    /**
     * 手动调用大模型生成候选 Mock 策略，不覆盖静态策略。
     */
    private void generateLlmMockStrategy() {
        if (generationResult instanceof ClassGenerationResult) {
            Messages.showInfoMessage(project, "整类模式会在生成阶段按方法调用大模型，无需单独生成一份类级 Mock 候选。", "UnitMind");
            return;
        }
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
        coverageSequence++;
        if (coverageRunning && coverageIndicator != null) coverageIndicator.cancel();
        coverageRunning = false;
        generationResult.setGeneratedTestFile(null);
        generationStatus = GenerationStatus.IDLE;
        generatedCode = "";
        editedCode = "";
        stepStatuses[3] = StepStatus.NOT_STARTED;
        stepStatuses[4] = StepStatus.NOT_STARTED;
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
        if (generationResult instanceof ClassGenerationResult
                && !((ClassGenerationResult) generationResult).isComplete() && !force) {
            Messages.showWarningDialog(project, "整类生成尚未完成或已取消，普通写入已阻止。如确需保留当前部分结果，请使用“强制写入草稿”。", "UnitMind");
            return;
        }
        String code = currentCode();
        if (code == null || code.trim().isEmpty()) {
            Messages.showWarningDialog(project, "测试代码为空，不能写入测试文件。", "UnitMind");
            return;
        }
        TestFileWriter.PreparedWrite preparedWrite;
        try {
            MethodModel method = generationResult.getMethodModel();
            String methodTargetId = method == null ? "未知方法"
                    : firstNonBlank(method.getMethodId(), method.getSignature(), method.getMethodName());
            GenerationTarget target = generationResult instanceof ClassGenerationResult
                    ? GenerationTarget.classTarget(((ClassGenerationResult) generationResult).getClassModel().getQualifiedName())
                    : GenerationTarget.method(methodTargetId);
            preparedWrite = testFileWriter.prepare(project, currentPsiFile,
                    generationResult.getGeneratedTestFile(), code, mode, target);
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
        int requestId = analysisSequence;
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在校验最终测试文件", false) {
            private ValidationResult validation;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    validation = generationService.validateCode(
                            project,
                            requestFile,
                            currentResult.getGeneratedTestFile(),
                            preparedWrite.getContent());
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (!isCurrentRequest(requestId, requestFile, currentResult)) return;
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
                boolean executionConfirmed = !passed || (currentResult instanceof ClassGenerationResult classResult
                        ? confirmClassJUnitWrite(classResult.getClassJUnitRepairReport(), force)
                        : confirmCoverageWrite(currentResult.getCoverageReport(), force));
                if (passed && !executionConfirmed) {
                    stepStatuses[4] = StepStatus.WARNING;
                    rebuildSteps();
                    return;
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

    private boolean confirmCoverageWrite(CoverageReport report, boolean force) {
        if (report == null || report.getStatus() == CoverageStatus.NOT_RUN || report.getStatus() == CoverageStatus.PASSED) {
            return true;
        }
        boolean protectedFailure = report.getStatus() == CoverageStatus.TEST_FAILED
                || report.getStatus() == CoverageStatus.SUSPECTED_SUT_BUG
                || report.getStatus() == CoverageStatus.NEEDS_REVIEW;
        if (protectedFailure && !force) {
            Messages.showErrorDialog(project,
                    (report.getStatus() == CoverageStatus.SUSPECTED_SUT_BUG
                    ? "测试可能发现了生产代码缺陷，插件已保留原断言并阻止普通写入。\n\n"
                    : "候选测试执行未通过或 Oracle 待确认，正常写入已阻止。\n\n")
                            + report.getMessage(),
                    "UnitMind 需要人工确认");
            return false;
        }
        if (force && protectedFailure) {
            return Messages.showYesNoDialog(project,
                    "原失败测试将按原样写入，用于复现疑似缺陷或等待 Oracle 确认。\n\n"
                            + report.getMessage(),
                    "写入失败回归测试",
                    "写入原失败测试",
                    "取消",
                    Messages.getWarningIcon()) == Messages.YES;
        }
        if (report.getStatus() != CoverageStatus.BELOW_THRESHOLD
                && report.getStatus() != CoverageStatus.STALE
                && report.getStatus() != CoverageStatus.ENVIRONMENT_ERROR) {
            return true;
        }
        String metrics = report.getGenerated() != null && report.getGenerated().isAvailable()
                ? String.format(java.util.Locale.ROOT, "行 %.1f%%，分支 %s",
                report.getGenerated().getLine().getRate() * 100.0d,
                report.getGenerated().getBranch().isApplicable()
                        ? String.format(java.util.Locale.ROOT, "%.1f%%", report.getGenerated().getBranch().getRate() * 100.0d)
                        : "N/A")
                : "覆盖率不可用";
        return Messages.showYesNoDialog(project,
                "当前覆盖率未达标或结果已失效，但测试代码编译通过。\n"
                        + metrics + "，缺口 " + report.getGaps().size() + " 个。\n"
                        + report.getMessage() + "\n\n仍然写入吗？",
                "UnitMind 覆盖率警告",
                "仍然写入",
                "取消",
                Messages.getWarningIcon()) == Messages.YES;
    }

    private boolean confirmClassJUnitWrite(ClassJUnitRepairReport report, boolean force) {
        if (report == null || report.getStatus() == ClassJUnitStatus.NOT_RUN
                || report.getStatus() == ClassJUnitStatus.PASSED) return true;
        boolean protectedFailure = report.getStatus() == ClassJUnitStatus.TEST_FAILED
                || report.getStatus() == ClassJUnitStatus.SUSPECTED_SUT_BUG
                || report.getStatus() == ClassJUnitStatus.NEEDS_REVIEW;
        if (protectedFailure && !force) {
            Messages.showErrorDialog(project,
                    (report.getStatus() == ClassJUnitStatus.SUSPECTED_SUT_BUG
                    ? "完整测试类可能发现了生产代码缺陷，原失败测试已保留。\n\n"
                    : "完整测试类未通过或 Oracle 待确认，正常写入已阻止。\n\n")
                            + report.getMessage(), "UnitMind 整类测试未通过");
            return false;
        }
        if (force && protectedFailure) {
            return Messages.showYesNoDialog(project,
                    "原失败整类测试将按原样写入，确定继续吗？\n\n" + report.getMessage(),
                    "写入失败回归测试", "写入原失败测试", "取消",
                    Messages.getWarningIcon()) == Messages.YES;
        }
        return true;
    }

    private void markValidationStale(String code) {
        editedCode = code == null ? "" : code;
        if (generationResult == null) {
            return;
        }
        ValidationResult stale = ValidationResult.status(ValidationStatus.STALE);
        stale.setCodeHash(CodeFingerprint.sha256(editedCode));
        generationResult.setValidationResult(stale);
        CoverageReport coverage = generationResult.getCoverageReport();
        if (coverage != null && coverage.getStatus() != CoverageStatus.NOT_RUN) {
            if (coverageRunning && coverageIndicator != null) coverageIndicator.cancel();
            coverage.setStatus(CoverageStatus.STALE);
            coverage.setMessage("测试代码已修改，覆盖率结果失效。");
        }
        if (generationResult instanceof ClassGenerationResult classResult
                && classResult.getClassJUnitRepairReport() != null
                && classResult.getClassJUnitRepairReport().getStatus() != ClassJUnitStatus.NOT_RUN) {
            classResult.getClassJUnitRepairReport().setStatus(ClassJUnitStatus.STALE);
            classResult.getClassJUnitRepairReport().setMessage("测试代码已修改，整类 JUnit 结果失效。");
        }
        stepStatuses[3] = StepStatus.WARNING;
    }

    private void runCoverageLoop() {
        if (generationResult == null || generationResult.getGeneratedTestFile() == null || coverageRunning) return;
        boolean classMode = generationResult.getGenerationScope() == GenerationScope.CLASS;
        if (!CoverageConfigManager.isExecutionConfirmed()) {
            int answer = Messages.showOkCancelDialog(project,
                    "UnitMind 将在本机隔离 JVM 中真实执行本次生成测试和对应的已有测试。"
                            + "测试代码仍可能访问网络、文件或外部服务，请确认当前代码适合执行。",
                    "UnitMind 测试执行安全提示",
                    "确认并运行",
                    "取消",
                    Messages.getWarningIcon());
            if (answer != Messages.OK) return;
            CoverageConfigManager.setExecutionConfirmed(true);
        }
        editedCode = currentCode();
        int requestId = ++coverageSequence;
        coverageRunning = true;
        if (classMode) {
            ((ClassGenerationResult) generationResult).setClassJUnitRepairReport(
                    ClassJUnitRepairReport.status(ClassJUnitStatus.RUNNING));
        } else {
            generationResult.setCoverageReport(CoverageReport.status(CoverageStatus.RUNNING));
        }
        stepStatuses[3] = StepStatus.RUNNING;
        rebuildSteps();
        GenerationResult currentResult = generationResult;
        String candidate = editedCode;
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, classMode
                ? "UnitMind 正在运行整类 JUnit 修复" : "UnitMind 正在运行覆盖率闭环", true) {
            private GenerationResult completed;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                coverageIndicator = indicator;
                indicator.setIndeterminate(false);
                completed = classMode
                        ? generationService.runFinalClassJUnitRepair(project, requestFile,
                        (ClassGenerationResult) currentResult, candidate, indicator::isCanceled,
                        (text, fraction) -> {
                            indicator.setText(text);
                            indicator.setFraction(Math.max(0d, Math.min(1d, fraction)));
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (requestId == coverageSequence && coverageRunning && !project.isDisposed()
                                        && testCodeStepPanel != null) {
                                    testCodeStepPanel.updateClassJUnitReport(
                                            ((ClassGenerationResult) currentResult).getClassJUnitRepairReport());
                                }
                            });
                        })
                        : generationService.runCoverageLoop(project, requestFile, currentResult,
                        candidate, indicator::isCanceled, (text, fraction) -> {
                            indicator.setText(text);
                            indicator.setFraction(Math.max(0d, Math.min(1d, fraction)));
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (requestId == coverageSequence && coverageRunning && !project.isDisposed()) {
                                    if (testCodeStepPanel != null) {
                                        testCodeStepPanel.updateCoverageReport(currentResult.getCoverageReport());
                                    }
                                }
                            });
                        });
            }

            @Override
            public void onSuccess() {
                if (requestId == coverageSequence) applyCoverageResult(completed);
            }

            @Override
            public void onCancel() {
                if (requestId != coverageSequence) return;
                if (completed == null) {
                    if (classMode) {
                        ClassJUnitRepairReport report = ((ClassGenerationResult) currentResult)
                                .getClassJUnitRepairReport();
                        report.setStatus(ClassJUnitStatus.CANCELLED);
                        report.setStopReason(ClassJUnitStopReason.CANCELLED);
                        report.setMessage("整类 JUnit 修复已取消。");
                    } else {
                        CoverageReport report = currentResult.getCoverageReport();
                        report.setStatus(CoverageStatus.CANCELLED);
                        report.setStopReason(CoverageStopReason.CANCELLED);
                        report.setMessage("覆盖率执行已取消。");
                    }
                    completed = currentResult;
                }
                applyCoverageResult(completed);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                if (requestId != coverageSequence) return;
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                if (classMode) {
                    ClassJUnitRepairReport report = ((ClassGenerationResult) currentResult)
                            .getClassJUnitRepairReport();
                    report.setStatus(ClassJUnitStatus.ENVIRONMENT_ERROR);
                    report.setStopReason(ClassJUnitStopReason.ENVIRONMENT_ERROR);
                    report.setMessage(message);
                } else {
                    CoverageReport report = currentResult.getCoverageReport();
                    report.setStatus(CoverageStatus.ENVIRONMENT_ERROR);
                    report.setStopReason(CoverageStopReason.ENVIRONMENT_ERROR);
                    report.setMessage(message);
                }
                applyCoverageResult(currentResult);
            }
        }.queue();
    }

    private void stopCoverageLoop() {
        if (coverageIndicator != null) coverageIndicator.cancel();
    }

    private void applyCoverageResult(GenerationResult completed) {
        coverageRunning = false;
        coverageIndicator = null;
        if (completed != null) {
            generationResult = completed;
            GeneratedTestFile file = completed.getGeneratedTestFile();
            generatedCode = file == null ? generatedCode : file.getContent();
            editedCode = generatedCode;
            String latestSourceHash = currentPsiFile == null ? ""
                    : ReadAction.compute(() -> CodeFingerprint.sha256(currentPsiFile.getText()));
            if (completed instanceof ClassGenerationResult classResult) {
                ClassJUnitRepairReport report = classResult.getClassJUnitRepairReport();
                if (report != null && !report.getSourceHash().isBlank()
                        && !report.getSourceHash().equals(latestSourceHash)) {
                    report.setStatus(ClassJUnitStatus.STALE);
                    report.setMessage("被测源码已修改，整类 JUnit 结果失效，请重新分析后运行。");
                }
                stepStatuses[3] = report != null && report.getStatus() == ClassJUnitStatus.PASSED
                        ? StepStatus.SUCCESS : StepStatus.WARNING;
            } else {
                CoverageReport report = completed.getCoverageReport();
                if (report != null && !report.getSourceHash().isBlank()
                        && !report.getSourceHash().equals(latestSourceHash)) {
                    report.setStatus(CoverageStatus.STALE);
                    report.setMessage("被测源码已修改，覆盖率结果失效，请重新分析后运行。");
                }
                CoverageStatus status = completed.getCoverageReport() == null
                        ? CoverageStatus.ENVIRONMENT_ERROR : completed.getCoverageReport().getStatus();
                stepStatuses[3] = status == CoverageStatus.PASSED ? StepStatus.SUCCESS : StepStatus.WARNING;
            }
        }
        rebuildSteps();
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
        int requestId = analysisSequence;
        PsiFile validatingFile = currentPsiFile;
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
                        validatingFile,
                        currentResult.getGeneratedTestFile(),
                        validatingCode);
            }

            @Override
            public void onSuccess() {
                if (!isCurrentRequest(requestId, validatingFile, currentResult)) return;
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
        if (generationResult instanceof ClassGenerationResult) {
            Messages.showInfoMessage(project, "整类模式请点击“重新分析”后再次进入测试代码步骤，系统会重新为全部方法生成。", "UnitMind 整类生成");
            return;
        }
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
        int requestId = analysisSequence;
        PsiFile requestFile = currentPsiFile;
        GenerationResult requestResult = generationResult;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                GenerationResult regenerated = generationService.regenerateWithLlmFeedback(
                        project, requestFile, requestResult, currentCode, feedback);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isCurrentRequest(requestId, requestFile, requestResult)) return;
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
                    if (!isCurrentRequest(requestId, requestFile, requestResult)) return;
                    generationStatus = GenerationStatus.SUCCESS;
                    stepStatuses[3] = StepStatus.WARNING;
                    lastError = message;
                    Messages.showErrorDialog(project, message, "UnitMind 大模型重新生成失败");
                    rebuildSteps();
                });
            }
        });
    }

    private boolean isCurrentRequest(int requestId, PsiFile requestFile, GenerationResult requestResult) {
        return requestId == analysisSequence && requestFile == currentPsiFile
                && requestResult == generationResult && !project.isDisposed();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
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
        if (generationResult instanceof ClassGenerationResult) {
            ClassGenerationResult classResult = (ClassGenerationResult) generationResult;
            long success = classResult.getMethodItems().stream().filter(item -> item.getStatus() == com.casc.unittestagent.model.MethodGenerationStatus.SUCCESS).count();
            long fallback = classResult.getMethodItems().stream().filter(item -> item.getStatus() == com.casc.unittestagent.model.MethodGenerationStatus.RULE_FALLBACK).count();
            long failed = classResult.getMethodItems().stream().filter(item -> item.getStatus() == com.casc.unittestagent.model.MethodGenerationStatus.FAILED).count();
            return "整类生成：" + success + " 个方法由大模型生成，" + fallback
                    + " 个方法使用规则草稿，" + failed + " 个方法失败。"
                    + " 整类覆盖率闭环首期不支持。";
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
