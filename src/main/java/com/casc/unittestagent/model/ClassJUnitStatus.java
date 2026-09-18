package com.casc.unittestagent.model;

/** 整类最终 JUnit 执行与修复闭环状态。 */
public enum ClassJUnitStatus {
    NOT_RUN,
    RUNNING,
    PASSED,
    TEST_FAILED,
    SUSPECTED_SUT_BUG,
    NEEDS_REVIEW,
    ENVIRONMENT_ERROR,
    STALE,
    CANCELLED
}
