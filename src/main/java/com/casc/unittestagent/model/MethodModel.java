package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述被测方法及其上下文的核心模型。
 * 解析、数据生成、场景规划、Mock 规划和源码生成都围绕该模型传递信息。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class MethodModel {
    /** 在类内稳定标识方法，参数使用擦除后类型以区分重载。 */
    private String methodId;
    /** 完整的可展示方法签名。 */
    private String signature;
    /** 被测方法是否为 static。 */
    private boolean staticMethod;
    /** 源码语言标识，当前用于标明分析结果来自 Java。 */
    private String language;
    /** 生成测试类需要复用的包名，保证测试文件与被测类处于同一包上下文。 */
    private String packageName;
    /** 被测类或生成测试类的简单类名，用于构造测试类名和实例名。 */
    private String className;
    /** 被测方法名称，代码生成阶段用它拼装实际调用语句。 */
    private String methodName;
    /** 被测方法返回类型，决定生成 void 调用还是结果断言。 */
    private String returnType;
    /** 被测方法访问修饰符，例如 public、protected、private 或 package-private。 */
    private String accessModifier = "package-private";
    /** 被测方法是否为 protected，可用于解释测试类必须保持同包生成的原因。 */
    private boolean protectedMethod;
    /** 被测方法声明抛出的异常类型列表，生成测试方法签名和异常断言时需要参考。 */
    private List<String> thrownExceptions = new ArrayList<>();
    /** 被测源码中的 import 列表，生成测试代码时优先复用框架接口来源。 */
    private List<String> imports = new ArrayList<>();
    /** 被测方法体文本，PSI 降级时用于保留当前方法上下文，避免整类解析失败后丢失关键信息。 */
    private String methodBodyText;
    /** 被测方法声明在源文件中的起止行，覆盖率只聚合此范围。 */
    private int sourceStartLine = -1;
    private int sourceEndLine = -1;
    /** 被测方法参数列表，是测试输入值生成和方法调用参数拼装的基础。 */
    private List<ParameterModel> parameters = new ArrayList<>();
    /** 从条件表达式提取出的参数约束，用于解释测试数据和分支覆盖关系。 */
    private List<ConstraintModel> constraints = new ArrayList<>();
    /** 异常保护分支列表，用于生成异常场景和安全绕过输入。 */
    private List<BranchModel> branches = new ArrayList<>();
    /** 被测方法涉及的外部依赖列表，用于生成 Mock 字段和 verify 规则。 */
    private List<DependencyModel> dependencies = new ArrayList<>();
    /** 被测方法的调用点列表，用于向大模型补充真实业务调用方式。 */
    private List<MethodUsageModel> usages = new ArrayList<>();
    /** 被测方法内部的一层调用链上下文，用于大模型补齐路径前置条件和参数对象桩。 */
    private List<CallContextModel> callContexts = new ArrayList<>();
    /** 当前类声明的方法名列表，用于区分本类内部调用和普通函数调用。 */
    private List<String> declaredMethodNames = new ArrayList<>();

    public String getMethodId() {
        return methodId;
    }

    public void setMethodId(String methodId) {
        this.methodId = methodId;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public boolean isStaticMethod() {
        return staticMethod;
    }

    public void setStaticMethod(boolean staticMethod) {
        this.staticMethod = staticMethod;
    }

    /**
     * 读取源码语言标识，当前用于标明分析结果来自 Java。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 源码语言标识
     */
    public String getLanguage() {
        return language;
    }

    /**
     * 更新源码语言标识，当前用于标明分析结果来自 Java。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param language 源码语言标识
     */
    public void setLanguage(String language) {
        this.language = language;
    }

    /**
     * 读取 Java 文件包名。
     * 非 Java 文件返回空字符串，调用方可以保持简单的包名处理逻辑。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 包名文本
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * 更新生成测试类需要复用的包名，保证测试文件与被测类处于同一包上下文。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param packageName 包名文本
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * 读取被测类或生成测试类的简单类名，用于构造测试类名和实例名。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 类名文本
     */
    public String getClassName() {
        return className;
    }

    /**
     * 更新被测类或生成测试类的简单类名，用于构造测试类名和实例名。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param className 类名文本
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * 读取被测方法名称，代码生成阶段用它拼装实际调用语句。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 方法名文本
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * 更新被测方法名称，代码生成阶段用它拼装实际调用语句。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param methodName 方法名文本
     */
    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    /**
     * 读取被测方法返回类型，决定生成 void 调用还是结果断言。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 返回类型文本
     */
    public String getReturnType() {
        return returnType;
    }

    /**
     * 更新被测方法返回类型，决定生成 void 调用还是结果断言。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param returnType 返回类型文本
     */
    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    /**
     * 读取被测方法访问修饰符。
     *
     * @return 访问修饰符文本
     */
    public String getAccessModifier() {
        return accessModifier;
    }

    /**
     * 更新被测方法访问修饰符。
     *
     * @param accessModifier 访问修饰符文本
     */
    public void setAccessModifier(String accessModifier) {
        this.accessModifier = accessModifier;
    }

    /**
     * 判断被测方法是否为 protected。
     * 生成测试类时保持同包可以直接调用 protected 方法，因此该标记用于说明访问策略。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 如果被测方法是 protected 则返回 true
     */
    public boolean isProtectedMethod() {
        return protectedMethod;
    }

    /**
     * 更新被测方法是否为 protected。
     * 解析器在分析方法声明时写入该值，后续生成器据此保持测试类与被测类同包。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param protectedMethod 被测方法是否为 protected
     */
    public void setProtectedMethod(boolean protectedMethod) {
        this.protectedMethod = protectedMethod;
    }

    /**
     * 读取被测方法声明抛出的异常类型列表。
     * 声明受检异常的方法需要在测试方法中统一透传或断言异常。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 被测方法 throws 类型列表
     */
    public List<String> getThrownExceptions() {
        return thrownExceptions;
    }

    /**
     * 更新被测方法声明抛出的异常类型列表。
     * 解析阶段保留这些信息，避免代码生成器通过硬编码推断异常类型。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param thrownExceptions 被测方法 throws 类型列表
     */
    public void setThrownExceptions(List<String> thrownExceptions) {
        this.thrownExceptions = thrownExceptions;
    }

    /**
     * 读取被测源码中的 import 列表。
     * 生成测试类时优先复用这些 import，尤其是 javax.servlet 与 jakarta.servlet 的选择。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 被测源码 import 列表
     */
    public List<String> getImports() {
        return imports;
    }

    /**
     * 更新被测源码中的 import 列表。
     * 解析器写入原始 import，使测试代码生成阶段无需重新读取源码文本。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param imports 被测源码 import 列表
     */
    public void setImports(List<String> imports) {
        this.imports = imports;
    }

    /**
     * 读取被测方法体文本。
     * 该字段主要服务于 JavaParser 失败后的 PSI 降级路径，使后续规则仍能基于当前方法上下文扩展。
     *
     * 异常情况：无主动抛出的受检异常。
     *
     * @return 被测方法体文本
     */
    public String getMethodBodyText() {
        return methodBodyText;
    }

    /**
     * 更新被测方法体文本。
     * 解析器和 PSI 降级逻辑写入该字段，避免整类解析失败时丢失当前方法的源码片段。
     *
     * 异常情况：无主动抛出的受检异常。
     *
     * @param methodBodyText 被测方法体文本
     */
    public void setMethodBodyText(String methodBodyText) {
        this.methodBodyText = methodBodyText;
    }

    public int getSourceStartLine() {
        return sourceStartLine;
    }

    public void setSourceStartLine(int sourceStartLine) {
        this.sourceStartLine = sourceStartLine;
    }

    public int getSourceEndLine() {
        return sourceEndLine;
    }

    public void setSourceEndLine(int sourceEndLine) {
        this.sourceEndLine = sourceEndLine;
    }

    /**
     * 读取被测方法参数列表，是测试输入值生成和方法调用参数拼装的基础。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 参数模型列表
     */
    public List<ParameterModel> getParameters() {
        return parameters;
    }

    /**
     * 更新被测方法参数列表，是测试输入值生成和方法调用参数拼装的基础。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param parameters 参数模型列表
     */
    public void setParameters(List<ParameterModel> parameters) {
        this.parameters = parameters;
    }

    /**
     * 读取从条件表达式提取出的参数约束，用于解释测试数据和分支覆盖关系。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 约束模型列表
     */
    public List<ConstraintModel> getConstraints() {
        return constraints;
    }

    /**
     * 更新从条件表达式提取出的参数约束，用于解释测试数据和分支覆盖关系。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param constraints 约束模型列表
     */
    public void setConstraints(List<ConstraintModel> constraints) {
        this.constraints = constraints;
    }

    /**
     * 读取异常保护分支列表，用于生成异常场景和安全绕过输入。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 分支模型列表
     */
    public List<BranchModel> getBranches() {
        return branches;
    }

    /**
     * 更新异常保护分支列表，用于生成异常场景和安全绕过输入。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param branches 分支模型列表
     */
    public void setBranches(List<BranchModel> branches) {
        this.branches = branches;
    }

    /**
     * 读取被测方法涉及的外部依赖列表，用于生成 Mock 字段和 verify 规则。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 依赖模型列表
     */
    public List<DependencyModel> getDependencies() {
        return dependencies;
    }

    /**
     * 更新被测方法涉及的外部依赖列表，用于生成 Mock 字段和 verify 规则。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param dependencies 依赖模型列表
     */
    public void setDependencies(List<DependencyModel> dependencies) {
        this.dependencies = dependencies;
    }

    /**
     * 读取被测方法的调用点列表。
     *
     * @return 调用点模型列表
     */
    public List<MethodUsageModel> getUsages() {
        return usages;
    }

    /**
     * 更新被测方法的调用点列表。
     *
     * @param usages 调用点模型列表
     */
    public void setUsages(List<MethodUsageModel> usages) {
        this.usages = usages;
    }

    public List<CallContextModel> getCallContexts() {
        return callContexts;
    }

    public void setCallContexts(List<CallContextModel> callContexts) {
        this.callContexts = callContexts;
    }

    /**
     * 读取当前类声明的方法名列表。
     * Mock 策略规划会用它识别 includedPlans、copy 等本类内部调用，避免误判普通库方法。
     *
     * @return 当前类声明的方法名列表
     */
    public List<String> getDeclaredMethodNames() {
        return declaredMethodNames;
    }

    /**
     * 更新当前类声明的方法名列表。
     *
     * @param declaredMethodNames 当前类声明的方法名列表
     */
    public void setDeclaredMethodNames(List<String> declaredMethodNames) {
        this.declaredMethodNames = declaredMethodNames;
    }
}
