package com.casc.unittestagent.model;

/**
 * 描述某类测试数据的取值范围配置。
 * 数值类型使用最小值、最大值和小数位控制生成范围，字符串类型使用正则表达式约束生成规则。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestDataRangeConfig {
    /** 配置对应的 Java 类型名称，支持基本类型和包装类型。 */
    private String typeName;
    /** 数值类型允许生成的最小值，边界测试会直接使用。 */
    private String minValue;
    /** 数值类型允许生成的最大值，边界测试会直接使用。 */
    private String maxValue;
    /** 浮点类型保留的小数位范围，当前校验要求 0 到 10。 */
    private Integer decimalScale;
    /** 字符串类型的生成规则，当前用于描述允许的字符和长度范围。 */
    private String stringRegex;

    /**
     * 创建空的测试数据范围配置。
     * 空构造用于表格模型、复制流程或序列化场景先创建对象，再逐项写入配置值。
     *
     * 异常情况：无主动抛出的受检异常。
     */
    public TestDataRangeConfig() {
    }

    /**
     * 创建完整的测试数据范围配置。
     * 默认配置和用户提交配置都会通过该构造方法保存类型、数值边界和字符串规则。
     *
     * 异常情况：无主动抛出的受检异常。
     *
     * @param typeName 配置对应的 Java 类型名称
     * @param minValue 数值类型最小边界文本
     * @param maxValue 数值类型最大边界文本
     * @param decimalScale 浮点类型小数位配置
     * @param stringRegex 字符串类型生成规则
     */
    public TestDataRangeConfig(String typeName, String minValue, String maxValue, Integer decimalScale, String stringRegex) {
        this.typeName = typeName;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.decimalScale = decimalScale;
        this.stringRegex = stringRegex;
    }

    /**
     * 复制当前配置对象。
     * 复制用于隔离 UI 编辑态和运行态，避免共享对象被意外修改。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 独立的配置副本
     */
    public TestDataRangeConfig copy() {
        return new TestDataRangeConfig(typeName, minValue, maxValue, decimalScale, stringRegex);
    }

    /**
     * 读取配置对应的 Java 类型名称，支持基本类型和包装类型。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 配置类型名
     */
    public String getTypeName() {
        return typeName;
    }

    /**
     * 更新配置对应的 Java 类型名称，支持基本类型和包装类型。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param typeName 配置对应的 Java 类型名称
     */
    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    /**
     * 读取数值类型允许生成的最小值，边界测试会直接使用。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 最小值配置
     */
    public String getMinValue() {
        return minValue;
    }

    /**
     * 更新数值类型允许生成的最小值，边界测试会直接使用。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param minValue 数值类型最小边界文本
     */
    public void setMinValue(String minValue) {
        this.minValue = minValue;
    }

    /**
     * 读取数值类型允许生成的最大值，边界测试会直接使用。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 最大值配置
     */
    public String getMaxValue() {
        return maxValue;
    }

    /**
     * 更新数值类型允许生成的最大值，边界测试会直接使用。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param maxValue 数值类型最大边界文本
     */
    public void setMaxValue(String maxValue) {
        this.maxValue = maxValue;
    }

    /**
     * 读取浮点类型保留的小数位范围，当前校验要求 0 到 10。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 小数位配置
     */
    public Integer getDecimalScale() {
        return decimalScale;
    }

    /**
     * 更新浮点类型保留的小数位范围，当前校验要求 0 到 10。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param decimalScale 浮点类型小数位配置
     */
    public void setDecimalScale(Integer decimalScale) {
        this.decimalScale = decimalScale;
    }

    /**
     * 读取字符串类型的生成规则，当前用于描述允许的字符和长度范围。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 字符串生成规则
     */
    public String getStringRegex() {
        return stringRegex;
    }

    /**
     * 更新字符串类型的生成规则，当前用于描述允许的字符和长度范围。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param stringRegex 字符串类型生成规则
     */
    public void setStringRegex(String stringRegex) {
        this.stringRegex = stringRegex;
    }

    /**
     * 判断FloatingType。
     * 该判断封装在模型内部，避免调用方重复理解配置或参数规则。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 如果当前配置代表浮点类型则返回 true
     */
    public boolean isFloatingType() {
        return "float".equals(typeName) || "Float".equals(typeName)
                || "double".equals(typeName) || "Double".equals(typeName)
                || "float / Float".equals(typeName) || "double / Double".equals(typeName);
    }

    /**
     * 判断StringType。
     * 该判断封装在模型内部，避免调用方重复理解配置或参数规则。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 如果当前配置代表字符串类型则返回 true
     */
    public boolean isStringType() {
        return "String".equalsIgnoreCase(typeName);
    }
}
