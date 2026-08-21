package com.casc.unittestagent.model;

/** 整类最终 JUnit 执行与修复闭环状态。 */
public enum ClassJUnitStatus {
    NOT_RUN,
    RUNNING,
    PASSED,
    TEST_FAILED,
    ENVIRONMENT_ERROR,
    STALE,
    CANCELLED
}
