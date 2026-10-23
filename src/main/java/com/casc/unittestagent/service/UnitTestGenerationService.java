package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CompiledTestArtifact;
import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.coverage.CoverageProgressListener;
import com.casc.unittestagent.coverage.CoverageLoopPolicy;
import com.casc.unittestagent.coverage.CoverageRunResult;
import com.casc.unittestagent.coverage.JacocoCoverageExecutor;
import com.casc.unittestagent.coverage.JavaBinaryName;
import com.casc.unittestagent.coverage.MockitoStaticMockSupport;
import com.casc.unittestagent.coverage.MockitoRuntimeDiagnosticEnricher;
import com.casc.unittestagent.coverage.MockitoNoInteractionsContradictionGuard;
import com.casc.unittestagent.coverage.ProjectTestEnvironment;
import com.casc.unittestagent.coverage.ProjectTestEnvironmentResolver;
import com.casc.unittestagent.coverage.ReflectiveAssertionNormalizer;
import com.casc.unittestagent.coverage.TestSandboxCompiler;
import com.casc.unittestagent.coverage.Slf4jMockVerificationGuard;
import com.casc.unittestagent.coverage.MissingFixtureDependencyDetector;
import com.casc.unittestagent.coverage.FixtureLifecycleAnalyzer;
import com.casc.unittestagent.coverage.MockWiringAnalyzer;
import com.casc.unittestagent.coverage.SpringFixtureAnalyzer;
import com.casc.unittestagent.coverage.SpringRuntimeFailureAnalyzer;
import com.casc.unittestagent.coverage.RunnableTestSelector;
import com.casc.unittestagent.model.RunnableTestSelectionReport;
import com.casc.unittestagent.generator.GeneratedTestCodePostProcessor;
import com.casc.unittestagent.generator.ConstructorFixturePreflight;
import com.casc.unittestagent.generator.SourceImportPreflight;
import com.casc.unittestagent.generator.CoverageTestClassStabilizer;
import com.casc.unittestagent.generator.ClassRuntimeRepairStabilizer;
import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.generator.RuntimeOracleGate;
import com.casc.unittestagent.generator.RepairCandidateRejectedException;
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
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.FailureCategory;
import com.casc.unittestagent.model.RepairStrategy;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassJUnitRepairAttempt;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitRepairScope;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.ClassJUnitStopReason;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.OracleDecision;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import com.casc.unittestagent.model.SourceBugAnalysis;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RepairCandidateRejection;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.TestCaseExecution;
import com.casc.unittestagent.model.TestExecutionSummary;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.ValidationStage;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.casc.unittestagent.validation.TestCodeValidator;
import com.github.javaparser.StaticJavaParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.intellij.openapi.application.PathManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

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
    private static final String COVERAGE_ENGINE_REVISION = "state-v5.14-spring-lifecycle-evidence";
    /** 历史候选回退先向模型反馈循环证据，只在同一转换重复时停止。 */
    private static final int MAX_HISTORICAL_REPAIR_RETRIES = 2;
    private static final Pattern MOCKITO_USAGE = Pattern.compile(
            "(?s)(?:import\\s+(?:static\\s+)?org\\.mockito|@Mock\\b|\\bMockito\\.|"
                    + "\\b(?:mock|spy|when|verify|doReturn|doThrow|doNothing)\\s*\\()");
    /** 源码分析器，负责将 PSI 文本转换为方法模型。 */

    /** 测试数据补全器，负责为方法参数填充候选值。 */

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
    private final ClassRuntimeRepairStabilizer classRuntimeRepairStabilizer =
            new ClassRuntimeRepairStabilizer();
    private final RuntimeOracleGate runtimeOracleGate = new RuntimeOracleGate();
    /** PSI 真实符号快照构建器。 */
    private final ProjectSymbolSnapshotBuilder symbolSnapshotBuilder;
    /** 项目编译验证器。 */
    private final TestCodeValidator testCodeValidator;
    /** 大模型客户端工厂。 */
    private final LlmClientFactory llmClientFactory;
    private final ProjectTestEnvironmentResolver coverageEnvironmentResolver;
    /** 最近一次规则生成接线的 Mockito 可用性；null=未知（按可用处理，兼容旧流程）。 */
    private Boolean mockitoAvailabilityOverride;
    private final TestSandboxCompiler sandboxCompiler;
    private final JacocoCoverageExecutor coverageExecutor;

    private final BooleanSupplier taskCancellation;
    private final SourceAnalysisService sourceAnalysisService;
    private final com.casc.unittestagent.coverage.ExecutionEnvironmentSession executionEnvironmentSession;

    public UnitTestGenerationService() { this((BooleanSupplier) null); }

    public UnitTestGenerationService(BooleanSupplier cancelled) {
        this(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), new DefaultLlmClientFactory(), cancelled);
    }

    public UnitTestGenerationService(ProjectSymbolSnapshotBuilder symbolSnapshotBuilder,
                                     TestCodeValidator testCodeValidator,
                                     LlmClientFactory llmClientFactory) {
        this(symbolSnapshotBuilder, testCodeValidator, llmClientFactory, null);
    }

    public UnitTestGenerationService(ProjectSymbolSnapshotBuilder symbolSnapshotBuilder,
                                     TestCodeValidator testCodeValidator,
                                     LlmClientFactory llmClientFactory, BooleanSupplier cancelled) {
        this(symbolSnapshotBuilder, testCodeValidator, llmClientFactory, cancelled, TestExecutionServices.defaults());
    }

    public UnitTestGenerationService(ProjectSymbolSnapshotBuilder symbolSnapshotBuilder, TestCodeValidator testCodeValidator,
            LlmClientFactory llmClientFactory, BooleanSupplier cancelled, TestExecutionServices execution) {
        this.coverageEnvironmentResolver = execution.environments();
        this.sandboxCompiler = execution.compiler();
        this.coverageExecutor = execution.executor();
        this.executionEnvironmentSession = new com.casc.unittestagent.coverage.ExecutionEnvironmentSession(coverageEnvironmentResolver);
        this.taskCancellation = cancelled;
        this.sourceAnalysisService = new SourceAnalysisService(symbolSnapshotBuilder, cancelled);
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
        return sourceAnalysisService.analyzeMethod(project, psiFile, editor);
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
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
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
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
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
        LlmClient llmClient = llmClientFactory.create(llmConfig, taskCancellation);
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

    /**
     * 规则生成器的真实环境接线：注入 classpath 可实例化探针与 Mockito 可用性。
     * 解析失败时保持默认（可用+无探针），不阻塞生成——候选引用 org.mockito 而项目
     * 缺 Mockito 的场景由 ProjectCompileVerifier 的 MOCKITO_UNAVAILABLE 可修复诊断兜底。
     */
    private boolean configureRuleGenerator(Project project, PsiFile psiFile) {
        // 默认按可用处理（兼容无环境上下文的旧流程）；解析成功才覆写。
        mockitoAvailabilityOverride = null;
        testGenerator.setMockitoAvailable(true);
        testGenerator.setTypeInstantiabilityProbe(null);
        if (project == null || psiFile == null) return true;
        try {
            ProjectTestEnvironment environment = coverageEnvironmentResolver.resolve(project, psiFile);
            if (!environment.isUsable()) return true;
            List<java.io.File> probeClasspath = new ArrayList<>(environment.getRuntimeClasspath());
            testGenerator.setTypeInstantiabilityProbe(
                    com.casc.unittestagent.generator.ClasspathTypeProbe.from(probeClasspath));
            boolean available = probeClasspath.stream()
                    .anyMatch(file -> file != null && file.getName().startsWith("mockito-core"));
            testGenerator.setMockitoAvailable(available);
            mockitoAvailabilityOverride = available;
            return available;
        } catch (Exception | Error resolveFailure) {
            if (resolveFailure instanceof ProcessCanceledException cancellation) throw cancellation;
            LOG.warn("UnitMind: rule generator environment probe failed, keep defaults: "
                    + resolveFailure.getClass().getSimpleName());
            return true;
        }
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
            configureRuleGenerator(project, psiFile);
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
                    LlmClient llmClient = llmClientFactory.create(llmConfig, taskCancellation);
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
                    if (llmException instanceof ProcessCanceledException cancellation) throw cancellation;
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
                generatedTestFile = validateAndRepair(project, psiFile, result, generatedTestFile, llmConfig, true);
            } else {
                var fixture = prepareGenerationFixtures(project, psiFile, result, generatedTestFile.getContent());
                generatedTestFile = copyWithContent(generatedTestFile, fixture.code());
                result.setValidationResult(fixture.diagnostics().isEmpty()
                        ? ValidationResult.status(ValidationStatus.NOT_RUN) : fixtureValidation(fixture));
            }
            result.setMethodModel(methodModel);
            result.setGeneratedTestFile(generatedTestFile);
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
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
        return validateCode(project, psiFile, generatedFile, code, taskCancellation);
    }

    public ValidationResult validateCode(Project project, PsiFile psiFile, GeneratedTestFile generatedFile,
                                         String code, BooleanSupplier cancelled) {
        ValidationResult validation = testCodeValidator.validate(project, psiFile, generatedFile, code, cancelled);
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
        // Class assembly probes have no method model. Runtime repair candidates retain their own scope gates.
        return validateAndRepair(project, psiFile, result, initialFile, llmConfig,
                result != null && result.getMethodModel() == null);
    }

    private ConstructorFixturePreflight.Result prepareGenerationFixtures(Project project, PsiFile psiFile,
                                                                          GenerationResult result, String code) {
        var imported = SourceImportPreflight.prepare(project, psiFile, code);
        code = imported.code();
        var policy = new LlmGenerationRequest();
        MockStrategy strategy = selectedMockStrategy(result);
        applySelectedMockPolicy(policy, strategy);
        boolean allowMocks = "SELECTED_ONLY".equals(policy.getMockUsagePolicy())
                || strategy == null && MOCKITO_USAGE.matcher(code).find()
                && !(mockitoAvailabilityOverride != null && !mockitoAvailabilityOverride);
        var fixture = ConstructorFixturePreflight.prepare(project, psiFile, code, allowMocks);
        if (imported.changed()) {
            var warnings = new ArrayList<>(fixture.warnings());
            warnings.add("已依据当前模块生产源码的真实类型绑定补齐导入：" + String.join(", ", imported.imports()));
            fixture = new ConstructorFixturePreflight.Result(fixture.code(), fixture.changes() + imported.imports().size(), warnings, fixture.diagnostics());
        }
        if (psiFile != null) {
            var lifecycleDiagnostics = new ArrayList<>(FixtureLifecycleAnalyzer.preflight(ReadAction.compute(psiFile::getText), fixture.code()));
            lifecycleDiagnostics.addAll(MockWiringAnalyzer.preflight(MockWiringAnalyzer.sources(psiFile), fixture.code()));
            lifecycleDiagnostics.addAll(SpringFixtureAnalyzer.preflight(MockWiringAnalyzer.sources(psiFile), fixture.code()));
            if (!lifecycleDiagnostics.isEmpty()) {
                var allDiagnostics = new ArrayList<>(fixture.diagnostics()); allDiagnostics.addAll(lifecycleDiagnostics);
                fixture = new ConstructorFixturePreflight.Result(fixture.code(), fixture.changes(), fixture.warnings(), allDiagnostics);
            }
        }
        addWarnings(result, fixture.warnings());
        if (fixture.changes() > 0 || !fixture.diagnostics().isEmpty()) LOG.info(
                "UnitMind generation preflight: imports=" + imported.imports().size()
                        + ", constructorFills=" + (fixture.changes() - imported.imports().size())
                        + ", unresolved=" + fixture.diagnostics().size());
        return fixture;
    }

    private static ValidationResult fixtureValidation(ConstructorFixturePreflight.Result fixture) {
        var validation = ValidationResult.status(ValidationStatus.FAILED);
        validation.setCodeHash(CodeFingerprint.sha256(fixture.code()));
        fixture.diagnostics().forEach(d -> d.setValidationStage(ValidationStage.SOURCE_PREFLIGHT));
        validation.setDiagnostics(fixture.diagnostics());
        return validation;
    }

    private GeneratedTestFile validateAndRepair(Project project,
                                        PsiFile psiFile,
                                        GenerationResult result,
                                        GeneratedTestFile initialFile,
                                        LlmConfig llmConfig,
                                        boolean prepareFixtures) {
        return validateAndRepair(project, psiFile, result, initialFile, llmConfig, prepareFixtures, taskCancellation);
    }

    private GeneratedTestFile validateAndRepair(Project project, PsiFile psiFile, GenerationResult result,
            GeneratedTestFile initialFile, LlmConfig llmConfig, boolean prepareFixtures, BooleanSupplier cancelled) {
        var operation = com.casc.unittestagent.util.OperationContext.capture(cancelled);
        GeneratedTestFile candidate = initialFile;
        int repairAttempts = 0;
        Set<String> seenFailures = new HashSet<>();
        GeneratedTestFile repairParent = null;
        ValidationResult repairParentValidation = null;
        String rejectedRepairFeedback = "";
        boolean allowRepair = llmConfig != null && llmConfig.isEnabled() && result.isGeneratedByLlm();
        LlmClient repairClient = allowRepair ? llmClientFactory.create(llmConfig, cancelled) : null;

        while (true) {
            operation.checkCancelled();
            if (!prepareFixtures) {
                var imported = SourceImportPreflight.prepare(project, psiFile, candidate.getContent());
                if (imported.changed()) {
                    candidate = copyWithContent(candidate, imported.code());
                    addWarnings(result, List.of("已依据生产源码类型绑定补齐导入：" + String.join(", ", imported.imports())));
                }
            }
            var fixture = prepareFixtures ? prepareGenerationFixtures(project, psiFile, result, candidate.getContent()) : null;
            if (fixture != null && fixture.changes() > 0) candidate = copyWithContent(candidate, fixture.code());
            ValidationResult validation = fixture != null && !fixture.diagnostics().isEmpty()
                    ? fixtureValidation(fixture) : validateCode(project, psiFile, candidate, candidate.getContent(), cancelled);
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
                        generatedCodePostProcessor.processRepair(repaired.getTestCode(), methodModel, candidate.getContent());
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
                if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
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
        long beforeNonRepairable = before.stream().filter(item -> !item.isRepairable()).count();
        long afterNonRepairable = after.stream().filter(item -> !item.isRepairable()).count();
        if (afterNonRepairable > beforeNonRepairable) return true;
        if (!before.isEmpty() && !after.isEmpty()
                && before.stream().allMatch(d -> d.getValidationStage() == ValidationStage.SOURCE_PREFLIGHT)
                && after.stream().allMatch(d -> d.getValidationStage() == ValidationStage.JAVA_COMPILATION
                && d.isRepairable() && "TEST_SOURCE".equals(d.getSourceOrigin()))) return false;
        // javac stops before flow analysis when symbol attribution fails. Fixing an import can
        // therefore expose MORE checked-exception errors without introducing a regression.
        // Only accept this known phase transition; unrelated/new resolution errors still use
        // the normal regression gate, and target-source/environment errors never qualify.
        if (!before.isEmpty() && !after.isEmpty()
                && before.stream().allMatch(item -> item.isRepairable() && "TEST_SOURCE".equals(item.getSourceOrigin())
                && Set.of("CANNOT_FIND_SYMBOL", "PACKAGE_NOT_FOUND").contains(item.getCode()))
                && after.stream().allMatch(item -> item.isRepairable() && "TEST_SOURCE".equals(item.getSourceOrigin())
                && "UNREPORTED_EXCEPTION".equals(item.getCode()))) return false;
        return after.size() > before.size();
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
        request.setMockUsagePolicy(enabledMockito
                && !(mockitoAvailabilityOverride != null && !mockitoAvailabilityOverride)
                ? "SELECTED_ONLY" : "FORBIDDEN");
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
            // 用户在场景表格取消勾选的场景保留展示，但不再进入生成。
            if (!scenario.isEnabled()) {
                continue;
            }
            executable.add(scenario);
        }
        return executable;
    }

    /**
     * 读取 PSI 并完成静态方法模型构建。
     */


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
            LlmClient llmClient = llmClientFactory.create(llmConfig, taskCancellation);
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
                    generatedCodePostProcessor.processRepair(llmResponse.getTestCode(), methodModel, currentCode);
            GeneratedTestFile regenerated = copyWithContent(result.getGeneratedTestFile(), processedCode.getCode());
            result.setGeneratedByLlm(true);
            result.setRuleBasedFallback(false);
            result.setLlmMessage("大模型重新生成完成。");
            addWarnings(result, llmResponse.getWarnings());
            addWarnings(result, processedCode.getWarnings());
            result.setGeneratedTestFile(validateAndRepair(project, psiFile, result, regenerated, llmConfig, true));
            return result;
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
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
     * 对已经聚合且编译通过的整类测试执行最终 JUnit，并在独立预算内做单调运行修复。
     * 本闭环不计算类级覆盖率；任何候选只有在保护既有通过测试且减少失败集合时才会被接纳。
     */
    public ClassGenerationResult runFinalClassJUnitRepair(Project project,
                                                          PsiFile psiFile,
                                                          ClassGenerationResult result,
                                                          String currentCode,
                                                          BooleanSupplier cancelled,
                                                          CoverageProgressListener progress) {
        runJUnitValidation(project, psiFile, result, currentCode, cancelled, progress, true);
        result.setClassJUnitRepairReport(result.getRuntimeValidationReport());
        return result;
    }

    /** 只做编译、真实执行和分类修复，不启动覆盖率探索或检查覆盖率阈值。 */
    public GenerationResult runRuntimeValidation(Project project, PsiFile psiFile,
                                                 GenerationResult result, String code,
                                                 BooleanSupplier cancelled,
                                                 CoverageProgressListener progress) {
        return runJUnitValidation(project, psiFile, result, code, cancelled, progress, true);
    }

    /** Explicitly retain passing test method groups; never invokes a model or weakens assertions. */
    public GenerationResult retainRunnableTests(Project project, PsiFile psiFile, GenerationResult result,
                                                String code, BooleanSupplier cancelled, CoverageProgressListener progress) {
        long started = System.currentTimeMillis();
        String original = code == null ? "" : code;
        var priorExclusions = result.getRunnableTestSelectionReport().getActiveExclusionOrigins();
        var selection = new RunnableTestSelectionReport(); selection.setOriginalCode(original);
        selection.inheritExclusions(priorExclusions);
        result.setFinalTestFileReport(new com.casc.unittestagent.model.FinalTestFileReport());
        selection.setStatus(RunnableTestSelectionReport.Status.RUNNING);
        result.setRunnableTestSelectionReport(selection);
        result.setRuntimeValidationReport(ClassJUnitRepairReport.status(ClassJUnitStatus.NOT_RUN));
        if (result instanceof ClassGenerationResult classResult) classResult.setClassJUnitRepairReport(result.getRuntimeValidationReport());
        result.setValidationResult(ValidationResult.status(ValidationStatus.NOT_RUN));
        CoverageReport coverage = CoverageReport.status(CoverageStatus.NOT_RUN);
        result.setCoverageReport(coverage);
        try {
            if (psiFile == null || result.getMethodModel() == null || result.getGeneratedTestFile() == null
                    || original.isBlank()) throw new IllegalArgumentException("缺少被测源码或待筛选的测试代码");
            String source = ReadAction.compute(psiFile::getText);
            String sourceHash = CodeFingerprint.sha256(source);
            selection.setSourceHash(sourceHash); coverage.setSourceHash(sourceHash);
            // Persist before the first execution; the .txt extension cannot enter Java compilation.
            Path base = Path.of(PathManager.getSystemPath(),
                    "unitmind", "test-selections");
            Files.createDirectories(base);
            Path archive = Files.createTempDirectory(base, "selection-");
            Path originalPath = archive.resolve("OriginalTest.java.txt");
            Files.writeString(originalPath, original, StandardCharsets.UTF_8);
            selection.setArchivePath(originalPath.toString());
            var environment = executionEnvironmentSession.resolve(project, psiFile, cancelled);
            if (!environment.isUsable()) throw new IllegalStateException(environment.getDiagnostics().stream()
                    .findFirst().map(com.casc.unittestagent.model.CompileDiagnostic::getMessage).orElse("无法复用项目执行环境"));
            coverage.setRuntimeConfigurationOrigin(environment.getRuntimeConfiguration().getOrigin());
            CoverageConfig config = CoverageConfigManager.load(); GeneratedTestFile template = result.getGeneratedTestFile();
            var sourceImports = SourceImportPreflight.prepare(project, psiFile, original);
            var selected = new RunnableTestSelector().select(sourceImports.code(), (candidate, measure) ->
                    executeSelectionCandidate(environment, template, candidate, targetClassName(result),
                            result.getMethodModel(), source, config, measure, cancelled), cancelled, progress);
            var completed = selected.report(); completed.setOriginalCode(original);
            completed.inheritExclusions(priorExclusions);
            if (sourceImports.changed()) completed.setMessage(completed.getMessage() + " 已依据生产源码补齐类型导入："
                    + String.join(", ", sourceImports.imports()));
            completed.setSourceHash(sourceHash); completed.setArchivePath(originalPath.toString());
            selection = completed; result.setRunnableTestSelectionReport(completed);
            var details = new JSONObject().put("status", completed.getStatus().name())
                    .put("runtimeConfigurationOrigin", environment.getRuntimeConfiguration().getOrigin())
                    .put("sourceHash", sourceHash).put("originalCodeHash", CodeFingerprint.sha256(original))
                    .put("selectedCodeHash", CodeFingerprint.sha256(completed.getSelectedCode()))
                    .put("originalMethodGroups", completed.getOriginalMethods()).put("executions", completed.getExecutions())
                    .put("runtimeEvidenceArchives", completed.getRuntimeEvidenceArchives())
                    .put("retainedTests", completed.getRetainedTests()).put("removedHelpers", completed.getRemovedHelpers()).put("message", completed.getMessage());
            if (selected.finalProbe() != null) {
                var snapshot = selected.finalProbe().run().getSnapshot();
                details.put("coverage", new JSONObject().put("available", snapshot.isAvailable())
                        .put("scope", snapshot.getMeasurementScope()).put("measuredMethods", snapshot.getMeasuredMethods())
                        .put("lineCovered", snapshot.getLine().getCovered()).put("lineMissed", snapshot.getLine().getMissed())
                        .put("branchCovered", snapshot.getBranch().getCovered()).put("branchMissed", snapshot.getBranch().getMissed())
                        .put("engineRevision", COVERAGE_ENGINE_REVISION).put("testsPassed", snapshot.getExecution().getSucceeded())
                        .put("lineRate", snapshot.isAvailable() ? snapshot.getLine().getRate() : JSONObject.NULL)
                        .put("branchRate", snapshot.isAvailable() && snapshot.getBranch().isApplicable()
                                ? snapshot.getBranch().getRate() : JSONObject.NULL));
            }
            var exclusions = new JSONArray();
            completed.getExcludedTests().forEach(item -> exclusions.put(new JSONObject()
                    .put("testId", item.testId()).put("reason", item.reason())));
            details.put("excludedTests", exclusions);
            details.put("inheritedExcludedTests", priorExclusions.stream().flatMap(o -> o.tests().stream())
                    .map(RunnableTestSelectionReport.ExcludedTest::testId).distinct().toList());
            Files.writeString(archive.resolve("selection-summary.json"), details.toString(2));
            if (completed.getStatus() == RunnableTestSelectionReport.Status.PASSED) {
                String retained = completed.getSelectedCode();
                Files.writeString(archive.resolve("RetainedTest.java.txt"), retained);
                result.setGeneratedTestFile(copyWithContent(template, retained));
                result.setValidationResult(selected.finalProbe().validation());
                var execution = selected.finalProbe().run().getSnapshot().getExecution();
                var runtime = ClassJUnitRepairReport.status(ClassJUnitStatus.PASSED);
                runtime.setCodeHash(CodeFingerprint.sha256(retained)); runtime.setSourceHash(sourceHash);
                runtime.setRuntimeConfigurationOrigin(environment.getRuntimeConfiguration().getOrigin());
                runtime.setInitialExecution(execution); runtime.setFinalExecution(execution);
                runtime.setDurationMillis(System.currentTimeMillis() - started);
                runtime.setStopReason(ClassJUnitStopReason.INITIAL_PASS); runtime.setMessage(completed.getMessage());
                result.setRuntimeValidationReport(runtime);
                if (result instanceof ClassGenerationResult classResult) classResult.setClassJUnitRepairReport(runtime);
                coverage.setCodeHash(CodeFingerprint.sha256(retained));
                coverage.setGenerated(selected.finalProbe().run().getSnapshot());
                coverage.setGaps(selected.finalProbe().run().getGaps());
                coverage.setHistory(List.of(coverage.getGenerated()));
                boolean available = coverage.getGenerated().isAvailable();
                coverage.setVisitedPhases(available
                        ? List.of(CoveragePhase.PREPARING, CoveragePhase.COMPILING, CoveragePhase.EXECUTING, CoveragePhase.ANALYZING)
                        : List.of(CoveragePhase.PREPARING, CoveragePhase.COMPILING, CoveragePhase.EXECUTING));
                coverage.setPhase(CoveragePhase.COMPLETED); coverage.setProgressFraction(1d);
                coverage.setProgressMessage(available ? "保留集合完整回归通过，覆盖率采集完成。" : "保留集合回归通过；覆盖率不可用。");
                coverage.setStatus(!available ? CoverageStatus.ENVIRONMENT_ERROR
                        : CoverageLoopPolicy.reached(coverage.getGenerated(), config) ? CoverageStatus.PASSED : CoverageStatus.BELOW_THRESHOLD);
                coverage.setStopReason(!available ? CoverageStopReason.ENVIRONMENT_ERROR
                        : coverage.getStatus() == CoverageStatus.PASSED ? CoverageStopReason.TARGET_REACHED : CoverageStopReason.NONE);
                coverage.setMessage(completed.getMessage() + " 当前报告为筛选后集合对所选被测方法的覆盖率，未执行自动补测。"
                        + "未通过用例的业务预期仍需人工检查，排除不代表这些预期无效。");
            } else {
                coverage.setStatus(completed.getStatus() == RunnableTestSelectionReport.Status.CANCELLED
                        ? CoverageStatus.CANCELLED : CoverageStatus.NOT_RUN);
                coverage.setMessage(completed.getMessage());
            }
        } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
            selection.setStatus(RunnableTestSelectionReport.Status.CANCELLED);
            selection.setMessage("筛选已取消，原代码保留。"); coverage.setStatus(CoverageStatus.CANCELLED);
            coverage.setMessage(selection.getMessage());
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
            selection.setStatus(RunnableTestSelectionReport.Status.BLOCKED);
            selection.setMessage("筛选未完成，原代码保留：" + safeMessage(ex));
            coverage.setStatus(CoverageStatus.NOT_RUN); coverage.setMessage(selection.getMessage());
        } finally { coverage.setDurationMillis(System.currentTimeMillis() - started); }
        return result;
    }

    private RunnableTestSelector.Probe executeSelectionCandidate(ProjectTestEnvironment environment,
            GeneratedTestFile template, String code, String targetClassName, MethodModel method, String source,
            CoverageConfig config, boolean measure, BooleanSupplier cancelled) {
        long started = System.currentTimeMillis();
        if (!CodeFingerprint.sha256(environment.getSourceText()).equals(CodeFingerprint.sha256(source))) {
            throw new IllegalStateException("编译环境与验证请求的源码快照不一致，请重新验证。");
        }
        try (var artifact = sandboxCompiler.compile(environment, copyWithContent(template, code), code, targetClassName, cancelled)) {
            var validation = ValidationResult.status(artifact.isSuccessful() ? ValidationStatus.PASSED
                    : artifact.getDiagnostics().stream().anyMatch(d -> !d.isRepairable())
                    ? ValidationStatus.ENVIRONMENT_ERROR : ValidationStatus.FAILED);
            validation.setCodeHash(CodeFingerprint.sha256(code));
            validation.setCandidateEvidence(artifact.getCandidateEvidence()); validation.setDiagnostics(new ArrayList<>(artifact.getDiagnostics()));
            validation.setDurationMillis(System.currentTimeMillis() - started);
            validation.setTestEnvironmentNote(environment.getTestEnvironmentNote());
            CoverageRunResult run = !artifact.isSuccessful() ? new CoverageRunResult()
                    : measure ? coverageExecutor.execute(artifact, method, source, config, cancelled)
                    : coverageExecutor.executeTestsOnly(artifact, config, cancelled);
            return new RunnableTestSelector.Probe(validation, run);
        }
    }

    /** 合并后执行完整测试类；不自动修改最终文本或已有测试。 */
    public GenerationResult runFinalTestRegression(Project project, PsiFile psiFile,
                                                  GenerationResult result, String code,
                                                  BooleanSupplier cancelled,
                                                  CoverageProgressListener progress) {
        return runJUnitValidation(project, psiFile, result, code, cancelled, progress, false);
    }

    /** Executes and measures the exact final file, without repairs, selection or coverage exploration. */
    public GenerationResult runFinalTestRegressionWithCoverage(Project project, PsiFile psiFile,
            GenerationResult result, String code, String targetPath, BooleanSupplier cancelled,
            CoverageProgressListener progress) {
        long started = System.currentTimeMillis();
        var finalFile = new com.casc.unittestagent.model.FinalTestFileReport();
        finalFile.setTargetPath(targetPath); result.setFinalTestFileReport(finalFile);
        var coverage = finalFile.getMethodCoverage();
        coverage.setCodeHash(CodeFingerprint.sha256(code));
        coverage.setStatus(CoverageStatus.RUNNING);
        var runtime = ClassJUnitRepairReport.status(ClassJUnitStatus.RUNNING);
        result.setRuntimeValidationReport(runtime);
        if (result instanceof ClassGenerationResult classResult) classResult.setClassJUnitRepairReport(runtime);
        var listener = progress == null ? CoverageProgressListener.NONE : progress;
        try {
            if (code == null || code.isBlank() || result.getMethodModel() == null || result.getGeneratedTestFile() == null) {
                throw new IllegalArgumentException("缺少最终完整测试代码或被测方法。");
            }
            result.setGeneratedTestFile(copyWithContent(result.getGeneratedTestFile(), code));
            String source = ReadAction.compute(psiFile::getText);
            runtime.setSourceHash(CodeFingerprint.sha256(source)); runtime.setCodeHash(CodeFingerprint.sha256(code));
            coverage.setSourceHash(runtime.getSourceHash());
            var environment = executionEnvironmentSession.resolve(project, psiFile, cancelled);
            runtime.setRuntimeConfigurationOrigin(environment.getRuntimeConfiguration().getOrigin());
            coverage.setRuntimeConfigurationOrigin(runtime.getRuntimeConfigurationOrigin());
            if (!environment.isUsable()) {
                result.setValidationResult(ValidationResult.status(ValidationStatus.ENVIRONMENT_ERROR));
                runtime.setStatus(ClassJUnitStatus.ENVIRONMENT_ERROR);
                throw new IllegalStateException("最终文件测试执行环境不可用。");
            }
            var config = CoverageConfigManager.load();
            listener.update("正在执行最终完整文件的独立 JUnit 回归...", 0.1);
            var baseline = executeSelectionCandidate(environment, result.getGeneratedTestFile(), code,
                    targetClassName(result), result.getMethodModel(), source, config, false, cancelled);
            result.setValidationResult(baseline.validation());
            var initial = baseline.run().getSnapshot().getExecution();
            requireDeclaredTestEvidence(code, initial);
            runtime.setInitialExecution(initial); runtime.setFinalExecution(initial);
            runtime.setCandidateEvidence(baseline.validation().getCandidateEvidence());
            coverage.setCandidateEvidence(baseline.validation().getCandidateEvidence());
            if (baseline.run().isCancelled() || isCancelled(cancelled)) throw new com.intellij.openapi.progress.ProcessCanceledException();
            if (baseline.validation().getStatus() != ValidationStatus.PASSED || !runtimeExecutionPassed(initial)) {
                MockitoRuntimeDiagnosticEnricher.enrich(initial.getDiagnostics(), code);
                MockWiringAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), code, initial.getDiagnostics());
                SpringFixtureAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), initial.getDiagnostics());
                com.casc.unittestagent.coverage.RuntimeEvidenceArchive.preserveSourceAnalysis(initial);
                MissingFixtureDependencyDetector.inspect(project, source, code, initial.getDiagnostics());
                runtime.setStatus(baseline.run().isEnvironmentError()
                        || baseline.validation().getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                        ? ClassJUnitStatus.ENVIRONMENT_ERROR : ClassJUnitStatus.TEST_FAILED);
                runtime.setStopReason(ClassJUnitStopReason.TEST_DESIGN_ERROR);
                runtime.setMessage("最终合并文件未通过完整回归，已阻止写入：" + baseline.run().getSnapshot().getMessage()
                        + finalFileFailureEvidence(baseline.validation(), initial));
                coverage.setStatus(CoverageStatus.TEST_FAILED); coverage.setMessage(runtime.getMessage());
                return result;
            }
            runtime.setStatus(ClassJUnitStatus.PASSED); runtime.setStopReason(ClassJUnitStopReason.INITIAL_PASS);
            runtime.setMessage("最终完整文件独立 JUnit 回归通过。");
            listener.update("正在对最终完整文件采集覆盖率...", 0.55);
            if (isCancelled(cancelled)) throw new com.intellij.openapi.progress.ProcessCanceledException();
            var probe = executeSelectionCandidate(environment, result.getGeneratedTestFile(), code,
                    targetClassName(result), result.getMethodModel(), source, config, true, cancelled);
            if (probe.run().isCancelled() || isCancelled(cancelled)) throw new com.intellij.openapi.progress.ProcessCanceledException();
            result.setValidationResult(probe.validation());
            if (runtime.getCandidateEvidence() == null || probe.validation().getCandidateEvidence() == null
                    || !runtime.getCandidateEvidence().identity().equals(probe.validation().getCandidateEvidence().identity())
                    || !com.casc.unittestagent.coverage.CandidateEvidenceFactory.isCurrent(project, runtime.getCandidateEvidence())) {
                runtime.setStatus(ClassJUnitStatus.STALE);
                coverage.setStatus(CoverageStatus.STALE);
                coverage.setMessage("最终两次验证之间执行环境已变化，请重新验证。");
                return result;
            }
            var run = probe.run(); var execution = run.getSnapshot().getExecution();
            requireDeclaredTestEvidence(code, execution);
            com.casc.unittestagent.validation.JUnitExecutionEvidence.requireSameInventory(initial, execution);
            if (probe.validation().getStatus() != ValidationStatus.PASSED
                    || execution.getFound() > 0 && !runtimeExecutionPassed(execution)
                    || !run.isEnvironmentError() && !runtimeExecutionPassed(execution)) {
                runtime.setStatus(ClassJUnitStatus.TEST_FAILED);
                runtime.setFinalExecution(execution);
                runtime.setStopReason(ClassJUnitStopReason.TEST_DESIGN_ERROR);
                runtime.setMessage("最终文件在覆盖率运行中未通过，已阻止写入：" + run.getSnapshot().getMessage()
                        + finalFileFailureEvidence(probe.validation(), execution));
                coverage.setStatus(CoverageStatus.TEST_FAILED); coverage.setMessage(runtime.getMessage());
                return result;
            }
            if (!run.getSnapshot().isAvailable()) throw new IllegalStateException(run.getSnapshot().getMessage());
            if (!coverage.getSourceHash().equals(ReadAction.compute(() -> CodeFingerprint.sha256(psiFile.getText())))) {
                runtime.setStatus(ClassJUnitStatus.STALE);
                coverage.setStatus(CoverageStatus.STALE);
                coverage.setMessage("最终覆盖率运行期间源码已变更，请重新验证。");
                return result;
            }
            runtime.setFinalExecution(execution);
            coverage.setGenerated(run.getSnapshot()); coverage.setGaps(run.getGaps());
            coverage.setHistory(List.of(run.getSnapshot())); finalFile.setClassCoverage(run.getClassSnapshot());
            finalFile.getClassCoverage().setCandidateEvidence(runtime.getCandidateEvidence());
            coverage.setStatus(CoverageLoopPolicy.reached(run.getSnapshot(), config)
                    ? CoverageStatus.PASSED : CoverageStatus.BELOW_THRESHOLD);
            coverage.setMessage("实际执行最终完整测试文件（包含已有测试与保留用例）获得的覆盖率；"
                    + "所选方法与所选业务类分别统计，不代表整个项目覆盖率。");
            listener.update("最终文件完整回归及覆盖率采集完成。", 1d);
        } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
            runtime.setStatus(ClassJUnitStatus.CANCELLED); runtime.setStopReason(ClassJUnitStopReason.CANCELLED);
            runtime.setMessage("最终验证已取消，未写入。");
            coverage.setStatus(CoverageStatus.CANCELLED); coverage.setMessage("最终验证已取消，未写入。");
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
            // A missing agent/report never turns into 0%; independent JUnit proof is retained.
            coverage.setStatus(CoverageStatus.ENVIRONMENT_ERROR);
            coverage.setMessage("最终文件覆盖率不可用：" + safeMessage(ex));
            if (runtime.getStatus() == ClassJUnitStatus.RUNNING) {
                runtime.setStatus(ClassJUnitStatus.ENVIRONMENT_ERROR);
                runtime.setMessage(coverage.getMessage());
            }
        } finally {
            runtime.setDurationMillis(System.currentTimeMillis() - started);
            coverage.setDurationMillis(System.currentTimeMillis() - started);
            coverage.setPhase(CoveragePhase.COMPLETED); coverage.setProgressFraction(1d);
            var measured = coverage.getGenerated(); var type = finalFile.getClassCoverage();
            LOG.info("UnitMind final file verification: engineRevision=" + COVERAGE_ENGINE_REVISION
                    + ", codeHash=" + coverage.getCodeHash() + ", sourceHash=" + coverage.getSourceHash()
                    + ", runtime=" + runtime.getStatus() + ", coverage=" + coverage.getStatus()
                    + ", tests=" + runtime.getFinalExecution().getSucceeded() + "/" + runtime.getFinalExecution().getFound()
                    + ", scope=" + measured.getMeasurementScope() + ", methods=" + measured.getMeasuredMethods()
                    + ", methodLines=" + measured.getLine().getCovered() + "/" + measured.getLine().getTotal()
                    + ", methodBranches=" + measured.getBranch().getCovered() + "/" + measured.getBranch().getTotal()
                    + ", classLines=" + type.getLine().getCovered() + "/" + type.getLine().getTotal()
                    + ", classBranches=" + type.getBranch().getCovered() + "/" + type.getBranch().getTotal());
        }
        return result;
    }

    private static String finalFileFailureEvidence(ValidationResult validation, TestExecutionSummary execution) {
        String runtime = execution.getDiagnostics().stream().limit(3)
                .map(d -> d.getTestId() + ": " + d.getMessage()).collect(Collectors.joining("；"));
        String compile = validation.getDiagnostics().stream().limit(3)
                .map(d -> "L" + d.getLine() + ": " + d.getMessage()).collect(Collectors.joining("；"));
        String evidence = runtime.isBlank() ? compile : runtime;
        return evidence.isBlank() ? "" : "\n" + evidence.substring(0, Math.min(2000, evidence.length()));
    }

    /** Shared by normal validation, repaired candidates and final-file regression. */
    private static void requireDeclaredTestEvidence(String code, TestExecutionSummary execution) {
        com.casc.unittestagent.validation.JUnitExecutionEvidence.requireDeclaredTests(code, execution);
    }

    private static boolean runtimeExecutionPassed(TestExecutionSummary execution) {
        return execution != null && execution.isFullySuccessful();
    }

    private GenerationResult runJUnitValidation(Project project, PsiFile psiFile,
                                                GenerationResult result, String currentCode,
                                                BooleanSupplier cancelled,
                                                CoverageProgressListener progress,
                                                boolean allowRepair) {
        return runJUnitValidation(project, psiFile, result, currentCode, cancelled, progress, allowRepair, Integer.MAX_VALUE);
    }

    private GenerationResult runJUnitValidation(Project project, PsiFile psiFile,
                                                GenerationResult result, String currentCode,
                                                BooleanSupplier cancelled, CoverageProgressListener progress,
                                                boolean allowRepair, int remainingRepairBudget) {
        long started = System.currentTimeMillis();
        CoverageProgressListener listener = progress == null ? CoverageProgressListener.NONE : progress;
        ClassJUnitRepairReport report = ClassJUnitRepairReport.status(ClassJUnitStatus.RUNNING);
        result.setRuntimeValidationReport(report);
        if (result instanceof ClassGenerationResult) {
            ((ClassGenerationResult) result).setClassJUnitRepairReport(report);
        }
        try {
            if (isCancelled(cancelled)) {
                return stopClassJUnit(result, report, ClassJUnitStatus.CANCELLED,
                        ClassJUnitStopReason.CANCELLED, "JUnit 运行验证已取消。");
            }
            if (result.getMethodModel() == null
                    || result.getGeneratedTestFile() == null) {
                return stopClassJUnit(result, report, ClassJUnitStatus.ENVIRONMENT_ERROR,
                        ClassJUnitStopReason.ENVIRONMENT_ERROR, "缺少整类生成结果或最终测试代码。");
            }
            String sourceText = ReadAction.compute(psiFile::getText);
            report.setSourceHash(CodeFingerprint.sha256(sourceText));
            ProjectTestEnvironment environment = executionEnvironmentSession.resolve(project, psiFile, cancelled);
            if (!CodeFingerprint.sha256(sourceText).equals(CodeFingerprint.sha256(environment.getSourceText()))) {
                throw new IllegalStateException("被测源码在环境解析期间已修改，请重新验证。");
            }
            report.setRuntimeConfigurationOrigin(environment.getRuntimeConfiguration().getOrigin());
            if (!environment.isUsable()) {
                String message = environment.getDiagnostics().isEmpty() ? "无法解析测试执行环境。"
                        : environment.getDiagnostics().get(0).getMessage();
                return stopClassJUnit(result, report, ClassJUnitStatus.ENVIRONMENT_ERROR,
                        ClassJUnitStopReason.ENVIRONMENT_ERROR, message);
            }
            CoverageConfig executionConfig = CoverageConfigManager.load();
            int maxRuntimeRepairs = Math.max(0, Math.min(executionConfig.getMaxRepairRounds(), remainingRepairBudget));
            GeneratedTestFile template = result.getGeneratedTestFile();
            String acceptedCode = currentCode == null || currentCode.isBlank()
                    ? template.getContent() : currentCode;
            result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
            boolean pluginOwnedCandidate = allowRepair && testCodeFingerprint(acceptedCode)
                    .equals(testCodeFingerprint(template.getContent()));
            Slf4jMockVerificationGuard.Result loggerPreflight =
                    Slf4jMockVerificationGuard.inspectAndRepair(
                            sourceText, acceptedCode, List.of(), pluginOwnedCandidate);
            if (loggerPreflight.isChanged()) {
                acceptedCode = loggerPreflight.getCode();
                result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
                addWarnings(result, List.of(loggerPreflight.repairMessage()));
            }
            MockitoNoInteractionsContradictionGuard.Result interactionPreflight =
                    MockitoNoInteractionsContradictionGuard.inspectAndRepair(
                            acceptedCode, pluginOwnedCandidate);
            if (interactionPreflight.isChanged()) {
                acceptedCode = interactionPreflight.getCode();
                result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
                addWarnings(result, List.of(interactionPreflight.message()));
            }
            String targetClassName = targetClassName(result);
            listener.update("正在执行整类初始 JUnit...", 0.08d);
            ClassRunCandidate initial = executeClassCandidate(environment, template, acceptedCode,
                    targetClassName, executionConfig, cancelled);
            result.setValidationResult(initial.validation);
            report.setInitialExecution(initial.execution);
            report.setFinalExecution(initial.execution);
            if (initial.cancelled || isCancelled(cancelled)) {
                return stopClassJUnit(result, report, ClassJUnitStatus.CANCELLED,
                        ClassJUnitStopReason.CANCELLED, "整类 JUnit 执行已取消。");
            }
            if (initial.environmentError) {
                return stopClassJUnit(result, report, ClassJUnitStatus.ENVIRONMENT_ERROR,
                        ClassJUnitStopReason.ENVIRONMENT_ERROR, initial.message);
            }
            if (initial.validation.getStatus() != ValidationStatus.PASSED) {
                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                        ClassJUnitStopReason.COMPILE_REPAIR_EXHAUSTED, initial.message);
            }
            TestExecutionSummary acceptedExecution = initial.execution;
            ValidationResult acceptedValidation = initial.validation;
            MockitoRuntimeDiagnosticEnricher.enrich(
                    acceptedExecution.getDiagnostics(), acceptedCode);
            MockWiringAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), acceptedCode, acceptedExecution.getDiagnostics());
            SpringFixtureAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), acceptedExecution.getDiagnostics());
            com.casc.unittestagent.coverage.RuntimeEvidenceArchive.preserveSourceAnalysis(acceptedExecution);
            MissingFixtureDependencyDetector.inspect(
                    project, sourceText, acceptedCode, acceptedExecution.getDiagnostics());
            Slf4jMockVerificationGuard.Result loggerFailure =
                    Slf4jMockVerificationGuard.inspectAndRepair(
                            sourceText, acceptedCode, acceptedExecution.getDiagnostics(), false);
            report.setInitialExecution(acceptedExecution);
            report.setFinalExecution(acceptedExecution);
            if (!acceptedExecution.getEvidenceFailures().isEmpty()) {
                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED, ClassJUnitStopReason.NO_TESTS_FOUND,
                        "JUnit 执行证据不完整，已阻止写入：" + String.join("；", acceptedExecution.getEvidenceFailures()));
            }
            if (runtimeExecutionPassed(acceptedExecution)) {
                return stopClassJUnit(result, report, ClassJUnitStatus.PASSED,
                        ClassJUnitStopReason.INITIAL_PASS, "完整测试类首次 JUnit 执行通过。");
            }
            if (acceptedExecution.isTimedOut()) {
                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                        ClassJUnitStopReason.TIMEOUT, "JUnit 运行验证超时，已阻止正常写入。");
            }
            if (acceptedExecution.getFound() == 0
                    || acceptedExecution.getSucceeded() == 0 && acceptedExecution.getFailed() == 0) {
                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                        ClassJUnitStopReason.NO_TESTS_FOUND, "未执行任何成功测试（零测试或全部跳过），已阻止正常写入。");
            }
            if (loggerFailure.hasBlockedMethods()
                    && hasRuntimeCategory(acceptedExecution.getDiagnostics(),
                    "MOCKITO_VERIFY_WRONG_INSTANCE")) {
                result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                        ClassJUnitStopReason.TEST_DESIGN_ERROR, loggerFailure.blockedMessage());
            }

            if (acceptedExecution.getSkipped() > 0) {
                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                        ClassJUnitStopReason.TEST_DESIGN_ERROR, "存在未执行的跳过测试，完整运行门禁未通过。");
            }
            LlmConfig llmConfig = LlmConfigManager.load();
            if (!allowRepair || !llmConfig.isEnabled() || maxRuntimeRepairs == 0) {
                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                        ClassJUnitStopReason.REPAIR_BUDGET_EXHAUSTED,
                        !allowRepair ? "最终合并测试类回归未通过，已保留原测试且阻止正常写入。"
                                : llmConfig.isEnabled() ? "JUnit 修复预算为 0。" : "大模型未启用，已保留首次执行结果。");
            }
            LlmClient client = llmClientFactory.create(llmConfig, cancelled);
            Set<String> seenCode = new LinkedHashSet<>();
            seenCode.add(testCodeFingerprint(acceptedCode));
            String previousAttempt = "";
            boolean acceptedAnyRepair = false;
            for (int round = 1; round <= maxRuntimeRepairs; round++) {
                if (isCancelled(cancelled)) {
                    return stopClassJUnit(result, report, ClassJUnitStatus.CANCELLED,
                            ClassJUnitStopReason.CANCELLED, "整类 JUnit 修复已取消。");
                }
                Set<String> passedIds = passedTestNames(acceptedExecution);
                Set<String> failingIds = failingTestNames(acceptedExecution);
                List<RuntimeDiagnostic> focusedDiagnostics = focusRuntimeDiagnostics(
                        acceptedExecution.getDiagnostics());
                Set<String> focusedFailingIds = failingTestNames(focusedDiagnostics);
                if (focusedFailingIds.isEmpty() && !failingIds.isEmpty()) {
                    focusedFailingIds = Set.of(failingIds.iterator().next());
                }
                // 非聚焦失败测试的 Oracle 不在本轮授权范围内：指纹门禁必须保护它们，
                // 否则 SHARED_FIXTURE 候选可静默掏空其断言（stabilizer 只恢复 passed 集合）。
                Set<String> protectedFailingIds = new LinkedHashSet<>(failingIds);
                protectedFailingIds.removeAll(focusedFailingIds);
                ClassJUnitRepairScope suggestedScope = SpringFixtureAnalyzer.allowsPropertyRepair(focusedDiagnostics) || MockWiringAnalyzer.allowsWiringRepair(focusedDiagnostics, acceptedCode) || FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(focusedDiagnostics) || focusedDiagnostics.stream()
                        .anyMatch(item -> "FIXTURE_DEPENDENCY_MISSING".equals(item.getCategory()))
                        ? ClassJUnitRepairScope.SHARED_FIXTURE : ClassJUnitRepairScope.METHOD_ONLY;
                listener.update("正在修复整类 JUnit 失败（" + round + " / "
                        + maxRuntimeRepairs + "，当前测试 " + focusedFailingIds + "）...",
                        0.12d + 0.78d * round / Math.max(1, maxRuntimeRepairs));
                LlmGenerationRequest request = classRepairRequest(project, psiFile, result, sourceText,
                        acceptedCode, focusedDiagnostics, passedIds, failingIds, focusedFailingIds, round,
                        suggestedScope, previousAttempt);
                LlmGenerationResponse response = null;
                // 与覆盖率闭环对齐：瞬时网络失败（429/超时等）做有界重试，重试不消耗修复轮次。
                for (int transportAttempt = 0; ; transportAttempt++) {
                    report.incrementModelRequests();
                    try {
                        response = client.repairClassTest(request);
                        break;
                    } catch (ProcessCanceledException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
                        if (transportAttempt < MAX_COVERAGE_TRANSPORT_RETRIES
                                && isRetryableCoverageFailure(ex)) {
                            LOG.warn("UnitMind class repair: transient model failure, runtimeRound=" + round
                                    + ", transportRetry=" + (transportAttempt + 1)
                                    + ", errorType=" + ex.getClass().getSimpleName());
                            try {
                                Thread.sleep(250L * (transportAttempt + 1));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                                        ClassJUnitStopReason.MODEL_ERROR,
                                        "整类 JUnit 修复模型调用被中断：" + safeMessage(interrupted));
                            }
                            continue;
                        }
                        return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                                ClassJUnitStopReason.MODEL_ERROR,
                                "整类 JUnit 修复模型调用失败：" + safeMessage(ex));
                    }
                }
                report.incrementRuntimeRepairRounds();
                OracleAssessment oracleAssessment;
                try {
                    oracleAssessment = runtimeOracleGate.assessment(response, focusedDiagnostics);
                } catch (IllegalArgumentException ex) {
                    return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                            ClassJUnitStopReason.MODEL_ERROR,
                            "整类运行修复缺少合法 Oracle 仲裁结果：" + safeMessage(ex));
                }
                reconcileLifecycleAssessment(oracleAssessment, focusedDiagnostics);
                reconcileWiringAssessment(oracleAssessment, focusedDiagnostics);
                reconcileSpringAssessment(oracleAssessment, focusedDiagnostics);
                report.setOracleAssessment(oracleAssessment);
                if (oracleAssessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG
                        && !oracleAssessment.getAuthority().permitsAutomaticOracleCorrection()) {
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    oracleAssessment.setEvidence(appendEvidence(oracleAssessment.getEvidence(),
                            "疑似 SUT Bug 缺少独立契约证据。"));
                }
                if (!oracleAuthorityReferenceSupported(request, oracleAssessment)) {
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    String scopeNote = evidenceScopeRejectionNote(request, oracleAssessment);
                    oracleAssessment.setEvidence(appendEvidence(oracleAssessment.getEvidence(),
                            scopeNote.isBlank()
                                    ? "oracleAuthorityRef 未引用本轮上下文中真实存在的同类型证据："
                                            + oracleAssessment.getAuthorityRef()
                                    : scopeNote));
                }
                normalizeUnsupportedFixtureClassification(
                        oracleAssessment, focusedDiagnostics);
                if (oracleAssessment.getDisposition() == RuntimeRepairDisposition.REPAIR_TEST
                        && oracleAssessment.getDecision() == OracleDecision.KEEP
                        && hasOracleSensitiveFailure(focusedDiagnostics)
                        && !isProvenFixtureRepair(
                        oracleAssessment, focusedDiagnostics)) {
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    oracleAssessment.setEvidence(appendEvidence(oracleAssessment.getEvidence(),
                            "当前交互或断言失败尚无充分装配证据，不能判定是测试装配错误或生产行为不符；已阻止通过改 Arrange 绕过失败。"));
                }
                boolean initializationRepair = SpringFixtureAnalyzer.allowsPropertyRepair(focusedDiagnostics) || FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(focusedDiagnostics)
                        || MockWiringAnalyzer.allowsWiringRepair(focusedDiagnostics, acceptedCode);
                ClassJUnitRepairScope scope = initializationRepair ? ClassJUnitRepairScope.SHARED_FIXTURE
                        : classRepairScope(response.getRepairScope(), suggestedScope);
                if (scope == ClassJUnitRepairScope.SHARED_FIXTURE
                        && !initializationRepair && !isProvenFixtureRepair(oracleAssessment, focusedDiagnostics)) {
                    scope = ClassJUnitRepairScope.METHOD_ONLY;
                }
                if (scope == ClassJUnitRepairScope.SHARED_FIXTURE) report.incrementFixtureRepairRounds();
                ClassJUnitRepairAttempt attempt = new ClassJUnitRepairAttempt();
                attempt.setRound(round);
                attempt.setRepairScope(scope);
                attempt.setFocusedTestIds(new ArrayList<>(focusedFailingIds));
                attempt.setDiagnosis(response.getDiagnosisRootCauseCategory());
                if (oracleAssessment.getDisposition() != RuntimeRepairDisposition.REPAIR_TEST) {
                    attempt.setRejectionReason(oracleStopMessage(oracleAssessment,
                            new ArrayList<>(methodNames(focusedFailingIds))));
                    report.getAttempts().add(attempt);
                    result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
                    report.setFinalExecution(acceptedExecution);
                    if (shouldAnalyzePossibleSourceFailure(oracleAssessment)) {
                        listener.update("正在分析疑似生产代码缺陷的可能位置...", 0.94d);
                        report.incrementModelRequests();
                        report.setSourceBugAnalysis(analyzeSuspectedSutBug(
                                client, request, oracleAssessment));
                    }
                    return stopClassJUnit(result, report,
                            classStatusForOracle(oracleAssessment), classStopReasonForOracle(oracleAssessment),
                            oracleStopMessage(oracleAssessment, new ArrayList<>(methodNames(focusedFailingIds))));
                }
                boolean strictOracleGate = CoverageConfigManager.load().isStrictOracleGate();
                if (RuntimeOracleGate.automaticOracleCorrectionBlocked(
                        oracleAssessment, strictOracleGate)) {
                    boolean strictOnly = strictOracleGate
                            && oracleAssessment.getAuthority().permitsAutomaticOracleCorrection();
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    attempt.setRejectionReason(strictOnly
                            ? RuntimeOracleGate.strictOracleGateMessage(oracleAssessment)
                            : unsafeAuthorityMessage(oracleAssessment));
                    report.getAttempts().add(attempt);
                    result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
                    report.setFinalExecution(acceptedExecution);
                    // 严格门禁停止也按分类路由：结构/环境类失败的终态不得占用 Oracle 待确认语义。
                    return stopClassJUnit(result, report, classStatusForOracle(oracleAssessment),
                            classStopReasonForOracle(oracleAssessment),
                            attempt.getRejectionReason());
                }
                try {
                    attempt.setCandidateHash(testCodeFingerprint(response.getTestCode()));
                    GeneratedTestCodePostProcessor.ProcessedCode processed =
                            generatedCodePostProcessor.processRepair(response.getTestCode(), result.getMethodModel(), acceptedCode);
                    addWarnings(result, response.getWarnings());
                    addWarnings(result, processed.getWarnings());
                    ClassRuntimeRepairStabilizer.Result stabilized = classRuntimeRepairStabilizer.stabilize(
                            acceptedCode, processed.getCode(), scope, passedIds, focusedFailingIds);
                    if (FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(focusedDiagnostics) || SpringFixtureAnalyzer.allowsPropertyRepair(focusedDiagnostics))
                        classRuntimeRepairStabilizer.validateInitializationRepair(acceptedCode, stabilized.getCode());
                    MockWiringAnalyzer.validateWiringRepair(acceptedCode, stabilized.getCode(), focusedDiagnostics);
                    SpringFixtureAnalyzer.validatePropertyRepair(acceptedCode, stabilized.getCode(), focusedDiagnostics);
                    String candidateCode = stabilized.getCode();
                    attempt.setCandidateHash(testCodeFingerprint(candidateCode));
                    if (!isProvenNoInteractionsContradictionRepair(
                            acceptedCode, candidateCode, focusedDiagnostics)) {
                        runtimeOracleGate.validateRepair(oracleAssessment, acceptedCode, candidateCode,
                                methodNames(focusedFailingIds), methodNames(protectedFailingIds));
                    }
                    attempt.setChangedTestIds(stabilized.getChangedTestIds());
                    attempt.setFixtureChanged(stabilized.isFixtureChanged());
                    String hash = testCodeFingerprint(candidateCode);
                    attempt.setCandidateHash(hash);
                    if (!seenCode.add(hash)) {
                        attempt.setRejectionReason("模型返回了已执行过的历史候选。");
                        report.getAttempts().add(attempt);
                        return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                                ClassJUnitStopReason.REPEATED_CODE, attempt.getRejectionReason());
                    }

                    // 运行修复可能引入编译问题；使用现有完整类编译修复，但限制为最多三轮，
                    // 并在修复后再次执行类级 AST 范围门禁。
                    LlmConfig compileConfig = limitedCompileRepairConfig(llmConfig, 3);
                    GeneratedTestFile candidateFile = copyWithContent(template, candidateCode);
                    GeneratedTestFile compileRepaired = validateAndRepair(
                            project, psiFile, result, candidateFile, compileConfig, result.getMethodModel() != null, cancelled);
                    ValidationResult compileValidation = result.getValidationResult();
                    report.addCompileRepairRounds(compileValidation == null
                            ? 0 : compileValidation.getRepairAttempts());
                    if (compileRepaired != null && !testCodeFingerprint(candidateCode)
                            .equals(testCodeFingerprint(compileRepaired.getContent()))) {
                        stabilized = classRuntimeRepairStabilizer.stabilize(
                                acceptedCode, compileRepaired.getContent(), scope, passedIds, focusedFailingIds);
                        if (FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(focusedDiagnostics) || SpringFixtureAnalyzer.allowsPropertyRepair(focusedDiagnostics))
                            classRuntimeRepairStabilizer.validateInitializationRepair(acceptedCode, stabilized.getCode());
                        MockWiringAnalyzer.validateWiringRepair(acceptedCode, stabilized.getCode(), focusedDiagnostics);
                        SpringFixtureAnalyzer.validatePropertyRepair(acceptedCode, stabilized.getCode(), focusedDiagnostics);
                        candidateCode = stabilized.getCode();
                        if (!isProvenNoInteractionsContradictionRepair(
                                acceptedCode, candidateCode, focusedDiagnostics)) {
                            runtimeOracleGate.validateRepair(oracleAssessment, acceptedCode, candidateCode,
                                    methodNames(focusedFailingIds), methodNames(protectedFailingIds));
                        }
                        attempt.setChangedTestIds(stabilized.getChangedTestIds());
                        attempt.setFixtureChanged(stabilized.isFixtureChanged());
                        attempt.setCandidateHash(testCodeFingerprint(candidateCode));
                    }

                    ClassRunCandidate candidate = executeClassCandidate(environment, template, candidateCode,
                            targetClassName, executionConfig, cancelled);
                    attempt.setCompileResult(candidate.validation);
                    attempt.setExecution(candidate.execution);
                    if (candidate.cancelled || isCancelled(cancelled)) {
                        report.getAttempts().add(attempt);
                        return stopClassJUnit(result, report, ClassJUnitStatus.CANCELLED,
                                ClassJUnitStopReason.CANCELLED, "整类 JUnit 修复已取消。");
                    }
                    if (candidate.environmentError) {
                        report.getAttempts().add(attempt);
                        return stopClassJUnit(result, report, ClassJUnitStatus.ENVIRONMENT_ERROR,
                                ClassJUnitStopReason.ENVIRONMENT_ERROR, candidate.message);
                    }
                    if (candidate.validation.getStatus() != ValidationStatus.PASSED) {
                        attempt.setRejectionReason("修复候选在编译预算内仍未通过：" + candidate.message);
                        report.getAttempts().add(attempt);
                        previousAttempt = attempt.getRejectionReason();
                        result.setValidationResult(acceptedValidation);
                        continue;
                    }
                    if (!candidate.execution.getEvidenceFailures().isEmpty()) {
                        attempt.setRejectionReason("修复候选缺少完整运行证据："
                                + String.join("；", candidate.execution.getEvidenceFailures()));
                        report.getAttempts().add(attempt);
                        result.setValidationResult(acceptedValidation);
                        return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                                ClassJUnitStopReason.TEST_DESIGN_ERROR, attempt.getRejectionReason());
                    }
                    MockitoRuntimeDiagnosticEnricher.enrich(
                            candidate.execution.getDiagnostics(), candidateCode);
                    MockWiringAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), candidateCode, candidate.execution.getDiagnostics());
                    SpringFixtureAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), candidate.execution.getDiagnostics());
                    com.casc.unittestagent.coverage.RuntimeEvidenceArchive.preserveSourceAnalysis(candidate.execution);
                    MissingFixtureDependencyDetector.inspect(
                            project, sourceText, candidateCode, candidate.execution.getDiagnostics());
                    Set<String> newPassed = passedTestNames(candidate.execution);
                    Set<String> newFailed = failingTestNames(candidate.execution);
                    if (!newPassed.containsAll(passedIds) || !failingIds.containsAll(newFailed)) {
                        attempt.setRejectionReason("修复候选使已通过/已跳过测试发生回归或引入新失败。");
                        report.incrementRegressionRejections();
                        report.getAttempts().add(attempt);
                        previousAttempt = attempt.getRejectionReason();
                        result.setValidationResult(acceptedValidation);
                        continue;
                    }
                    // skipped 不变量：失败测试被改成 assumption 跳过会让 failed 数下降甚至
                    // isSuccessful 为真，但那不是修复——skipped 名单不得扩大。
                    if (!skippedTestNames(acceptedExecution).containsAll(
                            skippedTestNames(candidate.execution))) {
                        attempt.setRejectionReason("修复候选把测试改为跳过（skipped/assumption）以伪造通过。");
                        report.incrementRegressionRejections();
                        report.getAttempts().add(attempt);
                        previousAttempt = attempt.getRejectionReason();
                        result.setValidationResult(acceptedValidation);
                        continue;
                    }
                    if (!candidate.execution.isFullySuccessful()
                            && candidate.execution.getFailed() >= acceptedExecution.getFailed()) {
                        attempt.setRejectionReason("修复候选未严格减少失败测试集合。");
                        report.getAttempts().add(attempt);
                        previousAttempt = attempt.getRejectionReason();
                        result.setValidationResult(acceptedValidation);
                        continue;
                    }

                    attempt.setAccepted(true);
                    acceptedAnyRepair = true;
                    report.getAttempts().add(attempt);
                    acceptedCode = candidateCode;
                    acceptedExecution = candidate.execution;
                    acceptedValidation = candidate.validation;
                    report.setFinalExecution(acceptedExecution);
                    result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
                    result.setValidationResult(candidate.validation);
                    previousAttempt = "";
                    if (runtimeExecutionPassed(acceptedExecution)) {
                        return stopClassJUnit(result, report, ClassJUnitStatus.PASSED,
                                ClassJUnitStopReason.REPAIRED,
                                "完整测试类已在 " + round + " 轮 JUnit 修复后通过。");
                    }
                } catch (ProcessCanceledException ex) {
                    throw ex;
                } catch (RepairCandidateRejectedException ex) {
                    var rejection = candidateRejection(ex.getStage().name(), attempt.getCandidateHash(), ex.getMessage(), focusedDiagnostics);
                    report.recordCandidateRejection(rejection);
                    report.incrementRegressionRejections();
                    attempt.setRejectionReason(candidateRejectionFeedback(rejection));
                    report.getAttempts().add(attempt);
                    previousAttempt = attempt.getRejectionReason();
                    result.setValidationResult(acceptedValidation);
                } catch (Exception ex) {
                    if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
                    attempt.setRejectionReason("整类修复候选被范围或编译门禁拒绝：" + safeMessage(ex));
                    report.getAttempts().add(attempt);
                    previousAttempt = attempt.getRejectionReason();
                    result.setValidationResult(acceptedValidation);
                }
            }
            result.setGeneratedTestFile(copyWithContent(template, acceptedCode));
            report.setFinalExecution(acceptedExecution);
            result.setValidationResult(acceptedValidation);
            if (!report.getCandidateRejections().isEmpty())
                report.setOracleAssessment(rejectedCandidateAssessment(acceptedExecution.getDiagnostics(), previousAttempt));
            return stopClassJUnit(result, report, ClassJUnitStatus.TEST_FAILED,
                    acceptedAnyRepair ? ClassJUnitStopReason.REPAIR_BUDGET_EXHAUSTED
                            : ClassJUnitStopReason.NO_PROGRESS,
                    acceptedAnyRepair ? "整类 JUnit 修复预算已用完，已保留最佳可编译候选。"
                            : "整类 JUnit 修复未取得单调进展，已保留初始可编译候选。");
        } catch (ProcessCanceledException ex) {
            stopClassJUnit(result, report, ClassJUnitStatus.CANCELLED,
                    ClassJUnitStopReason.CANCELLED, "JUnit 运行验证已取消。");
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
            return stopClassJUnit(result, report, ClassJUnitStatus.ENVIRONMENT_ERROR,
                    ClassJUnitStopReason.ENVIRONMENT_ERROR, safeMessage(ex));
        } finally {
            report.setCodeHash(CodeFingerprint.sha256(result.getGeneratedTestFile() == null
                    ? "" : result.getGeneratedTestFile().getContent()));
            report.setDurationMillis(System.currentTimeMillis() - started);
        }
    }

    private LlmGenerationRequest classRepairRequest(Project project,
                                                    PsiFile psiFile,
                                                    GenerationResult result,
                                                    String sourceText,
                                                    String currentCode,
                                                    List<RuntimeDiagnostic> focusedDiagnostics,
                                                    Set<String> passedIds,
                                                    Set<String> allFailingIds,
                                                    Set<String> focusedFailingIds,
                                                    int round,
                                                    ClassJUnitRepairScope suggestedScope,
                                                    String previousAttempt) {
        MethodModel contextMethod = result.getMethodModel();
        LlmGenerationRequest request = ReadAction.compute(() -> buildLlmRequest(
                project, contextMethod, executableScenarios(result.getScenarios()),
                contextMethod.getMethodBodyText(), result.getSymbolSnapshot()));
        applyGenerationScope(request, result, contextMethod);
        request.setClassSourceCode(sourceText);
        request.setExistingTestCodeSummary(currentCode);
        request.setRuntimeDiagnostics(focusedDiagnostics == null
                ? List.of() : new ArrayList<>(focusedDiagnostics));
        request.setFocusTestIds(new ArrayList<>(focusedFailingIds));
        request.setFailingTestIds(new ArrayList<>(allFailingIds));
        request.setPassedTestIds(new ArrayList<>(passedIds));
        request.setRepairAttempt(round);
        request.setPreviousAttemptSummary(previousAttempt);
        request.setMockUsagePolicy(mockPolicyForExistingCode(currentCode));
        request.setUserInstruction("本轮已按失败测试隔离：只处理 focusTestIds="
                + focusedFailingIds + "，不得修改 allFailingTestIds 中的其他测试。建议修复范围="
                + suggestedScope.name() + "。只有确定证明的共享 fixture 缺失才可使用 SHARED_FIXTURE。");
        configureMockitoSupport(request, project, psiFile);
        return request;
    }

    private ClassJUnitRepairScope suggestedClassRepairScope(TestExecutionSummary execution,
                                                            int round,
                                                            String previousAttempt) {
        if (round > 1 && previousAttempt != null && !previousAttempt.isBlank()) {
            return ClassJUnitRepairScope.SHARED_FIXTURE;
        }
        if (execution == null || execution.getDiagnostics().size() < 2) {
            return ClassJUnitRepairScope.METHOD_ONLY;
        }
        long fixtureSignals = execution.getDiagnostics().stream().filter(item -> {
            String evidence = (item.getExceptionType() + " " + item.getMessage() + " "
                    + item.getFailureDetails()).toLowerCase(Locale.ROOT);
            return evidence.contains("nullpointerexception")
                    || evidence.contains("injectmocks")
                    || evidence.contains("beforeeach")
                    || evidence.contains("zero interactions with this mock")
                    || evidence.contains("failed to release mocks");
        }).count();
        return fixtureSignals >= 2 ? ClassJUnitRepairScope.SHARED_FIXTURE
                : ClassJUnitRepairScope.METHOD_ONLY;
    }

    private ClassJUnitRepairScope classRepairScope(String value,
                                                   ClassJUnitRepairScope fallback) {
        try {
            return ClassJUnitRepairScope.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback == null ? ClassJUnitRepairScope.METHOD_ONLY : fallback;
        }
    }

    private LlmConfig limitedCompileRepairConfig(LlmConfig source, int maxRounds) {
        LlmConfig config = new LlmConfig();
        if (source == null) return config;
        config.setEnabled(source.isEnabled());
        config.setApiUrl(source.getApiUrl());
        config.setApiKey(source.getApiKey());
        config.setModel(source.getModel());
        config.setTimeoutSeconds(source.getTimeoutSeconds());
        config.setMaxTokens(source.getMaxTokens());
        config.setTemperature(source.getTemperature());
        config.setFallbackToRuleBased(source.isFallbackToRuleBased());
        config.setMaxCompileRepairRounds(Math.min(Math.max(0, maxRounds),
                source.getMaxCompileRepairRounds()));
        return config;
    }

    private ClassRunCandidate executeClassCandidate(ProjectTestEnvironment environment,
                                                    GeneratedTestFile template,
                                                    String code,
                                                    String targetClassName,
                                                    CoverageConfig config,
                                                    BooleanSupplier cancelled) {
        long started = System.currentTimeMillis();
        try (CompiledTestArtifact artifact = sandboxCompiler.compile(
                environment, copyWithContent(template, code), code, targetClassName, cancelled)) {
            ValidationResult validation = ValidationResult.status(artifact.isSuccessful()
                    ? ValidationStatus.PASSED : artifact.getDiagnostics().stream()
                    .anyMatch(item -> !item.isRepairable())
                    ? ValidationStatus.ENVIRONMENT_ERROR : ValidationStatus.FAILED);
            validation.setCodeHash(CodeFingerprint.sha256(code));
            validation.setCandidateEvidence(artifact.getCandidateEvidence());
            validation.setTestEnvironmentNote(environment.getTestEnvironmentNote());
            validation.setDiagnostics(new ArrayList<>(artifact.getDiagnostics()));
            validation.setDurationMillis(System.currentTimeMillis() - started);
            if (!artifact.isSuccessful()) {
                String message = artifact.getDiagnostics().isEmpty() ? "整类测试编译失败。"
                        : artifact.getDiagnostics().get(0).getCode() + " - "
                        + artifact.getDiagnostics().get(0).getMessage();
                return new ClassRunCandidate(validation, new TestExecutionSummary(), false,
                        validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR, message);
            }
            CoverageRunResult run = coverageExecutor.executeTestsOnly(artifact, config, cancelled);
            TestExecutionSummary execution = run.getSnapshot() == null
                    ? new TestExecutionSummary() : run.getSnapshot().getExecution();
            requireDeclaredTestEvidence(code, execution);
            MockitoRuntimeDiagnosticEnricher.enrich(execution.getDiagnostics(), code);
            String message = run.getSnapshot() == null ? "整类 JUnit 未返回结果。"
                    : run.getSnapshot().getMessage();
            return new ClassRunCandidate(validation, execution, run.isCancelled(),
                    run.isEnvironmentError(), message);
        }
    }

    private GenerationResult stopClassJUnit(GenerationResult result,
                                                 ClassJUnitRepairReport report,
                                                 ClassJUnitStatus status,
                                                 ClassJUnitStopReason reason,
                                                 String message) {
        if (result.getValidationResult() != null)
            report.setCandidateEvidence(result.getValidationResult().getCandidateEvidence());
        report.setStatus(status);
        report.setStopReason(reason);
        report.setMessage(message);
        result.setRuntimeValidationReport(report);
        return result;
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
            ProjectTestEnvironment environment = executionEnvironmentSession.resolve(project, psiFile, cancelled);
            if (!CodeFingerprint.sha256(sourceText).equals(CodeFingerprint.sha256(environment.getSourceText()))) {
                throw new IllegalStateException("被测源码在环境解析期间已修改，请重新验证。");
            }
            report.setRuntimeConfigurationOrigin(environment.getRuntimeConfiguration().getOrigin());
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
            LlmClient coverageClient = llmConfig.isEnabled() ? llmClientFactory.create(llmConfig, cancelled) : null;
            boolean pluginOwnedCandidate = testCodeFingerprint(candidateCode)
                    .equals(testCodeFingerprint(template.getContent()));

            boolean generationPreflightNeeded = false;
            while (true) {
                if (isCancelled(cancelled)) {
                    restoreAcceptedCandidate(result, report, template, bestAccepted);
                    return stopCoverage(result, report, CoverageStatus.CANCELLED,
                            CoverageStopReason.CANCELLED, "覆盖率执行已取消。");
                }
                Slf4jMockVerificationGuard.Result loggerPreflight =
                        Slf4jMockVerificationGuard.inspectAndRepair(
                                sourceText, candidateCode, List.of(), pluginOwnedCandidate);
                if (loggerPreflight.isChanged()) {
                    candidateCode = loggerPreflight.getCode();
                    seenCode.add(testCodeFingerprint(candidateCode));
                    result.setGeneratedTestFile(copyWithContent(template, candidateCode));
                    addWarnings(result, List.of(loggerPreflight.repairMessage()));
                }
                MockitoNoInteractionsContradictionGuard.Result interactionPreflight =
                        MockitoNoInteractionsContradictionGuard.inspectAndRepair(
                                candidateCode, pluginOwnedCandidate);
                if (interactionPreflight.isChanged()) {
                    candidateCode = interactionPreflight.getCode();
                    seenCode.add(testCodeFingerprint(candidateCode));
                    result.setGeneratedTestFile(copyWithContent(template, candidateCode));
                    addWarnings(result, List.of(interactionPreflight.message()));
                }
                updateCoverageProgress(report, listener, config, CoveragePhase.COMPILING,
                        attemptBudget.totalCalls() + 1,
                        "正在编译第 " + (attemptBudget.totalCalls() + 1) + " 个候选测试...", 0.10d);
                GeneratedTestFile candidateFile = copyWithContent(template, candidateCode);
                try (CompiledTestArtifact artifact = generationPreflightNeeded
                        ? sandboxCompiler.compileGeneratedCandidate(environment, candidateFile, candidateCode, targetClassName, MockWiringAnalyzer.sources(psiFile), cancelled)
                        : sandboxCompiler.compile(environment, candidateFile, candidateCode, targetClassName, cancelled)) {
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
                                    mockitoSupport, sourceText, compileRepairInstruction);
                            String improved = improvement.code;
                            String improvedHash = testCodeFingerprint(improved);
                            if (improvement.unchanged) {
                                restoreAcceptedCandidate(result, report, template, bestAccepted);
                                return stopCoverage(result, report,
                                        coverageFailureStatus(bestAccepted, config),
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
                                return stopCoverage(result, report,
                                        coverageFailureStatus(bestAccepted, config),
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
                                    sourceText,
                                    "上一覆盖候选无法编译（" + message + "），不要修补或复用该候选。"
                                            + "从 currentTestCode 重新追加一个更小、不同构造方式的测试，命中主 coverage gap。");
                            String alternativeHash = testCodeFingerprint(alternative.code);
                            if (!seenCode.add(alternativeHash)) {
                                return stopCoverage(result, report,
                                        coverageFailureStatus(bestAccepted, config),
                                        CoverageStopReason.REPEATED_CODE, "替代覆盖策略返回了重复测试代码。");
                            }
                            candidateCode = alternative.code;
                            generationPreflightNeeded = true;
                            continue;
                        }
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        return stopCoverage(result, report,
                                coverageFailureStatus(bestAccepted, config),
                                CoverageStopReason.TEST_FAILURE,
                                message);
                    }

                    lastCompileFailedCode = "";
                    lastCompileDiagnostics.clear();
                    updateCoverageProgress(report, listener, config, CoveragePhase.EXECUTING,
                            attemptBudget.totalCalls() + 1,
                            "正在隔离 JVM 中运行候选测试...", 0.45d);
                    generationPreflightNeeded = false;
                    CoverageRunResult run = coverageExecutor.execute(
                            artifact, method, sourceText, config, cancelled);
                    report.setGenerated(run.getSnapshot());
                    report.setGaps(run.getGaps());
                    requireDeclaredTestEvidence(candidateCode, run.getSnapshot().getExecution());
                    if (!run.getSnapshot().getExecution().getEvidenceFailures().isEmpty()) {
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        return stopCoverage(result, report, CoverageStatus.TEST_FAILED,
                                CoverageStopReason.TEST_DESIGN_ERROR,
                                "JUnit 执行证据不完整，已停止自动修复："
                                        + String.join("；", run.getSnapshot().getExecution().getEvidenceFailures()));
                    }
                    if (!runtimeExecutionPassed(run.getSnapshot().getExecution())) run.getSnapshot().setAvailable(false);
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
                    if (!run.getSnapshot().getExecution().isFullySuccessful()) {
                        report.incrementRuntimeFailures();
                        MockitoRuntimeDiagnosticEnricher.enrich(
                                run.getSnapshot().getExecution().getDiagnostics(), candidateCode);
                        MockWiringAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), candidateCode, run.getSnapshot().getExecution().getDiagnostics());
                        SpringFixtureAnalyzer.enrich(MockWiringAnalyzer.sources(psiFile), run.getSnapshot().getExecution().getDiagnostics());
                        com.casc.unittestagent.coverage.RuntimeEvidenceArchive.preserveSourceAnalysis(run.getSnapshot().getExecution());
                        MissingFixtureDependencyDetector.inspect(
                                project, sourceText, candidateCode,
                                run.getSnapshot().getExecution().getDiagnostics());
                        Slf4jMockVerificationGuard.Result loggerFailure =
                                Slf4jMockVerificationGuard.inspectAndRepair(
                                        sourceText, candidateCode,
                                        run.getSnapshot().getExecution().getDiagnostics(), false);
                        if (loggerFailure.hasBlockedMethods()
                                && hasRuntimeCategory(
                                run.getSnapshot().getExecution().getDiagnostics(),
                                "MOCKITO_VERIFY_WRONG_INSTANCE")) {
                            result.setGeneratedTestFile(copyWithContent(template, candidateCode));
                            return stopCoverage(result, report, CoverageStatus.TEST_FAILED,
                                    CoverageStopReason.TEST_DESIGN_ERROR,
                                    loggerFailure.blockedMessage());
                        }
                        candidateFailureMemory.put(testCodeFingerprint(candidateCode),
                                runtimeFailureSummary(run.getSnapshot().getExecution().getDiagnostics()));
                        // Compilation succeeded, so older compile diagnostics are resolved. Sending them
                        // together with the current runtime failure makes the model repair obsolete code.
                        compilerDiagnosticHistory.clear();
                        List<RuntimeDiagnostic> focusedRuntimeDiagnostics = focusRuntimeDiagnostics(
                                run.getSnapshot().getExecution().getDiagnostics());
                        Set<String> focusedFailingTestNames = rememberRuntimeDiagnostics(
                                runtimeDiagnosticHistory, focusedRuntimeDiagnostics);
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
                            if (SpringRuntimeFailureAnalyzer.hasSharedFailure(focusedRuntimeDiagnostics) || FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(focusedRuntimeDiagnostics)
                                    || MockWiringAnalyzer.allowsWiringRepair(focusedRuntimeDiagnostics, runtimeRepairBase)) {
                                // Initialization cannot be repaired inside a test method which has never run.
                                // Reuse the class runtime engine, including its compile re-audit, full
                                // regression, immutable test bodies and shared-fixture scope checks.
                                updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                        attemptBudget.totalCalls() + 1, "正在修复测试初始化与共享 Fixture...", 0.65d);
                                runJUnitValidation(project, psiFile, result, runtimeRepairBase, cancelled, listener,
                                        true, attemptBudget.remainingRepairs(config));
                                ClassJUnitRepairReport fixtureReport = result.getRuntimeValidationReport();
                                for (int used = 0; used < fixtureReport.getRuntimeRepairRounds(); used++)
                                    attemptBudget.consume(CoverageAttemptKind.REPAIR, report);
                                for (int request = 0; request < fixtureReport.getModelRequests(); request++) report.incrementModelRequests();
                                fixtureReport.getCandidateRejections().forEach(report::recordCandidateRejection);
                                report.setOracleAssessment(fixtureReport.getOracleAssessment());
                                if (fixtureReport.getStatus() == ClassJUnitStatus.CANCELLED || isCancelled(cancelled))
                                    return stopCoverage(result, report, CoverageStatus.CANCELLED, CoverageStopReason.CANCELLED, fixtureReport.getMessage());
                                if (fixtureReport.getStatus() != ClassJUnitStatus.PASSED) {
                                    report.setGenerated(run.getSnapshot()); report.setGaps(run.getGaps());
                                    report.setCodeHash(testCodeFingerprint(result.getGeneratedTestFile().getContent()));
                                    return stopCoverage(result, report,
                                            fixtureReport.getStatus() == ClassJUnitStatus.ENVIRONMENT_ERROR ? CoverageStatus.ENVIRONMENT_ERROR : CoverageStatus.TEST_FAILED,
                                            fixtureReport.getStatus() == ClassJUnitStatus.ENVIRONMENT_ERROR ? CoverageStopReason.ENVIRONMENT_ERROR : CoverageStopReason.TEST_DESIGN_ERROR,
                                            fixtureReport.getMessage());
                                }
                                candidateCode = result.getGeneratedTestFile().getContent();
                                seenCode.add(testCodeFingerprint(candidateCode));
                                compilerDiagnosticHistory.clear(); runtimeDiagnosticHistory.clear();
                                lastRuntimeFailedCode = ""; lastRuntimeFailureSignature = "";
                                continue;
                            }
                            updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                    attemptBudget.totalCalls() + 1,
                                    "正在根据测试运行失败修复候选测试...", 0.65d);
                            String runtimeRepairInstruction =
                                    "执行通用运行修复协议。本请求已按 testId 隔离，只诊断和修复 "
                                            + focusedFailingTestNames + "；其他失败测试将在后续请求中独立处理。"
                                            + "实际异常只是失败证据，不是新的期望；必须保持测试原意。"
                                            + "成功/正常/有效场景意外抛异常时，修复 Arrange、输入、fixture、前置条件或依赖 stub，"
                                            + "不得改成 assertThrows、捕获异常、删除或弱化断言。"
                                            + "若生产源码 branches 可直接证明当前输入命中 action=throw，"
                                            + "优先修正参数关系使其满足所有 safeCondition；"
                                            + "仅当场景本身就是异常契约时才可据源码改为精确 assertThrows。"
                                            + "不得引入反射猜字段，不得在 when/verify 之外使用 Mockito matcher。"
                                            + pendingRuntimeCycleInstruction;
                            pendingRuntimeCycleInstruction = "";
                            CoverageImprovement improvement = improveCoverageCandidate(project, result, report, runtimeRepairBase,
                                    compilerDiagnosticHistory, runtimeDiagnosticHistory,
                                    focusedFailingTestNames, runtimePassedTestNames, attemptBudget, CoverageAttemptKind.REPAIR,
                                    config, coverageClient, mockitoSupport,
                                    sourceText,
                                    runtimeRepairInstruction);
                            if (improvement.oracleAssessment != null
                                    && improvement.oracleAssessment.getDisposition()
                                    != RuntimeRepairDisposition.REPAIR_TEST) {
                                report.setOracleAssessment(improvement.oracleAssessment);
                                // 绑定停止时仍在失败的测试名单，供证据续修提示与用户权威作用域使用。
                                report.setFailingTestNamesAtStop(new ArrayList<>(focusedFailingTestNames));
                                // restoreReportCheckpoint 只用于继续自动探索。一旦疑似 SUT Bug，
                                // UI 必须保留真正失败的快照和诊断，不能展示上一个绿色检查点。
                                report.setGenerated(run.getSnapshot());
                                report.setGaps(run.getGaps());
                                report.setCodeHash(testCodeFingerprint(candidateCode));
                                result.setGeneratedTestFile(copyWithContent(template, candidateCode));
                                if (shouldAnalyzePossibleSourceFailure(improvement.oracleAssessment)) {
                                    updateCoverageProgress(report, listener, config,
                                            CoveragePhase.DIAGNOSING_SUT_BUG,
                                            attemptBudget.totalCalls(),
                                            "正在分析疑似生产代码缺陷的可能位置...", 0.94d);
                                    report.incrementModelRequests();
                                    report.setSourceBugAnalysis(analyzeSuspectedSutBug(
                                            coverageClient, improvement.analysisRequest,
                                            improvement.oracleAssessment));
                                }
                                return stopCoverage(result, report,
                                        coverageStatusForOracle(improvement.oracleAssessment),
                                        coverageStopReasonForOracle(improvement.oracleAssessment),
                                        oracleStopMessage(improvement.oracleAssessment,
                                                report.getFailingTestNamesAtStop()));
                            }
                            String improved = improvement.code;
                            String improvedHash = testCodeFingerprint(improved);
                            if (improvement.unchanged) {
                                restoreAcceptedCandidate(result, report, template, bestAccepted);
                                return stopCoverage(result, report,
                                        coverageFailureStatus(bestAccepted, config),
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
                                return stopCoverage(result, report,
                                        coverageFailureStatus(bestAccepted, config),
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
                                    sourceText,
                                    "上一覆盖候选运行失败，不要修补或复用该候选。"
                                            + "从 currentTestCode 使用不同输入、fixture 或 stub 策略重新命中主 coverage gap。");
                            String alternativeHash = testCodeFingerprint(alternative.code);
                            if (!seenCode.add(alternativeHash)) {
                                return stopCoverage(result, report,
                                        coverageFailureStatus(bestAccepted, config),
                                        CoverageStopReason.REPEATED_CODE, "替代覆盖策略返回了重复测试代码。");
                            }
                            candidateCode = alternative.code;
                            generationPreflightNeeded = true;
                            continue;
                        }
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        return stopCoverage(result, report,
                                coverageFailureStatus(bestAccepted, config),
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
                    boolean targetReached = coverageTargetReached(bestAccepted, config);
                    boolean hasActionableGaps = bestAccepted != null && !bestAccepted.gaps.isEmpty();
                    boolean explorationAvailable = canImprove(
                            coverageClient, attemptBudget, config, CoverageAttemptKind.EXPLORATION);
                    if (CoverageLoopPolicy.perfect(bestAccepted == null ? null : bestAccepted.snapshot)
                            || (targetReached && !hasActionableGaps)) {
                        report.setStatus(CoverageStatus.PASSED);
                        report.setStopReason(CoverageStopReason.TARGET_REACHED);
                        report.setMessage(CoverageLoopPolicy.perfect(bestAccepted.snapshot)
                                ? "生成测试已达到满覆盖。"
                                : "生成测试已达到覆盖率目标，且没有可继续处理的覆盖缺口。");
                        break;
                    }
                    if (!accepted) {
                        report.incrementNoProgressCandidates();
                        restoreAcceptedCandidate(result, report, template, bestAccepted);
                        if (CoverageLoopPolicy.shouldContinueImproving(
                                bestAccepted == null ? null : bestAccepted.snapshot,
                                hasActionableGaps, explorationAvailable)) {
                            updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                                    attemptBudget.totalCalls() + 1,
                                    "本轮无覆盖增益，正在从最佳候选尝试不同输入策略...", 0.85d);
                            CoverageImprovement alternative = improveCoverageCandidate(project, result, report,
                                    bestAccepted.code, new ArrayList<>(), new ArrayList<>(), Set.of(), Set.of(), attemptBudget,
                                    CoverageAttemptKind.EXPLORATION, config,
                                    coverageClient, mockitoSupport,
                                    sourceText,
                                    "上一候选编译和运行成功但覆盖率没有任何增量，已被回滚。"
                                            + "必须保留 currentTestCode，并针对尚未覆盖的单个分支采用不同输入/fixture策略；"
                                            + "不要重复上一种反射、Mock 或参数构造方案。");
                            String alternativeHash = testCodeFingerprint(alternative.code);
                            if (!seenCode.add(alternativeHash)) {
                                report.setStatus(coverageStatusForBest(
                                        bestAccepted, config, CoverageStatus.BELOW_THRESHOLD));
                                report.setStopReason(CoverageStopReason.REPEATED_CODE);
                                report.setMessage("无增益后的替代策略返回了重复测试代码。");
                                break;
                            }
                            candidateCode = alternative.code;
                            generationPreflightNeeded = true;
                            continue;
                        }
                        report.setStatus(coverageStatusForBest(
                                bestAccepted, config, CoverageStatus.BELOW_THRESHOLD));
                        report.setStopReason(CoverageStopReason.NO_PROGRESS);
                        report.setMessage(targetReached
                                ? "覆盖率已达标；后续候选无增益，已保留本次最佳可用版本。"
                                : "候选测试无覆盖增益，已回滚到本次最佳可用版本。");
                        break;
                    }
                    if (!CoverageLoopPolicy.shouldContinueImproving(
                            bestAccepted.snapshot, hasActionableGaps, explorationAvailable)) {
                        report.setStatus(coverageStatusForBest(
                                bestAccepted, config, CoverageStatus.BELOW_THRESHOLD));
                        report.setStopReason(hasActionableGaps
                                ? CoverageStopReason.MAX_ROUNDS : CoverageStopReason.NO_PROGRESS);
                        report.setMessage(!hasActionableGaps
                                ? "当前覆盖率仍有遗漏，但未识别到可继续处理的覆盖缺口。"
                                : coverageClient == null
                                ? (targetReached
                                ? "覆盖率已达标；大模型未启用，无法继续提高覆盖率。"
                                : "大模型未启用，仅生成覆盖率报告。")
                                : (targetReached
                                ? "覆盖率已达标，并已继续探索至覆盖率补测轮次上限。"
                                : "已达到覆盖率补测轮次上限。"));
                        break;
                    }
                    updateCoverageProgress(report, listener, config, CoveragePhase.IMPROVING,
                            attemptBudget.totalCalls() + 1,
                            targetReached
                                    ? "覆盖率已达标，正在使用剩余轮次继续提高覆盖率..."
                                    : "正在把未覆盖目标反馈给大模型...", 0.85d);
                    CoverageImprovement improvement = improveCoverageCandidate(project, result, report, candidateCode,
                            new ArrayList<>(), new ArrayList<>(), Set.of(), Set.of(), attemptBudget,
                            CoverageAttemptKind.EXPLORATION, config, coverageClient, mockitoSupport,
                            sourceText,
                            "只新增命中 coverage.gaps 的测试，保持当前已通过且有覆盖贡献的测试不变。");
                    String improved = improvement.code;
                    String improvedHash = testCodeFingerprint(improved);
                    if (!seenCode.add(improvedHash)) {
                        report.setStatus(coverageStatusForBest(
                                bestAccepted, config, CoverageStatus.BELOW_THRESHOLD));
                        report.setStopReason(CoverageStopReason.REPEATED_CODE);
                        report.setMessage("大模型返回了重复测试代码。");
                        break;
                    }
                    candidateCode = improved;
                    generationPreflightNeeded = true;
                }
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
            CoverageStatus fallback = report.getGenerated().isAvailable()
                    ? CoverageStatus.BELOW_THRESHOLD : CoverageStatus.TEST_FAILED;
            CoverageStatus status = coverageStatusForBest(bestAccepted, config, fallback);
            return stopCoverage(result, report, status, CoverageStopReason.MODEL_ERROR, ex.getMessage());
        } catch (ProcessCanceledException ex) {
            // 用户取消必须按取消语义向上传递给 Task.Backgroundable，不能误报环境错误。
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
            restoreAcceptedCandidate(result, report, coverageTemplate, bestAccepted);
            LOG.warn("UnitMind coverage: loop failed", ex);
            return stopCoverage(result, report, CoverageStatus.ENVIRONMENT_ERROR,
                    CoverageStopReason.ENVIRONMENT_ERROR, safeMessage(ex));
        } finally {
            report.setDurationMillis(System.currentTimeMillis() - started);
            logCoverageOutcome(report);
        }
    }

    /**
     * 补充证据续修：把用户在“补充要求”中输入的证据注入为 USER_REQUIREMENT 权威场景，
     * 再从当前测试代码重跑覆盖率闭环。轻量实现——闭环状态每次都从 currentCode 重建，
     * 不做跨调用持久化；续修次数受 {@code unitmind.coverage.maxEvidenceResumes} 限制。
     */
    public GenerationResult resumeCoverageLoopWithEvidence(Project project,
                                                           PsiFile psiFile,
                                                           GenerationResult result,
                                                           String currentCode,
                                                           String evidence,
                                                           BooleanSupplier cancelled,
                                                           CoverageProgressListener progress) {
        if (result == null || result.getMethodModel() == null || result.getGeneratedTestFile() == null) {
            throw new IllegalArgumentException("缺少被测方法或候选测试代码，无法续修。");
        }
        CoverageReport stopped = result.getCoverageReport();
        if (stopped == null || stopped.getStopReason() != CoverageStopReason.ORACLE_INCONCLUSIVE) {
            throw new IllegalArgumentException("当前结果不是 Oracle 证据不足停止态，无法补充证据续修。");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("补充证据不能为空。");
        }
        CoverageConfig config = CoverageConfigManager.load();
        if (!evidenceResumeAllowed(result.getEvidenceResumeCount(), config)) {
            throw new IllegalArgumentException("补充证据续修次数已达上限（"
                    + config.getMaxEvidenceResumes() + "）。请人工确认失败测试，或重新生成后再试。");
        }
        result.getScenarios().add(buildEvidenceScenario(
                evidence.strip(), stopped.getFailingTestNamesAtStop(),
                result.getEvidenceResumeCount() + 1));
        // 先计数再运行：取消的续修同样消耗额度，倾向保护模型预算而非反复试探。
        result.setEvidenceResumeCount(result.getEvidenceResumeCount() + 1);
        return runCoverageLoop(project, psiFile, result, currentCode, cancelled, progress);
    }

    /**
     * 构建用户证据场景。必须满足 executableScenarios 的过滤规则（type 非 null、
     * 不以 _REASON 结尾、enabled=true），且只能追加到场景列表末尾——
     * oracleAuthorityReferenceSupported 按 index 引用 USER_REQUIREMENT:k，插入会挪动既有引用。
     */
    static TestScenario buildEvidenceScenario(String evidence, List<String> failingTests, int ordinal) {
        TestScenario scenario = new TestScenario();
        scenario.setName("user_evidence_" + ordinal);
        scenario.setDisplayNameCn("用户补充证据（Oracle 权威）");
        scenario.setMethodNameEn("userEvidence" + ordinal);
        scenario.setType("USER_EVIDENCE");
        String bounded = evidence.length() > 2000 ? evidence.substring(0, 2000) : evidence;
        scenario.setDescription(bounded);
        scenario.setExpectedAssertions(List.of(bounded + (failingTests == null || failingTests.isEmpty()
                ? "" : "（适用于失败测试：" + String.join("、", failingTests) + "）")));
        scenario.setOracleKind("UNKNOWN");
        scenario.setOracleEvidence("USER_REQUIREMENT");
        scenario.setOracleConfidence("HIGH");
        scenario.setEnabled(true);
        scenario.setEvidenceScopedFailingTests(failingTests);
        return scenario;
    }

    public static boolean evidenceResumeAllowed(int used, CoverageConfig config) {
        return config != null && config.evidenceResumeAllowed(used);
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
                                                         String productionClassSource,
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
            request.setClassSourceCode(productionClassSource);
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
            String proposedCandidateHash = "";
            try {
                report.incrementModelRequests();
                LlmGenerationResponse response = client.improveCoverage(request);
                proposedCandidateHash = response.hasMethodPatches()
                        ? CodeFingerprint.sha256(response.getMethodPatches().stream().filter(java.util.Objects::nonNull)
                        .map(com.casc.unittestagent.llm.LlmMethodPatch::getMethodCode).collect(java.util.stream.Collectors.joining("\n")))
                        : testCodeFingerprint(response.getTestCode());
                boolean runtimeRepair = runtimeDiagnostics != null && !runtimeDiagnostics.isEmpty();
                // 响应形态契约必须先于仲裁枚举解析检查：否则形态违约（如运行修复返回整类
                // testCode、disposition 为空）会以普通 IllegalArgumentException 终止闭环，
                // 而不是进入下面的格式重试。终止类 disposition（无补丁）是合法形态，须放行。
                boolean terminalDisposition = "SUSPECTED_SUT_BUG".equalsIgnoreCase(
                        response.getRepairDisposition())
                        || "INCONCLUSIVE".equalsIgnoreCase(response.getRepairDisposition());
                if (runtimeRepair && !response.hasMethodPatches() && !terminalDisposition) {
                    throw new LlmResponseFormatException(
                            "运行修复必须返回 methodPatches，不接受整类 testCode；diagnosis 必须包含合法 repairDisposition。");
                }
                if (!runtimeRepair && response.hasMethodPatches()) {
                    throw new LlmResponseFormatException(
                            "编译修复/覆盖率探索必须返回完整测试类 testCode，不接受 methodPatches。");
                }
                OracleAssessment oracleAssessment = runtimeRepair
                        ? runtimeOracleGate.assessment(response, runtimeDiagnostics) : null;
                if (oracleAssessment != null) report.setOracleAssessment(oracleAssessment);
                if (oracleAssessment != null
                        && oracleAssessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG
                        && !oracleAssessment.getAuthority().permitsAutomaticOracleCorrection()) {
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    oracleAssessment.setEvidence(appendEvidence(oracleAssessment.getEvidence(),
                            "疑似 SUT Bug 缺少独立契约证据。"));
                }
                if (oracleAssessment != null
                        && !oracleAuthorityReferenceSupported(request, oracleAssessment)) {
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    String scopeNote = evidenceScopeRejectionNote(request, oracleAssessment);
                    oracleAssessment.setEvidence(appendEvidence(oracleAssessment.getEvidence(),
                            scopeNote.isBlank()
                                    ? "oracleAuthorityRef 未引用本轮上下文中真实存在的同类型证据："
                                            + oracleAssessment.getAuthorityRef()
                                    : scopeNote));
                }
                if (oracleAssessment != null) {
                    normalizeUnsupportedFixtureClassification(oracleAssessment, runtimeDiagnostics);
                    reconcileWiringAssessment(oracleAssessment, runtimeDiagnostics);
                    reconcileSpringAssessment(oracleAssessment, runtimeDiagnostics);
                }
                if (oracleAssessment != null
                        && oracleAssessment.getDisposition() == RuntimeRepairDisposition.REPAIR_TEST
                        && oracleAssessment.getDecision() == OracleDecision.KEEP
                        && hasOracleSensitiveFailure(runtimeDiagnostics)
                        && !isProvenFixtureRepair(oracleAssessment, runtimeDiagnostics)) {
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    oracleAssessment.setEvidence(appendEvidence(oracleAssessment.getEvidence(),
                            "当前交互或断言失败尚无充分装配证据，不能判定是测试装配错误或生产行为不符；已阻止通过改 Arrange 绕过失败。"));
                }
                if (oracleAssessment != null
                        && oracleAssessment.getDisposition() != RuntimeRepairDisposition.REPAIR_TEST) {
                    return CoverageImprovement.terminal(currentCode, oracleAssessment, request);
                }
                if (oracleAssessment != null
                        && RuntimeOracleGate.automaticOracleCorrectionBlocked(
                        oracleAssessment, config.isStrictOracleGate())) {
                    oracleAssessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
                    oracleAssessment.setEvidence(appendEvidence(oracleAssessment.getEvidence(),
                            config.isStrictOracleGate()
                                    && oracleAssessment.getAuthority().permitsAutomaticOracleCorrection()
                                    ? RuntimeOracleGate.strictOracleGateMessage(oracleAssessment)
                                    : unsafeAuthorityMessage(oracleAssessment)));
                    return CoverageImprovement.terminal(currentCode, oracleAssessment, request);
                }
                Set<String> editableTests = runtimeFailingTestNames == null || runtimeFailingTestNames.isEmpty()
                        ? failingTestNames(runtimeDiagnostics) : runtimeFailingTestNames;
                String modelCode = response.hasMethodPatches()
                        ? coverageTestClassStabilizer.applyMethodPatches(
                        currentCode, response.getMethodPatches(), editableTests)
                        : response.getTestCode();
                GeneratedTestCodePostProcessor.ProcessedCode processed =
                        generatedCodePostProcessor.processRepair(modelCode, method, currentCode);
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
                if (runtimeRepair) {
                    proposedCandidateHash = testCodeFingerprint(nextCode);
                    if (!isProvenNoInteractionsContradictionRepair(
                            currentCode, nextCode, runtimeDiagnostics)) {
                        runtimeOracleGate.validateRepair(
                                oracleAssessment, currentCode, nextCode, editableTests);
                    }
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
                        // 不重置格式/传输重试预算：此前每次“无变化”重试都会恢复全部传输与格式
                        // 重试额度，单次改进调用最坏可放大出十余次模型请求且不消耗任何语义轮次。
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
            } catch (RepairCandidateRejectedException ex) {
                var rejection = candidateRejection(ex.getStage().name(), proposedCandidateHash, ex.getMessage(), runtimeDiagnostics);
                report.recordCandidateRejection(rejection);
                // A proposed but unsafe change consumes repair budget. It is never executed,
                // never replaces currentCode, and never becomes a new runtime root cause.
                attemptBudget.consume(attemptKind, report);
                previousAttemptSummary = candidateRejectionFeedback(rejection);
                LOG.warn("UnitMind coverage: candidate rejected, stage=" + rejection.stage()
                        + ", observed=" + rejection.observedFailureCategories()
                        + ", hash=" + shortHash(proposedCandidateHash));
                if (attemptBudget.canUse(attemptKind, config)) continue;
                return CoverageImprovement.terminal(currentCode,
                        rejectedCandidateAssessment(runtimeDiagnostics, previousAttemptSummary + " 修复预算已用完。"));
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
                if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
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

    static boolean hasOracleSensitiveFailure(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null) return false;
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            String category = diagnostic.getCategory() == null ? "" : diagnostic.getCategory();
            if ((category.startsWith("ASSERTION_")
                    && !"ASSERT_THROWS_WRAPPED_BY_REFLECTION".equals(category))
                    || category.equals("ASSERTION_FAILURE")
                    || category.equals("MOCKITO_INVOCATION_COUNT_MISMATCH")
                    || category.equals("MOCKITO_ARGUMENT_MISMATCH")
                    || category.equals("MOCKITO_WANTED_BUT_NOT_INVOKED")
                    || category.equals(MockWiringAnalyzer.WIRING) || category.equals(MockWiringAnalyzer.INTERNAL)) {
                return true;
            }
        }
        return false;
    }

    static boolean isProvenFixtureRepair(OracleAssessment assessment,
                                         List<RuntimeDiagnostic> diagnostics) {
        if (assessment == null || diagnostics == null || diagnostics.isEmpty()) {
            return false;
        }
        if (SpringFixtureAnalyzer.BINDING_FAILURE.equals(assessment.getRootCauseCategory()))
            return SpringFixtureAnalyzer.allowsPropertyRepair(diagnostics) && !hasOracleSensitiveFailure(diagnostics);
        if (MockWiringAnalyzer.WIRING.equals(assessment.getRootCauseCategory()))
            return diagnostics.stream().allMatch(d -> d != null && MockWiringAnalyzer.WIRING.equals(d.getCategory())
                    && !d.getMockWiringEvidence().isEmpty() && d.getMockWiringEvidence().stream().allMatch(e ->
                    MockWiringAnalyzer.WIRING.equals(e.category()) && !e.testHash().isBlank()
                            && !e.productionHash().isBlank() && !e.removableAssignment().isBlank()));
        if (FixtureLifecycleAnalyzer.CATEGORY.equals(assessment.getRootCauseCategory()))
            return FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(diagnostics) && !hasOracleSensitiveFailure(diagnostics);
        if (!"FIXTURE_DEPENDENCY_MISSING".equals(assessment.getRootCauseCategory())) return false;
        boolean proven = false;
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            if ("FIXTURE_DEPENDENCY_MISSING".equals(diagnostic.getCategory())) {
                proven = true;
                continue;
            }
            if (hasOracleSensitiveFailure(List.of(diagnostic))) return false;
        }
        return proven;
    }

    private static void reconcileSpringAssessment(OracleAssessment assessment, List<RuntimeDiagnostic> diagnostics) {
        if (assessment == null || !SpringRuntimeFailureAnalyzer.hasSharedFailure(diagnostics)) return;
        boolean proven = SpringFixtureAnalyzer.allowsPropertyRepair(diagnostics);
        assessment.setRootCauseCategory(proven ? SpringFixtureAnalyzer.BINDING_FAILURE : SpringRuntimeFailureAnalyzer.CATEGORY);
        assessment.setEvidence(appendEvidence(assessment.getEvidence(), "真实 Spring 初始化证据：" + SpringRuntimeFailureAnalyzer.summary(diagnostics)));
        if (proven && assessment.getDecision() == OracleDecision.KEEP) {
            assessment.setAuthority(OracleAuthority.NONE); assessment.setAuthorityRef("NONE");
        } else if (!proven) {
            assessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
            assessment.setEvidence(appendEvidence(assessment.getEvidence(), "尚无源码与最内层异常共同证明的属性修复范围；保留测试，不能修改业务 Oracle。"));
        }
    }

    private static void reconcileWiringAssessment(OracleAssessment assessment, List<RuntimeDiagnostic> diagnostics) {
        if (assessment == null || diagnostics == null || diagnostics.isEmpty()) return;
        var proven = diagnostics.stream().filter(d -> d != null && !d.getMockWiringEvidence().isEmpty()).toList();
        if (proven.isEmpty()) return;
        String category = proven.stream().anyMatch(d -> MockWiringAnalyzer.INTERNAL.equals(d.getCategory()))
                ? MockWiringAnalyzer.INTERNAL : MockWiringAnalyzer.WIRING;
        assessment.setRootCauseCategory(category);
        assessment.setEvidence(List.of("已按当前源码与真实失败位置恢复归因；模型未证实的对象实例或空参数推断不作为根因。"));
        for (var d : proven) for (var evidence : d.getMockWiringEvidence())
            assessment.setEvidence(appendEvidence(assessment.getEvidence(), "源码装配证据：" + evidence.fact()));
        if (MockWiringAnalyzer.INTERNAL.equals(category) && assessment.getDecision() == OracleDecision.KEEP) {
            assessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
            assessment.setEvidence(appendEvidence(assessment.getEvidence(),
                    "验证了未接入的内部状态 mock，保持原 Oracle 无法修复；改变验证方式须独立契约授权，不能伪装为装配修复。"));
        } else if (MockWiringAnalyzer.WIRING.equals(category) && assessment.getDecision() == OracleDecision.KEEP) {
            assessment.setAuthority(OracleAuthority.NONE); assessment.setAuthorityRef("NONE");
        }
    }

    private static void reconcileLifecycleAssessment(OracleAssessment assessment, List<RuntimeDiagnostic> diagnostics) {
        if (!FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(diagnostics)) return;
        if (!FixtureLifecycleAnalyzer.CATEGORY.equals(assessment.getRootCauseCategory()))
            assessment.setEvidence(appendEvidence(assessment.getEvidence(), "已按实际 Mockito 初始化证据恢复根因分类；测试方法尚未执行。"));
        assessment.setRootCauseCategory(FixtureLifecycleAnalyzer.CATEGORY);
        if (assessment.getDecision() == OracleDecision.KEEP) {
            // A runner-authorized fixture repair does not require authority to change business expectations.
            assessment.setAuthority(OracleAuthority.NONE); assessment.setAuthorityRef("NONE");
        }
    }

    /**
     * 模型不得用 Fixture 缺失覆盖真实的运行失败类别。三方证据不成立时，
     * 恢复为确定性诊断器给出的类别，避免 UI 继续显示误导性分类。
     */
    static boolean normalizeUnsupportedFixtureClassification(OracleAssessment assessment,
                                                               List<RuntimeDiagnostic> diagnostics) {
        if (assessment == null
                || !"FIXTURE_DEPENDENCY_MISSING".equals(assessment.getRootCauseCategory())
                || isProvenFixtureRepair(assessment, diagnostics)) {
            return false;
        }
        String observedCategory = diagnostics == null ? "" : diagnostics.stream()
                .filter(java.util.Objects::nonNull)
                .map(RuntimeDiagnostic::getCategory)
                .filter(category -> category != null && !category.isBlank()
                        && !"FIXTURE_DEPENDENCY_MISSING".equals(category))
                .findFirst().orElse("");
        assessment.setRootCauseCategory(observedCategory.isBlank()
                ? "UNSUPPORTED_FIXTURE_CLASSIFICATION" : observedCategory);
        if (FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(diagnostics)) {
            assessment.setEvidence(appendEvidence(assessment.getEvidence(),
                    "真实证据属于 Mockito 初始化失败，已恢复初始化分类；它不要求业务路径上的 NPE 证据。"));
            return true;
        }
        assessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
        assessment.setEvidence(appendEvidence(assessment.getEvidence(),
                "已拒绝模型的 FIXTURE_DEPENDENCY_MISSING 分类：缺少源码构造依赖、"
                        + "测试声明和真实 NullPointerException 三方证明；已恢复真实运行诊断类别。"));
        return true;
    }

    static boolean shouldAnalyzePossibleSourceFailure(OracleAssessment assessment) {
        if (assessment == null) return false;
        return assessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG
                || "ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN".equals(
                assessment.getRootCauseCategory());
    }

    static boolean isProvenNoInteractionsContradictionRepair(String currentCode,
                                                              String proposedCode,
                                                              List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()
                || diagnostics.stream().filter(java.util.Objects::nonNull)
                .anyMatch(item -> !"MOCKITO_NO_INTERACTIONS_CONTRADICTION".equals(
                        item.getCategory()))) {
            return false;
        }
        MockitoNoInteractionsContradictionGuard.Result deterministic =
                MockitoNoInteractionsContradictionGuard.inspectAndRepair(currentCode, true);
        return deterministic.isChanged()
                && testCodeFingerprint(deterministic.getCode())
                .equals(testCodeFingerprint(proposedCode));
    }

    private static boolean hasRuntimeCategory(List<RuntimeDiagnostic> diagnostics, String category) {
        if (diagnostics == null || category == null) return false;
        return diagnostics.stream().filter(java.util.Objects::nonNull)
                .anyMatch(diagnostic -> category.equals(diagnostic.getCategory()));
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
                        diagnostic.isSharedContextFailure() ? nullToSignature(diagnostic.getRootCauseMessage()) : "",
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

    /** skipped 名单：接受门禁用它守住“失败→跳过”伪修复（failed 会下降但不是修复）。 */
    static Set<String> skippedTestNames(TestExecutionSummary summary) {
        Set<String> names = new LinkedHashSet<>();
        if (summary == null) return names;
        for (TestCaseExecution test : summary.getTestCases()) {
            if (test == null || !test.isSkipped()) continue;
            String name = testName(test.getTestId());
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    private static Set<String> methodNames(Set<String> testIds) {
        Set<String> names = new LinkedHashSet<>();
        if (testIds == null) return names;
        for (String id : testIds) {
            String name = testName(id);
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    /**
     * Oracle 仲裁停止时的终态路由：按失败类别的 repairStrategy 分流，而不是把一切
     * 非 SUSPECTED_SUT_BUG 的仲裁一律归为“Oracle 证据不足”——那会把依赖缺失、
     * 框架启动失败、外部资源不可达等运行时环境问题错误归咎到 Oracle。
     * EVIDENCE_REQUIRED/UNCLASSIFIED 保留 NEEDS_REVIEW+ORACLE_INCONCLUSIVE（证据续修入口）；
     * DETERMINISTIC → TEST_FAILED+TEST_DESIGN_ERROR（沿用 Slf4j 守卫先例）；
     * ENVIRONMENT → TEST_FAILED+RUNTIME_ENVIRONMENT_FAILURE（写入门禁保持受保护语义）。
     */
    static CoverageStatus coverageStatusForOracle(OracleAssessment assessment) {
        if (assessment == null) return CoverageStatus.NEEDS_REVIEW;
        if (assessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG) {
            return CoverageStatus.SUSPECTED_SUT_BUG;
        }
        return oracleCategoryIsStructural(assessment) ? CoverageStatus.TEST_FAILED : CoverageStatus.NEEDS_REVIEW;
    }

    static CoverageStopReason coverageStopReasonForOracle(OracleAssessment assessment) {
        if (assessment == null) return CoverageStopReason.ORACLE_INCONCLUSIVE;
        if (assessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG) {
            return CoverageStopReason.SUSPECTED_SUT_BUG;
        }
        FailureCategory category = FailureCategory.fromCode(assessment.getRootCauseCategory());
        if (category.getRepairStrategy() == RepairStrategy.DETERMINISTIC) {
            return CoverageStopReason.TEST_DESIGN_ERROR;
        }
        if (category.getRepairStrategy() == RepairStrategy.ENVIRONMENT) {
            return CoverageStopReason.RUNTIME_ENVIRONMENT_FAILURE;
        }
        return CoverageStopReason.ORACLE_INCONCLUSIVE;
    }

    static ClassJUnitStatus classStatusForOracle(OracleAssessment assessment) {
        if (assessment == null) return ClassJUnitStatus.NEEDS_REVIEW;
        if (assessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG) {
            return ClassJUnitStatus.SUSPECTED_SUT_BUG;
        }
        return oracleCategoryIsStructural(assessment) ? ClassJUnitStatus.TEST_FAILED : ClassJUnitStatus.NEEDS_REVIEW;
    }

    static ClassJUnitStopReason classStopReasonForOracle(OracleAssessment assessment) {
        if (assessment == null) return ClassJUnitStopReason.ORACLE_INCONCLUSIVE;
        if (assessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG) {
            return ClassJUnitStopReason.SUSPECTED_SUT_BUG;
        }
        FailureCategory category = FailureCategory.fromCode(assessment.getRootCauseCategory());
        if (category.getRepairStrategy() == RepairStrategy.DETERMINISTIC) {
            return ClassJUnitStopReason.TEST_DESIGN_ERROR;
        }
        if (category.getRepairStrategy() == RepairStrategy.ENVIRONMENT) {
            return ClassJUnitStopReason.RUNTIME_ENVIRONMENT_FAILURE;
        }
        return ClassJUnitStopReason.ORACLE_INCONCLUSIVE;
    }

    /** 结构性（测试侧可修）或环境性失败：终态不占用 Oracle 待确认语义。 */
    private static boolean oracleCategoryIsStructural(OracleAssessment assessment) {
        RepairStrategy strategy = FailureCategory.fromCode(assessment.getRootCauseCategory()).getRepairStrategy();
        return strategy == RepairStrategy.DETERMINISTIC || strategy == RepairStrategy.ENVIRONMENT;
    }

    static String oracleStopMessage(OracleAssessment assessment) {
        return oracleStopMessage(assessment, List.of());
    }

    /**
     * 按 FailureCategory 元数据生成诚实的停止文案：结构错误、缺证据与环境问题不再
     * 统一显示为“Oracle 证据冲突或不足”；EVIDENCE_REQUIRED 类别附带用户可补充的信息模板。
     */
    static String oracleStopMessage(OracleAssessment assessment, List<String> failingTestNames) {
        if (assessment == null) return "Oracle 证据不足，已保留原失败测试。";
        FailureCategory category = FailureCategory.fromCode(assessment.getRootCauseCategory());
        String prefix;
        if (assessment.getDisposition() == RuntimeRepairDisposition.SUSPECTED_SUT_BUG) {
            prefix = "疑似生产代码缺陷，已停止自动修复并保留原失败测试。";
        } else {
            prefix = switch (category.getRepairStrategy()) {
                case EVIDENCE_REQUIRED -> "缺少可授权修改 Oracle 的独立证据（" + category.getLabelCn()
                        + "），已停止自动修复并保留原失败测试。可在下方补充证据后继续修复："
                        + category.formatRequiredUserInfo(failingTestNames);
                case DETERMINISTIC -> "测试侧结构错误（" + category.getLabelCn()
                        + "）：自动修复已停止，原失败测试已保留，可人工修正后重新校验。";
                case ENVIRONMENT -> {
                    String info = category.getRequiredUserInfo();
                    if (category == FailureCategory.FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE)
                        yield "Spring 共享上下文初始化失败，测试正文尚未执行；请查看最内层 Caused by 和完整运行证据，"
                                + "检查属性绑定、Bean 依赖与容器对象生命周期。已保留原测试，不能按单个业务用例失败处理。";
                    yield "测试执行环境不可用（" + category.getLabelCn()
                            + "）：请检查 SDK、测试依赖或项目索引后重试。"
                            + (info.isBlank() ? "" : info);
                }
                default -> {
                    // UNCLASSIFIED 不再宣称“Oracle 证据冲突或不足”——运行时失败未归类
                    // 与 Oracle 无关，如实说明并引导补充归因信息。
                    String info = category.formatRequiredUserInfo(failingTestNames);
                    yield "运行失败未能归类（" + category.getLabelCn()
                            + "），已停止自动修复并保留原失败测试。"
                            + (info.isBlank() ? "" : "可补充信息辅助归因：" + info);
                }
            };
        }
        String evidence = assessment.getEvidence().isEmpty()
                ? "" : " 证据：" + String.join("；", assessment.getEvidence());
        return prefix + " 分类=" + category.displayText()
                + "，权威来源=" + assessment.getAuthority()
                + "，证据引用=" + assessment.getAuthorityRef() + "。" + evidence;
    }

    static boolean oracleAuthorityReferenceSupported(LlmGenerationRequest request,
                                                     OracleAssessment assessment) {
        if (assessment == null) return false;
        String reference = assessment.getAuthorityRef() == null ? "" : assessment.getAuthorityRef().trim();
        if (!assessment.getAuthority().permitsAutomaticOracleCorrection()) {
            return "NONE".equals(reference);
        }
        int separator = reference.lastIndexOf(':');
        if (separator <= 0 || separator == reference.length() - 1) return false;
        String type = reference.substring(0, separator);
        int index;
        try {
            index = Integer.parseInt(reference.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return false;
        }
        if (!assessment.getAuthority().name().equals(type) || index < 0 || request == null) return false;
        ProjectSymbolSnapshot snapshot = request.getSymbolSnapshot();
        return switch (assessment.getAuthority()) {
            case USER_REQUIREMENT -> request.getScenarios() != null
                    && index < request.getScenarios().size()
                    && request.getScenarios().get(index) != null
                    && "USER_REQUIREMENT".equalsIgnoreCase(
                    request.getScenarios().get(index).getOracleEvidence())
                    && request.getScenarios().get(index).getExpectedAssertions() != null
                    && !request.getScenarios().get(index).getExpectedAssertions().isEmpty()
                    // 用户补充证据绑定作用域：名单非空时必须与本轮失败测试有交集，否则不得授权修改本轮 Oracle。
                    && evidenceScopeCoversFailingTests(request, index);
            case EXISTING_TEST -> snapshot != null && index < snapshot.getTestExamples().size();
            case CONTRACT_FACT -> snapshot != null && index < snapshot.getContractFacts().size()
                    && isIndependentContractFact(snapshot.getContractFacts().get(index));
            default -> false;
        };
    }

    private static boolean isIndependentContractFact(String value) {
        String fact = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return fact.startsWith("CONTRACT:") || fact.startsWith("REQUIREMENT:")
                || fact.startsWith("EXISTING_TEST_ORACLE:");
    }

    /**
     * 用户补充证据的作用域判定：evidenceScopedFailingTests 非空时，名单必须与
     * 本轮失败测试（request.failingTestIds，testName() 规范化格式）有交集。
     * 名单为空（未限定）或本轮无失败名单时不强校验。
     */
    static boolean evidenceScopeCoversFailingTests(LlmGenerationRequest request, int index) {
        if (request == null || request.getScenarios() == null
                || index < 0 || index >= request.getScenarios().size()
                || request.getScenarios().get(index) == null) {
            return true;
        }
        List<String> scoped = request.getScenarios().get(index).getEvidenceScopedFailingTests();
        if (scoped == null || scoped.isEmpty()) return true;
        List<String> failing = request.getFailingTestIds();
        if (failing == null || failing.isEmpty()) return true;
        return failing.stream().anyMatch(scoped::contains);
    }

    /** 引用校验失败且根因是作用域不匹配时的专用降级说明；空串表示不适用。 */
    static String evidenceScopeRejectionNote(LlmGenerationRequest request, OracleAssessment assessment) {
        if (assessment == null || assessment.getAuthority() != OracleAuthority.USER_REQUIREMENT) return "";
        String reference = assessment.getAuthorityRef() == null ? "" : assessment.getAuthorityRef().trim();
        int separator = reference.lastIndexOf(':');
        if (separator <= 0) return "";
        int index;
        try {
            index = Integer.parseInt(reference.substring(separator + 1));
        } catch (NumberFormatException ex) {
            return "";
        }
        if (request == null || request.getScenarios() == null
                || index < 0 || index >= request.getScenarios().size()
                || request.getScenarios().get(index) == null) {
            return "";
        }
        List<String> scoped = request.getScenarios().get(index).getEvidenceScopedFailingTests();
        if (scoped == null || scoped.isEmpty()) return "";
        if (evidenceScopeCoversFailingTests(request, index)) return "";
        return "用户补充证据的作用域（适用于失败测试：" + String.join("、", scoped)
                + "）不包含本轮失败测试，不得据此修改本轮 Oracle。";
    }

    private static String unsafeAuthorityMessage(OracleAssessment assessment) {
        return "拒绝自动修改 Oracle：" + (assessment == null ? "NONE" : assessment.getAuthority())
                + " 不是独立契约证据。当前生产实现或 runtime actual 不能用来把可能的 Bug 改写成测试期望。";
    }

    private static List<String> appendEvidence(List<String> evidence, String value) {
        List<String> result = new ArrayList<>(evidence == null ? List.of() : evidence);
        if (value != null && !value.isBlank()) result.add(value);
        return result;
    }

    /**
     * 最多调用一次只读归因。失败只影响解释可用性，绝不改变 Oracle 终态或测试代码。
     */
    private SourceBugAnalysis analyzeSuspectedSutBug(LlmClient client,
                                                      LlmGenerationRequest request,
                                                      OracleAssessment assessment) {
        if (client == null || request == null || assessment == null
                || !shouldAnalyzePossibleSourceFailure(assessment)) {
            return SourceBugAnalysis.failed("缺少疑似缺陷归因所需的运行上下文。");
        }
        try {
            SourceBugAnalysis analysis = client.analyzeSuspectedSutBug(request, assessment);
            if (analysis == null || !analysis.isAvailable()) {
                return SourceBugAnalysis.failed("大模型未返回可展示的源码缺陷归因。");
            }
            analysis.setAttempted(true);
            return analysis;
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
            LOG.warn("UnitMind source bug analysis failed: " + safeMessage(ex));
            return SourceBugAnalysis.failed("源码缺陷归因未完成：" + safeMessage(ex));
        }
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
                || snapshot.getExecution() == null || !snapshot.getExecution().isFullySuccessful()) {
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
        // 会话失效保护：面板 invalidateGeneratedTestCode 会置空 generatedTestFile 表示
        // 用户已编辑输入、结果将按新数据重新生成；此时跳过候选回写，避免被取消的循环
        // 把旧候选写回共享对象（用户主动停止不会置空，最佳候选仍正常保留）。
        if (result.getGeneratedTestFile() == null) {
            report.setGenerated(accepted.snapshot);
        report.setCandidateEvidence(accepted.snapshot.getCandidateEvidence());
            report.setGaps(new ArrayList<>(accepted.gaps));
            report.setCodeHash(testCodeFingerprint(accepted.code));
            return;
        }
        result.setGeneratedTestFile(copyWithContent(template, accepted.code));
        report.setGenerated(accepted.snapshot);
        report.setCandidateEvidence(accepted.snapshot.getCandidateEvidence());
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
        report.setCandidateEvidence(accepted.snapshot.getCandidateEvidence());
        report.setGaps(new ArrayList<>(accepted.gaps));
        report.setCodeHash(testCodeFingerprint(accepted.code));
    }

    private boolean coverageTargetReached(AcceptedCoverageCandidate accepted, CoverageConfig config) {
        return accepted != null && CoverageLoopPolicy.reached(accepted.snapshot, config);
    }

    /** 继续探索失败时保留最佳检查点的成功语义，不把已经达标的结果降级。 */
    private CoverageStatus coverageStatusForBest(AcceptedCoverageCandidate accepted,
                                                 CoverageConfig config,
                                                 CoverageStatus fallback) {
        return coverageTargetReached(accepted, config) ? CoverageStatus.PASSED : fallback;
    }

    private CoverageStatus coverageFailureStatus(AcceptedCoverageCandidate accepted,
                                                  CoverageConfig config) {
        return coverageStatusForBest(accepted, config, accepted == null
                ? CoverageStatus.TEST_FAILED : CoverageStatus.BELOW_THRESHOLD);
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
                + ", candidateRejections=" + report.getCandidateRejections().size()
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

    private static final class ClassRunCandidate {
        private final ValidationResult validation;
        private final TestExecutionSummary execution;
        private final boolean cancelled;
        private final boolean environmentError;
        private final String message;

        private ClassRunCandidate(ValidationResult validation,
                                  TestExecutionSummary execution,
                                  boolean cancelled,
                                  boolean environmentError,
                                  String message) {
            this.validation = validation == null
                    ? ValidationResult.status(ValidationStatus.NOT_RUN) : validation;
            this.execution = execution == null ? new TestExecutionSummary() : execution;
            this.cancelled = cancelled;
            this.environmentError = environmentError;
            this.message = message == null ? "" : message;
        }
    }

    private void configureMockitoSupport(LlmGenerationRequest request, Project project, PsiFile psiFile) {
        if (request == null) return;
        if (psiFile != null) {
            request.setClassSourceCode(ReadAction.compute(psiFile::getText));
            request.setSpringPropertyBindings(SpringFixtureAnalyzer.properties(MockWiringAnalyzer.sources(psiFile)));
        }
        if (project == null || psiFile == null) {
            request.setMockitoStaticMockingSupported(false);
            request.setMockitoStaticMockingSupport("缺少项目上下文，按不支持静态 Mock 处理");
            return;
        }
        try {
            ProjectTestEnvironment environment = coverageEnvironmentResolver.resolve(project, psiFile);
            applyMockitoSupport(request, MockitoStaticMockSupport.detect(environment.getRuntimeClasspath()));
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
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
            return CodeFingerprint.sha256(StaticJavaParser.parse(source).toString().replace("\r\n", "\n").replace('\r', '\n'));
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

    private static RepairCandidateRejection candidateRejection(String stage, String hash, String reason,
                                                                List<RuntimeDiagnostic> observed) {
        return new RepairCandidateRejection(stage, hash, reason,
                observed == null ? List.of() : observed.stream().filter(java.util.Objects::nonNull)
                        .map(RuntimeDiagnostic::getCategory).filter(java.util.Objects::nonNull).distinct().toList());
    }

    private static String candidateRejectionFeedback(RepairCandidateRejection rejection) {
        return "候选补丁未通过安全门禁，未执行、未接纳。原运行失败分类=" + rejection.observedFailureCategories()
                + "；拒绝阶段=" + rejection.stage() + "；原因=" + rejection.reason()
                + "。请从 currentTestCode 针对原 runtimeDiagnostics 重新修复，保留断言、验证参数及次数和执行路径；不得重复该补丁。";
    }

    private static OracleAssessment rejectedCandidateAssessment(List<RuntimeDiagnostic> observed, String feedback) {
        var assessment = new OracleAssessment();
        assessment.setDisposition(RuntimeRepairDisposition.INCONCLUSIVE);
        assessment.setDecision(OracleDecision.KEEP);
        assessment.setAuthority(OracleAuthority.NONE);
        assessment.setAuthorityRef("NONE");
        assessment.setRootCauseCategory(observed == null ? "TEST_RUNTIME_FAILURE" : observed.stream()
                .filter(java.util.Objects::nonNull).map(RuntimeDiagnostic::getCategory)
                .filter(category -> category != null && !category.isBlank()).findFirst().orElse("TEST_RUNTIME_FAILURE"));
        assessment.setEvidence(List.of(feedback));
        return assessment;
    }

    /** 分离探索与修复配额，避免候选语法/运行错误吞掉真正的覆盖率探索机会。 */
    static final class CoverageAttemptBudget {
        private int explorationCalls;
        private int repairCalls;

        int totalCalls() { return explorationCalls + repairCalls; }
        int remainingRepairs(CoverageConfig config) { return Math.max(0, config.getMaxRepairRounds() - repairCalls); }

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

        /** 返回有变化候选后计入预算；在安全门禁被拒绝的补丁也单独扣除预算。 */
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
        private final OracleAssessment oracleAssessment;
        private final LlmGenerationRequest analysisRequest;

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
            this.oracleAssessment = null;
            this.analysisRequest = null;
        }

        private CoverageImprovement(String code,
                                    OracleAssessment oracleAssessment,
                                    LlmGenerationRequest analysisRequest) {
            this.code = code == null ? "" : code;
            String hash = testCodeFingerprint(this.code);
            this.rawModelHash = hash;
            this.postProcessedHash = hash;
            this.stabilizedHash = hash;
            this.normalizedHash = hash;
            this.unchanged = false;
            this.unchangedStage = "";
            this.oracleAssessment = oracleAssessment;
            this.analysisRequest = analysisRequest;
        }

        private static CoverageImprovement terminal(String code,
                                                    OracleAssessment assessment,
                                                    LlmGenerationRequest analysisRequest) {
            return new CoverageImprovement(code, assessment, analysisRequest);
        }

        private static CoverageImprovement terminal(String code, OracleAssessment assessment) {
            return terminal(code, assessment, null);
        }
    }

    private static final class CoverageModelException extends Exception {
        private CoverageModelException(String message, Throwable cause) {
            super(message, cause);
        }
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
        diagnostics.stream().filter(java.util.Objects::nonNull)
                .filter(d -> d.isSharedContextFailure() && !d.isContextFailureSecondary())
                .filter(d -> !history.contains(d)).forEach(history::add);
        return failingTestNames(history);
    }

    /**
     * 一次模型请求只处理一个失败测试。优先处理有确定性、不需要改 Oracle 的
     * Mockito/反射/fixture 错误，避免另一个契约不足的失败终止整批修复。
     */
    static List<RuntimeDiagnostic> focusRuntimeDiagnostics(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();
        SpringRuntimeFailureAnalyzer.enrich(diagnostics);
        var primaryContext = diagnostics.stream().filter(java.util.Objects::nonNull)
                .filter(d -> d.isSharedContextFailure() && !d.isContextFailureSecondary()).findFirst();
        if (primaryContext.isPresent()) return List.of(primaryContext.get());
        Map<String, List<RuntimeDiagnostic>> byTest = new java.util.LinkedHashMap<>();
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            String test = testName(diagnostic.getTestId());
            if (test.isBlank()) test = diagnostic.getTestId();
            byTest.computeIfAbsent(test == null ? "" : test, ignored -> new ArrayList<>())
                    .add(diagnostic);
        }
        return byTest.values().stream()
                .min(java.util.Comparator.comparingInt(UnitTestGenerationService::diagnosticGroupPriority))
                .map(ArrayList::new).orElseGet(ArrayList::new);
    }

    private static int diagnosticGroupPriority(List<RuntimeDiagnostic> diagnostics) {
        int priority = 3;
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            String category = diagnostic == null || diagnostic.getCategory() == null
                    ? "" : diagnostic.getCategory();
            if (java.util.Set.of(
                    "MOCKITO_NO_INTERACTIONS_CONTRADICTION",
                    "MOCKITO_MISSING_METHOD_INVOCATION",
                    "MOCKITO_VOID_STUB_MISUSE",
                    "MOCKITO_WRONG_RETURN_TYPE",
                    "MOCKITO_INVALID_MATCHER_USAGE",
                    "MOCKITO_FIXTURE_INITIALIZATION_FAILURE",
                    "ASSERT_THROWS_WRAPPED_BY_REFLECTION",
                    "FIXTURE_DEPENDENCY_MISSING").contains(category)) {
                priority = Math.min(priority, 0);
            } else if (!hasOracleSensitiveFailure(List.of(diagnostic))) {
                priority = Math.min(priority, 1);
            } else {
                priority = Math.min(priority, 2);
            }
        }
        return priority;
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
                    + safeLogValue(diagnostic.getCategory()) + " failurePhase=" + diagnostic.getFailurePhase()
                    + " fixtureField=" + safeLogValue(diagnostic.getFixtureField())
                    + " wiringEvidence=" + safeLogValue(diagnostic.getMockWiringEvidence().stream()
                            .map(e -> e.category() + ":" + e.subject() + "->" + e.receiver() + "@L" + e.line()
                                    + ":test=" + e.testHash() + ":production=" + e.productionHash()).toList().toString())
                    + " originLocations=" + diagnostic.getOriginLocations().stream()
                            .map(origin -> origin.testId() + ":" + origin.sourceLine()).toList() + " expectedValue="
                    + safeLogValue(diagnostic.getExpectedValue()) + " actualValue="
                    + safeLogValue(diagnostic.getActualValue()) + " expectedException="
                    + safeLogValue(diagnostic.getExpectedExceptionType()) + " actualException="
                    + safeLogValue(diagnostic.getActualExceptionType()) + " rootCause="
                    + safeLogValue(diagnostic.getRootCauseType()) + " rootCauseMessage="
                    + safeLogValue(diagnostic.getRootCauseMessage()) + " sharedContext=" + diagnostic.isSharedContextFailure()
                    + " secondary=" + diagnostic.isContextFailureSecondary() + " contextKey=" + diagnostic.getContextKey()
                    + " primaryTest=" + safeLogValue(diagnostic.getPrimaryFailureTestId())
                    + " evidenceArchive=" + safeLogValue(diagnostic.getEvidenceArchivePath()) + " message="
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
     * 候选闭环占 6%-84%，余下区间留给最终校验。
     */
    static double coverageProgressFraction(CoverageConfig config,
                                           CoveragePhase phase,
                                           int candidate,
                                           double fallback) {
        if (phase == CoveragePhase.PREPARING) return 0.03d;
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
        request.setMethodSignature(sourceAnalysisService.methodSignature(methodModel));
        request.setReturnType(methodModel.getReturnType());
        request.setAccessModifier(methodModel.getAccessModifier());
        request.setStaticMethod(methodModel.isStaticMethod());
        request.setMethodSourceCode(methodModel.getMethodBodyText() == null || methodModel.getMethodBodyText().isBlank()
                ? sourceText : methodModel.getMethodBodyText());
        request.setClassFields(sourceAnalysisService.classFields(methodModel));
        List<String> chainedCallReturnTypes = sourceAnalysisService.chainedCallReturnTypes(project, methodModel);
        List<String> requestImports = sourceAnalysisService.augmentedImportsFromChainTypes(methodModel, chainedCallReturnTypes);
        List<CallContextModel> requestCallContexts = sourceAnalysisService.enrichChainCallContexts(project, methodModel);
        request.setImports(String.join("\n", requestImports));
        request.setKnownTypes(sourceAnalysisService.knownTypes(methodModel, chainedCallReturnTypes, requestImports));
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
        // 项目 classpath 无 Mockito 时不得硬指派 Mockito：置空框架并让策略侧强制 FORBIDDEN。
        request.setMockFramework(mockitoAvailabilityOverride != null && !mockitoAvailabilityOverride
                ? "" : "Mockito");
        return request;
    }

    /**
     * 使用 IntelliJ 引用索引补充项目内调用点。
     * 如果索引没有结果，则保留 JavaParser 在当前文件中提取到的 usages。
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

}
