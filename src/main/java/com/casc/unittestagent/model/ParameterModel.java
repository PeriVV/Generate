package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述被测方法的单个参数及其测试数据集合。
 * 参数模型保存类型、可空性、普通值、边界值和非法值，是场景输入生成的基础。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class ParameterModel {
    /** 场景名或参数名，生成测试方法名和参数映射时会使用。 */
    private String name;
    /** 场景类型或 Java 类型，用于选择测试数据生成和代码拼装策略。 */
    private String type;
    /** 参数是否允许使用 null 作为非法输入，非基本类型默认可空。 */
    private boolean nullable;
    /** 普通路径输入候选值，用于生成默认成功用例。 */
    private List<String> normalValues = new ArrayList<>();
    /** 边界输入候选值，用于覆盖最小值、最大值等边界场景。 */
    private List<String> boundaryValues = new ArrayList<>();
    /** 非法输入候选值，用于触发异常或保护分支。 */
    private List<String> invalidValues = new ArrayList<>();

    /**
     * 读取场景名或参数名，生成测试方法名和参数映射时会使用。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 名称文本
     */
    public String getName() {
        return name;
    }

    /**
     * 更新场景名或参数名，生成测试方法名和参数映射时会使用。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param name 名称文本
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 读取场景类型或 Java 类型，用于选择测试数据生成和代码拼装策略。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 类型文本
     */
    public String getType() {
        return type;
    }

    /**
     * 更新场景类型或 Java 类型，用于选择测试数据生成和代码拼装策略。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param type 类型文本
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * 判断参数是否允许使用 null 作为非法输入，非基本类型默认可空。
     * 该判断封装在模型内部，避免调用方重复理解配置或参数规则。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 如果参数允许 null 输入则返回 true
     */
    public boolean isNullable() {
        return nullable;
    }

    /**
     * 更新参数是否允许使用 null 作为非法输入，非基本类型默认可空。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param nullable 参数是否可空
     */
    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    /**
     * 读取普通路径输入候选值，用于生成默认成功用例。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 普通输入候选值列表
     */
    public List<String> getNormalValues() {
        return normalValues;
    }

    /**
     * 更新普通路径输入候选值，用于生成默认成功用例。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param normalValues 普通输入候选值列表
     */
    public void setNormalValues(List<String> normalValues) {
        this.normalValues = normalValues;
    }

    /**
     * 读取边界输入候选值，用于覆盖最小值、最大值等边界场景。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 边界输入候选值列表
     */
    public List<String> getBoundaryValues() {
        return boundaryValues;
    }

    /**
     * 更新边界输入候选值，用于覆盖最小值、最大值等边界场景。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param boundaryValues 边界输入候选值列表
     */
    public void setBoundaryValues(List<String> boundaryValues) {
        this.boundaryValues = boundaryValues;
    }

    /**
     * 读取非法输入候选值，用于触发异常或保护分支。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 非法输入候选值列表
     */
    public List<String> getInvalidValues() {
        return invalidValues;
    }

    /**
     * 更新非法输入候选值，用于触发异常或保护分支。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param invalidValues 非法输入候选值列表
     */
    public void setInvalidValues(List<String> invalidValues) {
        this.invalidValues = invalidValues;
    }
}
