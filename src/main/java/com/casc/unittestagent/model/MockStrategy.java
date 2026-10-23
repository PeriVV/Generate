package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述 Mockito 相关的生成策略。
 * 该对象保存 mock 字段、桩规则和验证规则，帮助预览页解释生成代码背后的依赖处理方式。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class MockStrategy {
    /** 需要声明为 @Mock 的字段名集合。 */
    private List<String> mockFields = new ArrayList<>();
    /** 需要预置返回值的 Mockito when 规则集合。 */
    private List<String> whenRules = new ArrayList<>();
    /** 正常路径下期望发生的依赖调用验证规则。 */
    private List<String> verifyRules = new ArrayList<>();
    /** 异常路径下不应发生的依赖调用验证规则。 */
    private List<String> neverRules = new ArrayList<>();
    /** Mock 策略页统一表格使用的依赖处理建议。 */
    private List<MockStrategyItem> strategyItems = new ArrayList<>();

    /**
     * 读取需要声明为 @Mock 的字段名集合。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 需要 Mock 的字段名列表
     */
    public List<String> getMockFields() {
        return mockFields;
    }

    /**
     * 更新需要声明为 @Mock 的字段名集合。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param mockFields 方法执行所需的输入参数
     */
    public void setMockFields(List<String> mockFields) {
        this.mockFields = mockFields;
    }

    /**
     * 读取需要预置返回值的 Mockito when 规则集合。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return Mockito when 规则列表
     */
    public List<String> getWhenRules() {
        return whenRules;
    }

    /**
     * 更新需要预置返回值的 Mockito when 规则集合。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param whenRules 方法执行所需的输入参数
     */
    public void setWhenRules(List<String> whenRules) {
        this.whenRules = whenRules;
    }

    /**
     * 读取正常路径下期望发生的依赖调用验证规则。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 正常路径 verify 规则列表
     */
    public List<String> getVerifyRules() {
        return verifyRules;
    }

    /**
     * 更新正常路径下期望发生的依赖调用验证规则。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param verifyRules 方法执行所需的输入参数
     */
    public void setVerifyRules(List<String> verifyRules) {
        this.verifyRules = verifyRules;
    }

    /**
     * 读取异常路径下不应发生的依赖调用验证规则。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 异常路径 never 规则列表
     */
    public List<String> getNeverRules() {
        return neverRules;
    }

    /**
     * 更新异常路径下不应发生的依赖调用验证规则。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param neverRules 方法执行所需的输入参数
     */
    public void setNeverRules(List<String> neverRules) {
        this.neverRules = neverRules;
    }

    /**
     * 读取统一 Mock 策略表格行。
     * 该列表用于展示字段依赖、参数 DTO、静态方法、本类调用和局部对象调用的处理建议。
     *
     * @return Mock 策略表格行
     */
    public List<MockStrategyItem> getStrategyItems() {
        return strategyItems;
    }

    /**
     * 更新统一 Mock 策略表格行。
     *
     * @param strategyItems Mock 策略表格行
     */
    public void setStrategyItems(List<MockStrategyItem> strategyItems) {
        this.strategyItems = strategyItems;
    }

    /** Detached editable inputs for a background execution task. */
    public MockStrategy copy() {
        var copy = new MockStrategy();
        copy.mockFields = mockFields == null ? new ArrayList<>() : new ArrayList<>(mockFields);
        copy.whenRules = whenRules == null ? new ArrayList<>() : new ArrayList<>(whenRules);
        copy.verifyRules = verifyRules == null ? new ArrayList<>() : new ArrayList<>(verifyRules);
        copy.neverRules = neverRules == null ? new ArrayList<>() : new ArrayList<>(neverRules);
        copy.strategyItems = strategyItems == null ? new ArrayList<>() : new ArrayList<>(strategyItems.stream().map(MockStrategyItem::copy).toList());
        return copy;
    }
}
