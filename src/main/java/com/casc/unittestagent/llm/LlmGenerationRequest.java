package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.GenerationScope;

import java.util.ArrayList;
import java.util.List;

/**
 * 发送给大模型的单元测试生成上下文。
 */
public class LlmGenerationRequest {
    private GenerationScope generationScope = GenerationScope.METHOD;
    private String methodId;
    private List<String> classMethodSignatures = new ArrayList<>();
    private String sharedFixtureSummary;
    private String packageName;
    private String className;
    private String methodName;
    private String methodSignature;
    private String returnType;
    private String accessModifier;
    private boolean staticMethod;
    private String methodSourceCode;
    private String classFields;
    private String imports;
    private List<String> knownTypes = new ArrayList<>();
    private List<String> chainedCallReturnTypes = new ArrayList<>();
    private List<ParameterModel> parameters = new ArrayList<>();
    private List<ConstraintModel> constraints = new ArrayList<>();
    private List<BranchModel> branches = new ArrayList<>();
    private List<TestScenario> scenarios = new ArrayList<>();
    private List<DependencyModel> dependencies = new ArrayList<>();
    private List<MethodUsageModel> usages = new ArrayList<>();
    private List<CallContextModel> callContexts = new ArrayList<>();
    private ProjectSymbolSnapshot symbolSnapshot;
    private List<CompileDiagnostic> compilerDiagnostics = new ArrayList<>();
    private CoverageReport coverageReport;
    private List<RuntimeDiagnostic> runtimeDiagnostics = new ArrayList<>();
    private int repairAttempt;
    private String existingTestCodeSummary;
    private String userInstruction;
    private String testFramework = "JUnit5";
    private String mockFramework = "Mockito";
    private boolean mockitoStaticMockingSupported;
    private String mockitoStaticMockingSupport = "未检测";
    /** Mockito 使用契约：FORBIDDEN / SELECTED_ONLY / PRESERVE_EXISTING / AS_NEEDED。 */
    private String mockUsagePolicy = "AS_NEEDED";
    /** 本轮需要优先诊断的失败测试。 */
    private List<String> focusTestIds = new ArrayList<>();
    /** 当前完整回归中所有失败测试，不得误当成已通过测试。 */
    private List<String> failingTestIds = new ArrayList<>();
    /** 当前完整回归中真实通过的测试。 */
    private List<String> passedTestIds = new ArrayList<>();
    /** 上一轮模型修改为何未进入下一候选，用于防止确定性重复。 */
    private String previousAttemptSummary = "";

    public GenerationScope getGenerationScope() { return generationScope; }
    public void setGenerationScope(GenerationScope generationScope) {
        this.generationScope = generationScope == null ? GenerationScope.METHOD : generationScope;
    }
    public String getMethodId() { return methodId; }
    public void setMethodId(String methodId) { this.methodId = methodId; }
    public List<String> getClassMethodSignatures() { return classMethodSignatures; }
    public void setClassMethodSignatures(List<String> classMethodSignatures) {
        this.classMethodSignatures = classMethodSignatures == null ? new ArrayList<>() : classMethodSignatures;
    }
    public String getSharedFixtureSummary() { return sharedFixtureSummary; }
    public void setSharedFixtureSummary(String sharedFixtureSummary) { this.sharedFixtureSummary = sharedFixtureSummary; }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public void setMethodSignature(String methodSignature) {
        this.methodSignature = methodSignature;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public String getAccessModifier() {
        return accessModifier;
    }

    public boolean isStaticMethod() { return staticMethod; }
    public void setStaticMethod(boolean staticMethod) { this.staticMethod = staticMethod; }

    public void setAccessModifier(String accessModifier) {
        this.accessModifier = accessModifier;
    }

    public String getMethodSourceCode() {
        return methodSourceCode;
    }

    public void setMethodSourceCode(String methodSourceCode) {
        this.methodSourceCode = methodSourceCode;
    }

    public String getClassFields() {
        return classFields;
    }

    public void setClassFields(String classFields) {
        this.classFields = classFields;
    }

    public String getImports() {
        return imports;
    }

    public void setImports(String imports) {
        this.imports = imports;
    }

    public List<String> getKnownTypes() {
        return knownTypes;
    }

    public void setKnownTypes(List<String> knownTypes) {
        this.knownTypes = knownTypes;
    }

    public List<String> getChainedCallReturnTypes() {
        return chainedCallReturnTypes;
    }

    public void setChainedCallReturnTypes(List<String> chainedCallReturnTypes) {
        this.chainedCallReturnTypes = chainedCallReturnTypes;
    }

    public List<ParameterModel> getParameters() {
        return parameters;
    }

    public void setParameters(List<ParameterModel> parameters) {
        this.parameters = parameters;
    }

    public List<ConstraintModel> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<ConstraintModel> constraints) {
        this.constraints = constraints;
    }

    public List<BranchModel> getBranches() {
        return branches;
    }

    public void setBranches(List<BranchModel> branches) {
        this.branches = branches;
    }

    public List<TestScenario> getScenarios() {
        return scenarios;
    }

    public void setScenarios(List<TestScenario> scenarios) {
        this.scenarios = scenarios;
    }

    public List<DependencyModel> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<DependencyModel> dependencies) {
        this.dependencies = dependencies;
    }

    public List<MethodUsageModel> getUsages() {
        return usages;
    }

    public void setUsages(List<MethodUsageModel> usages) {
        this.usages = usages;
    }

    public List<CallContextModel> getCallContexts() {
        return callContexts;
    }

    public void setCallContexts(List<CallContextModel> callContexts) {
        this.callContexts = callContexts;
    }

    public ProjectSymbolSnapshot getSymbolSnapshot() {
        return symbolSnapshot;
    }

    public void setSymbolSnapshot(ProjectSymbolSnapshot symbolSnapshot) {
        this.symbolSnapshot = symbolSnapshot;
    }

    public List<CompileDiagnostic> getCompilerDiagnostics() {
        return compilerDiagnostics;
    }

    public void setCompilerDiagnostics(List<CompileDiagnostic> compilerDiagnostics) {
        this.compilerDiagnostics = compilerDiagnostics == null ? new ArrayList<>() : compilerDiagnostics;
    }

    public CoverageReport getCoverageReport() {
        return coverageReport;
    }

    public void setCoverageReport(CoverageReport coverageReport) {
        this.coverageReport = coverageReport;
    }

    public List<RuntimeDiagnostic> getRuntimeDiagnostics() {
        return runtimeDiagnostics;
    }

    public void setRuntimeDiagnostics(List<RuntimeDiagnostic> runtimeDiagnostics) {
        this.runtimeDiagnostics = runtimeDiagnostics == null ? new ArrayList<>() : runtimeDiagnostics;
    }

    public int getRepairAttempt() {
        return repairAttempt;
    }

    public void setRepairAttempt(int repairAttempt) {
        this.repairAttempt = repairAttempt;
    }

    public String getExistingTestCodeSummary() {
        return existingTestCodeSummary;
    }

    public void setExistingTestCodeSummary(String existingTestCodeSummary) {
        this.existingTestCodeSummary = existingTestCodeSummary;
    }

    public String getUserInstruction() {
        return userInstruction;
    }

    public void setUserInstruction(String userInstruction) {
        this.userInstruction = userInstruction;
    }

    public String getTestFramework() {
        return testFramework;
    }

    public void setTestFramework(String testFramework) {
        this.testFramework = testFramework;
    }

    public String getMockFramework() {
        return mockFramework;
    }

    public void setMockFramework(String mockFramework) {
        this.mockFramework = mockFramework;
    }

    public boolean isMockitoStaticMockingSupported() {
        return mockitoStaticMockingSupported;
    }

    public void setMockitoStaticMockingSupported(boolean mockitoStaticMockingSupported) {
        this.mockitoStaticMockingSupported = mockitoStaticMockingSupported;
    }

    public String getMockitoStaticMockingSupport() {
        return mockitoStaticMockingSupport;
    }

    public void setMockitoStaticMockingSupport(String mockitoStaticMockingSupport) {
        this.mockitoStaticMockingSupport = mockitoStaticMockingSupport == null ? "" : mockitoStaticMockingSupport;
    }

    public String getMockUsagePolicy() { return mockUsagePolicy; }
    public void setMockUsagePolicy(String value) {
        mockUsagePolicy = value == null || value.isBlank() ? "AS_NEEDED" : value;
    }
    public List<String> getFocusTestIds() { return focusTestIds; }
    public void setFocusTestIds(List<String> value) {
        focusTestIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getFailingTestIds() { return failingTestIds; }
    public void setFailingTestIds(List<String> value) {
        failingTestIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getPassedTestIds() { return passedTestIds; }
    public void setPassedTestIds(List<String> value) {
        passedTestIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public String getPreviousAttemptSummary() { return previousAttemptSummary; }
    public void setPreviousAttemptSummary(String value) {
        previousAttemptSummary = value == null ? "" : value;
    }
}
