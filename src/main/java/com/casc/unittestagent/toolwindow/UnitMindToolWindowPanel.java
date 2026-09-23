package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
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
 * 前端当前只开放单方法生成，整类生成保留在服务与评测链路中，不在此处接线。
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
    private final JButton writeButton = new JButton("写入文件");

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
        for (int index = 1; index < stepStatuses.length; index++) stepStatuses[index] = StepStatus.NOT_STARTED;
        // 重新分析开启新会话：必须使正在运行的覆盖率闭环失效（与 setCurrentContext 一致），
        // 否则旧任务的进度回调与 onSuccess 会把旧方法的结果整体替换进新会话。
        coverageSequence++;
        if (coverageIndicator != null) coverageIndicator.cancel();
        coverageRunning = false;
        coverageIndicator = null;
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
                        markAnalysisFailed();
                        rebuildSteps();
                    });
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> applyAnalysisResult(result));
            }
        }.queue();
    }

    /**
     * 分析失败时只标记已尝试的步骤，后续步骤保持“未开始”。
     */
    private void markAnalysisFailed() {
        stepStatuses[0] = StepStatus.FAILED;
        stepStatuses[1] = StepStatus.FAILED;
        for (int index = 2; index < stepStatuses.length; index++) stepStatuses[index] = StepStatus.NOT_STARTED;
        generationStatus = GenerationStatus.FAILED;
        currentStepIndex = 0;
    }

    /**
     * 应用静态分析结果，只更新方法概要和参数约束步骤。
     */
    private void applyAnalysisResult(GenerationResult result) {
        generationResult = result;
        generatedCode = "";
        editedCode = "";
        if (generationResult == null || generationResult.getMethodModel() == null) {
            lastError = warningText();
            markAnalysisFailed();
        } else {
            stepStatuses[0] = StepStatus.SUCCESS;
            for (int index = 1; index < stepStatuses.length; index++) stepStatuses[index] = StepStatus.NOT_STARTED;
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
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在生成测试代码", false) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = generationService.generateTestCode(project, requestFile, currentResult);
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
            stepStatuses[0] = StepStatus.SUCCESS;
            stepStatuses[1] = StepStatus.SUCCESS;
            stepStatuses[2] = StepStatus.SUCCESS;
            stepStatuses[3] = StepStatus.FAILED;
            stepStatuses[4] = StepStatus.NOT_STARTED;
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
     * 重建步骤内容。
     */
    private void rebuildSteps() {
        if (testCodeStepPanel != null && testCodeStepPanel.hasEditor()) {
            editedCode = testCodeStepPanel.getCode();
        }
        updateHeader();
        stepperPanel.update(currentStepIndex, stepStatuses);
        contentPanel.removeAll();
        boolean analysisFailed = generationStatus == GenerationStatus.FAILED
                && (generationResult == null || generationResult.getMethodModel() == null);
        contentPanel.add(new StaticAnalysisStepPanel(
                generationResult,
                sourcePath(),
                analysisFailed ? warningText() : null,
                this::analyzeCurrentMethod), STEPS[0]);
        contentPanel.add(new ScenarioStepPanel(
                generationResult,
                this::restoreRecommendedScenarios,
                this::onGenerationInputsEdited), STEPS[1]);
        contentPanel.add(new MockStrategyStepPanel(
                generationResult,
                mockStrategyLlmRunning,
                this::generateLlmMockStrategy,
                this::useStaticMockStrategy,
                this::useLlmMockStrategy,
                this::onGenerationInputsEdited), STEPS[2]);
        testCodeStepPanel = new TestCodeStepPanel(
                project,
                generationStatus,
                generatedCode,
                currentCode(),
                lastError,
                generationNote(),
                generationResult == null ? null : generationResult.getValidationResult(),
                generationResult == null ? null : generationResult.getCoverageReport(),
                null,
                coverageRunning,
                true,
                true,
                false,
                this::regenerateTestCode,
                this::validateCurrentCode,
                this::markValidationStale,
                this::runCoverageLoop,
                this::stopCoverageLoop,
                this::resumeWithEvidence,
                generationResult == null ? 0 : generationResult.getEvidenceResumeCount());
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
                && !coverageRunning
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
        invalidateGeneratedTestCode();
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
        invalidateGeneratedTestCode();
        rebuildSteps();
    }

    /**
     * 场景或 Mock 策略被编辑后调用：旧测试代码与最新规划不再一致，必须失效。
     * 编辑发生在表格内，不能立即重建面板（会销毁正在编辑的表格），
     * 只就地清理状态并禁用写入，用户进入测试代码步骤时会按最新数据重新生成。
     */
    private void onGenerationInputsEdited() {
        if (generationResult == null) {
            return;
        }
        invalidateGeneratedTestCode();
    }

    /**
     * 使当前已生成测试代码及其验证、覆盖率结果失效。
     * Mock 策略切换与场景/策略表格编辑共用该入口，保证“所见规划”与“生成代码”一致。
     */
    private void invalidateGeneratedTestCode() {
        coverageSequence++;
        if (coverageRunning && coverageIndicator != null) coverageIndicator.cancel();
        coverageRunning = false;
        coverageIndicator = null;
        if (generationResult != null) {
            generationResult.setGeneratedTestFile(null);
            CoverageReport coverage = generationResult.getCoverageReport();
            if (coverage != null && coverage.getStatus() != CoverageStatus.NOT_RUN) {
                coverage.setStatus(CoverageStatus.STALE);
                coverage.setMessage("场景或 Mock 策略已修改，覆盖率结果失效。");
            }
        }
        generationStatus = GenerationStatus.IDLE;
        generatedCode = "";
        editedCode = "";
        stepStatuses[3] = StepStatus.NOT_STARTED;
        stepStatuses[4] = StepStatus.NOT_STARTED;
        writeButton.setEnabled(false);
    }

    /**
     * “恢复推荐”场景：清空用户编辑，重新基于静态分析规划推荐场景。
     * 场景恢复后旧测试代码同样失效。
     */
    private void restoreRecommendedScenarios() {
        if (generationResult == null || generationResult.getMethodModel() == null) {
            return;
        }
        invalidateGeneratedTestCode();
        generationResult.getScenarios().clear();
        stepStatuses[1] = StepStatus.NOT_STARTED;
        planScenariosIfNeeded();
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
        // 覆盖率闭环运行期间结论未定，写入会写过时快照；两个写入入口都汇聚于此，统一拦截。
        if (coverageRunning) {
            Messages.showWarningDialog(project,
                    "覆盖率闭环仍在运行，结论未定；请等待结束或先停止再写入。", "UnitMind");
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
            preparedWrite = testFileWriter.prepare(project, currentPsiFile,
                    generationResult.getGeneratedTestFile(), code, mode, GenerationTarget.method(methodTargetId));
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
                            lastError + "\n\n正常写入已阻止。如确需保留草稿，请使用“强制写入”。",
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
                boolean executionConfirmed = !passed || confirmCoverageWrite(currentResult.getCoverageReport(), force);
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

    /** 写入门禁动作：由 coverageWriteGateAction(状态×force) 纯函数决定，可独立测试。 */
    enum WriteGateAction {
        /** 直接放行（未运行/已通过）。 */
        ALLOW,
        /** 闭环运行中，结论未定：无条件阻断（force 也不放行）。 */
        BLOCK_RUNNING,
        /** 受保护失败（测试失败/疑似缺陷/待确认）：普通写入阻断，force 走“写入原失败测试”确认。 */
        BLOCK_PROTECTED,
        /** 受保护失败 + force：弹“写入原失败测试”确认。 */
        CONFIRM_FORCE_WRITE,
        /** 取消/未达标/失效/环境错误：弹覆盖率警告确认。 */
        CONFIRM_WARNING
    }

    /** 写入门禁状态矩阵：RUNNING/CANCELLED 不得落入静默放行的兜底分支。 */
    static WriteGateAction coverageWriteGateAction(CoverageStatus status, boolean force) {
        if (status == null || status == CoverageStatus.NOT_RUN || status == CoverageStatus.PASSED) {
            return WriteGateAction.ALLOW;
        }
        if (status == CoverageStatus.RUNNING) {
            return WriteGateAction.BLOCK_RUNNING;
        }
        boolean protectedFailure = status == CoverageStatus.TEST_FAILED
                || status == CoverageStatus.SUSPECTED_SUT_BUG
                || status == CoverageStatus.NEEDS_REVIEW;
        if (protectedFailure) {
            return force ? WriteGateAction.CONFIRM_FORCE_WRITE : WriteGateAction.BLOCK_PROTECTED;
        }
        if (status == CoverageStatus.CANCELLED || status == CoverageStatus.BELOW_THRESHOLD
                || status == CoverageStatus.STALE || status == CoverageStatus.ENVIRONMENT_ERROR) {
            return WriteGateAction.CONFIRM_WARNING;
        }
        return WriteGateAction.ALLOW;
    }

    private boolean confirmCoverageWrite(CoverageReport report, boolean force) {
        WriteGateAction action = coverageWriteGateAction(report == null ? null : report.getStatus(), force);
        switch (action) {
            case ALLOW:
                return true;
            case BLOCK_RUNNING:
                // 结论未定：写入内容会被最终候选替换，中途写入必然写过时快照；force 也必须先让闭环结束。
                Messages.showErrorDialog(project,
                        "覆盖率闭环仍在运行，结论未定；中途写入会写过时快照。\n请等待闭环结束或先停止再写入。",
                        "UnitMind 写入保护");
                return false;
            case BLOCK_PROTECTED:
                Messages.showErrorDialog(project,
                        (report.getStatus() == CoverageStatus.SUSPECTED_SUT_BUG
                        ? "测试可能发现了生产代码缺陷，插件已保留原断言并阻止普通写入。\n\n"
                        : "候选测试执行未通过或 Oracle 待确认，正常写入已阻止。\n\n")
                                + report.getMessage(),
                        "UnitMind 需要人工确认");
                return false;
            case CONFIRM_FORCE_WRITE:
                return Messages.showYesNoDialog(project,
                        "原失败测试将按原样写入，用于复现疑似缺陷或等待 Oracle 确认。\n\n"
                                + report.getMessage(),
                        "写入失败回归测试",
                        "写入原失败测试",
                        "取消",
                        Messages.getWarningIcon()) == Messages.YES;
            case CONFIRM_WARNING:
            default:
                if (report.getStatus() != CoverageStatus.CANCELLED
                        && report.getStatus() != CoverageStatus.BELOW_THRESHOLD
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
                        : report.getStatus() == CoverageStatus.CANCELLED
                        ? "闭环已取消，未取得覆盖率结论" : "覆盖率不可用";
                return Messages.showYesNoDialog(project,
                        "当前覆盖率未达标或结果已失效，但测试代码编译通过。\n"
                                + metrics + "，缺口 " + report.getGaps().size() + " 个。\n"
                                + report.getMessage() + "\n\n仍然写入吗？",
                        "UnitMind 覆盖率警告",
                        "仍然写入",
                        "取消",
                        Messages.getWarningIcon()) == Messages.YES;
        }
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
        stepStatuses[3] = StepStatus.WARNING;
    }

    private void runCoverageLoop() {
        if (generationResult == null || generationResult.getGeneratedTestFile() == null || coverageRunning) return;
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
        generationResult.setCoverageReport(CoverageReport.status(CoverageStatus.RUNNING));
        stepStatuses[3] = StepStatus.RUNNING;
        rebuildSteps();
        GenerationResult currentResult = generationResult;
        String candidate = editedCode;
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在运行覆盖率闭环", true) {
            private GenerationResult completed;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                coverageIndicator = indicator;
                indicator.setIndeterminate(false);
                completed = generationService.runCoverageLoop(project, requestFile, currentResult,
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
                    CoverageReport report = currentResult.getCoverageReport();
                    report.setStatus(CoverageStatus.CANCELLED);
                    report.setStopReason(CoverageStopReason.CANCELLED);
                    report.setMessage("覆盖率执行已取消。");
                    completed = currentResult;
                }
                applyCoverageResult(completed);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                if (requestId != coverageSequence) return;
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                CoverageReport report = currentResult.getCoverageReport();
                report.setStatus(CoverageStatus.ENVIRONMENT_ERROR);
                report.setStopReason(CoverageStopReason.ENVIRONMENT_ERROR);
                report.setMessage(message);
                applyCoverageResult(currentResult);
            }
        }.queue();
    }

    private void stopCoverageLoop() {
        if (coverageIndicator != null) coverageIndicator.cancel();
    }

    /**
     * 补充证据续修：把“补充要求”中的用户证据注入为 USER_REQUIREMENT 权威场景，
     * 再从当前测试代码重跑覆盖率闭环。仅在 Oracle 证据不足停止态可用（按钮由
     * TestCodeStepPanel.evidenceResumeAvailable 控制）。
     *
     * 关键接线约束：不得在服务端校验前替换 GenerationResult 上的停止态报告——
     * resumeCoverageLoopWithEvidence 的前置校验读取 stopReason==ORACLE_INCONCLUSIVE，
     * RUNNING 新报告（stopReason=NONE）会让校验必然失败，并销毁 oracleAssessment、
     * 失败测试名单等续修赖以工作的诊断状态。RUNNING 报告由服务端 runCoverageLoop 自建。
     */
    private void resumeWithEvidence(String evidence) {
        if (generationResult == null || generationResult.getGeneratedTestFile() == null || coverageRunning) return;
        if (evidence == null || evidence.isBlank()) {
            Messages.showWarningDialog(project, "请先在补充要求中填写证据内容。", "UnitMind");
            return;
        }
        CoverageReport stoppedReport = generationResult.getCoverageReport();
        if (stoppedReport == null
                || stoppedReport.getStopReason() != CoverageStopReason.ORACLE_INCONCLUSIVE) {
            Messages.showWarningDialog(project, "当前结果不是 Oracle 证据不足停止态，无法补充证据续修。", "UnitMind");
            return;
        }
        CoverageConfig config = CoverageConfigManager.load();
        if (!UnitTestGenerationService.evidenceResumeAllowed(
                generationResult.getEvidenceResumeCount(), config)) {
            Messages.showWarningDialog(project,
                    "补充证据续修次数已达上限（" + config.getMaxEvidenceResumes()
                            + "）。请人工确认失败测试，或重新生成后再试。", "UnitMind");
            return;
        }
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
        stepStatuses[3] = StepStatus.RUNNING;
        rebuildSteps();
        GenerationResult currentResult = generationResult;
        String candidate = editedCode;
        String userEvidence = evidence.strip();
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在补充证据后继续修复", true) {
            private GenerationResult completed;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                coverageIndicator = indicator;
                indicator.setIndeterminate(false);
                completed = generationService.resumeCoverageLoopWithEvidence(project, requestFile,
                        currentResult, candidate, userEvidence, indicator::isCanceled,
                        (text, fraction) -> {
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
                // 启动前取消：保留原 Oracle 停止态报告（含续修提示与失败名单），仅还原 UI，
                // 用户仍可再次补充证据；服务端已返回结果则正常应用。
                if (completed == null) {
                    abortResume("证据续修已取消。");
                    return;
                }
                applyCoverageResult(completed);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                if (requestId != coverageSequence) return;
                // 不改写覆盖率报告：原停止态诊断（oracleAssessment/失败名单/证据提示）必须
                // 保留，否则按钮永久消失且证据上下文丢失。只还原 UI 并向用户说明原因。
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                abortResume("补充证据续修未启动：" + message);
            }
        }.queue();
    }

    /** 续修未产生任何结果（取消/启动失败）：不动报告，仅结束运行态并重建步骤。 */
    private void abortResume(String message) {
        coverageRunning = false;
        coverageIndicator = null;
        stepStatuses[3] = StepStatus.WARNING;
        rebuildSteps();
        Messages.showWarningDialog(project, message, "UnitMind");
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
            CoverageReport report = completed.getCoverageReport();
            if (report != null && !report.getSourceHash().isBlank()
                    && !report.getSourceHash().equals(latestSourceHash)) {
                report.setStatus(CoverageStatus.STALE);
                report.setMessage("被测源码已修改，覆盖率结果失效，请重新分析后运行。");
            }
            CoverageStatus status = report == null ? CoverageStatus.ENVIRONMENT_ERROR : report.getStatus();
            stepStatuses[3] = status == CoverageStatus.PASSED ? StepStatus.SUCCESS : StepStatus.WARNING;
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
