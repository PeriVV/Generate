package com.casc.unittestagent.model;

/**
 * 描述 UnitMind 向导步骤的运行状态。
 * 工具窗口用该状态明确区分未开始、进行中、成功、有警告和失败，避免用户看到无反馈的空页面。
 */
public enum StepStatus {
    /** 步骤尚未开始。 */
    NOT_STARTED("未开始"),
    /** 步骤正在运行。 */
    RUNNING("进行中"),
    /** 步骤已完成且没有明显告警。 */
    SUCCESS("已完成"),
    /** 步骤已完成但存在可恢复告警。 */
    WARNING("有警告"),
    /** 步骤执行失败。 */
    FAILED("失败");

    /** 展示给用户的中文状态文本。 */
    private final String displayText;

    /**
     * 创建步骤状态。
     *
     * @param displayText 中文展示文本
     */
    StepStatus(String displayText) {
        this.displayText = displayText;
    }

    /**
     * 读取中文展示文本。
     *
     * @return 中文状态
     */
    public String getDisplayText() {
        return displayText;
    }
}
