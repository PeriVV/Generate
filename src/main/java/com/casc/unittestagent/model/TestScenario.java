package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述一个待生成的测试场景。
 * 场景模型把测试方法名、类型、说明、输入表达式、断言预期和覆盖分支关联起来。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestScenario {
    /** 场景名或参数名，生成测试方法名和参数映射时会使用。 */
    private String name;
    /** UI 展示使用的中文场景名，避免中文进入 Java 方法标识符。 */
    private String displayNameCn;
    /** 测试代码使用的英文方法名，避免中文编码或团队规范造成编译问题。 */
    private String methodNameEn;
    /** 场景类型或 Java 类型，用于选择测试数据生成和代码拼装策略。 */
    private String type;
    /** 测试场景说明，用于预览页解释该场景的覆盖目的。 */
    private String description;
    /** 场景对应的输入表达式集合，预留给更细粒度的输入展示。 */
    private List<String> inputExpressions = new ArrayList<>();
    /** 场景期望断言集合，预留给更丰富的断言生成。 */
    private List<String> expectedAssertions = new ArrayList<>();
    /** 该场景计划覆盖的源码分支条件。 */
    private List<String> coveredBranches = new ArrayList<>();
    /** 断言类型，例如 RETURN_VALUE / STATE / EXCEPTION / INTERACTION / UNKNOWN。 */
    private String oracleKind = "UNKNOWN";
    /** 断言依据，例如 SOURCE / EXISTING_TEST / STATIC_ANALYSIS / USER_REQUIREMENT。 */
    private String oracleEvidence = "STATIC_ANALYSIS";
    /** 断言可信度：HIGH / MEDIUM / LOW。 */
    private String oracleConfidence = "LOW";

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
     * 读取 UI 展示使用的中文场景名。
     * 预览页使用该字段展示业务含义，测试代码生成不会直接使用中文。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 中文场景名
     */
    public String getDisplayNameCn() {
        return displayNameCn == null || displayNameCn.isBlank() ? name : displayNameCn;
    }

    /**
     * 更新 UI 展示使用的中文场景名。
     * 场景规划器写入中文说明，避免 UI 层从英文方法名反推业务含义。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param displayNameCn 中文场景名
     */
    public void setDisplayNameCn(String displayNameCn) {
        this.displayNameCn = displayNameCn;
    }

    /**
     * 读取测试代码使用的英文方法名。
     * Java 标识符统一使用英文，避免中文方法名在不同编码环境中出现乱码。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 英文测试方法名
     */
    public String getMethodNameEn() {
        return methodNameEn == null || methodNameEn.isBlank() ? name : methodNameEn;
    }

    /**
     * 更新测试代码使用的英文方法名。
     * 代码生成器只读取该字段作为测试方法名，中文仅保留在展示层。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param methodNameEn 英文测试方法名
     */
    public void setMethodNameEn(String methodNameEn) {
        this.methodNameEn = methodNameEn;
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
     * 读取测试场景说明，用于预览页解释该场景的覆盖目的。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 测试场景说明文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 更新测试场景说明，用于预览页解释该场景的覆盖目的。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param description 场景说明文本
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 读取场景对应的输入表达式集合，预留给更细粒度的输入展示。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 场景输入表达式列表
     */
    public List<String> getInputExpressions() {
        return inputExpressions;
    }

    /**
     * 更新场景对应的输入表达式集合，预留给更细粒度的输入展示。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param inputExpressions 输入表达式列表
     */
    public void setInputExpressions(List<String> inputExpressions) {
        this.inputExpressions = inputExpressions;
    }

    /**
     * 读取场景期望断言集合，预留给更丰富的断言生成。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 场景期望断言列表
     */
    public List<String> getExpectedAssertions() {
        return expectedAssertions;
    }

    /**
     * 更新场景期望断言集合，预留给更丰富的断言生成。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param expectedAssertions 期望断言列表
     */
    public void setExpectedAssertions(List<String> expectedAssertions) {
        this.expectedAssertions = expectedAssertions;
    }

    /**
     * 读取该场景计划覆盖的源码分支条件。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 场景计划覆盖的分支条件列表
     */
    public List<String> getCoveredBranches() {
        return coveredBranches;
    }

    /**
     * 更新该场景计划覆盖的源码分支条件。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param coveredBranches 覆盖分支列表
     */
    public void setCoveredBranches(List<String> coveredBranches) {
        this.coveredBranches = coveredBranches;
    }

    public String getOracleKind() { return oracleKind; }
    public void setOracleKind(String value) { oracleKind = value == null ? "UNKNOWN" : value; }
    public String getOracleEvidence() { return oracleEvidence; }
    public void setOracleEvidence(String value) {
        oracleEvidence = value == null ? "STATIC_ANALYSIS" : value;
    }
    public String getOracleConfidence() { return oracleConfidence; }
    public void setOracleConfidence(String value) {
        oracleConfidence = value == null ? "LOW" : value;
    }
}
