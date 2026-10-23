package com.casc.unittestagent.model;

/**
 * 描述 Mock 策略页中的一行依赖处理建议。
 * 该模型不直接驱动 Mockito 代码生成，主要用于把字段依赖、参数 DTO、静态方法、本类调用和局部对象调用统一展示。
 */
public class MockStrategyItem {
    /** 是否建议生成对应 Mock 或验证代码。 */
    private boolean generate;
    /** 依赖类型，例如字段依赖、静态方法依赖或本类内部调用。 */
    private String dependencyType;
    /** 依赖对象名或类名，用于表格展示和后续人工判断。 */
    private String objectName;
    /** 被调用方法名或调用表达式。 */
    private String methodName;
    /** 源码中的原始调用线索。 */
    private String source;
    /** 建议使用的 Mockito 策略或处理方式。 */
    private String mockitoStrategy;
    /** 不能自动处理时给用户的简短说明。 */
    private String remark;

    /**
     * 创建空的 Mock 策略项。
     */
    public MockStrategyItem() {
    }

    /**
     * 创建完整的 Mock 策略项。
     *
     * @param generate 是否建议生成对应 Mock 或验证代码
     * @param dependencyType 依赖类型
     * @param objectName 依赖对象名或类名
     * @param methodName 被调用方法名或调用表达式
     * @param source 源码中的原始调用线索
     * @param mockitoStrategy 建议使用的 Mockito 策略或处理方式
     * @param remark 不能自动处理时给用户的简短说明
     */
    public MockStrategyItem(boolean generate,
                            String dependencyType,
                            String objectName,
                            String methodName,
                            String source,
                            String mockitoStrategy,
                            String remark) {
        this.generate = generate;
        this.dependencyType = dependencyType;
        this.objectName = objectName;
        this.methodName = methodName;
        this.source = source;
        this.mockitoStrategy = mockitoStrategy;
        this.remark = remark;
    }

    /**
     * 判断是否建议生成对应 Mock 或验证代码。
     *
     * @return 建议生成时返回 true
     */
    public boolean isGenerate() {
        return generate;
    }

    /**
     * 更新是否建议生成对应 Mock 或验证代码。
     *
     * @param generate 是否建议生成
     */
    public void setGenerate(boolean generate) {
        this.generate = generate;
    }

    /**
     * 读取依赖类型。
     *
     * @return 依赖类型
     */
    public String getDependencyType() {
        return dependencyType;
    }

    /**
     * 更新依赖类型。
     *
     * @param dependencyType 依赖类型
     */
    public void setDependencyType(String dependencyType) {
        this.dependencyType = dependencyType;
    }

    /**
     * 读取依赖对象名或类名。
     *
     * @return 依赖对象名或类名
     */
    public String getObjectName() {
        return objectName;
    }

    /**
     * 更新依赖对象名或类名。
     *
     * @param objectName 依赖对象名或类名
     */
    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    /**
     * 读取被调用方法名或调用表达式。
     *
     * @return 被调用方法名或调用表达式
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * 更新被调用方法名或调用表达式。
     *
     * @param methodName 被调用方法名或调用表达式
     */
    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    /**
     * 读取源码中的原始调用线索。
     *
     * @return 源码调用线索
     */
    public String getSource() {
        return source;
    }

    /**
     * 更新源码中的原始调用线索。
     *
     * @param source 源码调用线索
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 读取建议使用的 Mockito 策略或处理方式。
     *
     * @return Mockito 策略或处理方式
     */
    public String getMockitoStrategy() {
        return mockitoStrategy;
    }

    /**
     * 更新建议使用的 Mockito 策略或处理方式。
     *
     * @param mockitoStrategy Mockito 策略或处理方式
     */
    public void setMockitoStrategy(String mockitoStrategy) {
        this.mockitoStrategy = mockitoStrategy;
    }

    /**
     * 读取不能自动处理时给用户的简短说明。
     *
     * @return 简短说明
     */
    public String getRemark() {
        return remark;
    }

    /**
     * 更新不能自动处理时给用户的简短说明。
     *
     * @param remark 简短说明
     */
    public void setRemark(String remark) {
        this.remark = remark;
    }

    /** Detached editable inputs for a background execution task. */
    public MockStrategyItem copy() {
        var copy = new MockStrategyItem();
        copy.generate = generate;
        copy.dependencyType = dependencyType;
        copy.objectName = objectName;
        copy.methodName = methodName;
        copy.source = source;
        copy.mockitoStrategy = mockitoStrategy;
        copy.remark = remark;
        return copy;
    }
}
