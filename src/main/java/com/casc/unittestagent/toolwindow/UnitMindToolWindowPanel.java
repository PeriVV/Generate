package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.model.RunnableTestSelectionReport;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.StepStatus;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.ui.UnitMindSettingsDialog;
import com.casc.unittestagent.ui.TestDependencySetupDialog;
import com.casc.unittestagent.dependency.TestDependencySetupService;
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
public class UnitMindToolWindowPanel extends JPanel implements com.intellij.openapi.Disposable {
    private volatile boolean disposed;
    /** 步骤名称。 */
    private static final String[] STEPS = {
            "静态分析结果", "测试场景", "Mock策略", "测试代码", "写入测试文件"
    };

    /** 当前项目。 */
    private final Project project;
    /** 写入服务。 */
    private final TestFileWriter testFileWriter = new TestFileWriter(new com.casc.unittestagent.ui.SwingWriteModeSelector());
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
    private volatile PsiFile currentPsiFile;
    /** 生成结果。 */
    private volatile GenerationResult generationResult;
    /** 当前步骤索引。 */
    private int currentStepIndex;
    /** 测试代码生成状态。 */
    private GenerationStatus generationStatus = GenerationStatus.IDLE;
    /** 原始生成代码。 */
    private String generatedCode = "";
    /** 当前测试文本的唯一状态；用户编辑由 DocumentListener 同步，后台结果在 EDT 替换。 */
    private String editedCode = "";
    /** 最近一次错误详情。 */
    private String lastError = "";
    /** 当前测试代码步骤面板。 */
    private TestCodeStepPanel testCodeStepPanel;
    /** 分析任务序号，用于忽略过期后台任务结果。 */
    private volatile int analysisSequence;
    /** 是否正在调用大模型生成候选 Mock 策略。 */
    private boolean mockStrategyLlmRunning;
    /** 是否正在隔离执行覆盖率闭环。 */
    private volatile boolean coverageRunning;
    /** 当前可取消的覆盖率后台进度。 */
    private ProgressIndicator coverageIndicator;
    /** 覆盖率任务序号，用于忽略切换方法后的过期回调。 */
    private volatile int coverageSequence;
    private boolean writeRunning;
    private volatile ProgressIndicator writeIndicator;
    private volatile int writeSequence;
    private boolean dependencySetupRunning;

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
                this::analyzeCurrentMethod,
                this::configureTestDependencies
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

    private void configureTestDependencies() {
        if (dependencySetupRunning || coverageRunning || writeRunning || generationStatus == GenerationStatus.RUNNING) return;
        if (!(currentPsiFile instanceof PsiJavaFile)) detectCurrentEditorContext();
        if (!(currentPsiFile instanceof PsiJavaFile)) {
            Messages.showInfoMessage(project, "请先打开目标模块的 Java 源文件，再点击配置测试依赖。", "配置测试依赖");
            return;
        }
        PsiFile source = currentPsiFile;
        TestDependencySetupService service = new TestDependencySetupService();
        dependencySetupRunning = true;
        headerPanel.setDependenciesEnabled(false);
        headerPanel.setDependencyStatus("分析依赖中...");
        new Task.Backgroundable(project, "分析当前模块的测试依赖", true) {
            private TestDependencySetupService.Context context;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.checkCanceled();
                try { context = service.inspect(project, source); }
                catch (java.io.IOException error) { throw new IllegalStateException(error.getMessage(), error); }
                indicator.checkCanceled();
            }
            @Override public void onSuccess() {
                if (project.isDisposed() || disposed) { dependencySetupRunning = false; return; }
                boolean importingMaven = false;
                try {
                    TestDependencySetupDialog dialog = new TestDependencySetupDialog(service, context);
                    if (!dialog.showAndGet()) return;
                    var plan = dialog.getPlan();
                    var applied = service.apply(plan);
                    if (generationResult != null && generationResult.getGeneratedTestFile() != null) {
                        markValidationStale(currentCode());
                        generationResult.getCoverageReport().setStatus(CoverageStatus.STALE);
                        generationResult.getCoverageReport().setMessage("测试依赖已变更，请重新运行验证。");
                    }
                    if (plan.mode() == TestDependencySetupService.Mode.PROJECT
                            && context.buildFile().getFileName().toString().equals("pom.xml")) {
                        var sourceFile = source.getVirtualFile();
                        headerPanel.setDependencyStatus("Maven 同步中...");
                        rebuildSteps();
                        new com.casc.unittestagent.dependency.MavenDependencyImporter(project).importPom(context.buildFile())
                                .onSuccess(ignored -> ApplicationManager.getApplication().invokeLater(() ->
                                        verifyMavenDependencyImport(service, context, applied, sourceFile)))
                                .onError(error -> ApplicationManager.getApplication().invokeLater(() ->
                                        finishDependencyImport(false, "Maven 同步未完成："
                                                + (error.getMessage() == null ? "同步已取消或异常中断" : error.getMessage())
                                                + "\n配置文件：" + context.buildFile()
                                                + "\n请查看 Maven 同步窗口的仓库、离线模式和依赖解析错误。")));
                        importingMaven = true;
                        return;
                    }
                    String refresh = plan.mode() == TestDependencySetupService.Mode.PROJECT ? refreshDependencyProject(context) : "请等待 IDEA 索引完成后重新验证。";
                    Messages.showInfoMessage(project, applied.message() + "\n" + refresh
                            + (applied.backup() == null ? "" : "\n修改前备份：" + applied.backup()), "测试依赖配置结果");
                } catch (Exception error) {
                    Messages.showErrorDialog(project, error.getMessage(), "测试依赖配置未完成");
                } catch (LinkageError unavailableMavenApi) {
                    Messages.showErrorDialog(project,
                            "pom.xml 已保存，但 Maven 导入接口不可用。请确认 Maven 插件已启用，或选择仅 IDEA 的离线导入模式。",
                            "测试依赖尚未导入");
                } finally {
                    if (!importingMaven) {
                        dependencySetupRunning = false;
                        headerPanel.setDependencyStatus("");
                        if (!disposed && !project.isDisposed()) rebuildSteps();
                    }
                }
            }
            @Override public void onCancel() {
                dependencySetupRunning = false;
                if (!disposed && !project.isDisposed()) { headerPanel.setDependencyStatus(""); showCurrentStep(); }
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                dependencySetupRunning = false;
                if (!disposed && !project.isDisposed()) {
                    headerPanel.setDependencyStatus("依赖分析失败");
                    Messages.showErrorDialog(project, error.getMessage(), "测试依赖分析失败");
                    showCurrentStep();
                }
            }
        }.queue();
    }

    private void verifyMavenDependencyImport(TestDependencySetupService service,
                                             TestDependencySetupService.Context before,
                                             TestDependencySetupService.Applied applied,
                                             com.intellij.openapi.vfs.VirtualFile sourceFile) {
        if (disposed || project.isDisposed()) { dependencySetupRunning = false; return; }
        headerPanel.setDependencyStatus("检查 Maven 依赖...");
        new Task.Backgroundable(project, "检查 Maven 测试依赖是否生效", true) {
            private TestDependencySetupService.Context imported;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.checkCanceled();
                PsiFile current = com.intellij.openapi.application.ReadAction.compute(() ->
                        sourceFile == null || !sourceFile.isValid() ? null : com.intellij.psi.PsiManager.getInstance(project).findFile(sourceFile));
                if (current == null) throw new IllegalStateException("原 Java 源文件已不可用，请重新检查目标模块的依赖。");
                try { imported = service.inspect(project, current); }
                catch (java.io.IOException error) { throw new IllegalStateException(error.getMessage(), error); }
                indicator.checkCanceled();
            }
            @Override public void onSuccess() {
                String message = TestDependencySetupService.resolutionMessage(imported)
                        + "\n配置文件：" + before.buildFile()
                        + (applied.backup() == null ? "" : "\n修改前备份：" + applied.backup());
                finishDependencyImport(TestDependencySetupService.dependenciesResolved(imported), message);
            }
            @Override public void onCancel() { finishDependencyImport(false, "依赖检查已取消；pom.xml 已保存，尚未确认依赖可见性。"); }
            @Override public void onThrowable(@NotNull Throwable error) { finishDependencyImport(false, "依赖检查未完成：" + error.getMessage()); }
        }.queue();
    }

    private void finishDependencyImport(boolean resolved, String message) {
        dependencySetupRunning = false;
        if (disposed || project.isDisposed()) return;
        headerPanel.setDependencyStatus(resolved ? "Maven 依赖已导入" : "Maven 依赖待处理");
        if (resolved) Messages.showInfoMessage(project, message, "测试依赖已导入");
        else Messages.showWarningDialog(project, message, "测试依赖尚未生效");
        rebuildSteps();
    }

    private String refreshDependencyProject(TestDependencySetupService.Context context) {
        try {
            if (context.buildFile().getFileName().toString().equals("pom.xml")) {
                throw new IllegalStateException("Maven 必须使用带结果检查的导入流程。");
            } else {
                var system = new com.intellij.openapi.externalSystem.model.ProjectSystemId("GRADLE");
                com.intellij.openapi.externalSystem.util.ExternalSystemUtil.refreshProject(context.refreshRoot().toString(),
                        new com.intellij.openapi.externalSystem.importing.ImportSpecBuilder(project, system)
                                .use(com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode.IN_BACKGROUND_ASYNC));
            }
            return "已请求构建工具刷新；请等待同步和索引结束。同步失败可查看 Maven/Gradle 窗口，文件写入不等于依赖解析通过。";
        } catch (RuntimeException error) {
            return "配置文件已更新，但未能请求自动刷新，请在 Maven/Gradle 窗口重新导入。";
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        analysisSequence++;
        coverageSequence++;
        if (coverageIndicator != null) coverageIndicator.cancel();
        coverageIndicator = null;
        coverageRunning = false;
        cancelWriteValidation();
        currentEditor = null;
        currentPsiFile = null;
        generationResult = null;
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
        cancelWriteValidation();
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
        cancelWriteValidation();
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
        new Task.Backgroundable(project, "UnitMind 正在分析方法", true) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = new UnitTestGenerationService(() -> indicator.isCanceled() || disposed || requestId != analysisSequence || project.isDisposed()).analyzeMethod(project, psiFile, editor);
                } catch (Exception ex) {
                    if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onCancel() {
                queueCurrentRequestUpdate(requestId, psiFile, null, () -> {
                    generationStatus = generationResult == null || generationResult.getGeneratedTestFile() == null
                            ? GenerationStatus.IDLE : GenerationStatus.SUCCESS;
                    mockStrategyLlmRunning = false;
                    stepStatuses[0] = StepStatus.WARNING;
                    lastError = "任务已取消。";
                    rebuildSteps();
                });
            }

            @Override
            public void onSuccess() {
                queueCurrentRequestUpdate(requestId, psiFile, null, () -> {
                    if (error != null) {
                        lastError = error;
                        markAnalysisFailed();
                        rebuildSteps();
                    } else {
                        applyAnalysisResult(result);
                    }
                });
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
        generationResult = new UnitTestGenerationService().planScenarios(generationResult);
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
        generationResult = new UnitTestGenerationService().planMockStrategy(generationResult);
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
        if (generationResult.getGeneratedTestFile() != null || generationStatus == GenerationStatus.RUNNING || mockStrategyLlmRunning) {
            return;
        }

        stepStatuses[3] = StepStatus.RUNNING;
        generationStatus = GenerationStatus.RUNNING;
        rebuildSteps();

        int requestId = analysisSequence;
        GenerationResult currentResult = generationResult;
        GenerationResult taskResult = generationCandidate(currentResult);
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在生成测试代码", true) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = new UnitTestGenerationService(() -> indicator.isCanceled() || !isCurrentRequest(requestId, requestFile, currentResult)).generateTestCode(project, requestFile, taskResult);
                } catch (Exception ex) {
                    if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onCancel() {
                queueCurrentRequestUpdate(requestId, requestFile, currentResult, () -> {
                    generationStatus = generationResult == null || generationResult.getGeneratedTestFile() == null
                            ? GenerationStatus.IDLE : GenerationStatus.SUCCESS;
                    mockStrategyLlmRunning = false;
                    stepStatuses[3] = StepStatus.WARNING;
                    lastError = "任务已取消。";
                    rebuildSteps();
                });
            }

            @Override
            public void onSuccess() {
                queueCurrentRequestUpdate(requestId, requestFile, currentResult, () -> {
                    if (error != null) {
                        lastError = error;
                        stepStatuses[3] = StepStatus.FAILED;
                        generationStatus = GenerationStatus.FAILED;
                        rebuildSteps();
                    } else {
                        applyGenerationResult(result);
                    }
                });
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
        // Source edits invalidate measurements as well as write gates; old line ranges are not current evidence.
        if (generationResult != null && currentPsiFile != null) {
            String sourceHash = currentSourceHash(currentPsiFile);
            var coverage = generationResult.getCoverageReport();
            if (!coverage.getSourceHash().isBlank() && !coverage.getSourceHash().equals(sourceHash)) {
                coverage.setStatus(CoverageStatus.STALE); coverage.setMessage("被测源码已修改，请重新分析并测量。");
            }
            var finalCoverage = generationResult.getFinalTestFileReport().getMethodCoverage();
            if (!finalCoverage.getSourceHash().isBlank() && !finalCoverage.getSourceHash().equals(sourceHash)) {
                generationResult.getFinalTestFileReport().markStale("被测源码已修改，最终文件覆盖率已失效。");
            }
        }
        // 页面只呈现当前状态，不能从已被后台结果替换的旧编辑器反向覆盖文本。
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
                generationResult == null ? null : generationResult.getRuntimeValidationReport(),
                coverageRunning || writeRunning,
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
        testCodeStepPanel.configureRunnableSelection(
                generationResult == null ? null : generationResult.getRunnableTestSelectionReport(),
                this::retainRunnableTests, this::restoreBeforeRunnableSelection, this::openBeforeRunnableSelection,
                coverageRunning || writeRunning);
        if (dependencySetupRunning) testCodeStepPanel.setDependencyImportPending();
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
        if (index == 4) rebuildSteps(); else showCurrentStep();
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
        headerPanel.setDependenciesEnabled(!dependencySetupRunning && !coverageRunning && !writeRunning
                && generationStatus != GenerationStatus.RUNNING);
        writeButton.setEnabled(generationResult != null
                && generationResult.getGeneratedTestFile() != null
                && generationStatus == GenerationStatus.SUCCESS
                && !coverageRunning && !writeRunning && !dependencySetupRunning
                && currentCode() != null
                && !currentCode().trim().isEmpty());
    }

    /**
     * 手动调用大模型生成候选 Mock 策略，不覆盖静态策略。
     */
    private void generateLlmMockStrategy() {
        if (generationResult == null || generationResult.getMethodModel() == null || mockStrategyLlmRunning
                || generationStatus == GenerationStatus.RUNNING || coverageRunning || writeRunning) {
            return;
        }
        planMockStrategyIfNeeded();
        mockStrategyLlmRunning = true;
        rebuildSteps();
        int requestId = analysisSequence;
        GenerationResult currentResult = generationResult;
        GenerationResult taskResult = generationCandidate(currentResult);
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在生成大模型 Mock 策略", true) {
            private GenerationResult result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = new UnitTestGenerationService(() -> indicator.isCanceled() || !isCurrentRequest(requestId, requestFile, currentResult)).generateLlmMockStrategy(taskResult);
                } catch (Exception ex) {
                    if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            @Override
            public void onCancel() {
                queueCurrentRequestUpdate(requestId, requestFile, currentResult, () -> {
                    generationStatus = generationResult == null || generationResult.getGeneratedTestFile() == null
                            ? GenerationStatus.IDLE : GenerationStatus.SUCCESS;
                    mockStrategyLlmRunning = false;
                    stepStatuses[2] = StepStatus.WARNING;
                    lastError = "任务已取消。";
                    rebuildSteps();
                });
            }

            @Override
            public void onSuccess() {
                queueCurrentRequestUpdate(requestId, requestFile, currentResult, () -> {
                    mockStrategyLlmRunning = false;
                    if (error != null) {
                        lastError = error;
                        Messages.showErrorDialog(project, error, "UnitMind 大模型 Mock 策略生成失败");
                    } else {
                        currentResult.setLlmMockStrategy(result.getLlmMockStrategy());
                        for (String warning : result.getWarnings()) {
                            if (!currentResult.getWarnings().contains(warning)) currentResult.getWarnings().add(warning);
                        }
                        currentStepIndex = 2;
                    }
                    rebuildSteps();
                });
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
        // Editing the inputs revokes all generation callbacks, including already queued EDT updates.
        analysisSequence++;
        mockStrategyLlmRunning = false;
        cancelWriteValidation();
        coverageSequence++;
        if (coverageRunning && coverageIndicator != null) coverageIndicator.cancel();
        coverageRunning = false;
        coverageIndicator = null;
        if (generationResult != null) {
            generationResult.setGeneratedTestFile(null);
            generationResult.getRuntimeValidationReport().setStatus(ClassJUnitStatus.STALE);
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
        if (dependencySetupRunning) return;
        if (mode == TestWriteMode.CANCEL) return;
        if (generationResult == null || generationResult.getGeneratedTestFile() == null) {
            Messages.showWarningDialog(project, "请先生成测试代码。", "UnitMind");
            return;
        }
        if (coverageRunning || writeRunning) {
            Messages.showWarningDialog(project, "测试验证仍在运行，请等待结束或先停止。", "UnitMind");
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
                    generationResult.getGeneratedTestFile(), code, mode, GenerationTarget.method(methodTargetId),
                    generationResult.getRunnableTestSelectionReport());
        } catch (Exception ex) {
                    if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
            stepStatuses[4] = StepStatus.FAILED;
            lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            Messages.showErrorDialog(project, lastError, "UnitMind 写入失败");
            rebuildSteps();
            return;
        }
        if (preparedWrite.isCancelled()) return;

        GenerationResult currentResult = generationResult;
        GenerationResult candidate = runtimeCandidate(currentResult,
                currentResult.getGeneratedTestFile().getContent());
        int requestId = analysisSequence;
        int writeId = ++writeSequence;
        PsiFile requestFile = currentPsiFile;
        writeRunning = true;
        var pendingFinalFile = new com.casc.unittestagent.model.FinalTestFileReport();
        pendingFinalFile.setTargetPath(preparedWrite.getTargetFile().getAbsolutePath());
        pendingFinalFile.getMethodCoverage().setStatus(CoverageStatus.RUNNING);
        currentResult.setFinalTestFileReport(pendingFinalFile);
        currentResult.setRuntimeValidationReport(ClassJUnitRepairReport.status(ClassJUnitStatus.RUNNING));
        stepStatuses[4] = StepStatus.RUNNING;
        rebuildSteps();
        new Task.Backgroundable(project, "UnitMind 正在验证测试并执行最终回归", true) {
            private TestFileWriter.PreparedWrite finalWrite = preparedWrite;
            private GenerationResult finalResult;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (!registerExecutionIndicator(indicator,
                        () -> writeId == writeSequence && isCurrentRequest(requestId, requestFile, currentResult), true)) return;
                indicator.setIndeterminate(false);
                java.util.function.BooleanSupplier cancelled =
                        () -> indicator.isCanceled() || writeId != writeSequence || project.isDisposed();
                try {
                    var outcome = new com.casc.unittestagent.service.TestWriteValidationService(testFileWriter,
                            new UnitTestGenerationService(cancelled)).validate(project, requestFile, candidate, code,
                            preparedWrite, force, cancelled, (text, fraction) -> {
                                indicator.setText(text); indicator.setFraction(fraction);
                            });
                    finalWrite = outcome.preparedWrite();
                    finalResult = outcome.result();
                } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
                    throw ex;
                } catch (Exception ex) {
                    if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                    error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }

            private boolean finishTask() {
                if (writeId != writeSequence) return false;
                writeRunning = false;
                writeIndicator = null;
                return isCurrentRequest(requestId, requestFile, currentResult)
                        && !project.isDisposed();
            }

            @Override
            public void onSuccess() {
                if (!finishTask()) return;
                if (error != null || finalResult == null) {
                    currentResult.getFinalTestFileReport().getMethodCoverage().setStatus(CoverageStatus.ENVIRONMENT_ERROR);
                    currentResult.getFinalTestFileReport().getMethodCoverage().setMessage("最终验证未完成，未写入。");
                    stepStatuses[4] = StepStatus.FAILED;
                    lastError = error == null ? "测试验证未返回结果。" : error;
                    Messages.showErrorDialog(project, lastError, "UnitMind 最终文件验证失败");
                    rebuildSteps();
                    return;
                }
                // 编辑器或输入在验证期间变化时，旧任务绝不能覆盖用户的新内容。
                if (!CodeFingerprint.sha256(code).equals(CodeFingerprint.sha256(currentCode()))) {
                    currentResult.getFinalTestFileReport().markStale("测试代码在验证期间已修改，未写入。");
                    stepStatuses[4] = StepStatus.WARNING;
                    lastError = "测试代码在验证期间已修改，请重新执行写入验证。";
                    rebuildSteps();
                    return;
                }
                ClassJUnitRepairReport runtime = finalResult.getRuntimeValidationReport();
                ValidationResult validation = finalResult.getValidationResult();
                if (runtime != candidate.getRuntimeValidationReport()) {
                    runtime.includeRepairHistory(candidate.getRuntimeValidationReport());
                }
                if (!runtime.getSourceHash().isBlank()
                        && !runtime.getSourceHash().equals(currentSourceHash(requestFile))) {
                    runtime.setStatus(ClassJUnitStatus.STALE);
                    runtime.setMessage("被测源码已修改，运行验证结果失效，请重新分析后写入。");
                }
                currentResult.setRuntimeValidationReport(runtime);
                currentResult.setValidationResult(validation);
                currentResult.setWarnings(candidate.getWarnings());
                currentResult.setFinalTestFileReport(finalResult.getFinalTestFileReport());
                if (runtime.getStatus() == ClassJUnitStatus.STALE) {
                    currentResult.getFinalTestFileReport().markStale(runtime.getMessage());
                }
                if (!force) {
                    String repairedCode = candidate.getGeneratedTestFile().getContent();
                    if (!code.equals(repairedCode)) {
                        currentResult.setGeneratedTestFile(candidate.getGeneratedTestFile());
                        generatedCode = repairedCode;
                        editedCode = repairedCode;
                        currentResult.getCoverageReport().setStatus(CoverageStatus.STALE);
                        currentResult.getCoverageReport().setMessage("运行修复已修改测试代码，覆盖率结果失效。");
                    }
                    boolean verified = runtimeWriteGateAction(runtime.getStatus(), false) == WriteGateAction.ALLOW
                            && validation != null
                            && validation.isPassedFor(CodeFingerprint.sha256(finalWrite.getContent()))
                            && runtime.isPassedFor(CodeFingerprint.sha256(finalWrite.getContent()),
                            currentSourceHash(requestFile));
                    if (!verified) {
                        stepStatuses[4] = StepStatus.WARNING;
                        lastError = runtimeFailureText(validation, runtime);
                        Messages.showErrorDialog(project, lastError + "\n\n正常写入已阻止；"
                                + "可在测试代码页查看诊断并修复，或显式保存未验证草稿。", "UnitMind 运行门禁未通过");
                        rebuildSteps();
                        return;
                    }
                } else {
                    int answer = Messages.showYesNoDialog(project,
                            "本次将跳过运行门禁，保存未验证草稿；该文件不代表测试运行通过。\n\n"
                                    + validationSummary(validation) + "\n\n确定仍要写入吗？",
                            "强制写入未验证草稿", "仍然写入草稿", "取消", Messages.getWarningIcon());
                    if (answer != Messages.YES) {
                        stepStatuses[4] = StepStatus.WARNING;
                        rebuildSteps();
                        return;
                    }
                }
                try {
                    testFileWriter.commit(project, finalWrite, validation, runtime, force);
                    if (!force) currentResult.getFinalTestFileReport().setWritten(true);
                    currentStepIndex = 4;
                    stepStatuses[4] = force ? StepStatus.WARNING : StepStatus.SUCCESS;
                    lastError = "";
                    Messages.showInfoMessage(project,
                            force ? "未验证草稿已强制写入。" : "最终测试类编译和完整 JUnit 回归通过，写入完成。", "UnitMind");
                } catch (Exception ex) {
                    if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                    currentResult.getFinalTestFileReport().markStale("最终写入未完成，覆盖率仅属于此前验证的快照。");
                    stepStatuses[4] = StepStatus.FAILED;
                    lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    Messages.showErrorDialog(project, lastError, "UnitMind 写入失败");
                }
                rebuildSteps();
            }

            @Override
            public void onCancel() {
                if (!finishTask()) return;
                currentResult.getFinalTestFileReport().getMethodCoverage().setStatus(CoverageStatus.CANCELLED);
                currentResult.getFinalTestFileReport().getMethodCoverage().setMessage("最终验证已取消，未写入。");
                currentResult.setRuntimeValidationReport(ClassJUnitRepairReport.status(ClassJUnitStatus.CANCELLED));
                stepStatuses[4] = StepStatus.WARNING;
                lastError = "写入验证已取消，未写入测试文件。";
                rebuildSteps();
            }

            @Override
            public void onThrowable(@NotNull Throwable failure) {
                if (!finishTask()) return;
                currentResult.getFinalTestFileReport().getMethodCoverage().setStatus(CoverageStatus.ENVIRONMENT_ERROR);
                currentResult.getFinalTestFileReport().getMethodCoverage().setMessage("最终验证异常终止，未写入。");
                currentResult.setRuntimeValidationReport(ClassJUnitRepairReport.status(ClassJUnitStatus.ENVIRONMENT_ERROR));
                stepStatuses[4] = StepStatus.FAILED;
                lastError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                rebuildSteps();
            }
        }.queue();
    }

    /** 后台执行使用独立结果，防止过期任务污染当前会话。 */
    static GenerationResult runtimeCandidate(GenerationResult original, String code) {
        GenerationResult copy = generationCandidate(original);
        copy.getGeneratedTestFile().setContent(code);
        return copy;
    }

    /** Background generation owns its editable inputs and output; initial generation has no file yet. */
    static GenerationResult generationCandidate(GenerationResult original) {
        return com.casc.unittestagent.service.GenerationSnapshotFactory.generationCandidate(original);
    }

    private static String currentSourceHash(PsiFile file) {
        return ReadAction.compute(() -> {
            com.intellij.openapi.editor.Document document = PsiDocumentManager.getInstance(file.getProject())
                    .getDocument(file);
            return CodeFingerprint.sha256(document == null ? file.getText() : document.getText());
        });
    }

    private String runtimeFailureText(ValidationResult validation, ClassJUnitRepairReport report) {
        if (validation != null && (validation.getStatus() == ValidationStatus.FAILED
                || validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR)) {
            return validationSummary(validation);
        }
        StringBuilder message = new StringBuilder("完整 JUnit 运行验证未通过：")
                .append(report.getStatus()).append("\n").append(report.getMessage());
        report.getFinalExecution().getDiagnostics().stream().limit(5).forEach(item ->
                message.append("\n").append(item.getTestId()).append("：")
                        .append(item.getCategory()).append(" ").append(item.getMessage()));
        return message.toString();
    }

    enum WriteGateAction { ALLOW, BLOCK_RUNNING, BLOCK_PROTECTED, CONFIRM_FORCE_WRITE }

    /** 运行门禁只认独立 JUnit 状态；覆盖率不足不会阻止已验证的测试写入。 */
    static WriteGateAction runtimeWriteGateAction(ClassJUnitStatus status, boolean force) {
        if (status == ClassJUnitStatus.RUNNING) return WriteGateAction.BLOCK_RUNNING;
        if (status == ClassJUnitStatus.PASSED) return WriteGateAction.ALLOW;
        return force ? WriteGateAction.CONFIRM_FORCE_WRITE : WriteGateAction.BLOCK_PROTECTED;
    }

    /** Registration and revocation share the EDT so a late task cannot replace a new task's handle. */
    boolean registerExecutionIndicator(ProgressIndicator indicator, java.util.function.BooleanSupplier ownsTask,
                                       boolean writeTask) {
        var registered = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable bind = () -> {
            if (!ownsTask.getAsBoolean()) { indicator.cancel(); return; }
            if (writeTask) writeIndicator = indicator;
            else coverageIndicator = indicator;
            registered.set(true);
        };
        var application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) bind.run();
        else application.invokeAndWait(bind, com.intellij.openapi.application.ModalityState.any());
        return registered.get();
    }

    private void cancelWriteValidation() {
        writeSequence++;
        if (writeIndicator != null) writeIndicator.cancel();
        writeIndicator = null;
        writeRunning = false;
    }

    private void markValidationStale(String code) {
        editedCode = code == null ? "" : code;
        if (generationResult == null) {
            return;
        }
        com.casc.unittestagent.service.GenerationEvidenceInvalidator.testEdited(generationResult, editedCode);
        if (writeRunning) cancelWriteValidation();
        if (coverageRunning) {
            coverageSequence++;
            if (coverageIndicator != null) coverageIndicator.cancel();
            coverageIndicator = null;
            coverageRunning = false;
        }
        stepStatuses[3] = StepStatus.WARNING;
    }

    private void runCoverageLoop() {
        if (dependencySetupRunning) return;
        if (generationResult == null || generationResult.getGeneratedTestFile() == null || coverageRunning || writeRunning) return;
        if (!ensureTestExecutionConfirmed()) return;
        editedCode = currentCode();
        int requestId = ++coverageSequence;
        coverageRunning = true;
        generationResult.setCoverageReport(CoverageReport.status(CoverageStatus.RUNNING));
        stepStatuses[3] = StepStatus.RUNNING;
        rebuildSteps();
        GenerationResult currentResult = generationResult;
        String candidate = editedCode;
        int analysisId = analysisSequence;
        GenerationResult taskResult = runtimeCandidate(currentResult, candidate);
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在运行覆盖率闭环", true) {
            private GenerationResult completed;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (!registerExecutionIndicator(indicator, this::current, false)) return;
                indicator.setIndeterminate(false);
                completed = new UnitTestGenerationService().runCoverageLoop(project, requestFile, taskResult,
                        candidate, () -> indicator.isCanceled() || !current(), (text, fraction) -> {
                            indicator.setText(text);
                            indicator.setFraction(Math.max(0d, Math.min(1d, fraction)));
                            CoverageReport display = taskResult.getCoverageReport().snapshotForDisplay();
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (current() && coverageRunning) {
                                    if (testCodeStepPanel != null) {
                                        testCodeStepPanel.updateCoverageReport(display);
                                    }
                                }
                            });
                        });
            }

            private boolean current() {
                return requestId == coverageSequence && isCurrentRequest(analysisId, requestFile, currentResult);
            }

            @Override
            public void onSuccess() {
                if (current()) applyCoverageResult(completed);
            }

            @Override
            public void onCancel() {
                if (!current()) return;
                if (completed == null) {
                    CoverageReport report = taskResult.getCoverageReport();
                    report.setStatus(CoverageStatus.CANCELLED);
                    report.setStopReason(CoverageStopReason.CANCELLED);
                    report.setMessage("覆盖率执行已取消。");
                    completed = taskResult;
                }
                applyCoverageResult(completed);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                if (!current()) return;
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                CoverageReport report = taskResult.getCoverageReport();
                report.setStatus(CoverageStatus.ENVIRONMENT_ERROR);
                report.setStopReason(CoverageStopReason.ENVIRONMENT_ERROR);
                report.setMessage(message);
                applyCoverageResult(taskResult);
            }
        }.queue();
    }

    private boolean ensureTestExecutionConfirmed() {
        if (!CoverageConfigManager.isExecutionConfirmed()) {
            int answer = Messages.showOkCancelDialog(project,
                    "UnitMind 将在本机隔离 JVM 中真实执行本次生成测试和对应的已有测试。"
                            + "测试代码仍可能访问网络、文件或外部服务，请确认当前代码适合执行。",
                    "UnitMind 测试执行安全提示",
                    "确认并运行",
                    "取消",
                    Messages.getWarningIcon());
            if (answer != Messages.OK) return false;
            CoverageConfigManager.setExecutionConfirmed(true);
        }
        return true;
    }

    private void retainRunnableTests() {
        if (dependencySetupRunning) return;
        if (generationResult == null || generationResult.getGeneratedTestFile() == null || coverageRunning || writeRunning) return;
        String originalCode = currentCode();
        if (originalCode == null || originalCode.isBlank()) {
            Messages.showWarningDialog(project, "测试代码为空，无法筛选。", "UnitMind"); return;
        }
        if (!ensureTestExecutionConfirmed()) return;
        editedCode = originalCode;
        int taskId = ++coverageSequence; int requestId = analysisSequence;
        PsiFile requestFile = currentPsiFile; GenerationResult currentResult = generationResult;
        GenerationResult candidate = runtimeCandidate(currentResult, originalCode);
        var previousSelection = currentResult.getRunnableTestSelectionReport();
        var priorIntent = new RunnableTestSelectionReport();
        priorIntent.inheritExclusions(previousSelection.getActiveExclusionOrigins());
        priorIntent.setSourceHash(previousSelection.getSourceHash());
        priorIntent.setOriginalCode(previousSelection.getOriginalCode());
        priorIntent.setArchivePath(previousSelection.getArchivePath());
        candidate.setRunnableTestSelectionReport(priorIntent);
        var running = new RunnableTestSelectionReport();
        running.inheritExclusions(priorIntent.getActiveExclusionOrigins());
        running.setSourceHash(currentSourceHash(requestFile));
        running.setOriginalCode(originalCode);
        running.setStatus(RunnableTestSelectionReport.Status.RUNNING);
        currentResult.setRunnableTestSelectionReport(running);
        coverageRunning = true; stepStatuses[3] = StepStatus.RUNNING; rebuildSteps();
        new Task.Backgroundable(project, "UnitMind 保留可运行用例并测覆盖率", true) {
            private GenerationResult completed;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                if (!registerExecutionIndicator(indicator, this::current, false)) return; indicator.setIndeterminate(false);
                completed = new UnitTestGenerationService().retainRunnableTests(project, requestFile, candidate, originalCode,
                        () -> indicator.isCanceled() || !current(), (text, fraction) -> {
                            indicator.setText(text); indicator.setFraction(Math.max(indicator.getFraction(), fraction));
                        });
            }
            private boolean current() {
                return taskId == coverageSequence && isCurrentRequest(requestId, requestFile, currentResult);
            }
            private void finish() {
                if (!current()) return;
                coverageRunning = false; coverageIndicator = null;
                applyRunnableSelectionResult(completed == null ? candidate : completed, originalCode, requestFile);
            }
            @Override public void onSuccess() { finish(); }
            @Override public void onCancel() {
                var selected = candidate.getRunnableTestSelectionReport();
                selected.setStatus(RunnableTestSelectionReport.Status.CANCELLED);
                selected.setMessage("筛选已取消，原代码保留。"); completed = candidate; finish();
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                var selected = candidate.getRunnableTestSelectionReport();
                selected.setStatus(RunnableTestSelectionReport.Status.BLOCKED);
                selected.setMessage("筛选未完成，原代码保留：" + error.getMessage()); completed = candidate; finish();
            }
        }.queue();
    }

    /** 任务身份已由调用方核验；同一次 EDT 更新同时发布文本及其验证、覆盖率报告。 */
    private void applyRunnableSelectionResult(GenerationResult completed, String originalCode, PsiFile requestFile) {
        var selected = completed.getRunnableTestSelectionReport();
        generationResult.setRunnableTestSelectionReport(selected);
        boolean unchanged = CodeFingerprint.sha256(originalCode).equals(CodeFingerprint.sha256(currentCode()))
                && selected.getSourceHash().equals(currentSourceHash(requestFile));
        if (selected.getStatus() == RunnableTestSelectionReport.Status.PASSED && unchanged) {
            generationResult.setFinalTestFileReport(new com.casc.unittestagent.model.FinalTestFileReport());
            generationResult.setGeneratedTestFile(completed.getGeneratedTestFile());
            generationResult.setValidationResult(completed.getValidationResult());
            generationResult.setRuntimeValidationReport(completed.getRuntimeValidationReport());
            generationResult.setCoverageReport(completed.getCoverageReport());
            editedCode = completed.getGeneratedTestFile().getContent();
            stepStatuses[3] = completed.getCoverageReport().getStatus() == CoverageStatus.PASSED
                    ? StepStatus.SUCCESS : StepStatus.WARNING;
        } else {
            if (selected.getStatus() == RunnableTestSelectionReport.Status.PASSED) {
                selected.setStatus(RunnableTestSelectionReport.Status.STALE);
                selected.setMessage("源码或测试代码已变更，本次筛选结果未应用；请重新筛选。");
                markValidationStale(currentCode());
            }
            stepStatuses[3] = StepStatus.WARNING;
        }
        rebuildSteps();
    }

    private void restoreBeforeRunnableSelection() {
        if (coverageRunning || writeRunning || generationResult == null) return;
        String original = generationResult.getRunnableTestSelectionReport().getOriginalCode();
        if (original.isBlank()) return;
        generationResult.getRunnableTestSelectionReport().releaseExclusions();
        editedCode = original; generationResult.getGeneratedTestFile().setContent(original);
        markValidationStale(original); rebuildSteps();
    }

    private void openBeforeRunnableSelection() {
        if (generationResult == null) return;
        String path = generationResult.getRunnableTestSelectionReport().getArchivePath();
        if (path.isBlank()) return;
        var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
        if (file != null) FileEditorManager.getInstance(project).openFile(file, true);
        else Messages.showWarningDialog(project, "本地备份文件不可用；仍可通过“恢复筛选前代码”恢复。", "UnitMind");
    }

    private void stopCoverageLoop() {
        if (writeRunning) {
            cancelWriteValidation();
            if (generationResult != null) {
                generationResult.setRuntimeValidationReport(ClassJUnitRepairReport.status(ClassJUnitStatus.CANCELLED));
                generationResult.getFinalTestFileReport().getMethodCoverage().setStatus(CoverageStatus.CANCELLED);
                generationResult.getFinalTestFileReport().getMethodCoverage().setMessage("写入验证已取消，未写入。");
            }
            stepStatuses[4] = StepStatus.WARNING;
            lastError = "写入验证已取消，未写入测试文件。";
            rebuildSteps();
            return;
        }
        if (coverageIndicator != null) {
            // A running task returns its last verified checkpoint through the normal callback.
            coverageIndicator.cancel();
        } else if (coverageRunning) {
            // A queued task has no indicator yet: revoke ownership before it can start work.
            coverageSequence++;
            coverageRunning = false;
            if (generationResult != null) {
                var coverage = generationResult.getCoverageReport();
                if (coverage.getStatus() == CoverageStatus.RUNNING) {
                    coverage.setStatus(CoverageStatus.CANCELLED);
                    coverage.setStopReason(CoverageStopReason.CANCELLED);
                    coverage.setMessage("覆盖率任务在启动前已取消。");
                }
                var selection = generationResult.getRunnableTestSelectionReport();
                if (selection.getStatus() == RunnableTestSelectionReport.Status.RUNNING) {
                    selection.setStatus(RunnableTestSelectionReport.Status.CANCELLED);
                    selection.setMessage("筛选在启动前已取消，原代码保留。");
                }
            }
            stepStatuses[3] = StepStatus.WARNING;
            rebuildSteps();
        }
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
        if (dependencySetupRunning) return;
        if (generationResult == null || generationResult.getGeneratedTestFile() == null || coverageRunning || writeRunning) return;
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
        int analysisId = analysisSequence;
        GenerationResult taskResult = runtimeCandidate(currentResult, candidate);
        // The stopped report is read only until the service replaces it with a fresh report.
        taskResult.setCoverageReport(stoppedReport);
        String userEvidence = evidence.strip();
        PsiFile requestFile = currentPsiFile;
        new Task.Backgroundable(project, "UnitMind 正在补充证据后继续修复", true) {
            private GenerationResult completed;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (!registerExecutionIndicator(indicator, this::current, false)) return;
                indicator.setIndeterminate(false);
                completed = new UnitTestGenerationService().resumeCoverageLoopWithEvidence(project, requestFile,
                        taskResult, candidate, userEvidence, () -> indicator.isCanceled() || !current(),
                        (text, fraction) -> {
                            indicator.setText(text);
                            indicator.setFraction(Math.max(0d, Math.min(1d, fraction)));
                            CoverageReport display = taskResult.getCoverageReport().snapshotForDisplay();
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (current() && coverageRunning) {
                                    if (testCodeStepPanel != null) {
                                        testCodeStepPanel.updateCoverageReport(display);
                                    }
                                }
                            });
                        });
            }

            private boolean current() {
                return requestId == coverageSequence && isCurrentRequest(analysisId, requestFile, currentResult);
            }

            @Override
            public void onSuccess() {
                if (current()) applyCoverageResult(completed);
            }

            @Override
            public void onCancel() {
                if (!current()) return;
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
                if (!current()) return;
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
            if (!completed.getFinalTestFileReport().getTargetPath().isBlank()) {
                completed.getFinalTestFileReport().markStale("覆盖率补测已更新预览，请重新验证最终合并文件。");
            }
            completed.getRuntimeValidationReport().setStatus(ClassJUnitStatus.STALE);
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
        if (dependencySetupRunning) return;
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
        new Task.Backgroundable(project, "UnitMind 正在重新校验测试代码", true) {
            private ValidationResult validation;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                validation = new UnitTestGenerationService().validateCode(
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
        if (dependencySetupRunning) return;
        if (coverageRunning || writeRunning || mockStrategyLlmRunning || generationStatus == GenerationStatus.RUNNING) return;
        if (generationResult == null || generationResult.getMethodModel() == null) {
            Messages.showWarningDialog(project, "请先完成方法分析。", "UnitMind");
            return;
        }
        editedCode = currentCode();
        generationStatus = GenerationStatus.RUNNING;
        generationResult.getRuntimeValidationReport().setStatus(ClassJUnitStatus.STALE);
        stepStatuses[3] = StepStatus.RUNNING;
        currentStepIndex = 3;
        rebuildSteps();
        String currentCode = editedCode;
        int requestId = analysisSequence;
        PsiFile requestFile = currentPsiFile;
        GenerationResult requestResult = generationResult;
        GenerationResult taskResult = runtimeCandidate(requestResult, currentCode);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                GenerationResult regenerated = new UnitTestGenerationService(() -> !isCurrentRequest(requestId, requestFile, requestResult)).regenerateWithLlmFeedback(
                        project, requestFile, taskResult, currentCode, feedback);
                queueCurrentRequestUpdate(requestId, requestFile, requestResult, () -> {
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
                    if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                queueCurrentRequestUpdate(requestId, requestFile, requestResult, () -> {
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
        return !disposed && requestId == analysisSequence && requestFile == currentPsiFile
                && requestResult == generationResult && !project.isDisposed();
    }

    void queueCurrentRequestUpdate(int requestId, PsiFile requestFile, GenerationResult requestResult, Runnable update) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isCurrentRequest(requestId, requestFile, requestResult)) update.run();
        });
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    /**
     * 编辑器是文本状态的视图；写入、校验、筛选和页面重建始终使用同一份文本。
     */
    private String currentCode() {
        return editedCode == null ? "" : editedCode;
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
            return "当前测试代码为规则兜底生成。部分普通场景仅验证调用不抛异常，请补充返回值、状态变化或交互断言；运行通过不代表业务预期已充分验证。";
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
