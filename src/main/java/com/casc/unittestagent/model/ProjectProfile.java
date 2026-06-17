package com.casc.unittestagent.model;

/**
 * 描述项目级测试框架偏好。
 * 当前默认使用 JUnit5 和 Mockito，后续如果支持多框架可通过该模型扩展。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class ProjectProfile {
    /** 项目使用的测试框架名称，默认值为 JUnit5。 */
    private String testFramework = "JUnit5";
    /** 项目使用的 Mock 框架名称，默认值为 Mockito。 */
    private String mockFramework = "Mockito";

    /**
     * 读取项目使用的测试框架名称，默认值为 JUnit5。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 测试框架名称
     */
    public String getTestFramework() {
        return testFramework;
    }

    /**
     * 更新项目使用的测试框架名称，默认值为 JUnit5。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param testFramework 测试框架名称
     */
    public void setTestFramework(String testFramework) {
        this.testFramework = testFramework;
    }

    /**
     * 读取项目使用的 Mock 框架名称，默认值为 Mockito。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return Mock 框架名称
     */
    public String getMockFramework() {
        return mockFramework;
    }

    /**
     * 更新项目使用的 Mock 框架名称，默认值为 Mockito。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param mockFramework Mock 框架名称
     */
    public void setMockFramework(String mockFramework) {
        this.mockFramework = mockFramework;
    }
}
