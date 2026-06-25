package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述被测类中的外部依赖字段及其调用情况。
 * 依赖信息来自字段和方法调用分析，后续用于生成 @Mock 字段、verify 规则和必要的 when 规则。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class DependencyModel {
    /** 依赖字段名，生成 @Mock 字段和 verify 调用时直接复用。 */
    private String fieldName;
    /** 依赖字段类型，生成测试类字段声明时需要保留。 */
    private String fieldType;
    /** 依赖业务类型，例如数据库、远程客户端或缓存，用于预览和策略解释。 */
    private String dependencyType;
    /** 被测方法实际调用的依赖方法集合，用于生成 verify 和可选 when 规则。 */
    private List<String> calledMethods = new ArrayList<>();
    /** 被测方法实际调用的完整依赖表达式，例如 query(request)。 */
    private List<String> callExpressions = new ArrayList<>();
    /** 依赖是否来自方法参数；接口型参数需要生成 @Mock 字段并直接参与方法调用。 */
    private boolean methodParameter;

    /**
     * 读取依赖字段名，生成 @Mock 字段和 verify 调用时直接复用。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 依赖字段名
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * 更新依赖字段名，生成 @Mock 字段和 verify 调用时直接复用。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param fieldName 依赖字段名
     */
    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    /**
     * 读取依赖字段类型，生成测试类字段声明时需要保留。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 依赖字段类型
     */
    public String getFieldType() {
        return fieldType;
    }

    /**
     * 更新依赖字段类型，生成测试类字段声明时需要保留。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param fieldType 依赖字段类型
     */
    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    /**
     * 读取依赖业务类型，例如数据库、远程客户端或缓存，用于预览和策略解释。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 依赖业务类型
     */
    public String getDependencyType() {
        return dependencyType;
    }

    /**
     * 更新依赖业务类型，例如数据库、远程客户端或缓存，用于预览和策略解释。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param dependencyType 依赖业务类型
     */
    public void setDependencyType(String dependencyType) {
        this.dependencyType = dependencyType;
    }

    /**
     * 读取被测方法实际调用的依赖方法集合，用于生成 verify 和可选 when 规则。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 依赖调用方法列表
     */
    public List<String> getCalledMethods() {
        return calledMethods;
    }

    /**
     * 更新被测方法实际调用的依赖方法集合，用于生成 verify 和可选 when 规则。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param calledMethods 依赖方法调用集合
     */
    public void setCalledMethods(List<String> calledMethods) {
        this.calledMethods = calledMethods;
    }

    /**
     * 读取被测方法实际调用的完整依赖表达式。
     * 完整表达式保留实参信息，生成 verify 和 when 规则时可以避免退化为 any()。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 依赖调用表达式列表
     */
    public List<String> getCallExpressions() {
        return callExpressions;
    }

    /**
     * 更新被测方法实际调用的完整依赖表达式。
     * 解析器在发现依赖调用时写入该列表，后续 Mock 策略和测试代码生成直接复用。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param callExpressions 依赖调用表达式列表
     */
    public void setCallExpressions(List<String> callExpressions) {
        this.callExpressions = callExpressions;
    }

    /**
     * 判断依赖是否来自方法参数。
     * 方法参数依赖不会出现在被测类字段中，但生成测试时仍需要声明为 @Mock。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 如果依赖来自方法参数则返回 true
     */
    public boolean isMethodParameter() {
        return methodParameter;
    }

    /**
     * 更新依赖是否来自方法参数。
     * 该标记用于区分字段依赖和接口型参数依赖，避免生成阶段遗漏 Mock 参数。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param methodParameter 依赖是否来自方法参数
     */
    public void setMethodParameter(boolean methodParameter) {
        this.methodParameter = methodParameter;
    }
}
