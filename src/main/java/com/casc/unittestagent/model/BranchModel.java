package com.casc.unittestagent.model;

/**
 * 描述被测方法中的异常保护分支。
 * 解析器写入原始条件、安全条件和异常类型，场景规划器据此构造异常用例与正常绕过用例。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class BranchModel {
    /** 源码中的原始分支条件，保留给预览页和异常场景命名使用。 */
    private String condition;
    /** 原始条件的安全反向条件，用于生成不会触发异常分支的正常输入。 */
    private String safeCondition;
    /** 分支触发后的动作类型，例如 throw，用于区分异常路径和普通路径。 */
    private String action;
    /** 异常分支抛出的异常类型，生成 assertThrows 时需要使用。 */
    private String exceptionType;

    /**
     * 读取源码中的原始分支条件，保留给预览页和异常场景命名使用。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 原始分支条件
     */
    public String getCondition() {
        return condition;
    }

    /**
     * 更新源码中的原始分支条件，保留给预览页和异常场景命名使用。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param condition 需要取反或标准化的条件表达式文本
     */
    public void setCondition(String condition) {
        this.condition = condition;
    }

    /**
     * 读取原始条件的安全反向条件，用于生成不会触发异常分支的正常输入。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 安全反向条件
     */
    public String getSafeCondition() {
        return safeCondition;
    }

    /**
     * 更新原始条件的安全反向条件，用于生成不会触发异常分支的正常输入。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param safeCondition 方法执行所需的输入参数
     */
    public void setSafeCondition(String safeCondition) {
        this.safeCondition = safeCondition;
    }

    /**
     * 读取分支触发后的动作类型，例如 throw，用于区分异常路径和普通路径。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 分支动作类型
     */
    public String getAction() {
        return action;
    }

    /**
     * 更新分支触发后的动作类型，例如 throw，用于区分异常路径和普通路径。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param action 方法执行所需的输入参数
     */
    public void setAction(String action) {
        this.action = action;
    }

    /**
     * 读取异常分支抛出的异常类型，生成 assertThrows 时需要使用。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 异常类型名称
     */
    public String getExceptionType() {
        return exceptionType;
    }

    /**
     * 更新异常分支抛出的异常类型，生成 assertThrows 时需要使用。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param exceptionType 方法执行所需的输入参数
     */
    public void setExceptionType(String exceptionType) {
        this.exceptionType = exceptionType;
    }
}
