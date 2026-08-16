package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CompiledTestArtifact;
import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.coverage.CoverageProgressListener;
import com.casc.unittestagent.coverage.CoverageLoopPolicy;
import com.casc.unittestagent.coverage.CoverageRunResult;
import com.casc.unittestagent.coverage.JacocoCoverageExecutor;
import com.casc.unittestagent.coverage.JavaTestSourceInspector;
import com.casc.unittestagent.coverage.JavaBinaryName;
import com.casc.unittestagent.coverage.MockitoStaticMockSupport;
import com.casc.unittestagent.coverage.MockitoRuntimeDiagnosticEnricher;
import com.casc.unittestagent.coverage.ProjectTestEnvironment;
import com.casc.unittestagent.coverage.ProjectTestEnvironmentResolver;
import com.casc.unittestagent.coverage.ReflectiveAssertionNormalizer;
import com.casc.unittestagent.coverage.TestSandboxCompiler;
import com.casc.unittestagent.generator.GeneratedTestCodePostProcessor;
import com.casc.unittestagent.generator.CoverageTestClassStabilizer;
import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.generator.TestScenarioPlanner;
import com.casc.unittestagent.llm.DefaultLlmClientFactory;
import com.casc.unittestagent.llm.LlmClient;
import com.casc.unittestagent.llm.LlmClientFactory;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.llm.LlmResponseFormatException;
import com.casc.unittestagent.llm.LlmOutputTruncatedException;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoveragePhase;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.CoverageStopReason;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GenerationScope;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.TestCaseExecution;
import com.casc.unittestagent.model.TestExecutionSummary;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.util.PsiUtils;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.casc.unittestagent.validation.TestCodeValidator;
import com.casc.unittestagent.writer.TestFileWriter;
import com.github.javaparser.StaticJavaParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiCallExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * 编排一次单元测试生成流程。
 * 该服务串联源码分析、测试数据补全、场景规划、Mock 规划和测试文件生成，并把异常转为可展示告警。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class UnitTestGenerationService {
    /** 日志对象，用于记录生成链路关键数据和异常堆栈。 */
    private static final Logger LOG = Logger.getInstance(UnitTestGenerationService.class);
    /** 发送给大模型的调用点数量上限，避免引用过多导致 Prompt 失控。 */
    private static final int MAX_USAGE_COUNT = 20;
    /** 瞬时网络失败单独重试，不挤占真正的覆盖率探索轮次。 */
    private static final int MAX_COVERAGE_TRANSPORT_RETRIES = 2;
    /** JSON/完整源码格式错误单独重试一次。 */
    private static final int MAX_COVERAGE_FORMAT_RETRIES = 1;
    /** 后处理后代码未变时使用独立强化重试，不消耗语义修复预算。 */
    private static final int MAX_COVERAGE_UNCHANGED_RETRIES = 2;
    private static final String COVERAGE_ENGINE_REVISION = "state-v5.1-runtime-patch-routing";
    /** 历史候选回退先向模型反馈循环证据，只在同一转换重复时停止。 */
    private static final int MAX_HISTORICAL_REPAIR_RETRIES = 2;
    private static final Pattern MOCKITO_USAGE = Pattern.compile(
            "(?s)(?:import\\s+(?:static\\s+)?org\\.mockito|@Mock\\b|\\bMockito\\.|"
                    + "\\b(?:mock|spy|when|verify|doReturn|doThrow|doNothing)\\s*\\()");
    /** 源码分析器，负责将 PSI 文本转换为方法模型。 */
    private final JavaSourceAnalyzer javaSourceAnalyzer = new JavaSourceAnalyzer();
    /** 测试数据补全器，负责为方法参数填充候选值。 */
    private final TestDataGenerator testDataGenerator = new TestDataGenerator();
    /** 测试场景规划器，负责根据模型生成用例类型。 */
    private final TestScenarioPlanner testScenarioPlanner = new TestScenarioPlanner();
    /** Mock 策略规划器，负责根据依赖调用生成规则描述。 */
    private final MockStrategyPlanner mockStrategyPlanner = new MockStrategyPlanner();
    /** 测试源码生成器，负责输出最终 JUnit 与 Mockito 代码。 */
    private final JUnitMockitoTestGenerator testGenerator = new JUnitMockitoTestGenerator();
    /** 生成代码后处理器，负责清理无效代码并提示可编译性风险。 */
    private final GeneratedTestCodePostProcessor generatedCodePostProcessor = new GeneratedTestCodePostProcessor();
    /** 纯覆盖率补测只接纳 AST 新增成员，避免全量重写破坏已通过脚手架。 */
    private final CoverageTestClassStabilizer coverageTestClassStabilizer = new CoverageTestClassStabilizer();
    /** PSI 真实符号快照构建器。 */
    private final ProjectSymbolSnapshotBuilder symbolSnapshotBuilder;
    /** 项目编译验证器。 */
    private final TestCodeValidator testCodeValidator;
    /** 大模型客户端工厂。 */
    private final LlmClientFactory llmClientFactory;
    private final ProjectTestEnvironmentResolver coverageEnvironmentResolver = new ProjectTestEnvironmentResolver();
    private final TestSandboxCompiler sandboxCompiler = new TestSandboxCompiler();
    private final JacocoCoverageExecutor coverageExecutor = new JacocoCoverageExecutor();
    private final TestFileWriter coverageFileResolver = new TestFileWriter();

    public UnitTestGenerationService() {
        this(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), new DefaultLlmClientFactory());
    }

    public UnitTestGenerationService(ProjectSymbolSnapshotBuilder symbolSnapshotBuilder,
                                     TestCodeValidator testCodeValidator,
                                     LlmClientFactory llmClientFactory) {
        this.symbolSnapshotBuilder = symbolSnapshotBuilder;
        this.testCodeValidator = testCodeValidator;
        this.llmClientFactory = llmClientFactory;
    }

    /**
     * 执行单元测试源码生成。
     * 方法把分析模型和场景转换为一个可预览、可写入磁盘的测试文件模型。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param project 当前 IntelliJ 项目上下文，用于消息提示、写命令和对话框归属
     * @param psiFile 当前 PSI 文件，通常是用户正在编辑的 Java 源文件
     * @param editor 当前编辑器，用于读取光标位置并定位优先分析的方法
     * @return 生成流程结果或生成的测试文件模型
     */
    public GenerationResult generate(Project project, PsiFile psiFile, Editor editor) {
        GenerationResult result = analyzeMethod(project, psiFile, editor);
        if (result.getMethodModel() == null) {
            return result;
        }
        return generateArtifacts(project, psiFile, result);
    }

    /**
     * 只执行方法概要与参数约束所需的静态分析。
     * 该入口不读取大模型配置、不发起 HTTP 请求，适合右键打开 UnitMind 后立即执行。
     */
    public GenerationResult analyzeMethod(Project project, PsiFile psiFile, Editor editor) {
        GenerationResult result = new GenerationResult();
        try {
            LOG.info("UnitMind: start static method analysis");
            Computable<StaticAnalysisContext> analysis =
                    () -> buildStaticAnalysisContext(project, psiFile, editor);
            StaticAnalysisContext staticContext = project == null
                    ? ApplicationManager.getApplication().runReadAction(analysis)
                    : DumbService.getInstance(project).runReadActionInSmartMode(analysis);
            MethodModel methodModel = staticContext.getMethodModel();
            testDataGenerator.enrich(methodModel);
            result.setMethodModel(methodModel);
            result.setSymbolSnapshot(staticContext.getSymbolSnapshot());
        } catch (Exception ex) {
            LOG.warn("UnitMind: static method analysis failed, errorType=" + ex.getClass().getSimpleName());
            result.getWarnings().add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return result;
    }

    /**
     * 基于已完成的静态分析继续生成测试场景、Mock 策略和测试代码。
     * 大模型调用只允许发生在该阶段，不能阻塞方法概要和参数约束分析。
     */
    public GenerationResult generateArtifacts(Project project, PsiFile psiFile, GenerationResult result) {
        result = planScenarios(result);
        result = planMockStrategy(result);
        return generateTestCode(project, psiFile, result);
    }

    /**
     * 基于静态分析结果规划测试场景，不调用大模型。
     */
    public GenerationResult planScenarios(GenerationResult result) {
        if (result == null) {
            result = new GenerationResult();
        }
        try {
            MethodModel methodModel = result.getMethodModel();
            if (methodModel == null) {
                throw new IllegalStateException("请先完成方法概要分析。");
            }
            List<TestScenario> scenarios = testScenarioPlanner.plan(methodModel);
            LOG.info("UnitMind: scenario count = " + scenarios.size());
            result.setScenarios(scenarios);
        } catch (Exception ex) {
            LOG.warn("UnitMind: plan scenarios failed, errorType=" + ex.getClass().getSimpleName());
            result.getWarnings().add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return result;
    }

    /**
     * 基于静态分析结果和规则场景规划 Mock 策略，不调用大模型。
     */
    public GenerationResult planMockStrategy(GenerationResult result) {
        if (result == null) {
            result = new GenerationResult();
        }
        try {
            MethodModel methodModel = result.getMethodModel();
            if (methodModel == null) {
                throw new IllegalStateException("请先完成方法概要分析。");
            }
            if (result.getScenarios().isEmpty()) {
                planScenarios(result);
            }
            MockStrategy ruleMockStrategy = mockStrategyPlanner.plan(methodModel, executableScenarios(result.getScenarios()));
            int mockCount = ruleMockStrategy.getMockFields() == null ? 0 : ruleMockStrategy.getMockFields().size();
            LOG.info("UnitMind: mock count = " + mockCount);
            result.setMockStrategy(ruleMockStrategy);
        } catch (Exception ex) {
            LOG.warn("UnitMind: plan mock strategy failed, errorType=" + ex.getClass().getSimpleName());
            result.getWarnings().add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return result;
    }

    /**
     * 手动调用大模型生成候选 Mock 策略，不覆盖静态 Mock 策略。
     */
    public GenerationResult generateLlmMockStrategy(GenerationResult result) throws Exception {
        if (result == null || result.getMethodModel() == null) {
            throw new IllegalStateException("请先完成方法概要分析。");
        }
        if (result.getScenarios().isEmpty()) {
            planScenarios(result);
        }
        if (result.getMockStrategy() == null) {
            planMockStrategy(result);
        }
        LlmConfig llmConfig = LlmConfigManager.load();
        if (!llmConfig.isEnabled()) {
            throw new IllegalStateException("大模型未启用，请先在 UnitMind 设置中启用并测试连接。");
        }
        MethodModel methodModel = result.getMethodModel();
        LlmGenerationRequest llmRequest = ReadAction.compute(() ->
                buildLlmRequest(null, methodModel, executableScenarios(result.getScenarios()),
                        methodModel.getMethodBodyText(), result.getSymbolSnapshot()));
        LlmClient llmClient = llmClientFactory.create(llmConfig);
        long llmStarted = System.currentTimeMillis();
        LlmGenerationResponse llmResponse = llmClient.generateMockStrategy(llmRequest);
        LOG.info("UnitMind LLM: mock strategy completed, durationMs="
                + (System.currentTimeMillis() - llmStarted) + ", strategyCount="
                + (llmResponse.getMockStrategy() == null || llmResponse.getMockStrategy().getStrategyItems() == null
                ? 0 : llmResponse.getMockStrategy().getStrategyItems().size()));
        result.setLlmMockStrategy(llmResponse.getMockStrategy());
        addWarnings(result, llmResponse.getWarnings());
        return result;
    }

    /**
     * 基于静态场景和静态 Mock 策略生成测试代码。只有这个阶段允许调用大模型。
     */
    public GenerationResult generateTestCode(Project project, PsiFile psiFile, GenerationResult result) {
        return generateTestCodeInternal(project, psiFile, result, true);
    }

    /** 保留给调用方的快速候选入口；当前整类主流程使用带独立编译门禁的 generateTestCode。 */
    public GenerationResult generateTestCodeCandidate(Project project, PsiFile psiFile, GenerationResult result) {
        return generateTestCodeInternal(project, psiFile, result, false);
    }

    private GenerationResult generateTestCodeInternal(Project project,
                                                        PsiFile psiFile,
                                                        GenerationResult result,
                                                        boolean validateCandidate) {
        if (result == null) {
            result = new GenerationResult();
        }
        try {
            LOG.info("UnitMind: start generate test code");
            MethodModel methodModel = result.getMethodModel();
            if (methodModel == null) {
                throw new IllegalStateException("请先完成方法概要分析。");
            }
            if (result.getScenarios().isEmpty()) {
                planScenarios(result);
            }
            if (result.getMockStrategy() == null) {
                planMockStrategy(result);
            }
            List<TestScenario> scenarios = executableScenarios(result.getScenarios());
            MockStrategy selectedMockStrategy = selectedMockStrategy(result);
            GeneratedTestFile ruleGeneratedTestFile = ensureGeneratedFile(methodModel,
                    testGenerator.generate(methodModel, scenarios, selectedMockStrategy));

            GeneratedTestFile generatedTestFile = ruleGeneratedTestFile;
            LlmConfig llmConfig = LlmConfigManager.load();
            LOG.info("UnitMind LLM: enabled=" + llmConfig.isEnabled());
            if (llmConfig.isEnabled()) {
                try {
                    ensureSymbolSnapshot(project, psiFile, result);
                    ProjectSymbolSnapshot symbolSnapshot = result.getSymbolSnapshot();
                    LlmGenerationRequest llmRequest = ReadAction.compute(
                            () -> buildLlmRequest(project, methodModel, scenarios, methodModel.getMethodBodyText(),
                                    symbolSnapshot));
                    applyGenerationScope(llmRequest, result);
                    configureMockitoSupport(llmRequest, project, psiFile);
                    applySelectedMockPolicy(llmRequest, selectedMockStrategy);
                    llmRequest.setUserInstruction("测试代码必须使用当前用户选择的 Mock 策略，不要重新选择字段级 Mock 或局部 mock。\n"
                            + selectedMockStrategySummary(selectedMockStrategy));
                    LlmClient llmClient = llmClientFactory.create(llmConfig);
                    long generationStarted = System.currentTimeMillis();
                    LlmGenerationResponse llmResponse = llmClient.generateUnitTest(llmRequest);
                    LOG.info("UnitMind LLM: generation completed, durationMs="
                            + (System.currentTimeMillis() - generationStarted));
                    GeneratedTestCodePostProcessor.ProcessedCode processedCode =
                            generatedCodePostProcessor.process(llmResponse.getTestCode(), methodModel);
                    generatedTestFile = copyWithContent(ruleGeneratedTestFile, processedCode.getCode());
                    result.setGeneratedByLlm(true);
                    result.setRuleBasedFallback(false);
                    result.setLlmMessage("大模型生成完成。");
                    addWarnings(result, llmResponse.getWarnings());
                    addWarnings(result, processedCode.getWarnings());
                } catch (Exception llmException) {
                    LOG.warn("UnitMind LLM: generation failed, errorType="
                            + llmException.getClass().getSimpleName());
                    String message = llmException.getMessage() == null ? llmException.getClass().getSimpleName() : llmException.getMessage();
                    result.getWarnings().add("大模型生成失败：" + message);
                    result.setGeneratedByLlm(false);
                    result.setRuleBasedFallback(true);
                    result.setLlmMessage("大模型生成失败：" + message);
                    if (!llmConfig.isFallbackToRuleBased()) {
                        result.getWarnings().add("已禁用规则兜底，但测试代码页不能空白，当前显示最小规则草稿。");
                        generatedTestFile = minimalFallbackFile(methodModel);
                    }
                }
            } else {
                result.setGeneratedByLlm(false);
                result.setRuleBasedFallback(true);
                result.setLlmMessage("当前测试代码为规则兜底生成，大模型生成失败或未启用。");
            }
            generatedTestFile = ensureGeneratedFile(methodModel, generatedTestFile);
            GeneratedTestCodePostProcessor.ProcessedCode processedCode =
                    generatedCodePostProcessor.process(generatedTestFile.getContent(), methodModel);
            generatedTestFile = copyWithContent(generatedTestFile, processedCode.getCode());
            addWarnings(result, processedCode.getWarnings());
            if (validateCandidate) {
                generatedTestFile = validateAndRepair(project, psiFile, result, generatedTestFile, llmConfig);
            } else {
                result.setValidationResult(ValidationResult.status(ValidationStatus.NOT_RUN));
            }
            result.setMethodModel(methodModel);
            result.setScenarios(result.getScenarios());
            result.setMockStrategy(result.getMockStrategy());
            result.setGeneratedTestFile(generatedTestFile);
        } catch (Exception ex) {
            LOG.warn("UnitMind: generate test code failed, errorType=" + ex.getClass().getSimpleName());
            result.getWarnings().add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return result;
    }

    /** 返回当前方法的确定性规则候选，供整类 AST 聚合冲突时降级。 */
    public GeneratedTestFile generateRuleBasedTestFile(GenerationResult result) {
        if (result == null || result.getMethodModel() == null) {
            throw new IllegalStateException("缺少被测方法。");
        }
        if (result.getScenarios().isEmpty()) planScenarios(result);
        if (result.getMockStrategy() == null) planMockStrategy(result);
        return ensureGeneratedFile(result.getMethodModel(), testGenerator.generate(
                result.getMethodModel(), executableScenarios(result.getScenarios()), selectedMockStrategy(result)));
    }

    private void applyGenerationScope(LlmGenerationRequest request, GenerationResult result) {
        applyGenerationScope(request, result, result == null ? null : result.getMethodModel());
    }

    private void applyGenerationScope(LlmGenerationRequest request,
                                      GenerationResult result,
                                      MethodModel requestMethod) {
        if (request == null || result == null) return;
        request.setGenerationScope(result.getGenerationScope());
        request.setMethodId(requestMethod == null ? "" : requestMethod.getMethodId());
        request.setClassMethodSignatures(result.getClassMethodSignatures());
        request.setSharedFixtureSummary(result.getSharedFixtureSummary());
    }

    private MockStrategy selectedMockStrategy(GenerationResult result) {
        if (result.isUseLlmMockStrategy() && result.getLlmMockStrategy() != null) {
            return result.getLlmMockStrategy();
        }
        return result.getMockStrategy();
    }

    private void addWarnings(GenerationResult result, List<String> warnings) {
        if (result == null || warnings == null) {
            return;
        }
        for (String warning : warnings) {
            if (warning != null && !warning.isBlank() && !result.getWarnings().contains(warning)) {
                result.getWarnings().add(warning);
            }
        }
    }

    /**
     * 验证候选测试代码；供生成闭环、手工重新校验和写入门禁复用。
     */
    public ValidationResult validateCode(Project project,
                                         PsiFile psiFile,
                                         GeneratedTestFile generatedFile,
                                         String code) {
        ValidationResult validation = testCodeValidator.validate(project, psiFile, generatedFile, code);
        LOG.info("UnitMind validation: status=" + validation.getStatus()
                + ", errors=" + validation.getDiagnostics().size()
                + ", durationMs=" + validation.getDurationMillis());
        if (validation.getStatus() != ValidationStatus.PASSED) {
            logValidationDiagnostics("validation", validation.getDiagnostics());
        }
        return validation;
    }

    GeneratedTestFile validateAndRepair(Project project,
                                        PsiFile psiFile,
                                        GenerationResult result,
                                        GeneratedTestFile initialFile,
                                        LlmConfig llmConfig) {
        GeneratedTestFile candidate = initialFile;
        int repairAttempts = 0;
        Set<String> seenFailures = new HashSet<>();
        GeneratedTestFile repairParent = null;
        ValidationResult repairParentValidation = null;
        String rejectedRepairFeedback = "";
        boolean allowRepair = llmConfig != null && llmConfig.isEnabled() && result.isGeneratedByLlm();
        LlmClient repairClient = allowRepair ? llmClientFactory.create(llmConfig) : null;

        while (true) {
            ValidationResult validation = validateCode(project, psiFile, candidate, candidate.getContent());
            validation.setRepairAttempts(repairAttempts);
            result.setValidationResult(validation);
            if (repairParent != null && repairParentValidation != null) {
                if (isCompileRegression(repairParentValidation, validation)) {
                    String regression = compileRegressionSummary(repairParentValidation, validation);
                    LOG.warn("UnitMind validation: rejected regressive compile repair, " + regression);
                    result.getWarnings().add("第 " + repairAttempts + " 轮编译修复使诊断恶化，已回滚：" + regression);
                    candidate = repairParent;
                    validation = repairParentValidation;
                    validation.setRepairAttempts(repairAttempts);
                    result.setValidationResult(validation);
                    rejectedRepairFeedback = "上一修复候选已被质量门禁回滚：" + regression
                            + "。必须从 currentTestCode 重新做更小修改，不得重复该候选。";
                } else {
                    rejectedRepairFeedback = "";
                }
                repairParent = null;
                repairParentValidation = null;
            }
            if (validation.getStatus() == ValidationStatus.PASSED) {
                result.setLlmMessage(repairAttempts == 0
                        ? result.getLlmMessage()
                        : "大模型生成完成，并根据编译诊断自动修复 " + repairAttempts + " 轮。");
                return candidate;
            }
            if (!allowRepair
                    || validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                    || !validation.hasRepairableErrors()
                    || repairAttempts >= llmConfig.getMaxCompileRepairRounds()) {
                addValidationWarning(result, validation);
                return candidate;
            }

            String failureFingerprint = validation.getCodeHash() + "|"
                    + diagnosticFingerprint(validation.getDiagnostics()) + "|" + rejectedRepairFeedback;
            if (!seenFailures.add(failureFingerprint)) {
                result.getWarnings().add("编译修复未取得进展，已提前停止重复请求。");
                addValidationWarning(result, validation);
                return candidate;
            }

            try {
                repairAttempts++;
                GenerationResult repairContext = repairContext(result, validation, candidate.getContent());
                MethodModel methodModel = repairContext.getMethodModel();
                ProjectSymbolSnapshot snapshot = repairContext.getSymbolSnapshot();
                LlmGenerationRequest repairRequest = ReadAction.compute(() ->
                        buildLlmRequest(project, methodModel, executableScenarios(repairContext.getScenarios()),
                                methodModel.getMethodBodyText(), snapshot));
                applyGenerationScope(repairRequest, result, methodModel);
                configureMockitoSupport(repairRequest, project, psiFile);
                applySelectedMockPolicy(repairRequest, selectedMockStrategy(repairContext));
                repairRequest.setExistingTestCodeSummary(candidate.getContent());
                repairRequest.setCompilerDiagnostics(validation.getDiagnostics());
                repairRequest.setRepairAttempt(repairAttempts);
                repairRequest.setUserInstruction("只修复编译错误，保持当前用户选择的 Mock 策略。\n"
                        + rejectedRepairFeedback + (rejectedRepairFeedback.isBlank() ? "" : "\n")
                        + affectedMethodSummary(result, validation, candidate.getContent())
                        + selectedMockStrategySummary(selectedMockStrategy(repairContext)));
                LOG.info("UnitMind validation: repair attempt=" + repairAttempts
                        + ", diagnosticCount=" + validation.getDiagnostics().size());
                LlmGenerationResponse repaired = repairClient.repairUnitTest(repairRequest);
                GeneratedTestCodePostProcessor.ProcessedCode processed =
                        generatedCodePostProcessor.process(repaired.getTestCode(), methodModel);
                String repairedCode = processed.getCode();
                if (result instanceof ClassGenerationResult) {
                    repairedCode = coverageTestClassStabilizer.preserveTestsDuringRepair(
                            candidate.getContent(), repairedCode);
                }
                LOG.info("UnitMind validation: repairResponse diagnosis="
                        + safeLogValue(repaired.getDiagnosisRootCauseCategory())
                        + ", evidenceCount=" + repaired.getDiagnosisEvidence().size()
                        + ", editTargets=" + repaired.getEdits().stream()
                        .map(edit -> safeLogValue(edit.getTargetMethod())).toList()
                        + ", semanticEdits="
                        + safeLogValue(semanticEditSummary(candidate.getContent(), repairedCode)));
                repairParent = candidate;
                repairParentValidation = validation;
                candidate = copyWithContent(candidate, repairedCode);
                addWarnings(result, repaired.getWarnings());
                addWarnings(result, processed.getWarnings());
            } catch (Exception ex) {
                result.getWarnings().add("自动编译修复失败：" + safeMessage(ex));
                ValidationResult lastValidation = result.getValidationResult();
                lastValidation.setRepairAttempts(repairAttempts);
                return candidate;
            }
        }
    }

    static boolean isCompileRegression(ValidationResult parent, ValidationResult candidate) {
        if (parent == null || candidate == null) return false;
        if (candidate.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                && parent.getStatus() != ValidationStatus.ENVIRONMENT_ERROR) return true;
        return isCompileDiagnosticRegression(parent.getDiagnostics(), candidate.getDiagnostics());
    }

    static boolean isCompileDiagnosticRegression(List<CompileDiagnostic> parent,
                                                 List<CompileDiagnostic> candidate) {
        List<CompileDiagnostic> before = parent == null ? List.of() : parent;
        List<CompileDiagnostic> after = candidate == null ? List.of() : candidate;
        if (after.size() > before.size()) return true;
        long beforeNonRepairable = before.stream().filter(item -> !item.isRepairable()).count();
        long afterNonRepairable = after.stream().filter(item -> !item.isRepairable()).count();
        return afterNonRepairable > beforeNonRepairable;
    }

    private static String compileRegressionSummary(ValidationResult parent, ValidationResult candidate) {
        return compileRegressionSummary(parent == null ? List.of() : parent.getDiagnostics(),
                candidate == null ? List.of() : candidate.getDiagnostics());
    }

    private static String compileRegressionSummary(List<CompileDiagnostic> parent,
                                                   List<CompileDiagnostic> candidate) {
        Set<String> beforeCodes = diagnosticCodes(parent);
        Set<String> afterCodes = diagnosticCodes(candidate);
        Set<String> introduced = new LinkedHashSet<>(afterCodes);
        introduced.removeAll(beforeCodes);
        Set<String> unresolved = new LinkedHashSet<>(beforeCodes);
        unresolved.retainAll(afterCodes);
        return "errors " + (parent == null ? 0 : parent.size()) + " -> "
                + (candidate == null ? 0 : candidate.size())
                + ", introduced=" + introduced + ", unresolved=" + unresolved;
    }

    private static Set<String> diagnosticCodes(List<CompileDiagnostic> diagnostics) {
        Set<String> codes = new LinkedHashSet<>();
        if (diagnostics == null) return codes;
        for (CompileDiagnostic diagnostic : diagnostics) {
            if (diagnostic != null) codes.add(diagnostic.getCode());
        }
        return codes;
    }

    /** 整类修复时根据编译诊断行选择对应的方法场景和 PSI 符号快照。 */
    private GenerationResult repairContext(GenerationResult result,
                                           ValidationResult validation,
                                           String currentCode) {
        if (!(result instanceof ClassGenerationResult) || validation == null) return result;
        ClassGenerationResult classResult = (ClassGenerationResult) result;
        for (CompileDiagnostic diagnostic : validation.getDiagnostics()) {
            if (diagnostic.getLine() <= 0) continue;
            MethodGenerationItem owner = methodOwnerAtLine(classResult, diagnostic.getLine(), currentCode);
            if (owner != null && owner.getResult() != null) return owner.getResult();
        }
        for (MethodGenerationItem item : classResult.getMethodItems()) {
            if (item.getResult() != null && item.getResult().getMethodModel() != null) return item.getResult();
        }
        return result;
    }

    private String affectedMethodSummary(GenerationResult result,
                                         ValidationResult validation,
                                         String currentCode) {
        if (!(result instanceof ClassGenerationResult) || validation == null) return "";
        ClassGenerationResult classResult = (ClassGenerationResult) result;
        Set<String> affected = new LinkedHashSet<>();
        for (CompileDiagnostic diagnostic : validation.getDiagnostics()) {
            MethodGenerationItem owner = methodOwnerAtLine(classResult, diagnostic.getLine(), currentCode);
            if (owner != null) affected.add(owner.getMethodId());
        }
        if (affected.isEmpty()) {
            return "当前是整类测试修复，必须保留其他方法已生成的测试。\n";
        }
        return "编译诊断涉及的方法为 " + String.join(", ", affected)
                + "。必须返回完整测试类并保留其他方法已通过的测试。\n";
    }

    private boolean containsGeneratedLine(MethodGenerationItem item, long line) {
        return item != null && line > 0 && item.getGeneratedStartLine() > 0
                && item.getGeneratedStartLine() <= line && line <= item.getGeneratedEndLine();
    }

    /**
     * 修复后的整类代码行号可能已改变，优先使用聚合器植入并要求 LLM 保留的 methodId 标记。
     * 标记被外部模型删除时，再回退到首轮 AST 记录的行范围。
     */
    private MethodGenerationItem methodOwnerAtLine(ClassGenerationResult result, long line, String code) {
        String markerId = methodMarkerAtLine(code, line);
        if (!markerId.isBlank()) {
            for (MethodGenerationItem item : result.getMethodItems()) {
                if (markerId.equals(item.getMethodId())) return item;
            }
        }
        for (MethodGenerationItem item : result.getMethodItems()) {
            if (containsGeneratedLine(item, line)) return item;
        }
        return null;
    }

    private String methodMarkerAtLine(String code, long diagnosticLine) {
        if (code == null || code.isBlank() || diagnosticLine <= 0) return "";
        String[] lines = code.split("\\R", -1);
        int limit = (int) Math.min(lines.length, diagnosticLine);
        String owner = "";
        for (int index = 0; index < limit; index++) {
            int marker = lines[index].indexOf("UnitMind method:");
            if (marker >= 0) owner = lines[index].substring(marker + "UnitMind method:".length()).trim();
        }
        return owner;
    }

    private void ensureSymbolSnapshot(Project project, PsiFile psiFile, GenerationResult result) {
        if (result == null || result.getMethodModel() == null) {
            return;
        }
        if (result.getSymbolSnapshot() != null && result.getSymbolSnapshot().isUsable()) {
            return;
        }
        Computable<ProjectSymbolSnapshot> buildSnapshot =
                () -> symbolSnapshotBuilder.build(project, psiFile, null, result.getMethodModel());
        ProjectSymbolSnapshot snapshot = project == null
                ? ApplicationManager.getApplication().runReadAction(buildSnapshot)
                : DumbService.getInstance(project).runReadActionInSmartMode(buildSnapshot);
        result.setSymbolSnapshot(snapshot);
    }

    private void addValidationWarning(GenerationResult result, ValidationResult validation) {
        if (validation == null || validation.getStatus() == ValidationStatus.PASSED) {
            return;
        }
        String prefix = validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                ? "编译环境校验失败：" : "生成代码编译未通过：";
        String detail = validation.getDiagnostics().isEmpty()
                ? validation.getStatus().name()
                : validation.getDiagnostics().get(0).getCode() + " - "
                + validation.getDiagnostics().get(0).getMessage();
        String warning = prefix + detail;
        if (!result.getWarnings().contains(warning)) {
            result.getWarnings().add(warning);
        }
    }

    private String diagnosticFingerprint(List<CompileDiagnostic> diagnostics) {
        StringBuilder builder = new StringBuilder();
        for (CompileDiagnostic diagnostic : diagnostics) {
            builder.append(diagnostic.getCode()).append('|')
                    .append(diagnostic.getSourceOrigin()).append('|')
                    .append(diagnostic.getLine()).append('|')
                    .append(diagnostic.getMessage()).append('\n');
        }
        return CodeFingerprint.sha256(builder.toString());
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String selectedMockStrategySummary(MockStrategy strategy) {
        if (strategy == null || strategy.getStrategyItems() == null || strategy.getStrategyItems().isEmpty()) {
            return "当前选择的 Mock 策略为空；禁止为了使用 Mockito 而新建 mock、spy 或 verify。";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < strategy.getStrategyItems().size(); i++) {
            if (i >= 30) {
                builder.append("- 其余策略省略。\n");
                break;
            }
            MockStrategyItem item = strategy.getStrategyItems().get(i);
            builder.append("- enabled=").append(item.isGenerate())
                    .append(", dependencyType=").append(item.getDependencyType())
                    .append(", target=").append(item.getObjectName())
                    .append(", method=").append(item.getMethodName())
                    .append(", source=").append(item.getSource())
                    .append(", mockitoStrategy=").append(item.getMockitoStrategy())
                    .append(", note=").append(item.getRemark())
                    .append("\n");
        }
        return builder.toString();
    }

    private void applySelectedMockPolicy(LlmGenerationRequest request, MockStrategy strategy) {
        if (request == null) return;
        boolean enabledMockito = false;
        if (strategy != null && strategy.getStrategyItems() != null) {
            for (MockStrategyItem item : strategy.getStrategyItems()) {
                if (item == null || !item.isGenerate()) continue;
                String value = item.getMockitoStrategy() == null
                        ? "" : item.getMockitoStrategy().toLowerCase(Locale.ROOT);
                if (value.contains("mock") || value.contains("spy") || value.contains("verify")) {
                    enabledMockito = true;
                    break;
                }
            }
        }
        request.setMockUsagePolicy(enabledMockito ? "SELECTED_ONLY" : "FORBIDDEN");
    }

    private String mockPolicyForExistingCode(String code) {
        String value = code == null ? "" : code;
        boolean usesMockito = MOCKITO_USAGE.matcher(value).find();
        return usesMockito ? "PRESERVE_EXISTING" : "FORBIDDEN";
    }

    private List<TestScenario> executableScenarios(List<TestScenario> scenarios) {
        List<TestScenario> executable = new ArrayList<>();
        for (TestScenario scenario : scenarios) {
            if (scenario.getType() == null || scenario.getType().endsWith("_REASON")) {
                continue;
            }
            executable.add(scenario);
        }
        return executable;
    }

    /**
     * 读取 PSI 并完成静态方法模型构建。
     */
    private StaticAnalysisContext buildStaticAnalysisContext(Project project, PsiFile psiFile, Editor editor) {
        String sourceText = PsiUtils.getSourceText(psiFile);
        String preferredMethodName = PsiUtils.getCurrentMethodName(psiFile, editor);
        int preferredSourceLine = -1;
        if (editor != null && editor.getDocument().getLineCount() > 0) {
            int caretOffset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), editor.getDocument().getTextLength()));
            preferredSourceLine = editor.getDocument().getLineNumber(caretOffset) + 1;
        }

        MethodModel methodModel;
        try {
            methodModel = javaSourceAnalyzer.analyze(sourceText, preferredMethodName, preferredSourceLine);
        } catch (Exception parseException) {
            LOG.warn("UnitMind: JavaParser analysis failed, use PSI fallback, errorType="
                    + parseException.getClass().getSimpleName());
            methodModel = buildPsiFallbackModel(psiFile, editor, preferredMethodName);
        }
        enrichUsagesFromPsi(project, psiFile, editor, preferredMethodName, methodModel);
        ProjectSymbolSnapshot snapshot = symbolSnapshotBuilder.build(project, psiFile, editor, methodModel);
        return new StaticAnalysisContext(methodModel, snapshot);
    }

    /**
     * 基于当前结果和用户补充要求再次调用大模型。
     */
    public GenerationResult regenerateWithLlmFeedback(Project project,
                                                      PsiFile psiFile,
                                                      GenerationResult result,
                                                      String currentCode,
                                                      String feedback) throws Exception {
        if (result == null || result.getMethodModel() == null) {
            throw new IllegalStateException("请先完成方法分析。");
        }
        LlmConfig llmConfig = LlmConfigManager.load();
        if (!llmConfig.isEnabled()) {
            throw new IllegalStateException("大模型未启用，请先在 UnitMind 设置中启用并测试连接。");
        }
        MethodModel methodModel = result.getMethodModel();
        ensureSymbolSnapshot(project, psiFile, result);
        ProjectSymbolSnapshot snapshot = result.getSymbolSnapshot();
        LlmGenerationRequest llmRequest = ReadAction.compute(() ->
                buildLlmRequest(project, methodModel, result.getScenarios(), methodModel.getMethodBodyText(), snapshot));
        applyGenerationScope(llmRequest, result);
        configureMockitoSupport(llmRequest, project, psiFile);
        applySelectedMockPolicy(llmRequest, selectedMockStrategy(result));
        llmRequest.setExistingTestCodeSummary(currentCode);
        llmRequest.setUserInstruction(feedback);
        try {
            LlmClient llmClient = llmClientFactory.create(llmConfig);
            long regenerationStarted = System.currentTimeMillis();
            LlmGenerationResponse llmResponse = llmClient.generateUnitTest(llmRequest);
            LOG.info("UnitMind LLM: regeneration completed, durationMs="
                    + (System.currentTimeMillis() - regenerationStarted));
            if (llmResponse.getMockStrategy() != null
                    && llmResponse.getMockStrategy().getStrategyItems() != null
                    && !llmResponse.getMockStrategy().getStrategyItems().isEmpty()) {
                result.setMockStrategy(llmResponse.getMockStrategy());
            }
            GeneratedTestCodePostProcessor.ProcessedCode processedCode =
                    generatedCodePostProcessor.process(llmResponse.getTestCode(), methodModel);
            GeneratedTestFile regenerated = copyWithContent(result.getGeneratedTestFile(), processedCode.getCode());
            result.setGeneratedByLlm(true);
            result.setRuleBasedFallback(false);
            result.setLlmMessage("大模型重新生成完成。");
            addWarnings(result, llmResponse.getWarnings());
            addWarnings(result, processedCode.getWarnings());
            result.setGeneratedTestFile(validateAndRepair(project, psiFile, result, regenerated, llmConfig));
            return result;
        } catch (Exception ex) {
            LOG.warn("UnitMind LLM: regenerate failed, errorType=" + ex.getClass().getSimpleName());
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            result.setLlmMessage("大模型重新生成失败：" + message);
            throw new IllegalStateException("大模型重新生成失败：" + message, ex);
        }
    }

    /**
     * 旧调用入口保留用于二进制兼容；缺少项目上下文时验证器会明确返回环境错误。
     */
    public GenerationResult regenerateWithLlmFeedback(GenerationResult result, String currentCode, String feedback) throws Exception {
        return regenerateWithLlmFeedback(null, null, result, currentCode, feedback);
    }

    /**
     * 执行候选测试、分析当前方法覆盖率，并在共享轮次预算内调用大模型补测。
     */
    public GenerationResult runCoverageLoop(Project project,
                                             PsiFile psiFile,
                                             GenerationResult result,
                                             String currentCode,
                                             BooleanSupplier cancelled,
                                             CoverageProgressListener progress) {
        long started = System.currentTimeMillis();
        CoverageConfig config = CoverageConfigManager.load();
        CoverageProgressListener listener = progress == null ? CoverageProgressListener.NONE : progress;
        CoverageReport report = CoverageReport.status(CoverageStatus.RUNNING);
        AcceptedCoverageCandidate bestAccepted = null;
        GeneratedTestFile coverageTemplate = null;
        result.setCoverageReport(report);
        updateCoverageProgress(report, listener, config, CoveragePhase.PREPARING, 1,
                "正在解析项目 JDK 和测试 classpath...", 0.03d);
        try {
            if (result.getMethodModel() == null || result.getGeneratedTestFile() == null) {
                return stopCoverage(result, report, CoverageStatus.ENVIRONMENT_ERROR,
                        CoverageStopReason.ENVIRONMENT_ERROR, "缺少被测方法或候选测试代码。");
            }
            MethodModel method = result.getMethodModel();
            ensureSymbolSnapshot(project, psiFile, result);
            String sourceText = ReadAction.compute(psiFile::getText);
            String sourceHash = CodeFingerprint.sha256(sourceText);
            report.setSourceHash(sourceHash);
            ProjectTestEnvironment environment = coverageEnvironmentResolver.resolve(project, psiFile);
            if (!environment.isUsable()) {
                String message = environment.getDiagnostics().isEmpty()
                        ? "无法解析测试执行环境。" : environment.getDiagnostics().get(0).getMessage();
                return stopCoverage(result, report, CoverageStatus.ENVIRONMENT_ERROR,
                        CoverageStopReason.ENVIRONMENT_ERROR, message);
            }
            MockitoStaticMockSupport mockitoSupport =
                    MockitoStaticMockSupport.detect(environment.getRuntimeClasspath());

            String targetClassName = targetClassName(result);
            GeneratedTestFile template = result.getGeneratedTestFile();
            coverageTemplate = template;
            String candidateCode = currentCode == null ? "" : currentCode;
            ReflectiveAssertionNormalizer.Result initialNormalization =
                    ReflectiveAssertionNormalizer.normalize(candidateCode);
            if (initialNormalization.isChanged()) {
                candidateCode = initialNormalization.getCode();
                addWarnings(result, List.of("已在覆盖率候选入口解包 "
                        + initialNormalization.getChanges()
                        + " 处 Method.invoke 的 InvocationTargetException，保留原目标异常断言。"));
            }
            Set<String> seenCode = new HashSet<>();
            seenCode.add(testCodeFingerprint(candidateCode));
            Map<String, Integer> repeatedRepairTransitions = new HashMap<>();
            Map<String, String> candidateFailureMemory = new HashMap<>();
            String pendingCompileCycleInstruction = "";
            String pendingRuntimeCycleInstruction = "";
            String lastRuntimeFailureSignature = "";
            String lastRuntimeFailedCode = "";
            String lastCompileFailedCode = "";
            List<CompileDiagnostic> lastCompileDiagnostics = new ArrayList<>();
            CoverageAttemptBudget attemptBudget = new CoverageAttemptBudget();
            List<CompileDiagnostic> compilerDiagnosticHistory = new ArrayList<>();
            List<RuntimeDiagnostic> runtimeDiagnosticHistory = new ArrayList<>();
            LlmConfig llmConfig = LlmConfigManager.load();
            LlmClient coverageClient = llmConfig.isEnabled() ? llmClientFactory.create(llmConfig) : null;

            while (true) {
                if (isCancelled(cancelled)) {
                    restoreAcceptedCandidate(result, report, template, bestAccepted);
                    return stopCoverage(result, report, CoverageStatus.CANCELLED,
                            CoverageStopReason.CANCELLED, "覆盖率执行已取消。");
                }
                updateCoverageProgress(report, listener, config, CoveragePhase.COMPILING,
                        attemptBudget.totalCalls() + 1,
                        "正在编译第 " + (attemptBudget.totalCalls() + 1) + " 个候选测试...", 0.10d);
                GeneratedTestFile candidateFile = copyWithContent(template, candidateCode);
                try (CompiledTestArtifact artifact = sandboxCompiler.compile(
                        environment, candidateFile, candidateCode, targetClassName)) {
                    if (!artifact.isSuccessful()) {
                        report.incrementCompileFailures();
                        candidateFailureMemory.put(testCodeFingerprint(candidateCode),
                                compilerFailureSummary(artifact.getDiagnostics()));
                        // Only diagnostics produced by the current candidate are actionable. A runtime
                        // failure from its parent is a regression constraint, not an active error on code
                        // which has not compiled and therefore has not run.
                        runtimeDiagnosticHistory.clear();
                        logCompilerDiagnostics(attemptBudget.totalCalls() + 1, artifact.getDiagnostics());
                        if (!lastCompileFailedCode.isBlank()
                                && !testCodeFingerprint(lastCompileFailedCode)
                                .equals(testCodeFingerprint(candidateCode))
                                && isCompileDiagnosticRegression(lastCompileDiagnostics,
                                artifact.getDiagnostics())) {
                            String regression = compileRegressionSummary(
                                    lastCompileDiagnostics, artifact.getDiagnostics());
                            report.incrementNoProgressCandidates();
                            pendingCompileCycleInstruction = "\n上一编译修复候选已被质量门禁回滚："
                                    + regression + "。必须从 currentTestCode 做更小、不同的修改。";
                            LOG.warn("UnitMind coverage: COMPILE_REGRESSION " + regression
                                    + ", rejectedEdits="
                                    + safeLogValue(semanticEditSummary(lastCompileFailedCode, candidateCode)));
                            candidateCode = lastCompileFailedCode;
                            continue;
                        }
                        lastCompileFailedCode = candidateCode;
                        lastCompileDiagnostics = new ArrayList<>(artifact.getDiagnostics());
                        boolean environmentError = artifact.getDiagnostics().stream()
                                .anyMatch(diagnostic -> !diagnostic.isRepairable());
                        if (environmentError) {
                            String message = artifact.getDiagnostics().isEmpty() ? "候选测试编译失败。"
                                    : artifact.getDiagnostics().get(0).getCode() + " - "
                                    + artifact.getDiagnostics().get(0).getMessage();
                            restoreAcceptedCandidate(result, report, template, bestAccepted);
                            return stopCoverage(result, report, CoverageStatus.ENVIRONMENT_ERROR,
                                    CoverageStopReason.ENVIRONMENT_ERROR, message);
                        }
                        rememberCompilerDiagnostics(compilerDiagnosticHistory, artifact.getDiagnostics());
                        if (canImprove(coverageClient, attemptBudget, config, CoverageAttemptKind.REPAIR)) {
                            updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                    attemptBudget.totalCalls() + 1,
                                    "正在根据编译诊断修复候选测试...", 0.25d);
                            String compileRepairInstruction =
                                    "只修复当前候选的编译错误，不得改写已通过测试的行为。"
                                            + pendingCompileCycleInstruction;
                            pendingCompileCycleInstruction = "";
                            CoverageImprovement improvement = improveCoverageCandidate(project, result, report, candidateCode,
                                    compilerDiagnosticHistory, runtimeDiagnosticHistory, Set.of(), Set.of(), attemptBudget,
                                    CoverageAttemptKind.REPAIR, config, coverageClient,
                                    mockitoSupport, compileRepairInstruction);
                            String improved = improvement.code;
                            String improvedHash = testCodeFingerprint(improved);
                            if (improvement.unchanged) {
                                restoreAcceptedCandidate(result, report, template, bestAccepted);
                                return stopCoverage(result, report, bestAccepted == null
                                                ? CoverageStatus.TEST_FAILED : CoverageStatus.BELOW_THRESHOLD,
                                        CoverageStopReason.REPEATED_CODE,
                                        unchangedRepairMessage("Compilation", improvement.unchangedStage,
                                                candidateFailureMemory.get(improvedHash)));
                            }
                            if (!seenCode.add(improvedHash)) {
                                String currentHash = testCodeFingerprint(candidateCode);
                                int repeatCount = rememberRepeatedTransition(
                                        repeatedRepairTransitions, currentHash, improvedHash);
                                LOG.info("UnitMind coverage: historical compile-repaired candidate, currentHash="
                                        + shortHash(currentHash) + ", candidateHash=" + shortHash(improvedHash)
                                        + ", rawModelHash=" + shortHash(improvement.rawModelHash)
                                        + ", postProcessedHash=" + shortHash(improvement.postProcessedHash)
                                        + ", stabilizedHash=" + shortHash(improvement.stabilizedHash)
                                        + ", normalizedHash=" + shortHash(improvement.normalizedHash)
                                        + ", transitionRepeat=" + repeatCount);
                                if (shouldRetryHistoricalRepair(repeatCount, attemptBudget, config)) {
                                    pendingCompileCycleInstruction =
                                            "\n上一轮编译修复处理后回到了已尝试的历史候选。"
                                                    + "不得恢复该实现；保留 currentTestCode 并针对 compilerDiagnostics 采用不同修复。"
                                                    + historicalFailureHint(candidateFailureMemory.get(improvedHash))
                                                    + "当错误为 REFLECTION_ASSERT_THROWS_WRAPS_CAUSE 时，"
                                                    + "在 assertThrows lambda 或 helper 中捕获 InvocationTargetException 并抛出 getCause()。";
                                    continue;
                                }
                                if (bestAccepted != null && canImprove(coverageClient, attemptBudget, config,
                                        CoverageAttemptKind.EXPLORATION)) {
                                    restoreAcceptedCandidate(result, report, template, bestAccepted);
                                    compilerDiagnosticHistory.clear();
                                    runtimeDiagnosticHistory.clear();
                                    candidateCode = bestAccepted.code;
                                    continue;
                                }
                                restoreAcceptedCandidate(result, report, template, bestAccepted);
                                boolean repairBudgetExhausted = !attemptBudget.canUse(
                                        CoverageAttemptKind.REPAIR, config);
                                return stopCoverage(result, report, bestAccepted == null
                                                ? CoverageStatus.TEST_FAILED : CoverageStatus.BELOW_THRESHOLD,
                                        repairBudgetExhausted
                                                ? CoverageStopReason.REPAIR_BUDGET_EXHAUSTED
                                                : CoverageStopReason.REPEATED_CODE,
                                        repeatedCompileMessage(repeatCount, repairBudgetExhausted,
                                                candidateFailureMemory.get(improvedHash)));
                            }
                            candidateCode = improved;
                            continue;
                        }
                        String message = artifact.getDiagnostics().isEmpty() ? "候选测试编译失败。"
                                : artifact.getDiagnostics().get(0).getCode() + " - "
                                + artifact.getDiagnostics().get(0).getMessage();
                        if (bestAccepted != null
                                && canImprove(coverageClient, attemptBudget, config,
                                CoverageAttemptKind.EXPLORATION)) {
                            restoreAcceptedCandidate(result, report, template, bestAccepted);
                            compilerDiagnosticHistory.clear();
                            runtimeDiagnosticHistory.clear();
                            updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                    attemptBudget.totalCalls() + 1,
                                    "修复预算已用完，正在从最佳版本尝试新的覆盖策略...", 0.85d);
                            CoverageImprovement alternative = improveCoverageCandidate(project, result, report,
                                    bestAccepted.code, new ArrayList<>(), new ArrayList<>(), Set.of(), Set.of(), attemptBudget,
                                    CoverageAttemptKind.EXPLORATION, config, coverageClient, mockitoSupport,
                                    "上一覆盖候选无法编译（" + message + "），不要修补或复用该候选。"
                                            + "从 currentTestCode 重新追加一个更小、不同构造方式的测试，命中主 coverage gap。");
                            String alternativeHash = testCodeFingerprint(alternative.code);
                            if (!seenCode.add(alternativeHash)) {
                                return stopCoverage(result, report, CoverageStatus.BELOW_THRESHOLD,
                                        CoverageStopReason.REPEATED_CODE, "替代覆盖策略返回了重复测试代码。");
                            }
                            candidateCode = alternative.code;
                            continue;
                        }
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        return stopCoverage(result, report,
                                bestAccepted == null ? CoverageStatus.TEST_FAILED : CoverageStatus.BELOW_THRESHOLD,
                                CoverageStopReason.TEST_FAILURE,
                                message);
                    }

                    lastCompileFailedCode = "";
                    lastCompileDiagnostics.clear();
                    updateCoverageProgress(report, listener, config, CoveragePhase.EXECUTING,
                            attemptBudget.totalCalls() + 1,
                            "正在隔离 JVM 中运行候选测试...", 0.45d);
                    CoverageRunResult run = coverageExecutor.execute(
                            artifact, method, sourceText, config, cancelled);
                    report.setGenerated(run.getSnapshot());
                    report.setGaps(run.getGaps());
                    report.getHistory().add(run.getSnapshot());
                    report.setCodeHash(testCodeFingerprint(candidateCode));
                    if (run.isCancelled() || isCancelled(cancelled)) {
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        return stopCoverage(result, report, CoverageStatus.CANCELLED,
                                CoverageStopReason.CANCELLED, "覆盖率执行已取消。");
                    }
                    if (run.isEnvironmentError()) {
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        return stopCoverage(result, report, CoverageStatus.ENVIRONMENT_ERROR,
                                CoverageStopReason.ENVIRONMENT_ERROR, run.getSnapshot().getMessage());
                    }
                    if (!run.getSnapshot().getExecution().isSuccessful()) {
                        report.incrementRuntimeFailures();
                        MockitoRuntimeDiagnosticEnricher.enrich(
                                run.getSnapshot().getExecution().getDiagnostics(), candidateCode);
                        candidateFailureMemory.put(testCodeFingerprint(candidateCode),
                                runtimeFailureSummary(run.getSnapshot().getExecution().getDiagnostics()));
                        // Compilation succeeded, so older compile diagnostics are resolved. Sending them
                        // together with the current runtime failure makes the model repair obsolete code.
                        compilerDiagnosticHistory.clear();
                        Set<String> focusedFailingTestNames = rememberRuntimeDiagnostics(runtimeDiagnosticHistory,
                                run.getSnapshot().getExecution().getDiagnostics());
                        Set<String> runtimeFailingTestNames = failingTestNames(run.getSnapshot().getExecution());
                        if (runtimeFailingTestNames.isEmpty()) runtimeFailingTestNames = focusedFailingTestNames;
                        Set<String> runtimePassedTestNames = passedTestNames(run.getSnapshot().getExecution());
                        logRuntimeDiagnostics(attemptBudget.totalCalls() + 1,
                                run.getSnapshot().getExecution().getDiagnostics());
                        String activeRuntimeSignature = runtimeFailureSignature(
                                run.getSnapshot().getExecution().getDiagnostics());
                        String runtimeRepairBase = candidateCode;
                        if (!lastRuntimeFailureSignature.isBlank()
                                && lastRuntimeFailureSignature.equals(activeRuntimeSignature)
                                && !lastRuntimeFailedCode.isBlank()
                                && !testCodeFingerprint(lastRuntimeFailedCode)
                                .equals(testCodeFingerprint(candidateCode))) {
                            String ineffectiveEdits = semanticEditSummary(lastRuntimeFailedCode, candidateCode);
                            report.incrementNoProgressCandidates();
                            report.incrementRuntimeNoProgressCandidates();
                            runtimeRepairBase = lastRuntimeFailedCode;
                            pendingRuntimeCycleInstruction += "\n上一方法补丁仅产生代码变化，"
                                    + "但全量运行失败签名完全未变，已回滚。无效修改="
                                    + ineffectiveEdits + "。不得只改断言消息、格式或等价表达式；"
                                    + "必须根据 oracleEvidence 改变导致该签名的 Arrange/Act/Assert。";
                            LOG.warn("UnitMind coverage: NO_RUNTIME_PROGRESS signature="
                                    + shortHash(CodeFingerprint.sha256(activeRuntimeSignature))
                                    + ", rejectedEdits=" + safeLogValue(ineffectiveEdits));
                        } else {
                            lastRuntimeFailureSignature = activeRuntimeSignature;
                            lastRuntimeFailedCode = candidateCode;
                        }
                        restoreReportCheckpoint(report, bestAccepted);
                        if (canImprove(coverageClient, attemptBudget, config, CoverageAttemptKind.REPAIR)) {
                            updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                    attemptBudget.totalCalls() + 1,
                                    "正在根据测试运行失败修复候选测试...", 0.65d);
                            String runtimeRepairInstruction =
                                    "执行通用运行修复协议，只修复 runtimeDiagnostics 对应的失败测试。"
                                            + "实际异常只是失败证据，不是新的期望；必须保持测试原意。"
                                            + "成功/正常/有效场景意外抛异常时，修复 Arrange、输入、fixture、前置条件或依赖 stub，"
                                            + "不得改成 assertThrows、捕获异常、删除或弱化断言。"
                                            + "不得引入反射猜字段，不得在 when/verify 之外使用 Mockito matcher。"
                                            + pendingRuntimeCycleInstruction;
                            pendingRuntimeCycleInstruction = "";
                            CoverageImprovement improvement = improveCoverageCandidate(project, result, report, runtimeRepairBase,
                                    compilerDiagnosticHistory, runtimeDiagnosticHistory,
                                    runtimeFailingTestNames, runtimePassedTestNames, attemptBudget, CoverageAttemptKind.REPAIR,
                                    config, coverageClient, mockitoSupport,
                                    runtimeRepairInstruction);
                            String improved = improvement.code;
                            String improvedHash = testCodeFingerprint(improved);
                            if (improvement.unchanged) {
                                restoreAcceptedCandidate(result, report, template, bestAccepted);
                                return stopCoverage(result, report, bestAccepted == null
                                                ? CoverageStatus.TEST_FAILED : CoverageStatus.BELOW_THRESHOLD,
                                        CoverageStopReason.REPEATED_CODE,
                                        unchangedRepairMessage("Runtime", improvement.unchangedStage,
                                                candidateFailureMemory.get(improvedHash)));
                            }
                            if (!seenCode.add(improvedHash)) {
                                String currentHash = testCodeFingerprint(candidateCode);
                                int repeatCount = rememberRepeatedTransition(
                                        repeatedRepairTransitions, currentHash, improvedHash);
                                LOG.info("UnitMind coverage: historical repaired candidate, currentHash="
                                        + shortHash(currentHash) + ", candidateHash=" + shortHash(improvedHash)
                                        + ", rawModelHash=" + shortHash(improvement.rawModelHash)
                                        + ", postProcessedHash=" + shortHash(improvement.postProcessedHash)
                                        + ", stabilizedHash=" + shortHash(improvement.stabilizedHash)
                                        + ", normalizedHash=" + shortHash(improvement.normalizedHash)
                                        + ", transitionRepeat=" + repeatCount);
                                if (shouldRetryHistoricalRepair(repeatCount, attemptBudget, config)) {
                                    pendingRuntimeCycleInstruction =
                                            "\n上一轮修复处理后回到了已尝试且未解决当前错误的历史候选。"
                                                    + "不得恢复该实现；必须保留 currentTestCode 并采用第三种修复。"
                                                    + historicalFailureHint(candidateFailureMemory.get(improvedHash))
                                                    + "当 expectedExceptionType 与 actualExceptionType 显示反射包装时，"
                                                    + "在 assertThrows lambda 或 helper 中解包 InvocationTargetException.getCause()。";
                                    continue;
                                }
                                if (bestAccepted != null && canImprove(coverageClient, attemptBudget, config,
                                        CoverageAttemptKind.EXPLORATION)) {
                                    restoreAcceptedCandidate(result, report, template, bestAccepted);
                                    compilerDiagnosticHistory.clear();
                                    runtimeDiagnosticHistory.clear();
                                    candidateCode = bestAccepted.code;
                                    continue;
                                }
                                restoreAcceptedCandidate(result, report, template, bestAccepted);
                                boolean repairBudgetExhausted = !attemptBudget.canUse(
                                        CoverageAttemptKind.REPAIR, config);
                                return stopCoverage(result, report, bestAccepted == null
                                                ? CoverageStatus.TEST_FAILED : CoverageStatus.BELOW_THRESHOLD,
                                        repairBudgetExhausted
                                                ? CoverageStopReason.REPAIR_BUDGET_EXHAUSTED
                                                : CoverageStopReason.REPEATED_CODE,
                                        repeatedRuntimeMessage(candidateCode, improvedHash,
                                                run.getSnapshot().getExecution().getDiagnostics(),
                                                repeatCount, repairBudgetExhausted,
                                                candidateFailureMemory.get(improvedHash)));
                            }
                            candidateCode = improved;
                            continue;
                        }
                        if (bestAccepted != null && canImprove(coverageClient, attemptBudget, config,
                                CoverageAttemptKind.EXPLORATION)) {
                            restoreAcceptedCandidate(result, report, template, bestAccepted);
                            compilerDiagnosticHistory.clear();
                            runtimeDiagnosticHistory.clear();
                            updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                    attemptBudget.totalCalls() + 1,
                                    "修复预算已用完，正在绕过失败候选继续探索...", 0.85d);
                            CoverageImprovement alternative = improveCoverageCandidate(project, result, report,
                                    bestAccepted.code, new ArrayList<>(), new ArrayList<>(), Set.of(), Set.of(), attemptBudget,
                                    CoverageAttemptKind.EXPLORATION, config, coverageClient, mockitoSupport,
                                    "上一覆盖候选运行失败，不要修补或复用该候选。"
                                            + "从 currentTestCode 使用不同输入、fixture 或 stub 策略重新命中主 coverage gap。");
                            String alternativeHash = testCodeFingerprint(alternative.code);
                            if (!seenCode.add(alternativeHash)) {
                                return stopCoverage(result, report, CoverageStatus.BELOW_THRESHOLD,
                                        CoverageStopReason.REPEATED_CODE, "替代覆盖策略返回了重复测试代码。");
                            }
                            candidateCode = alternative.code;
                            continue;
                        }
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        return stopCoverage(result, report, bestAccepted == null
                                        ? CoverageStatus.TEST_FAILED : CoverageStatus.BELOW_THRESHOLD,
                                CoverageStopReason.TEST_FAILURE, run.getSnapshot().getMessage());
                    }

                    // This candidate proves that all earlier compile/runtime defects are fixed. Do not send stale
                    // failures into a pure coverage-gap improvement prompt.
                    compilerDiagnosticHistory.clear();
                    runtimeDiagnosticHistory.clear();

                    updateCoverageProgress(report, listener, config, CoveragePhase.ANALYZING,
                            attemptBudget.totalCalls() + 1,
                            "正在分析当前方法的行和分支覆盖率...", 0.75d);
                    AcceptedCoverageCandidate selected = chooseCoverageCandidate(
                            bestAccepted, candidateCode, run.getSnapshot(), run.getGaps());
                    boolean accepted = selected != bestAccepted;
                    bestAccepted = selected;
                    if (accepted) {
                        acceptCoverageCandidate(result, report, template, bestAccepted);
                        report.incrementAcceptedCandidates();
                    }
                    if (CoverageLoopPolicy.reached(run.getSnapshot(), config)) {
                        report.setStatus(CoverageStatus.PASSED);
                        report.setStopReason(CoverageStopReason.TARGET_REACHED);
                        report.setMessage("生成测试已达到覆盖率目标。");
                        break;
                    }
                    if (!accepted) {
                        report.incrementNoProgressCandidates();
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        if (canImprove(coverageClient, attemptBudget, config, CoverageAttemptKind.EXPLORATION)) {
                            updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                    attemptBudget.totalCalls() + 1,
                                    "本轮无覆盖增益，正在从最佳候选尝试不同输入策略...", 0.85d);
                            CoverageImprovement alternative = improveCoverageCandidate(project, result, report,
                                    bestAccepted.code, new ArrayList<>(), new ArrayList<>(), Set.of(), Set.of(), attemptBudget,
                                    CoverageAttemptKind.EXPLORATION, config,
                                    coverageClient, mockitoSupport,
                                    "上一候选编译和运行成功但覆盖率没有任何增量，已被回滚。"
                                            + "必须保留 currentTestCode，并针对尚未覆盖的单个分支采用不同输入/fixture策略；"
                                            + "不要重复上一种反射、Mock 或参数构造方案。");
                            String alternativeHash = testCodeFingerprint(alternative.code);
                            if (!seenCode.add(alternativeHash)) {
                                report.setStatus(CoverageStatus.BELOW_THRESHOLD);
                                report.setStopReason(CoverageStopReason.REPEATED_CODE);
                                report.setMessage("无增益后的替代策略返回了重复测试代码。");
                                break;
                            }
                            candidateCode = alternative.code;
                            continue;
                        }
                        report.setStatus(CoverageStatus.BELOW_THRESHOLD);
                        report.setStopReason(CoverageStopReason.NO_PROGRESS);
                        report.setMessage("候选测试无覆盖增益，已回滚到本次最佳可用版本。");
                        break;
                    }
                    if (!canImprove(coverageClient, attemptBudget, config, CoverageAttemptKind.EXPLORATION)) {
                        report.setStatus(CoverageStatus.BELOW_THRESHOLD);
                        report.setStopReason(CoverageStopReason.MAX_ROUNDS);
                        report.setMessage(coverageClient == null
                                ? "大模型未启用，仅生成覆盖率报告。" : "已达到覆盖率补测轮次上限。");
                        break;
                    }
                    updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                            attemptBudget.totalCalls() + 1,
                            "正在把未覆盖目标反馈给大模型...", 0.85d);
                    CoverageImprovement improvement = improveCoverageCandidate(project, result, report, candidateCode,
                            new ArrayList<>(), new ArrayList<>(), Set.of(), Set.of(), attemptBudget,
                            CoverageAttemptKind.EXPLORATION, config, coverageClient, mockitoSupport,
                            "只新增命中 coverage.gaps 的测试，保持当前已通过且有覆盖贡献的测试不变。");
                    String improved = improvement.code;
                    String improvedHash = testCodeFingerprint(improved);
                    if (!seenCode.add(improvedHash)) {
                        report.setStatus(CoverageStatus.BELOW_THRESHOLD);
                        report.setStopReason(CoverageStopReason.REPEATED_CODE);
                        report.setMessage("大模型返回了重复测试代码。");
                        break;
                    }
                    candidateCode = improved;
                }
            }

            if (!isCancelled(cancelled) && config.isExistingBaselineEnabled()) {
                updateCoverageProgress(report, listener, config, CoveragePhase.BASELINE,
                        attemptBudget.totalCalls() + 1,
                        "正在计算已有测试基线...", 0.92d);
                report.setExisting(runExistingBaseline(psiFile, environment, result,
                        targetClassName, sourceText, config, cancelled));
            }
            GeneratedTestFile finalFile = result.getGeneratedTestFile();
            updateCoverageProgress(report, listener, config, CoveragePhase.VALIDATING,
                    attemptBudget.totalCalls() + 1,
                    "正在校验最终测试代码...", 0.97d);
            ValidationResult validation = validateCode(project, psiFile, finalFile, finalFile.getContent());
            result.setValidationResult(validation);
            updateCoverageProgress(report, listener, config, CoveragePhase.COMPLETED,
                    attemptBudget.totalCalls() + 1,
                    "覆盖率闭环完成。", 1.0d);
            return result;
        } catch (CoverageModelException ex) {
            restoreAcceptedCandidate(result, report, coverageTemplate, bestAccepted);
            LOG.warn("UnitMind coverage: model improvement failed after " + report.getRounds() + " calls", ex);
            CoverageStatus status = report.getGenerated().isAvailable()
                    ? CoverageStatus.BELOW_THRESHOLD : CoverageStatus.TEST_FAILED;
            return stopCoverage(result, report, status, CoverageStopReason.MODEL_ERROR, ex.getMessage());
        } catch (Exception ex) {
            restoreAcceptedCandidate(result, report, coverageTemplate, bestAccepted);
            LOG.warn("UnitMind coverage: loop failed", ex);
            return stopCoverage(result, report, CoverageStatus.ENVIRONMENT_ERROR,
                    CoverageStopReason.ENVIRONMENT_ERROR, safeMessage(ex));
        } finally {
            report.setDurationMillis(System.currentTimeMillis() - started);
            logCoverageOutcome(report);
        }
    }

    private CoverageImprovement improveCoverageCandidate(Project project,
                                                         GenerationResult result,
                                                         CoverageReport report,
                                                         String currentCode,
                                                         List<CompileDiagnostic> compilerDiagnostics,
                                                         List<com.casc.unittestagent.model.RuntimeDiagnostic> runtimeDiagnostics,
                                                         Set<String> runtimeFailingTestNames,
                                                         Set<String> runtimePassedTestNames,
                                                         CoverageAttemptBudget attemptBudget,
                                                         CoverageAttemptKind attemptKind,
                                                         CoverageConfig config,
                                                         LlmClient client,
                                                         MockitoStaticMockSupport mockitoSupport,
                                                         String instruction) throws CoverageModelException {
        MethodModel method = result.getMethodModel();
        LlmResponseFormatException previousFormatFailure = null;
        int formatRetries = 0;
        int transportRetries = 0;
        int unchangedResponses = 0;
        String previousAttemptSummary = "";
        if (attemptBudget == null || !attemptBudget.canUse(attemptKind, config)) {
            throw new CoverageModelException("当前类型的覆盖率模型调用预算已用完。", null);
        }
        while (true) {
            int iteration = attemptBudget.totalCalls() + 1;
            LlmGenerationRequest request = ReadAction.compute(() -> buildLlmRequest(project, method,
                    executableScenarios(result.getScenarios()), method.getMethodBodyText(), result.getSymbolSnapshot()));
            request.setExistingTestCodeSummary(currentCode);
            request.setCompilerDiagnostics(compilerDiagnostics);
            request.setRuntimeDiagnostics(runtimeDiagnostics);
            request.setCoverageReport(report);
            request.setRepairAttempt(iteration);
            applyMockitoSupport(request, mockitoSupport);
            request.setMockUsagePolicy(mockPolicyForExistingCode(currentCode));
            request.setFocusTestIds(runtimeDiagnostics == null ? List.of() : runtimeDiagnostics.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(RuntimeDiagnostic::getTestId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
            request.setFailingTestIds(runtimeFailingTestNames == null
                    ? List.of() : new ArrayList<>(runtimeFailingTestNames));
            request.setPassedTestIds(runtimePassedTestNames == null
                    ? List.of() : new ArrayList<>(runtimePassedTestNames));
            request.setPreviousAttemptSummary(previousAttemptSummary);
            String effectiveInstruction = instruction == null ? "" : instruction;
            if (previousFormatFailure != null) {
                effectiveInstruction = effectiveInstruction + "\n上一请求的模型响应为空或格式不正确。必须返回当前 taskMode 约定 JSON；"
                        + (runtimeDiagnostics != null && !runtimeDiagnostics.isEmpty()
                        ? "methodPatches 必须是非空的完整测试方法声明，不要返回整类。错误："
                        : "testCode 必须是非空的完整 Java 测试类字符串，不要只返回解释。错误：")
                        + safeMessage(previousFormatFailure);
            }
            if (unchangedResponses > 0) {
                effectiveInstruction += attemptKind == CoverageAttemptKind.REPAIR
                        ? "\n上一轮修复返回的 testCode 与 currentTestCode 完全相同，错误仍然存在。"
                        + "先按 testId 在 currentTestCode 中定位完整失败测试，再用 stackTrace、context.methodSourceCode、"
                        + "branches、constraints 和 callContexts 找到使原测试意图未成立的最早原因。"
                        + "sourceSnippet/compilerDiagnostics.snippet 非空时作为精确定位线索；为空时不得因此放弃。"
                        + "这次必须修改失败测试的 Arrange、Act 或 Assert 中与根因对应的代码。"
                        + "若是成功/正常/有效场景的意外异常，必须修复输入、fixture、前置条件或 mock 依赖返回值，"
                        + "不得把实际异常改成 assertThrows，也不得再次原样返回。"
                        : "\n上一轮响应经 AST 增量合并后没有新增任何测试。"
                        + "这次必须追加一个名称唯一、输入或 fixture 策略不同且命中 coverage.gaps[0] 的 @Test，"
                        + "不得再次原样返回。";
            }
            request.setUserInstruction(effectiveInstruction.strip());
            try {
                report.incrementModelRequests();
                LlmGenerationResponse response = client.improveCoverage(request);
                boolean runtimeRepair = runtimeDiagnostics != null && !runtimeDiagnostics.isEmpty();
                Set<String> editableTests = runtimeFailingTestNames == null || runtimeFailingTestNames.isEmpty()
                        ? failingTestNames(runtimeDiagnostics) : runtimeFailingTestNames;
                if (runtimeRepair && !response.hasMethodPatches()) {
                    throw new LlmResponseFormatException("运行修复必须返回 methodPatches，不接受整类 testCode。");
                }
                String modelCode = response.hasMethodPatches()
                        ? coverageTestClassStabilizer.applyMethodPatches(
                        currentCode, response.getMethodPatches(), editableTests)
                        : response.getTestCode();
                GeneratedTestCodePostProcessor.ProcessedCode processed =
                        generatedCodePostProcessor.process(modelCode, method);
                String nextCode = processed.getCode();
                String rawModelHash = testCodeFingerprint(modelCode);
                String postProcessedHash = testCodeFingerprint(nextCode);
                String postProcessedCode = nextCode;
                CoverageTestClassStabilizer.CandidateMode candidateMode = runtimeRepair
                        ? CoverageTestClassStabilizer.CandidateMode.RUNTIME_METHOD_PATCH
                        : compilerDiagnostics != null && !compilerDiagnostics.isEmpty()
                        ? CoverageTestClassStabilizer.CandidateMode.COMPILE_REPAIR
                        : CoverageTestClassStabilizer.CandidateMode.COVERAGE_EXPLORATION;
                nextCode = coverageTestClassStabilizer.stabilizeCandidate(
                        currentCode, nextCode, candidateMode);
                String stabilizedHash = testCodeFingerprint(nextCode);
                String stabilizedCode = nextCode;
                ReflectiveAssertionNormalizer.Result normalization =
                        ReflectiveAssertionNormalizer.normalize(nextCode);
                if (normalization.isChanged()) {
                    nextCode = normalization.getCode();
                    addWarnings(result, List.of("已自动解包 " + normalization.getChanges()
                            + " 处 Method.invoke 的 InvocationTargetException，避免 assertThrows 观察到反射包装异常。"));
                }
                addWarnings(result, response.getWarnings());
                addWarnings(result, processed.getWarnings());
                LOG.info("UnitMind coverage: structuredResponse diagnosis="
                        + safeLogValue(response.getDiagnosisRootCauseCategory())
                        + ", oracleDecision=" + safeLogValue(response.getOracleDecision())
                        + ", evidenceCount=" + response.getDiagnosisEvidence().size()
                        + ", editTargets=" + response.getEdits().stream()
                        .map(edit -> safeLogValue(edit.getTargetMethod())).toList()
                        + ", methodPatchCount=" + response.getMethodPatches().size());
                String currentHash = testCodeFingerprint(currentCode);
                String responseHash = testCodeFingerprint(nextCode);
                String unchangedStage = unchangedStage(currentHash, rawModelHash, postProcessedHash,
                        stabilizedHash, responseHash);
                LOG.info("UnitMind coverage: model improvement call=" + attemptBudget.totalCalls()
                        + ", kind=" + attemptKind
                        + ", codeChanged=" + !currentHash.equals(responseHash)
                        + ", currentHash=" + shortHash(currentHash)
                        + ", rawModelHash=" + shortHash(rawModelHash)
                        + ", postProcessedHash=" + shortHash(postProcessedHash)
                        + ", stabilizedHash=" + shortHash(stabilizedHash)
                        + ", normalizedHash=" + shortHash(responseHash)
                        + ", runtimeDiagnostics=" + runtimeDiagnostics.size()
                        + ", compilerDiagnostics=" + compilerDiagnostics.size()
                        + ", rawResponseLength=" + (response.getRawResponse() == null
                        ? 0 : response.getRawResponse().length()));
                if (currentHash.equals(responseHash)) {
                    report.recordUnchangedResponse(unchangedStage);
                    unchangedResponses++;
                    String rejectedCandidate = switch (unchangedStage) {
                        case "POST_PROCESSOR" -> modelCode;
                        case "TEST_PROTECTION" -> postProcessedCode;
                        case "NORMALIZATION" -> stabilizedCode;
                        default -> modelCode;
                    };
                    previousAttemptSummary = previousAttemptSummary(unchangedStage,
                            runtimeFailingTestNames, runtimePassedTestNames,
                            semanticEditSummary(currentCode, rejectedCandidate));
                    if (attemptKind == CoverageAttemptKind.EXPLORATION) {
                        report.incrementNoProgressCandidates();
                    }
                    if (unchangedResponses <= MAX_COVERAGE_UNCHANGED_RETRIES) {
                        previousFormatFailure = null;
                        formatRetries = 0;
                        transportRetries = 0;
                        LOG.info("UnitMind coverage: unchanged response, retrying with stronger instruction, kind="
                                + attemptKind + ", stage=" + unchangedStage
                                + ", unchangedResponses=" + unchangedResponses);
                        continue;
                    }
                    LOG.info("UnitMind coverage: unchanged retry limit reached, kind=" + attemptKind
                            + ", stage=" + unchangedStage + ", unchangedResponses=" + unchangedResponses);
                    return new CoverageImprovement(nextCode, rawModelHash, postProcessedHash,
                            stabilizedHash, responseHash, true, unchangedStage);
                }
                attemptBudget.consumeChangedCandidate(attemptKind, report, true);
                return new CoverageImprovement(nextCode, rawModelHash, postProcessedHash,
                        stabilizedHash, responseHash, false, "");
            } catch (LlmOutputTruncatedException ex) {
                LOG.warn("UnitMind coverage: model output truncated, semanticCall=" + iteration
                        + ", error=" + safeMessage(ex));
                throw new CoverageModelException("大模型输出被 token 上限截断。已停止无意义的同格式重试；"
                        + "运行修复已使用方法级补丁协议，请重试本轮。", ex);
            } catch (LlmResponseFormatException ex) {
                previousFormatFailure = ex;
                if (formatRetries < MAX_COVERAGE_FORMAT_RETRIES) {
                    formatRetries++;
                    report.incrementFormatRetries();
                    LOG.warn("UnitMind coverage: invalid model response, semanticCall=" + iteration
                            + ", formatRetry=" + formatRetries + ", errorType="
                            + ex.getClass().getSimpleName() + ", error=" + safeMessage(ex));
                    continue;
                }
                throw new CoverageModelException("大模型连续返回空内容或无法解析的格式，已停止补测；"
                        + "格式重试 " + formatRetries + " 次，语义补测轮次未被占用。", ex);
            } catch (Exception ex) {
                if (isRetryableCoverageFailure(ex) && transportRetries < MAX_COVERAGE_TRANSPORT_RETRIES) {
                    transportRetries++;
                    report.incrementTransportRetries();
                    LOG.warn("UnitMind coverage: transient model failure, semanticCall=" + iteration
                            + ", transportRetry=" + transportRetries + ", errorType="
                            + ex.getClass().getSimpleName());
                    try {
                        Thread.sleep(250L * transportRetries);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CoverageModelException("覆盖率补测网络重试被中断。", interrupted);
                    }
                    continue;
                }
                throw new CoverageModelException("覆盖率补测模型调用失败：" + safeMessage(ex)
                        + "；已进行 " + transportRetries + " 次瞬时网络重试，当前最佳候选已保留。", ex);
            }
        }
    }

    private static String unchangedStage(String currentHash,
                                         String rawModelHash,
                                         String postProcessedHash,
                                         String stabilizedHash,
                                         String normalizedHash) {
        if (currentHash.equals(rawModelHash)) return "MODEL_OUTPUT";
        if (currentHash.equals(postProcessedHash)) return "POST_PROCESSOR";
        if (currentHash.equals(stabilizedHash)) return "TEST_PROTECTION";
        if (currentHash.equals(normalizedHash)) return "NORMALIZATION";
        return "UNKNOWN";
    }

    private static String previousAttemptSummary(String stage,
                                                 Set<String> failingTests,
                                                 Set<String> passedTests,
                                                 String rejectedEdits) {
        String editable = failingTests == null || failingTests.isEmpty()
                ? "[]" : failingTests.toString();
        String protectedTests = passedTests == null || passedTests.isEmpty()
                ? "[]" : passedTests.toString();
        String editEvidence = rejectedEdits == null || rejectedEdits.isBlank()
                ? "" : "上轮语义修改=" + rejectedEdits + "。";
        return editEvidence + switch (stage == null ? "" : stage) {
            case "MODEL_OUTPUT" -> "上一轮模型在语义上原样返回 currentTestCode，活动错误仍存在。"
                    + "这次必须实际修改失败测试 " + editable + " 或共享 fixture。";
            case "TEST_PROTECTION" -> "上一轮修改不属于当前模式允许的范围，已被候选稳定器恢复。"
                    + "可编辑的失败测试=" + editable + "；必须保护的已通过测试=" + protectedTests + "。"
                    + "这次只修改可编辑失败测试或确有必要的共享 fixture。";
            case "POST_PROCESSOR" -> "上一轮修改被代码后处理抵消；请使用语法结构明确、不依赖无用 import 或空生命周期方法的修复。";
            case "NORMALIZATION" -> "上一轮修改被反射断言规范化抵消；请保留目标异常契约并正确解包 InvocationTargetException.getCause()。";
            default -> "上一轮处理后测试语义未变，必须针对活动诊断采用不同修复。";
        };
    }

    static String semanticEditSummary(String currentCode, String proposedCode) {
        try {
            var current = StaticJavaParser.parse(currentCode == null ? "" : currentCode);
            var proposed = StaticJavaParser.parse(proposedCode == null ? "" : proposedCode);
            Map<String, String> before = new java.util.LinkedHashMap<>();
            Map<String, String> after = new java.util.LinkedHashMap<>();
            current.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                    .forEach(method -> before.put(method.getSignature().asString(), method.toString()));
            proposed.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                    .forEach(method -> after.put(method.getSignature().asString(), method.toString()));
            Set<String> names = new LinkedHashSet<>();
            names.addAll(before.keySet());
            names.addAll(after.keySet());
            List<String> edits = new ArrayList<>();
            for (String name : names) {
                String left = before.get(name);
                String right = after.get(name);
                if (java.util.Objects.equals(left, right)) continue;
                edits.add((left == null ? "ADDED " : right == null ? "REMOVED " : "CHANGED ") + name);
                if (edits.size() >= 8) break;
            }
            return edits.isEmpty() ? "class-level/import/format-only change" : edits.toString();
        } catch (RuntimeException ignored) {
            return "unparseable candidate";
        }
    }

    static boolean isRetryableCoverageFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (current instanceof java.net.SocketTimeoutException
                    || message.contains("timeout") || message.contains("timed out")
                    || message.contains("connection reset") || message.contains("connection refused")
                    || message.contains("unexpected end of stream")
                    || message.contains("llm request failed: 429")
                    || message.contains("llm request failed: 502")
                    || message.contains("llm request failed: 503")
                    || message.contains("llm request failed: 504")) {
                return true;
            }
            if (current instanceof IOException && !message.contains("llm request failed: 4")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static Set<String> failingTestNames(List<RuntimeDiagnostic> diagnostics) {
        Set<String> names = new LinkedHashSet<>();
        if (diagnostics == null) return names;
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.getTestId() == null) continue;
            String name = testName(diagnostic.getTestId());
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    /** 忽略断言自定义消息和栈行号，只表示外部可观测的失败状态。 */
    static String runtimeFailureSignature(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return "NO_DIAGNOSTIC";
        return diagnostics.stream().filter(java.util.Objects::nonNull)
                .map(diagnostic -> String.join("|",
                        testName(diagnostic.getTestId()),
                        nullToSignature(diagnostic.getCategory()),
                        nullToSignature(diagnostic.getExceptionType()),
                        nullToSignature(diagnostic.getExpectedValue()),
                        nullToSignature(diagnostic.getActualValue()),
                        nullToSignature(diagnostic.getExpectedExceptionType()),
                        nullToSignature(diagnostic.getActualExceptionType()),
                        nullToSignature(diagnostic.getRootCauseType()),
                        String.valueOf(diagnostic.getExpectedInvocationCount()),
                        String.valueOf(diagnostic.getActualInvocationCount()),
                        frameWithoutLine(diagnostic.getProductionFrame()),
                        frameWithoutLine(diagnostic.getRootCauseFrame())))
                .sorted().reduce((left, right) -> left + "\n" + right).orElse("NO_DIAGNOSTIC");
    }

    private static String nullToSignature(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String frameWithoutLine(String frame) {
        return nullToSignature(frame).replaceAll(":\\d+\\)?$", ")");
    }

    static Set<String> failingTestNames(TestExecutionSummary summary) {
        Set<String> names = new LinkedHashSet<>();
        if (summary == null) return names;
        for (TestCaseExecution test : summary.getTestCases()) {
            if (test == null || test.isSuccessful() || test.isSkipped()) continue;
            String name = testName(test.getTestId());
            if (!name.isBlank()) names.add(name);
        }
        if (names.isEmpty()) names.addAll(failingTestNames(summary.getDiagnostics()));
        return names;
    }

    static Set<String> passedTestNames(TestExecutionSummary summary) {
        Set<String> names = new LinkedHashSet<>();
        if (summary == null) return names;
        for (TestCaseExecution test : summary.getTestCases()) {
            if (test == null || !test.isSuccessful() || test.isSkipped()) continue;
            String name = testName(test.getTestId());
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    private static String testName(String testId) {
        String id = testId == null ? "" : testId;
        int hash = id.indexOf('#');
        if (hash >= 0) id = id.substring(hash + 1);
        int parenthesis = id.indexOf('(');
        if (parenthesis >= 0) id = id.substring(0, parenthesis);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        return id.trim();
    }

    static AcceptedCoverageCandidate chooseCoverageCandidate(
            AcceptedCoverageCandidate current,
            String code,
            CoverageSnapshot snapshot,
            List<com.casc.unittestagent.model.CoverageGap> gaps) {
        if (snapshot == null || !snapshot.isAvailable()
                || snapshot.getExecution() == null || !snapshot.getExecution().isSuccessful()) {
            return current;
        }
        if (current == null || CoverageLoopPolicy.improved(current.snapshot, snapshot)) {
            return new AcceptedCoverageCandidate(code, snapshot, gaps);
        }
        return current;
    }

    private void acceptCoverageCandidate(GenerationResult result,
                                         CoverageReport report,
                                         GeneratedTestFile template,
                                         AcceptedCoverageCandidate accepted) {
        if (result == null || report == null || template == null || accepted == null) return;
        result.setGeneratedTestFile(copyWithContent(template, accepted.code));
        report.setGenerated(accepted.snapshot);
        report.setGaps(new ArrayList<>(accepted.gaps));
        report.setCodeHash(testCodeFingerprint(accepted.code));
    }

    private void restoreAcceptedCandidate(GenerationResult result,
                                          CoverageReport report,
                                          GeneratedTestFile template,
                                          AcceptedCoverageCandidate accepted) {
        acceptCoverageCandidate(result, report, template, accepted);
    }

    /** 运行失败会产生一个不可用 snapshot；修复提示仍应看到上一个可运行检查点的缺口。 */
    private void restoreReportCheckpoint(CoverageReport report, AcceptedCoverageCandidate accepted) {
        if (report == null || accepted == null) return;
        report.setGenerated(accepted.snapshot);
        report.setGaps(new ArrayList<>(accepted.gaps));
        report.setCodeHash(testCodeFingerprint(accepted.code));
    }

    private void logCoverageOutcome(CoverageReport report) {
        if (report == null) return;
        CoverageSnapshot generated = report.getGenerated();
        String line = generated != null && generated.isAvailable()
                ? String.format(Locale.ROOT, "%.4f", generated.getLine().getRate()) : "unavailable";
        String branch = generated != null && generated.isAvailable() && generated.getBranch().isApplicable()
                ? String.format(Locale.ROOT, "%.4f", generated.getBranch().getRate()) : "n/a";
        LOG.info("UnitMind coverage outcome: status=" + report.getStatus()
                + ", engineRevision=" + COVERAGE_ENGINE_REVISION
                + ", stopReason=" + report.getStopReason()
                + ", semanticRounds=" + report.getRounds()
                + ", explorationRounds=" + report.getExplorationRounds()
                + ", repairRounds=" + report.getRepairRounds()
                + ", modelRequests=" + report.getModelRequests()
                + ", transportRetries=" + report.getTransportRetries()
                + ", formatRetries=" + report.getFormatRetries()
                + ", acceptedCandidates=" + report.getAcceptedCandidates()
                + ", compileFailures=" + report.getCompileFailures()
                + ", runtimeFailures=" + report.getRuntimeFailures()
                + ", noProgressCandidates=" + report.getNoProgressCandidates()
                + ", runtimeNoProgressCandidates=" + report.getRuntimeNoProgressCandidates()
                + ", unchangedResponses=" + report.getUnchangedResponses()
                + ", modelUnchangedResponses=" + report.getModelUnchangedResponses()
                + ", discardedChanges=" + report.getDiscardedChanges()
                + ", lastUnchangedStage=" + report.getLastUnchangedStage()
                + ", lineRate=" + line + ", branchRate=" + branch
                + ", durationMs=" + report.getDurationMillis());
    }

    static final class AcceptedCoverageCandidate {
        private final String code;
        private final CoverageSnapshot snapshot;
        private final List<com.casc.unittestagent.model.CoverageGap> gaps;

        private AcceptedCoverageCandidate(String code,
                                          CoverageSnapshot snapshot,
                                          List<com.casc.unittestagent.model.CoverageGap> gaps) {
            this.code = code == null ? "" : code;
            this.snapshot = snapshot;
            this.gaps = gaps == null ? new ArrayList<>() : new ArrayList<>(gaps);
        }

        String getCode() { return code; }
        CoverageSnapshot getSnapshot() { return snapshot; }
    }

    private void configureMockitoSupport(LlmGenerationRequest request, Project project, PsiFile psiFile) {
        if (request == null) return;
        if (project == null || psiFile == null) {
            request.setMockitoStaticMockingSupported(false);
            request.setMockitoStaticMockingSupport("缺少项目上下文，按不支持静态 Mock 处理");
            return;
        }
        try {
            ProjectTestEnvironment environment = coverageEnvironmentResolver.resolve(project, psiFile);
            applyMockitoSupport(request, MockitoStaticMockSupport.detect(environment.getRuntimeClasspath()));
        } catch (Exception ex) {
            request.setMockitoStaticMockingSupported(false);
            request.setMockitoStaticMockingSupport("能力检测失败，按不支持静态 Mock 处理：" + safeMessage(ex));
        }
    }

    private void applyMockitoSupport(LlmGenerationRequest request, MockitoStaticMockSupport support) {
        if (request == null) return;
        request.setMockitoStaticMockingSupported(support != null && support.isSupported());
        request.setMockitoStaticMockingSupport(support == null ? "未检测" : support.getDescription());
    }

    /**
     * Coverage-loop identity is based on a canonical Java AST, so whitespace-only formatting from
     * the model or post-processor cannot consume a semantic attempt or evade cycle detection.
     * Invalid candidates deliberately fall back to their raw text because the parser cannot prove
     * semantic equivalence for code that does not parse.
     */
    static String testCodeFingerprint(String code) {
        String source = code == null ? "" : code;
        try {
            return CodeFingerprint.sha256(StaticJavaParser.parse(source).toString());
        } catch (RuntimeException ignored) {
            return CodeFingerprint.sha256(source);
        }
    }

    static String repeatedRuntimeMessage(String currentCode,
                                         String improvedHash,
                                         List<RuntimeDiagnostic> diagnostics,
                                         int repeatCount,
                                         boolean repairBudgetExhausted,
                                         String historicalFailure) {
        boolean unchanged = testCodeFingerprint(currentCode).equals(improvedHash);
        String headline;
        if (unchanged) {
            headline = repairBudgetExhausted
                    ? "运行修复未修改当前测试代码，且修复预算已耗尽。"
                    : "运行修复未修改当前测试代码，已按重复代码策略停止。";
        } else if (repairBudgetExhausted) {
            headline = "运行修复返回了已尝试过的历史测试代码，但修复预算已耗尽，未执行替代转换重试。";
        } else {
            headline = "运行修复连续回到已尝试过的历史测试代码，同一转换已尝试 "
                    + Math.max(1, repeatCount) + " 次，已停止避免循环。";
        }
        StringBuilder message = new StringBuilder(headline);
        if (historicalFailure != null && !historicalFailure.isBlank()) {
            message.append("\n该历史候选此前失败原因：").append(historicalFailure);
        }
        if (diagnostics != null && !diagnostics.isEmpty()) {
            RuntimeDiagnostic diagnostic = diagnostics.get(0);
            message.append("\n未修复的运行错误：");
            if (!diagnostic.getTestId().isBlank()) message.append(diagnostic.getTestId()).append(" - ");
            if (!diagnostic.getExceptionType().isBlank()) message.append(diagnostic.getExceptionType()).append(": ");
            message.append(diagnostic.getMessage().isBlank() ? "未提供异常消息" : diagnostic.getMessage());
        } else {
            message.append("\nJUnit 未产生可用的失败诊断；请查看子进程输出或重新运行。");
        }
        return message.toString();
    }

    static String unchangedRepairMessage(String phase,
                                         String stage,
                                         String historicalFailure) {
        String kind = "Compilation".equals(phase) ? "编译" : "运行";
        String reason = switch (stage == null ? "" : stage) {
            case "MODEL_OUTPUT" -> "模型返回的测试代码与当前代码相同";
            case "POST_PROCESSOR" -> "模型曾修改代码，但修改被代码后处理抵消";
            case "TEST_PROTECTION" -> "模型曾修改代码，但修改不属于当前模式允许的范围，已被候选稳定器恢复";
            case "NORMALIZATION" -> "模型曾修改代码，但修改被规范化阶段抵消";
            default -> "修复处理后的测试代码未发生变化";
        };
        StringBuilder message = new StringBuilder(kind)
                .append("修复在独立无变化重试后仍未产生新候选：")
                .append(reason)
                .append("。未扣减正式修复预算。");
        if (historicalFailure != null && !historicalFailure.isBlank()) {
            message.append("\n当前候选失败原因：").append(historicalFailure);
        }
        return message.toString();
    }

    static String repeatedCompileMessage(int repeatCount,
                                         boolean repairBudgetExhausted,
                                         String historicalFailure) {
        StringBuilder message = new StringBuilder(repairBudgetExhausted
                ? "编译修复返回了已尝试过的历史测试代码，但修复预算已耗尽，未执行替代转换重试。"
                : "编译修复连续回到已尝试过的历史测试代码，同一转换已尝试 "
                + Math.max(1, repeatCount) + " 次，已停止避免循环。");
        if (historicalFailure != null && !historicalFailure.isBlank()) {
            message.append("\n该历史候选此前失败原因：").append(historicalFailure);
        }
        return message.toString();
    }

    private static String historicalFailureHint(String failure) {
        return failure == null || failure.isBlank()
                ? "" : "该历史候选此前失败原因：" + failure + "；";
    }

    private static String compilerFailureSummary(List<CompileDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return "编译失败但没有可用诊断";
        StringBuilder summary = new StringBuilder();
        for (CompileDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            if (summary.length() > 0) summary.append(" | ");
            summary.append(diagnostic.getCode()).append(": ").append(diagnostic.getMessage());
            if (summary.length() >= 800) break;
        }
        return summary.length() == 0 ? "编译失败但没有可用诊断" : summary.toString();
    }

    private static String runtimeFailureSummary(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return "运行失败但没有可用诊断";
        StringBuilder summary = new StringBuilder();
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            if (summary.length() > 0) summary.append(" | ");
            if (!diagnostic.getTestId().isBlank()) summary.append(diagnostic.getTestId()).append(": ");
            if (!diagnostic.getCategory().isBlank()) summary.append(diagnostic.getCategory()).append(" - ");
            summary.append(diagnostic.getMessage());
            if (diagnostic.getExpectedInvocationCount() >= 0
                    && diagnostic.getActualInvocationCount() >= 0) {
                summary.append(" [verify expected=").append(diagnostic.getExpectedInvocationCount())
                        .append(", actual=").append(diagnostic.getActualInvocationCount()).append(']');
            }
            if (summary.length() >= 800) break;
        }
        return summary.length() == 0 ? "运行失败但没有可用诊断" : summary.toString();
    }

    enum CoverageAttemptKind { EXPLORATION, REPAIR }

    static int rememberRepeatedTransition(Map<String, Integer> transitions,
                                          String currentHash,
                                          String candidateHash) {
        if (transitions == null) return 1;
        String key = (currentHash == null ? "" : currentHash) + "->"
                + (candidateHash == null ? "" : candidateHash);
        return transitions.merge(key, 1, Integer::sum);
    }

    static boolean shouldRetryHistoricalRepair(int repeatCount,
                                               CoverageAttemptBudget budget,
                                               CoverageConfig config) {
        return repeatCount <= MAX_HISTORICAL_REPAIR_RETRIES
                && budget != null && budget.canUse(CoverageAttemptKind.REPAIR, config);
    }

    private String shortHash(String hash) {
        return hash == null || hash.length() <= 12 ? String.valueOf(hash) : hash.substring(0, 12);
    }

    /** 分离探索与修复配额，避免候选语法/运行错误吞掉真正的覆盖率探索机会。 */
    static final class CoverageAttemptBudget {
        private int explorationCalls;
        private int repairCalls;

        int totalCalls() { return explorationCalls + repairCalls; }

        boolean canUse(CoverageAttemptKind kind, CoverageConfig config) {
            if (config == null || kind == null) return false;
            return kind == CoverageAttemptKind.REPAIR
                    ? repairCalls < config.getMaxRepairRounds()
                    : explorationCalls < config.getMaxRounds();
        }

        void consume(CoverageAttemptKind kind, CoverageReport report) {
            if (kind == CoverageAttemptKind.REPAIR) {
                repairCalls++;
                if (report != null) report.incrementRepairRounds();
            } else {
                explorationCalls++;
                if (report != null) report.incrementExplorationRounds();
            }
            if (report != null) report.setRounds(totalCalls());
        }

        /** 只有产生了可执行验证的新代码候选，才计入探索/修复语义预算。 */
        boolean consumeChangedCandidate(CoverageAttemptKind kind,
                                        CoverageReport report,
                                        boolean changed) {
            if (!changed) return false;
            consume(kind, report);
            return true;
        }
    }

    private static final class CoverageImprovement {
        private final String code;
        private final String rawModelHash;
        private final String postProcessedHash;
        private final String stabilizedHash;
        private final String normalizedHash;
        private final boolean unchanged;
        private final String unchangedStage;

        private CoverageImprovement(String code,
                                    String rawModelHash,
                                    String postProcessedHash,
                                    String stabilizedHash,
                                    String normalizedHash,
                                    boolean unchanged,
                                    String unchangedStage) {
            this.code = code;
            this.rawModelHash = rawModelHash;
            this.postProcessedHash = postProcessedHash;
            this.stabilizedHash = stabilizedHash;
            this.normalizedHash = normalizedHash;
            this.unchanged = unchanged;
            this.unchangedStage = unchangedStage == null ? "" : unchangedStage;
        }
    }

    private static final class CoverageModelException extends Exception {
        private CoverageModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private CoverageSnapshot runExistingBaseline(PsiFile psiFile,
                                                 ProjectTestEnvironment environment,
                                                 GenerationResult result,
                                                 String targetClassName,
                                                 String sourceText,
                                                 CoverageConfig config,
                                                 BooleanSupplier cancelled) {
        CoverageSnapshot unavailable = new CoverageSnapshot();
        try {
            List<File> candidates = existingTestCandidates(psiFile, result.getGeneratedTestFile(), targetClassName);
            if (candidates.isEmpty()) {
                unavailable.setMessage("未找到对应的已有测试类。");
                return unavailable;
            }
            int compileFailures = 0;
            for (File existing : candidates) {
                String existingCode = Files.readString(existing.toPath(), StandardCharsets.UTF_8);
                JavaTestSourceInspector.Inspection inspection = JavaTestSourceInspector.inspect(existingCode);
                if (!inspection.isParsed() || inspection.getTopLevelTypes().isEmpty()) continue;
                GeneratedTestFile descriptor = new GeneratedTestFile();
                descriptor.setPackageName(inspection.getPackageName());
                descriptor.setClassName(inspection.getTopLevelTypes().get(0));
                descriptor.setFileName(existing.getName());
                descriptor.setContent(existingCode);
                try (CompiledTestArtifact artifact = sandboxCompiler.compile(environment,
                        descriptor, existingCode, targetClassName)) {
                    if (!artifact.isSuccessful()) {
                        compileFailures++;
                        continue;
                    }
                    CoverageRunResult run = coverageExecutor.execute(artifact, result.getMethodModel(),
                            sourceText, config, cancelled);
                    return run.getSnapshot();
                }
            }
            unavailable.setMessage(compileFailures > 0
                    ? "找到已有测试类，但隔离基线编译失败。" : "未找到可解析的已有测试类。");
            return unavailable;
        } catch (Exception ex) {
            unavailable.setMessage("已有测试基线不可用：" + safeMessage(ex));
            return unavailable;
        }
    }

    /**
     * 固定的 FooTest.java 只是首选项。项目里的测试经常使用 FooTests、FooServiceTest
     * 或框架特定后缀，因此在同包内按文件名和源码引用排序后继续尝试。
     */
    private List<File> existingTestCandidates(PsiFile psiFile,
                                              GeneratedTestFile generatedTestFile,
                                              String targetClassName) throws IOException {
        File preferred = coverageFileResolver.resolveTargetFile(psiFile, generatedTestFile);
        List<File> result = new ArrayList<>();
        if (preferred != null && preferred.isFile()) result.add(preferred);
        File directory = preferred == null ? null : preferred.getParentFile();
        if (directory == null || !directory.isDirectory()) return result;

        String targetSimpleName = targetClassName == null ? "" : targetClassName.substring(
                targetClassName.lastIndexOf('.') + 1);
        List<File> alternatives = new ArrayList<>();
        try (Stream<java.nio.file.Path> files = Files.list(directory.toPath())) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(java.nio.file.Path::toFile)
                    .filter(file -> preferred == null || !file.equals(preferred))
                    .forEach(alternatives::add);
        }
        alternatives.sort(Comparator
                .comparingInt((File file) -> existingTestScore(file.getName(), targetSimpleName))
                .reversed().thenComparing(File::getName));
        for (File alternative : alternatives) {
            if (result.size() >= 6) break;
            String name = alternative.getName();
            if (existingTestScore(name, targetSimpleName) >= 2) {
                result.add(alternative);
                continue;
            }
            String code = Files.readString(alternative.toPath(), StandardCharsets.UTF_8);
            if (!targetSimpleName.isBlank() && code.matches("(?s).*\\b"
                    + Pattern.quote(targetSimpleName) + "\\b.*")) {
                result.add(alternative);
            }
        }
        return result;
    }

    private int existingTestScore(String fileName, String targetSimpleName) {
        if (fileName == null || targetSimpleName == null || targetSimpleName.isBlank()) return 0;
        String base = fileName.endsWith(".java")
                ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
        if (base.equals(targetSimpleName + "Test") || base.equals(targetSimpleName + "Tests")) return 4;
        if (base.startsWith(targetSimpleName) && base.contains("Test")) return 3;
        if (base.contains(targetSimpleName) && base.contains("Test")) return 2;
        return 0;
    }

    private boolean canImprove(LlmClient client,
                               CoverageAttemptBudget budget,
                               CoverageConfig config,
                               CoverageAttemptKind kind) {
        return client != null && budget != null && budget.canUse(kind, config);
    }

    private void rememberCompilerDiagnostics(List<CompileDiagnostic> history,
                                             List<CompileDiagnostic> diagnostics) {
        history.clear();
        if (diagnostics == null) return;
        Set<String> seen = new LinkedHashSet<>();
        for (CompileDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            String key = diagnostic.getCode() + "|" + diagnostic.getLine() + "|"
                    + diagnostic.getColumn() + "|" + diagnostic.getSourceOrigin() + "|"
                    + diagnostic.getMessage();
            if (seen.add(key)) history.add(diagnostic);
            if (history.size() >= 12) break;
        }
    }

    static Set<String> rememberRuntimeDiagnostics(List<RuntimeDiagnostic> history,
                                                  List<RuntimeDiagnostic> diagnostics) {
        history.clear();
        if (diagnostics == null) return Set.of();
        Set<String> seen = new LinkedHashSet<>();
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            String key = diagnostic.getTestId() + "|" + diagnostic.getExceptionType() + "|"
                    + diagnostic.getExpectedValue() + "|" + diagnostic.getActualValue() + "|"
                    + diagnostic.getExpectedExceptionType() + "|" + diagnostic.getActualExceptionType() + "|"
                    + diagnostic.getRootCauseType() + "|" + diagnostic.getExpectedInvocationCount() + "|"
                    + diagnostic.getActualInvocationCount() + "|" + diagnostic.getSourceLine() + "|"
                    + diagnostic.getMessage();
            // 一次 JUnit 执行的多个失败都是当前候选的活动事实。
            // 旧实现只保留与第一个失败同根的诊断，会把独立 Oracle 错误静默丢掉；
            // 现在按执行顺序保留最多 4 个去重失败，并由方法级补丁分别处理。
            if (seen.add(key)) history.add(diagnostic);
            if (history.size() >= 4) break;
        }
        return failingTestNames(history);
    }

    private static String runtimeRootKey(RuntimeDiagnostic diagnostic) {
        if (diagnostic == null) return "";
        String category = diagnostic.getCategory() == null ? "" : diagnostic.getCategory();
        String key = category + "|" + diagnostic.getExceptionType() + "|" + diagnostic.getRootCauseType();
        // Assertion and verification failures commonly share the same exception class while having
        // unrelated contracts. Retain their message in the grouping key; fixture/precondition
        // failures instead group by root type so one broken shared setup can be repaired together.
        if (category.isBlank() || category.startsWith("ASSERT") || category.startsWith("MOCKITO_")) {
            key += "|" + diagnostic.getMessage();
        }
        return key.strip();
    }

    private void logCompilerDiagnostics(int candidate, List<CompileDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            LOG.info("UnitMind coverage: candidate=" + candidate + " compile failed without diagnostics");
            return;
        }
        int count = Math.min(5, diagnostics.size());
        for (int index = 0; index < count; index++) {
            CompileDiagnostic diagnostic = diagnostics.get(index);
            LOG.info("UnitMind coverage: candidate=" + candidate + " compileDiagnostic="
                    + safeLogValue(diagnostic.getCode()) + " line=" + diagnostic.getLine()
                    + " origin=" + safeLogValue(diagnostic.getSourceOrigin())
                    + " message=" + safeLogValue(diagnostic.getMessage()));
        }
    }

    private void logValidationDiagnostics(String stage, List<CompileDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return;
        int count = Math.min(12, diagnostics.size());
        for (int index = 0; index < count; index++) {
            CompileDiagnostic diagnostic = diagnostics.get(index);
            LOG.info("UnitMind validation: stage=" + stage + ", diagnosticIndex=" + index
                    + ", code=" + safeLogValue(diagnostic.getCode())
                    + ", line=" + diagnostic.getLine() + ", column=" + diagnostic.getColumn()
                    + ", repairable=" + diagnostic.isRepairable()
                    + ", origin=" + safeLogValue(diagnostic.getSourceOrigin())
                    + ", message=" + safeLogValue(diagnostic.getMessage())
                    + ", snippet=" + safeLogValue(diagnostic.getSnippet()));
        }
    }

    private void logRuntimeDiagnostics(int candidate, List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            LOG.info("UnitMind coverage: candidate=" + candidate + " test failed without diagnostics");
            return;
        }
        int count = Math.min(5, diagnostics.size());
        for (int index = 0; index < count; index++) {
            RuntimeDiagnostic diagnostic = diagnostics.get(index);
            LOG.info("UnitMind coverage: candidate=" + candidate + " runtimeDiagnostic test="
                    + safeLogValue(diagnostic.getTestId()) + " exception="
                    + safeLogValue(diagnostic.getExceptionType()) + " category="
                    + safeLogValue(diagnostic.getCategory()) + " expectedValue="
                    + safeLogValue(diagnostic.getExpectedValue()) + " actualValue="
                    + safeLogValue(diagnostic.getActualValue()) + " expectedException="
                    + safeLogValue(diagnostic.getExpectedExceptionType()) + " actualException="
                    + safeLogValue(diagnostic.getActualExceptionType()) + " rootCause="
                    + safeLogValue(diagnostic.getRootCauseType()) + " message="
                    + safeLogValue(diagnostic.getMessage()));
        }
    }

    private String safeLogValue(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private void updateCoverageProgress(CoverageReport report,
                                        CoverageProgressListener listener,
                                        CoverageConfig config,
                                        CoveragePhase phase,
                                        int candidate,
                                        String text,
                                        double fraction) {
        double visualFraction = coverageProgressFraction(config, phase, candidate, fraction);
        report.updateProgress(phase, candidate, text, visualFraction);
        listener.update(text, visualFraction);
    }

    /**
     * 把每个候选内部会重复的阶段映射到全局单调进度区间。
     * 候选闭环占 6%-84%，余下区间留给基线和最终校验。
     */
    static double coverageProgressFraction(CoverageConfig config,
                                           CoveragePhase phase,
                                           int candidate,
                                           double fallback) {
        if (phase == CoveragePhase.PREPARING) return 0.03d;
        if (phase == CoveragePhase.BASELINE) return 0.90d;
        if (phase == CoveragePhase.VALIDATING) return 0.96d;
        if (phase == CoveragePhase.COMPLETED) return 1.0d;
        if (phase != CoveragePhase.COMPILING && phase != CoveragePhase.EXECUTING
                && phase != CoveragePhase.ANALYZING && phase != CoveragePhase.IMPROVING) {
            return fallback;
        }
        int maxCandidates = Math.max(1, (config == null ? 10
                : config.getMaxRounds() + config.getMaxRepairRounds()) + 1);
        int candidateIndex = Math.max(0, Math.min(maxCandidates - 1, candidate - 1));
        double local = switch (phase) {
            case COMPILING -> 0.12d;
            case EXECUTING -> 0.48d;
            case ANALYZING -> 0.78d;
            case IMPROVING -> 0.98d;
            default -> 0d;
        };
        return 0.06d + ((candidateIndex + local) / maxCandidates) * 0.78d;
    }

    private boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    private String targetClassName(GenerationResult result) {
        MethodModel method = result.getMethodModel();
        ProjectSymbolSnapshot snapshot = result.getSymbolSnapshot();
        if (snapshot != null && snapshot.getTargetType() != null && !snapshot.getTargetType().isBlank()) {
            return JavaBinaryName.fromSourceQualifiedName(snapshot.getTargetType(), method.getPackageName());
        }
        String sourceName = method.getPackageName() == null || method.getPackageName().isBlank()
                ? method.getClassName() : method.getPackageName() + "." + method.getClassName();
        return JavaBinaryName.fromSourceQualifiedName(sourceName, method.getPackageName());
    }

    private GenerationResult stopCoverage(GenerationResult result,
                                          CoverageReport report,
                                          CoverageStatus status,
                                          CoverageStopReason reason,
                                          String message) {
        report.setStatus(status);
        report.setStopReason(reason);
        report.setMessage(message);
        report.setProgressMessage(message);
        result.setCoverageReport(report);
        return result;
    }

    /**
     * 构造发送给大模型的结构化上下文。
     */
    LlmGenerationRequest buildLlmRequest(Project project,
                                         MethodModel methodModel,
                                         List<TestScenario> scenarios,
                                         String sourceText,
                                         ProjectSymbolSnapshot symbolSnapshot) {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setPackageName(methodModel.getPackageName());
        request.setClassName(methodModel.getClassName());
        request.setMethodName(methodModel.getMethodName());
        request.setMethodSignature(methodSignature(methodModel));
        request.setReturnType(methodModel.getReturnType());
        request.setAccessModifier(methodModel.getAccessModifier());
        request.setStaticMethod(methodModel.isStaticMethod());
        request.setMethodSourceCode(methodModel.getMethodBodyText() == null || methodModel.getMethodBodyText().isBlank()
                ? sourceText : methodModel.getMethodBodyText());
        request.setClassFields(classFields(methodModel));
        List<String> chainedCallReturnTypes = chainedCallReturnTypes(project, methodModel);
        List<String> requestImports = augmentedImportsFromChainTypes(methodModel, chainedCallReturnTypes);
        List<CallContextModel> requestCallContexts = enrichChainCallContexts(project, methodModel);
        request.setImports(String.join("\n", requestImports));
        request.setKnownTypes(knownTypes(methodModel, chainedCallReturnTypes, requestImports));
        request.setChainedCallReturnTypes(chainedCallReturnTypes);
        request.setParameters(methodModel.getParameters());
        request.setConstraints(methodModel.getConstraints());
        request.setBranches(methodModel.getBranches());
        request.setDependencies(methodModel.getDependencies());
        request.setUsages(methodModel.getUsages());
        request.setCallContexts(requestCallContexts);
        request.setSymbolSnapshot(symbolSnapshot);
        request.setScenarios(scenarios);
        request.setExistingTestCodeSummary("");
        request.setTestFramework("JUnit5");
        request.setMockFramework("Mockito");
        return request;
    }

    /**
     * 使用 IntelliJ 引用索引补充项目内调用点。
     * 如果索引没有结果，则保留 JavaParser 在当前文件中提取到的 usages。
     */
    private void enrichUsagesFromPsi(Project project, PsiFile psiFile, Editor editor, String preferredMethodName, MethodModel methodModel) {
        if (project == null || methodModel == null) {
            return;
        }
        PsiMethod method = currentPsiMethod(psiFile, editor, preferredMethodName);
        if (method == null || methodModel.getMethodName() == null || !methodModel.getMethodName().equals(method.getName())) {
            return;
        }

        List<MethodUsageModel> usages = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ReferencesSearch.search(method).forEach(reference -> {
            if (usages.size() >= MAX_USAGE_COUNT) {
                return false;
            }
            MethodUsageModel usage = psiUsage(project, reference);
            if (usage == null || isTestUsage(usage.getCallerFilePath())) {
                return true;
            }
            String key = usage.getCallerFilePath() + ":" + usage.getLineNumber() + ":" + usage.getCallExpression();
            if (seen.add(key)) {
                usages.add(usage);
            }
            return true;
        });
        if (!usages.isEmpty()) {
            methodModel.setUsages(usages);
            LOG.info("UnitMind: psi usage count = " + usages.size());
        }
    }

    /**
     * 将一个 PSI 引用转换成发送给大模型的调用点模型。
     */
    private MethodUsageModel psiUsage(Project project, PsiReference reference) {
        if (reference == null || reference.getElement() == null) {
            return null;
        }
        PsiElement element = reference.getElement();
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, false);
        if (call == null) {
            return null;
        }

        MethodUsageModel usage = new MethodUsageModel();
        PsiClass callerClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        PsiMethod callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        PsiFile containingFile = element.getContainingFile();
        usage.setCallerClassName(callerClass == null ? "" : callerClass.getName());
        usage.setCallerFilePath(containingFile == null || containingFile.getVirtualFile() == null ? "" : containingFile.getVirtualFile().getPath());
        usage.setCallerMethodName(callerMethod == null ? "<initializer>" : callerMethod.getName());
        usage.setCallerSignature(callerMethod == null ? "" : psiMethodSignature(callerMethod));
        usage.setCallExpression(call.getText());
        String receiver = call.getMethodExpression().getQualifierExpression() == null
                ? "" : call.getMethodExpression().getQualifierExpression().getText();
        usage.setReceiver(receiver);
        usage.setCallStyle(psiCallStyle(receiver, callerClass == null ? "" : callerClass.getName()));
        usage.setArgumentExpressions(psiArgumentExpressions(call));
        usage.setLineNumber(psiLineNumber(project, containingFile, element));
        usage.setConfidence("HIGH");
        usage.setSourceSnippet(trimSnippet(psiSourceSnippet(call)));
        return usage;
    }

    /**
     * 构造 PSI 方法签名文本。
     */
    private String psiMethodSignature(PsiMethod method) {
        StringBuilder builder = new StringBuilder();
        builder.append(typeText(method.getReturnType(), "void")).append(" ").append(method.getName()).append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(typeText(parameters[i].getType(), "Object")).append(" ").append(parameters[i].getName());
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * 提取 PSI 调用实参。
     */
    private List<String> psiArgumentExpressions(PsiMethodCallExpression call) {
        List<String> arguments = new ArrayList<>();
        for (PsiExpression expression : call.getArgumentList().getExpressions()) {
            arguments.add(expression.getText());
        }
        return arguments;
    }

    /**
     * 判断 PSI 调用风格。
     */
    private String psiCallStyle(String receiver, String callerClassName) {
        if (receiver == null || receiver.isBlank()) {
            return "unqualified";
        }
        if ("this".equals(receiver)) {
            return "this";
        }
        if ("super".equals(receiver)) {
            return "super";
        }
        if (receiver.equals(callerClassName)) {
            return "class";
        }
        return "qualified";
    }

    /**
     * 计算引用所在行号。
     */
    private int psiLineNumber(Project project, PsiFile file, PsiElement element) {
        if (project == null || file == null || element == null) {
            return -1;
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return -1;
        }
        return document.getLineNumber(element.getTextRange().getStartOffset()) + 1;
    }

    /**
     * 获取包含调用表达式的语句片段。
     */
    private String psiSourceSnippet(PsiMethodCallExpression call) {
        PsiStatement statement = PsiTreeUtil.getParentOfType(call, PsiStatement.class, false);
        return statement == null ? call.getText() : statement.getText();
    }

    /**
     * 过滤测试目录中的调用点，避免把已有测试代码当成业务用法。
     */
    private boolean isTestUsage(String filePath) {
        if (filePath == null) {
            return false;
        }
        String normalized = filePath.replace('\\', '/');
        return normalized.contains("/src/test/");
    }

    /**
     * 限制单个调用片段长度，避免局部语句过长导致 Prompt 膨胀。
     */
    private String trimSnippet(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }

    /**
     * 构造方法签名文本，便于 Prompt 不只依赖方法名。
     */
    private String methodSignature(MethodModel methodModel) {
        StringBuilder builder = new StringBuilder();
        builder.append(methodModel.getReturnType()).append(" ").append(methodModel.getMethodName()).append("(");
        for (int i = 0; i < methodModel.getParameters().size(); i++) {
            ParameterModel parameter = methodModel.getParameters().get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameter.getType()).append(" ").append(parameter.getName());
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * 提取字段依赖摘要。
     */
    private String classFields(MethodModel methodModel) {
        StringBuilder builder = new StringBuilder();
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(dependency.getFieldType()).append(" ").append(dependency.getFieldName());
        }
        return builder.toString();
    }

    /**
     * 汇总发送给大模型的已知类型白名单。
     * 大模型只能在这些类型、源码 import 和 JDK/JUnit/Mockito 常用类型中选择，避免按命名习惯猜类型。
     */
    private List<String> knownTypes(MethodModel methodModel,
                                    List<String> chainedCallReturnTypes,
                                    List<String> requestImports) {
        Set<String> types = new LinkedHashSet<>();
        addKnownType(types, methodModel.getClassName());
        addKnownType(types, methodModel.getReturnType());
        for (String importName : requestImports) {
            if (!importName.endsWith(".*")) {
                addKnownType(types, simpleTypeName(importName));
            }
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            addKnownType(types, parameter.getType());
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            addKnownType(types, dependency.getFieldType());
        }
        for (String chainedCallReturnType : chainedCallReturnTypes) {
            addKnownType(types, chainedTypeName(chainedCallReturnType));
        }
        types.add("String");
        types.add("Object");
        types.add("List");
        types.add("Map");
        types.add("Set");
        types.add("Optional");
        types.add("Path");
        types.add("Files");
        types.add("TempDir");
        types.add("Method");
        return new ArrayList<>(types);
    }

    private void addKnownType(Set<String> types, String type) {
        String simple = simpleTypeName(type);
        if (!simple.isBlank()) {
            types.add(simple);
        }
    }

    /**
     * 尽量解析链式调用中间节点的真实返回类型。
     * 无法解析时显式写入 UNKNOWN_USE_RETURNS_DEEP_STUBS，让大模型不要猜中间返回类型。
     */
    private List<String> chainedCallReturnTypes(Project project, MethodModel methodModel) {
        List<String> returnTypes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<CallContextModel> callContexts = methodModel.getCallContexts() == null ? new ArrayList<>() : methodModel.getCallContexts();
        for (CallContextModel callContext : callContexts) {
            if (!isChainContext(callContext)) {
                continue;
            }
            String receiverName = ownerName(callContext.getOwner());
            String receiverType = receiverType(methodModel, callContext.getOwner(), receiverName);
            collectChainedCallReturnTypes(project, methodModel, callContext.getExpression(), receiverName, receiverType, returnTypes, seen);
        }
        if (!returnTypes.isEmpty()) {
            return returnTypes;
        }
        String body = methodModel.getMethodBodyText() == null ? "" : methodModel.getMethodBodyText();
        for (ParameterModel parameter : methodModel.getParameters()) {
            collectChainedCallReturnTypes(project, methodModel, body, parameter.getName(), parameter.getType(), returnTypes, seen);
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            collectChainedCallReturnTypes(project, methodModel, body, dependency.getFieldName(), dependency.getFieldType(), returnTypes, seen);
        }
        return returnTypes;
    }

    private void collectChainedCallReturnTypes(Project project,
                                               MethodModel methodModel,
                                               String expression,
                                               String receiverName,
                                               String receiverType,
                                               List<String> returnTypes,
                                               Set<String> seen) {
        if (receiverName == null || receiverName.isBlank() || expression == null || expression.isBlank()) {
            return;
        }
        List<ChainSegment> segments = parseCallChain(expression, receiverName);
        if (segments.isEmpty()) {
            return;
        }
        String currentExpression = receiverName;
        String currentType = receiverType;
        for (ChainSegment segment : segments) {
            if (currentType == null || currentType.isBlank()) {
                returnTypes.add(currentExpression + "." + segment.asCode() + "(): UNKNOWN_USE_RETURNS_DEEP_STUBS");
                break;
            }
            String cacheKey = currentType + "|" + segment.getMethodName() + "|" + segment.getArgumentCount();
            if (!seen.add(cacheKey + "|" + currentExpression)) {
                currentExpression = currentExpression + "." + segment.asCode();
                continue;
            }
            PsiClass psiClass = resolvePsiClass(project, methodModel, currentType);
            String returnType = resolveMethodReturnType(psiClass, segment.getMethodName(), segment.getArgumentCount());
            if (returnType == null || returnType.isBlank()) {
                returnTypes.add(currentExpression + "." + segment.asCode() + "(): UNKNOWN_USE_RETURNS_DEEP_STUBS");
                break;
            }
            returnTypes.add(currentExpression + "." + segment.asCode() + "(): " + returnType);
            currentExpression = currentExpression + "." + segment.asCode();
            currentType = rawType(returnType);
        }
    }

    private String resolveMethodReturnType(PsiClass psiClass, String methodName, int argumentCount) {
        if (psiClass == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        String resolvedReturnType = null;
        for (PsiMethod method : psiClass.findMethodsByName(methodName, true)) {
            if (method.getParameterList().getParametersCount() == argumentCount) {
                String candidate = canonicalTypeText(method.getReturnType(), "");
                if (resolvedReturnType != null && !resolvedReturnType.equals(candidate)) {
                    return null;
                }
                resolvedReturnType = candidate;
            }
        }
        return resolvedReturnType;
    }

    private List<CallContextModel> enrichChainCallContexts(Project project, MethodModel methodModel) {
        List<CallContextModel> contexts = new ArrayList<>();
        if (methodModel == null || methodModel.getCallContexts() == null) {
            return contexts;
        }

        Set<String> contextKeys = new LinkedHashSet<>();
        for (CallContextModel callContext : methodModel.getCallContexts()) {
            CallContextModel copy = copyCallContext(callContext);
            if (copy != null && contextKeys.add(callContextKey(copy))) {
                contexts.add(copy);
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        for (CallContextModel callContext : methodModel.getCallContexts()) {
            if (!isChainContext(callContext) || "CHAIN_STEP_CALL".equals(callContext.getKind())) {
                continue;
            }
            String receiverName = ownerName(callContext.getOwner());
            String receiverType = receiverType(methodModel, callContext.getOwner(), receiverName);
            List<ChainSegment> segments = parseCallChain(callContext.getExpression(), receiverName);
            String currentType = receiverType;
            String currentExpression = receiverName;
            for (ChainSegment segment : segments) {
                if (currentType == null || currentType.isBlank()) {
                    break;
                }
                String cacheKey = currentType + "|" + segment.getMethodName() + "|" + segment.getArgumentCount();
                if (!seen.add(cacheKey)) {
                    currentType = null;
                    break;
                }
                PsiClass psiClass = resolvePsiClass(project, methodModel, currentType);
                String returnType = resolveMethodReturnType(psiClass, segment.getMethodName(), segment.getArgumentCount());
                CallContextModel synthetic = new CallContextModel();
                synthetic.setKind("CHAIN_STEP_CALL");
                synthetic.setOwner(currentType);
                synthetic.setMethodName(segment.getMethodName());
                synthetic.setExpression(currentExpression + "." + segment.asCode());
                synthetic.setReturnType(returnType == null || returnType.isBlank() ? "UNKNOWN_USE_RETURNS_DEEP_STUBS" : returnType);
                synthetic.setSignature((currentType == null || currentType.isBlank() ? "" : currentType) + "." + segment.getMethodName()
                        + "(" + segment.getArgumentCount() + ")");
                synthetic.setSourceCode("");
                synthetic.setParameterUsageSummary("");
                if (contextKeys.add(callContextKey(synthetic))) {
                    contexts.add(synthetic);
                }
                currentType = rawType(synthetic.getReturnType());
                currentExpression = currentExpression + "." + segment.asCode();
            }
        }
        return contexts;
    }

    private String callContextKey(CallContextModel context) {
        return String.join("|",
                contextValue(context.getKind()),
                contextValue(context.getOwner()),
                contextValue(context.getMethodName()),
                contextValue(context.getExpression()),
                contextValue(context.getSignature()));
    }

    private String contextValue(String value) {
        return value == null ? "" : value;
    }

    private CallContextModel copyCallContext(CallContextModel source) {
        if (source == null) {
            return null;
        }
        CallContextModel context = new CallContextModel();
        context.setKind(source.getKind());
        context.setOwner(source.getOwner());
        context.setMethodName(source.getMethodName());
        context.setExpression(source.getExpression());
        context.setReturnType(source.getReturnType());
        context.setSignature(source.getSignature());
        context.setSourceCode(source.getSourceCode());
        context.setParameterUsageSummary(source.getParameterUsageSummary());
        context.setNestedCalls(source.getNestedCalls() == null ? new ArrayList<>() : new ArrayList<>(source.getNestedCalls()));
        return context;
    }

    private PsiClass resolvePsiClass(Project project, MethodModel methodModel, String type) {
        if (project == null || type == null || type.isBlank()) {
            return null;
        }
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        String simple = simpleTypeName(type);
        if (type.contains(".")) {
            PsiClass direct = facade.findClass(type, scope);
            if (direct != null) {
                return direct;
            }
        }
        for (String importName : methodModel.getImports()) {
            if (importName.endsWith("." + simple)) {
                PsiClass imported = facade.findClass(importName, scope);
                if (imported != null) {
                    return imported;
                }
            }
        }
        if (methodModel.getPackageName() != null && !methodModel.getPackageName().isBlank()) {
            PsiClass samePackage = facade.findClass(methodModel.getPackageName() + "." + simple, scope);
            if (samePackage != null) {
                return samePackage;
            }
        }
        return facade.findClass("java.lang." + simple, scope);
    }

    private boolean isChainContext(CallContextModel callContext) {
        return callContext != null
                && callContext.getExpression() != null
                && callContext.getExpression().contains(".")
                && callContext.getOwner() != null
                && !callContext.getOwner().isBlank();
    }

    private String receiverType(MethodModel methodModel, String owner, String receiverName) {
        String type = ownerType(owner);
        if (type != null && !type.isBlank()) {
            return type;
        }
        if (receiverName != null && !receiverName.isBlank()) {
            for (ParameterModel parameter : methodModel.getParameters()) {
                if (receiverName.equals(parameter.getName())) {
                    return parameter.getType();
                }
            }
            for (DependencyModel dependency : methodModel.getDependencies()) {
                if (receiverName.equals(dependency.getFieldName())) {
                    return dependency.getFieldType();
                }
            }
        }
        return "";
    }

    private String ownerType(String owner) {
        if (owner == null || owner.isBlank()) {
            return "";
        }
        int colon = owner.indexOf(':');
        return colon < 0 ? owner.trim() : owner.substring(colon + 1).trim();
    }

    private String ownerName(String owner) {
        if (owner == null || owner.isBlank()) {
            return "";
        }
        int colon = owner.indexOf(':');
        return colon < 0 ? owner.trim() : owner.substring(0, colon).trim();
    }

    private List<ChainSegment> parseCallChain(String expression, String receiverName) {
        List<ChainSegment> segments = new ArrayList<>();
        if (expression == null || receiverName == null) {
            return segments;
        }
        String text = expression.trim();
        if (!text.startsWith(receiverName)) {
            return segments;
        }
        int index = receiverName.length();
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index >= text.length() || text.charAt(index) != '.') {
            return segments;
        }
        index++;
        while (index < text.length()) {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            int nameStart = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '(' || c == '.' || Character.isWhitespace(c)) {
                    break;
                }
                index++;
            }
            if (index >= text.length() || index == nameStart || text.charAt(index) != '(') {
                break;
            }
            String methodName = text.substring(nameStart, index).trim();
            int openParen = index;
            int closeParen = matchingParen(text, openParen);
            if (closeParen < 0) {
                break;
            }
            String arguments = text.substring(openParen + 1, closeParen);
            segments.add(new ChainSegment(methodName, arguments));
            index = closeParen + 1;
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index >= text.length() || text.charAt(index) != '.') {
                break;
            }
            index++;
        }
        return segments;
    }

    private int matchingParen(String text, int openParen) {
        if (text == null || openParen < 0 || openParen >= text.length() || text.charAt(openParen) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = openParen; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String rawType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        if (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        return value.trim();
    }

    private String canonicalTypeText(PsiType type, String defaultValue) {
        return type == null ? defaultValue : type.getCanonicalText();
    }

    private String chainedTypeName(String chainedCallReturnType) {
        if (chainedCallReturnType == null || chainedCallReturnType.isBlank()) {
            return "";
        }
        int index = chainedCallReturnType.lastIndexOf("): ");
        if (index < 0) {
            return chainedCallReturnType;
        }
        return chainedCallReturnType.substring(index + 3).trim();
    }

    private List<String> augmentedImportsFromChainTypes(MethodModel methodModel,
                                                        List<String> chainedCallReturnTypes) {
        Set<String> imports = new LinkedHashSet<>();
        if (methodModel != null && methodModel.getImports() != null) {
            imports.addAll(methodModel.getImports());
        }
        if (chainedCallReturnTypes != null) {
            for (String chainedCallReturnType : chainedCallReturnTypes) {
                String type = chainedTypeName(chainedCallReturnType);
                if (type.contains(".") && !type.startsWith("java.lang.")) {
                    imports.add(type);
                }
            }
        }
        return new ArrayList<>(imports);
    }

    private static final class ChainSegment {
        private final String methodName;
        private final String arguments;

        private ChainSegment(String methodName, String arguments) {
            this.methodName = methodName == null ? "" : methodName.trim();
            this.arguments = arguments == null ? "" : arguments.trim();
        }

        private String getMethodName() {
            return methodName;
        }

        private int getArgumentCount() {
            if (arguments.isBlank()) {
                return 0;
            }
            int count = 1;
            int depth = 0;
            for (int i = 0; i < arguments.length(); i++) {
                char c = arguments.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    count++;
                }
            }
            return count;
        }

        private String asCode() {
            return methodName + "(" + arguments + ")";
        }
    }

    private String simpleTypeName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        if (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    /**
     * 保留规则生成器推导出的文件名和包名，仅替换大模型返回的源码。
     */
    private GeneratedTestFile copyWithContent(GeneratedTestFile base, String content) {
        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName(base == null ? "" : base.getPackageName());
        file.setClassName(base == null ? "" : base.getClassName());
        file.setFileName(base == null ? "" : base.getFileName());
        file.setContent(content == null ? "" : content);
        return file;
    }

    /**
     * 兜底确保测试代码不为空。
     */
    private GeneratedTestFile ensureGeneratedFile(MethodModel methodModel, GeneratedTestFile file) {
        if (file == null || file.getContent() == null || file.getContent().trim().isEmpty()) {
            return minimalFallbackFile(methodModel);
        }
        return file;
    }

    /**
     * 最小测试类草稿，保证失败场景下测试代码页不空白。
     */
    private GeneratedTestFile minimalFallbackFile(MethodModel methodModel) {
        String className = methodModel == null || methodModel.getClassName() == null || methodModel.getClassName().isBlank()
                ? "Generated" : methodModel.getClassName();
        String packageName = methodModel == null || methodModel.getPackageName() == null ? "" : methodModel.getPackageName();
        String testClassName = className + "Test";
        StringBuilder code = new StringBuilder();
        if (!packageName.isBlank()) {
            code.append("package ").append(packageName).append(";\n\n");
        }
        code.append("import org.junit.jupiter.api.Test;\n\n");
        code.append("class ").append(testClassName).append(" {\n\n");
        code.append("    @Test\n");
        code.append("    void shouldExecuteMethod() {\n");
        code.append("        // TODO: 大模型生成失败，当前为 UnitMind 规则兜底测试草稿。\n");
        code.append("    }\n");
        code.append("}\n");

        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName(packageName);
        file.setClassName(testClassName);
        file.setFileName(testClassName + ".java");
        file.setContent(code.toString());
        return file;
    }

    /**
     * 使用 IntelliJ PSI 构造基础方法模型。
     * 该降级路径绕过整类 JavaParser 解析失败问题，优先保留光标所在方法、参数、返回值和字段依赖。
     */
    private MethodModel buildPsiFallbackModel(PsiFile psiFile, Editor editor, String preferredMethodName) {
        PsiMethod method = currentPsiMethod(psiFile, editor, preferredMethodName);
        if (method == null) {
            throw new IllegalStateException("源码解析失败，且 PSI 未能定位当前方法。请确认光标位于需要生成测试的方法内部。");
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            throw new IllegalStateException("PSI 降级解析失败：当前方法没有所属类。");
        }

        MethodModel model = new MethodModel();
        model.setLanguage("Java");
        model.setPackageName(psiFile instanceof PsiJavaFile ? ((PsiJavaFile) psiFile).getPackageName() : "");
        model.setClassName(containingClass.getName());
        model.setMethodName(method.getName());
        model.setReturnType(typeText(method.getReturnType(), "void"));
        model.setAccessModifier(psiAccessModifier(method));
        model.setProtectedMethod(method.hasModifierProperty(PsiModifier.PROTECTED));
        model.setStaticMethod(method.hasModifierProperty(PsiModifier.STATIC));
        model.setImports(extractPsiImports(psiFile));
        model.setThrownExceptions(extractPsiThrownExceptions(method));
        model.setMethodBodyText(method.getBody() == null ? "" : method.getBody().getText());
        Document sourceDocument = PsiDocumentManager.getInstance(psiFile.getProject()).getDocument(psiFile);
        if (sourceDocument != null) {
            model.setSourceStartLine(sourceDocument.getLineNumber(method.getTextRange().getStartOffset()) + 1);
            model.setSourceEndLine(sourceDocument.getLineNumber(method.getTextRange().getEndOffset()) + 1);
        }
        model.setDeclaredMethodNames(extractPsiMethodNames(containingClass));
        model.setParameters(extractPsiParameters(method));
        model.setSignature(psiMethodSignature(method));
        String ownerName = containingClass.getQualifiedName() == null ? containingClass.getName() : containingClass.getQualifiedName();
        List<String> idTypes = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            idTypes.add(typeText(parameter.getType(), "Object").replaceAll("<.*>", "").replace("...", "[]"));
        }
        model.setMethodId(ownerName + "#" + method.getName() + "(" + String.join(",", idTypes) + ")");
        model.setDependencies(extractPsiDependencies(containingClass, model.getMethodBodyText()));
        model.setCallContexts(extractPsiCallContexts(containingClass, method, model));
        return model;
    }

    private List<CallContextModel> extractPsiCallContexts(PsiClass containingClass, PsiMethod method, MethodModel model) {
        List<CallContextModel> contexts = new ArrayList<>();
        if (method.getBody() == null) {
            return contexts;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : method.getBody().getText().isBlank()
                ? new ArrayList<PsiMethodCallExpression>()
                : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
            CallContextModel context = psiCallContext(containingClass, method, model, call);
            if (context != null && seen.add(context.getKind() + "|" + context.getExpression())) {
                contexts.add(context);
            }
        }
        return contexts;
    }

    private CallContextModel psiCallContext(PsiClass containingClass, PsiMethod currentMethod, MethodModel model, PsiMethodCallExpression call) {
        String expression = call.getText();
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || methodName.isBlank()) {
            return null;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null || "this".equals(qualifier.getText())) {
            PsiMethod resolved = call.resolveMethod();
            if (resolved != null && resolved != currentMethod && resolved.getContainingClass() == containingClass) {
                CallContextModel context = simplePsiCallContext("INTERNAL_METHOD_CALL",
                        model.getClassName(),
                        methodName,
                        expression,
                        typeText(resolved.getReturnType(), ""),
                        psiMethodSignature(resolved),
                        resolved.getBody() == null ? "" : resolved.getBody().getText(),
                        psiParameterUsageSummary(resolved));
                context.setNestedCalls(psiNestedCallSummaries(resolved));
                return context;
            }
            return null;
        }
        String qualifierText = qualifier.getText();
        if (Character.isUpperCase(qualifierText.charAt(0))) {
            return simplePsiCallContext("STATIC_CALL", qualifierText, methodName, expression, "", "", "", "");
        }
        String root = rootReceiver(qualifierText);
        for (ParameterModel parameter : model.getParameters()) {
            if (parameter.getName().equals(root)) {
                return simplePsiCallContext(qualifierText.contains(".") ? "PARAMETER_CHAIN_CALL" : "PARAMETER_OBJECT_CALL",
                        root + ":" + parameter.getType(), methodName, expression, "", "", "", "");
            }
        }
        return null;
    }

    private CallContextModel simplePsiCallContext(String kind,
                                                  String owner,
                                                  String methodName,
                                                  String expression,
                                                  String returnType,
                                                  String signature,
                                                  String sourceCode,
                                                  String parameterUsageSummary) {
        CallContextModel context = new CallContextModel();
        context.setKind(kind);
        context.setOwner(owner);
        context.setMethodName(methodName);
        context.setExpression(expression);
        context.setReturnType(returnType);
        context.setSignature(signature);
        context.setSourceCode(sourceCode);
        context.setParameterUsageSummary(parameterUsageSummary);
        return context;
    }

    private List<String> psiNestedCallSummaries(PsiMethod method) {
        List<String> summaries = new ArrayList<>();
        if (method == null || method.getBody() == null) {
            return summaries;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
            PsiMethod resolved = call.resolveMethod();
            if (resolved != null && resolved != method && seen.add(resolved.getName())) {
                summaries.add(psiMethodSignature(resolved) + "; parameterUsage=" + psiParameterUsageSummary(resolved));
            }
        }
        return summaries;
    }

    private String psiParameterUsageSummary(PsiMethod method) {
        if (method == null || method.getBody() == null) {
            return "";
        }
        List<String> usages = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            Set<String> calls = new LinkedHashSet<>();
            for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                if (qualifier != null && parameter.getName().equals(rootReceiver(qualifier.getText()))) {
                    calls.add(qualifier.getText() + "." + call.getMethodExpression().getReferenceName() + "(...)");
                }
            }
            if (!calls.isEmpty()) {
                usages.add(parameter.getName() + " -> " + String.join(", ", calls));
            }
        }
        return usages.isEmpty() ? "未发现参数对象方法读取" : String.join("; ", usages);
    }

    private String rootReceiver(String qualifierText) {
        int dot = qualifierText == null ? -1 : qualifierText.indexOf('.');
        return dot < 0 ? qualifierText : qualifierText.substring(0, dot);
    }

    /**
     * 识别 PSI 方法真实访问修饰符。
     */
    private String psiAccessModifier(PsiMethod method) {
        if (method.hasModifierProperty(PsiModifier.PUBLIC)) {
            return "public";
        }
        if (method.hasModifierProperty(PsiModifier.PROTECTED)) {
            return "protected";
        }
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return "private";
        }
        return "package-private";
    }

    /**
     * 提取 PSI 类中声明的方法名。
     * PSI 降级路径也需要这些名称来识别本类内部调用。
     */
    private List<String> extractPsiMethodNames(PsiClass psiClass) {
        List<String> names = new ArrayList<>();
        for (PsiMethod method : psiClass.getMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * 定位当前应分析的方法。
     * 优先取光标所在方法；如果光标不在方法内，则按 JavaParser 同样策略回退到指定方法名或首个 public 方法。
     */
    private PsiMethod currentPsiMethod(PsiFile psiFile, Editor editor, String preferredMethodName) {
        if (psiFile != null && editor != null) {
            PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
            PsiMethod current = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
            if (current != null) {
                return current;
            }
        }

        PsiClass psiClass = firstPsiClass(psiFile);
        if (psiClass == null) {
            return null;
        }
        for (PsiMethod method : psiClass.getMethods()) {
            if (preferredMethodName != null && preferredMethodName.equals(method.getName())) {
                return method;
            }
        }
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC)) {
                return method;
            }
        }
        PsiMethod[] methods = psiClass.getMethods();
        return methods.length == 0 ? null : methods[0];
    }

    /**
     * 获取文件中的首个类声明。
     * PSI 降级只需要当前文件主类的字段和方法上下文，内部类不在本次兜底范围内主动展开。
     */
    private PsiClass firstPsiClass(PsiFile psiFile) {
        if (!(psiFile instanceof PsiJavaFile)) {
            return null;
        }
        PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
        return classes.length == 0 ? null : classes[0];
    }

    /**
     * 提取 PSI import 列表。
     * 生成代码时需要复用 JdbcTemplate、Servlet 等类型来源，避免因为降级路径丢失 import。
     */
    private List<String> extractPsiImports(PsiFile psiFile) {
        List<String> imports = new ArrayList<>();
        if (!(psiFile instanceof PsiJavaFile) || ((PsiJavaFile) psiFile).getImportList() == null) {
            return imports;
        }
        for (PsiImportStatement importStatement : ((PsiJavaFile) psiFile).getImportList().getImportStatements()) {
            String qualifiedName = importStatement.getQualifiedName();
            if (qualifiedName != null) {
                imports.add(qualifiedName);
            }
        }
        return imports;
    }

    /**
     * 提取方法声明中的 throws 类型。
     * 降级模型保留这些类型，保证测试方法签名仍能处理受检异常。
     */
    private List<String> extractPsiThrownExceptions(PsiMethod method) {
        List<String> exceptions = new ArrayList<>();
        for (PsiType type : method.getThrowsList().getReferencedTypes()) {
            exceptions.add(type.getPresentableText());
        }
        return exceptions;
    }

    /**
     * 提取方法参数。
     * PSI 给出的类型文本直接进入模型，后续测试数据生成器继续按已有规则补默认值。
     */
    private List<ParameterModel> extractPsiParameters(PsiMethod method) {
        List<ParameterModel> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            ParameterModel model = new ParameterModel();
            model.setName(parameter.getName());
            model.setType(typeText(parameter.getType(), "Object"));
            model.setNullable(!parameter.getType().equals(PsiType.INT)
                    && !parameter.getType().equals(PsiType.LONG)
                    && !parameter.getType().equals(PsiType.BOOLEAN)
                    && !parameter.getType().equals(PsiType.DOUBLE)
                    && !parameter.getType().equals(PsiType.FLOAT)
                    && !parameter.getType().equals(PsiType.SHORT)
                    && !parameter.getType().equals(PsiType.BYTE)
                    && !parameter.getType().equals(PsiType.CHAR));
            parameters.add(model);
        }
        return parameters;
    }

    /**
     * 提取字段依赖并结合方法体文本补充调用表达式。
     * 降级路径不做完整 AST 分析，但会识别 RuntimeDataSourceRegistry 和 JdbcTemplate 这类核心协作者。
     */
    private List<DependencyModel> extractPsiDependencies(PsiClass containingClass, String methodBodyText) {
        List<DependencyModel> dependencies = new ArrayList<>();
        for (PsiField field : containingClass.getFields()) {
            String fieldName = field.getName();
            String fieldType = typeText(field.getType(), "Object");
            if (!looksLikeDependency(fieldName, fieldType)) {
                continue;
            }
            DependencyModel dependency = new DependencyModel();
            dependency.setFieldName(fieldName);
            dependency.setFieldType(fieldType);
            dependency.setDependencyType(dependencyType(fieldName, fieldType));
            dependency.setCalledMethods(new ArrayList<>());
            dependency.setCallExpressions(new ArrayList<>());
            if (methodBodyText != null && methodBodyText.contains(fieldName + ".getJdbcTemplate(")) {
                dependency.getCalledMethods().add("getJdbcTemplate");
                dependency.getCallExpressions().add("getJdbcTemplate(any(), any())");
            }
            if (methodBodyText != null && methodBodyText.contains(fieldName + ".hasConfig(")) {
                dependency.getCalledMethods().add("hasConfig");
                dependency.getCallExpressions().add("hasConfig(any(), any())");
            }
            dependencies.add(dependency);
        }
        return dependencies;
    }

    /**
     * 判断字段是否应作为外部依赖。
     * 规则覆盖常见仓储、客户端、运行时数据源注册表和 JdbcTemplate。
     */
    private boolean looksLikeDependency(String fieldName, String fieldType) {
        String text = (fieldName + " " + fieldType).toLowerCase(Locale.ROOT);
        return text.contains("mapper")
                || text.contains("repository")
                || text.contains("dao")
                || text.contains("client")
                || text.contains("template")
                || text.contains("registry")
                || text.contains("jdbc")
                || text.contains("redis")
                || text.contains("cache")
                || text.contains("service");
    }

    /**
     * 推断依赖业务类型。
     * 该分类主要用于 Mock 预览解释，运行时数据源和 JdbcTemplate 统一归为数据库协作。
     */
    private String dependencyType(String fieldName, String fieldType) {
        String text = (fieldName + " " + fieldType).toLowerCase(Locale.ROOT);
        if (text.contains("jdbc") || text.contains("template") || text.contains("registry")
                || text.contains("mapper") || text.contains("repository") || text.contains("dao")) {
            return "DATABASE";
        }
        if (text.contains("client")) {
            return "REMOTE_CLIENT";
        }
        if (text.contains("redis") || text.contains("cache")) {
            return "CACHE";
        }
        return "UNKNOWN";
    }

    /**
     * 转换 PSI 类型文本。
     * 优先使用 presentableText 保持生成源码简洁，缺失时使用调用方给定默认值。
     */
    private String typeText(PsiType type, String defaultValue) {
        return type == null ? defaultValue : type.getPresentableText();
    }

    /**
     * 静态分析阶段产物。
     */
    private static final class StaticAnalysisContext {
        /** 当前方法模型。 */
        private final MethodModel methodModel;
        /** 当前方法的 PSI 真实符号快照。 */
        private final ProjectSymbolSnapshot symbolSnapshot;

        private StaticAnalysisContext(MethodModel methodModel, ProjectSymbolSnapshot symbolSnapshot) {
            this.methodModel = methodModel;
            this.symbolSnapshot = symbolSnapshot;
        }

        private MethodModel getMethodModel() {
            return methodModel;
        }

        private ProjectSymbolSnapshot getSymbolSnapshot() {
            return symbolSnapshot;
        }
    }
}
