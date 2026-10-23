package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 承载单元测试生成流程的完整结果。
 * 服务层把方法模型、场景、Mock 策略、生成文件和告警统一放入该对象，供 UI 一次性展示。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class GenerationResult {
    private GenerationScope generationScope = GenerationScope.METHOD;
    private List<String> classMethodSignatures = new ArrayList<>();
    private String sharedFixtureSummary;
    /** 源码分析得到的方法模型，是后续所有生成结果的基础上下文。 */
    private MethodModel methodModel;
    /** 规划出的测试场景列表，决定最终生成多少个测试方法。 */
    private List<TestScenario> scenarios = new ArrayList<>();
    /** 依赖调用对应的 Mock 策略，用于生成和展示 Mockito 相关代码。 */
    private MockStrategy mockStrategy;
    /** 大模型生成的候选 Mock 策略，不覆盖静态 Mock 策略。 */
    private MockStrategy llmMockStrategy;
    /** 测试代码生成时是否使用大模型候选 Mock 策略。 */
    private boolean useLlmMockStrategy;
    /** 生成完成的测试文件模型，写入磁盘前先在 UI 中展示。 */
    private GeneratedTestFile generatedTestFile;
    /** 生成过程中的可恢复告警，服务层用它把异常信息反馈给用户。 */
    private List<String> warnings = new ArrayList<>();
    /** 测试代码是否来自大模型。 */
    private boolean generatedByLlm;
    /** 测试代码是否来自规则兜底。 */
    private boolean ruleBasedFallback;
    /** 大模型调用状态或失败原因。 */
    private String llmMessage;
    /** 当前方法在 IntelliJ 项目索引中的真实符号快照。 */
    private ProjectSymbolSnapshot symbolSnapshot;
    /** 当前候选测试代码的项目编译验证结果。 */
    private ValidationResult validationResult = ValidationResult.status(ValidationStatus.NOT_RUN);
    private CoverageReport coverageReport = CoverageReport.status(CoverageStatus.NOT_RUN);
    private FinalTestFileReport finalTestFileReport = new FinalTestFileReport();
    public FinalTestFileReport getFinalTestFileReport() { return finalTestFileReport; }
    public void setFinalTestFileReport(FinalTestFileReport value) {
        finalTestFileReport = value == null ? new FinalTestFileReport() : value;
    }
    private RunnableTestSelectionReport runnableTestSelectionReport = new RunnableTestSelectionReport();
    public RunnableTestSelectionReport getRunnableTestSelectionReport() { return runnableTestSelectionReport; }
    public void setRunnableTestSelectionReport(RunnableTestSelectionReport value) {
        runnableTestSelectionReport = value == null ? new RunnableTestSelectionReport() : value;
    }
    /**
     * 本结果上已使用的“补充证据续修”次数。
     * 放在 GenerationResult 而非 CoverageReport：覆盖率闭环每次运行都会替换 report，
     * 计数放 report 上将随每次续修清零、上限永远无法触发。
     */
    private int evidenceResumeCount;
    /** 独立于覆盖率的完整 JUnit 执行与分类修复报告。 */
    private ClassJUnitRepairReport runtimeValidationReport =
            ClassJUnitRepairReport.status(ClassJUnitStatus.NOT_RUN);

    public ClassJUnitRepairReport getRuntimeValidationReport() { return runtimeValidationReport; }
    public void setRuntimeValidationReport(ClassJUnitRepairReport value) {
        runtimeValidationReport = value == null
                ? ClassJUnitRepairReport.status(ClassJUnitStatus.NOT_RUN) : value;
    }

    public GenerationScope getGenerationScope() { return generationScope; }
    public void setGenerationScope(GenerationScope generationScope) {
        this.generationScope = generationScope == null ? GenerationScope.METHOD : generationScope;
    }
    public List<String> getClassMethodSignatures() { return classMethodSignatures; }
    public void setClassMethodSignatures(List<String> classMethodSignatures) {
        this.classMethodSignatures = classMethodSignatures == null ? new ArrayList<>() : classMethodSignatures;
    }
    public String getSharedFixtureSummary() { return sharedFixtureSummary; }
    public void setSharedFixtureSummary(String sharedFixtureSummary) { this.sharedFixtureSummary = sharedFixtureSummary; }

    /**
     * 读取源码分析得到的方法模型，是后续所有生成结果的基础上下文。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 方法模型
     */
    public MethodModel getMethodModel() {
        return methodModel;
    }

    /**
     * 更新源码分析得到的方法模型，是后续所有生成结果的基础上下文。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param methodModel 源码分析得到的被测方法模型
     */
    public void setMethodModel(MethodModel methodModel) {
        this.methodModel = methodModel;
    }

    /**
     * 读取规划出的测试场景列表，决定最终生成多少个测试方法。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 测试场景列表
     */
    public List<TestScenario> getScenarios() {
        return scenarios;
    }

    /**
     * 更新规划出的测试场景列表，决定最终生成多少个测试方法。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param scenarios 已规划的测试场景列表
     */
    public void setScenarios(List<TestScenario> scenarios) {
        this.scenarios = scenarios;
    }

    /**
     * 读取依赖调用对应的 Mock 策略，用于生成和展示 Mockito 相关代码。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return Mock 策略
     */
    public MockStrategy getMockStrategy() {
        return mockStrategy;
    }

    /**
     * 更新依赖调用对应的 Mock 策略，用于生成和展示 Mockito 相关代码。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param mockStrategy 依赖调用对应的 Mock 策略
     */
    public void setMockStrategy(MockStrategy mockStrategy) {
        this.mockStrategy = mockStrategy;
    }

    public MockStrategy getLlmMockStrategy() {
        return llmMockStrategy;
    }

    public void setLlmMockStrategy(MockStrategy llmMockStrategy) {
        this.llmMockStrategy = llmMockStrategy;
    }

    public boolean isUseLlmMockStrategy() {
        return useLlmMockStrategy;
    }

    public void setUseLlmMockStrategy(boolean useLlmMockStrategy) {
        this.useLlmMockStrategy = useLlmMockStrategy;
    }

    /**
     * 读取生成完成的测试文件模型，写入磁盘前先在 UI 中展示。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 生成文件模型
     */
    public GeneratedTestFile getGeneratedTestFile() {
        return generatedTestFile;
    }

    /**
     * 更新生成完成的测试文件模型，写入磁盘前先在 UI 中展示。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param generatedTestFile 准备写入磁盘的测试文件模型
     */
    public void setGeneratedTestFile(GeneratedTestFile generatedTestFile) {
        this.generatedTestFile = generatedTestFile;
    }

    /**
     * 读取生成过程中的可恢复告警，服务层用它把异常信息反馈给用户。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 告警信息列表
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * 更新生成过程中的可恢复告警，服务层用它把异常信息反馈给用户。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param warnings 方法执行所需的输入参数
     */
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public boolean isGeneratedByLlm() {
        return generatedByLlm;
    }

    public void setGeneratedByLlm(boolean generatedByLlm) {
        this.generatedByLlm = generatedByLlm;
    }

    public boolean isRuleBasedFallback() {
        return ruleBasedFallback;
    }

    public void setRuleBasedFallback(boolean ruleBasedFallback) {
        this.ruleBasedFallback = ruleBasedFallback;
    }

    public String getLlmMessage() {
        return llmMessage;
    }

    public void setLlmMessage(String llmMessage) {
        this.llmMessage = llmMessage;
    }

    public ProjectSymbolSnapshot getSymbolSnapshot() {
        return symbolSnapshot;
    }

    public void setSymbolSnapshot(ProjectSymbolSnapshot symbolSnapshot) {
        this.symbolSnapshot = symbolSnapshot;
    }

    public ValidationResult getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(ValidationResult validationResult) {
        this.validationResult = validationResult == null
                ? ValidationResult.status(ValidationStatus.NOT_RUN)
                : validationResult;
    }

    public CoverageReport getCoverageReport() {
        return coverageReport;
    }

    public void setCoverageReport(CoverageReport coverageReport) {
        this.coverageReport = coverageReport == null
                ? CoverageReport.status(CoverageStatus.NOT_RUN)
                : coverageReport;
    }

    public int getEvidenceResumeCount() { return evidenceResumeCount; }

    public void setEvidenceResumeCount(int evidenceResumeCount) {
        this.evidenceResumeCount = Math.max(0, evidenceResumeCount);
    }
}
