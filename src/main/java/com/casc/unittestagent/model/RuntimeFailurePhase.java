package com.casc.unittestagent.model;

/** Execution phase is evidence, not an authorization to change the business Oracle. */
public enum RuntimeFailurePhase {
    UNKNOWN("未知阶段"), DISCOVERY("测试发现"), TEST_SETUP("测试初始化"), TEST_BODY("测试方法"), TEST_TEARDOWN("测试清理");
    private final String labelCn;
    RuntimeFailurePhase(String labelCn) { this.labelCn = labelCn; }
    public String getLabelCn() { return labelCn; }
}
