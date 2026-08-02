package com.casc.unittestagent.service;

import com.casc.unittestagent.generator.GeneratedTestCodePostProcessor;
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
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.util.PsiUtils;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.casc.unittestagent.validation.TestCodeValidator;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 编译失败后的自动修复次数上限。 */
    private static final int MAX_REPAIR_ATTEMPTS = 2;
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
    /** PSI 真实符号快照构建器。 */
    private final ProjectSymbolSnapshotBuilder symbolSnapshotBuilder;
    /** 项目编译验证器。 */
    private final TestCodeValidator testCodeValidator;
    /** 大模型客户端工厂。 */
    private final LlmClientFactory llmClientFactory;

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
            generatedTestFile = validateAndRepair(project, psiFile, result, generatedTestFile, llmConfig);
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
        boolean allowRepair = llmConfig != null && llmConfig.isEnabled() && result.isGeneratedByLlm();
        LlmClient repairClient = allowRepair ? llmClientFactory.create(llmConfig) : null;

        while (true) {
            ValidationResult validation = validateCode(project, psiFile, candidate, candidate.getContent());
            validation.setRepairAttempts(repairAttempts);
            result.setValidationResult(validation);
            if (validation.getStatus() == ValidationStatus.PASSED) {
                result.setLlmMessage(repairAttempts == 0
                        ? result.getLlmMessage()
                        : "大模型生成完成，并根据编译诊断自动修复 " + repairAttempts + " 轮。");
                return candidate;
            }
            if (!allowRepair
                    || validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                    || !validation.hasRepairableErrors()
                    || repairAttempts >= MAX_REPAIR_ATTEMPTS) {
                addValidationWarning(result, validation);
                return candidate;
            }

            String failureFingerprint = validation.getCodeHash() + "|" + diagnosticFingerprint(validation.getDiagnostics());
            if (!seenFailures.add(failureFingerprint)) {
                result.getWarnings().add("编译修复未取得进展，已提前停止重复请求。");
                addValidationWarning(result, validation);
                return candidate;
            }

            try {
                repairAttempts++;
                MethodModel methodModel = result.getMethodModel();
                ProjectSymbolSnapshot snapshot = result.getSymbolSnapshot();
                LlmGenerationRequest repairRequest = ReadAction.compute(() ->
                        buildLlmRequest(project, methodModel, executableScenarios(result.getScenarios()),
                                methodModel.getMethodBodyText(), snapshot));
                repairRequest.setExistingTestCodeSummary(candidate.getContent());
                repairRequest.setCompilerDiagnostics(validation.getDiagnostics());
                repairRequest.setRepairAttempt(repairAttempts);
                repairRequest.setUserInstruction("只修复编译错误，保持当前用户选择的 Mock 策略。\n"
                        + selectedMockStrategySummary(selectedMockStrategy(result)));
                LOG.info("UnitMind validation: repair attempt=" + repairAttempts
                        + ", diagnosticCount=" + validation.getDiagnostics().size());
                LlmGenerationResponse repaired = repairClient.repairUnitTest(repairRequest);
                GeneratedTestCodePostProcessor.ProcessedCode processed =
                        generatedCodePostProcessor.process(repaired.getTestCode(), methodModel);
                candidate = copyWithContent(candidate, processed.getCode());
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
            return "当前选择的 Mock 策略为空。";
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

        MethodModel methodModel;
        try {
            methodModel = javaSourceAnalyzer.analyze(sourceText, preferredMethodName);
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
     * 构造发送给大模型的结构化上下文。
     */
    private LlmGenerationRequest buildLlmRequest(Project project,
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
        request.setMethodSourceCode(methodModel.getMethodBodyText() == null || methodModel.getMethodBodyText().isBlank()
                ? sourceText : methodModel.getMethodBodyText());
        request.setImports(String.join("\n", methodModel.getImports()));
        request.setClassFields(classFields(methodModel));
        List<String> chainedCallReturnTypes = chainedCallReturnTypes(project, methodModel);
        methodModel.setCallContexts(enrichChainCallContexts(project, methodModel));
        augmentImportsFromChainTypes(methodModel, chainedCallReturnTypes);
        request.setKnownTypes(knownTypes(methodModel, chainedCallReturnTypes));
        request.setChainedCallReturnTypes(chainedCallReturnTypes);
        request.setParameters(methodModel.getParameters());
        request.setConstraints(methodModel.getConstraints());
        request.setBranches(methodModel.getBranches());
        request.setDependencies(methodModel.getDependencies());
        request.setUsages(methodModel.getUsages());
        request.setCallContexts(methodModel.getCallContexts());
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
    private List<String> knownTypes(MethodModel methodModel, List<String> chainedCallReturnTypes) {
        Set<String> types = new LinkedHashSet<>();
        addKnownType(types, methodModel.getClassName());
        addKnownType(types, methodModel.getReturnType());
        for (String importName : methodModel.getImports()) {
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

        for (CallContextModel callContext : methodModel.getCallContexts()) {
            CallContextModel copy = copyCallContext(callContext);
            if (copy != null) {
                contexts.add(copy);
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        for (CallContextModel callContext : methodModel.getCallContexts()) {
            if (!isChainContext(callContext)) {
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
                contexts.add(synthetic);
                currentType = rawType(synthetic.getReturnType());
                currentExpression = currentExpression + "." + segment.asCode();
            }
        }
        return contexts;
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

    private void augmentImportsFromChainTypes(MethodModel methodModel, List<String> chainedCallReturnTypes) {
        if (methodModel == null || chainedCallReturnTypes == null || chainedCallReturnTypes.isEmpty()) {
            return;
        }
        Set<String> imports = new LinkedHashSet<>(methodModel.getImports());
        for (String chainedCallReturnType : chainedCallReturnTypes) {
            String type = chainedTypeName(chainedCallReturnType);
            if (type.contains(".") && !type.startsWith("java.lang.")) {
                imports.add(type);
            }
        }
        methodModel.setImports(new ArrayList<>(imports));
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
        model.setImports(extractPsiImports(psiFile));
        model.setThrownExceptions(extractPsiThrownExceptions(method));
        model.setMethodBodyText(method.getBody() == null ? "" : method.getBody().getText());
        model.setDeclaredMethodNames(extractPsiMethodNames(containingClass));
        model.setParameters(extractPsiParameters(method));
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
