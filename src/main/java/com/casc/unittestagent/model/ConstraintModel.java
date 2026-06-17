package com.casc.unittestagent.model;

/**
 * 描述从源码条件表达式中提取出的参数约束。
 * 约束会在测试数据生成和预览页中使用，用来解释输入值为什么能够覆盖指定路径。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class ConstraintModel {
    /** 约束关联的参数名，用于把条件表达式映射回具体测试输入。 */
    private String parameterName;
    /** 源码中的原始约束表达式，便于用户在预览页追溯分析依据。 */
    private String expression;
    /** 用于绕过异常或危险分支的安全表达式，测试数据生成会优先参考它。 */
    private String safeExpression;
    /** 约束分类，例如空值检查、范围约束或参数关系，帮助后续策略选择。 */
    private String kind;
    /** 约束来源位置，例如普通 if 或 if-throw，用于解释置信度和场景类型。 */
    private String source;
    /** 启发式提取结果的置信度，供后续展示或过滤策略使用。 */
    private double confidence;

    /**
     * 读取约束关联的参数名，用于把条件表达式映射回具体测试输入。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 约束关联的参数名
     */
    public String getParameterName() {
        return parameterName;
    }

    /**
     * 更新约束关联的参数名，用于把条件表达式映射回具体测试输入。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param parameterName 方法执行所需的输入参数
     */
    public void setParameterName(String parameterName) {
        this.parameterName = parameterName;
    }

    /**
     * 读取源码中的原始约束表达式，便于用户在预览页追溯分析依据。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 原始约束表达式
     */
    public String getExpression() {
        return expression;
    }

    /**
     * 更新源码中的原始约束表达式，便于用户在预览页追溯分析依据。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param expression 需要分析或取反的表达式节点
     */
    public void setExpression(String expression) {
        this.expression = expression;
    }

    /**
     * 读取用于绕过异常或危险分支的安全表达式，测试数据生成会优先参考它。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 安全表达式
     */
    public String getSafeExpression() {
        return safeExpression;
    }

    /**
     * 更新用于绕过异常或危险分支的安全表达式，测试数据生成会优先参考它。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param safeExpression 方法执行所需的输入参数
     */
    public void setSafeExpression(String safeExpression) {
        this.safeExpression = safeExpression;
    }

    /**
     * 读取约束分类，例如空值检查、范围约束或参数关系，帮助后续策略选择。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 约束分类
     */
    public String getKind() {
        return kind;
    }

    /**
     * 更新约束分类，例如空值检查、范围约束或参数关系，帮助后续策略选择。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param kind 方法执行所需的输入参数
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * 读取约束来源位置，例如普通 if 或 if-throw，用于解释置信度和场景类型。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 约束来源
     */
    public String getSource() {
        return source;
    }

    /**
     * 更新约束来源位置，例如普通 if 或 if-throw，用于解释置信度和场景类型。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param source 方法执行所需的输入参数
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 读取启发式提取结果的置信度，供后续展示或过滤策略使用。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 约束置信度
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * 更新启发式提取结果的置信度，供后续展示或过滤策略使用。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param confidence 方法执行所需的输入参数
     */
    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
