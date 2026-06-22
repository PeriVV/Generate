package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 发送给大模型的单元测试生成上下文。
 */
public class LlmGenerationRequest {
    private String packageName;
    private String className;
    private String methodName;
    private String methodSignature;
    private String returnType;
    private String methodSourceCode;
    private String classFields;
    private String imports;
    private List<ParameterModel> parameters = new ArrayList<>();
    private List<ConstraintModel> constraints = new ArrayList<>();
    private List<BranchModel> branches = new ArrayList<>();
    private List<TestScenario> scenarios = new ArrayList<>();
    private List<DependencyModel> dependencies = new ArrayList<>();
    private List<MethodUsageModel> usages = new ArrayList<>();
    private String existingTestCodeSummary;
    private String userInstruction;
    private String testFramework = "JUnit5";
    private String mockFramework = "Mockito";

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
}
