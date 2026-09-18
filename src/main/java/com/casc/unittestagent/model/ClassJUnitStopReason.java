package com.casc.unittestagent.model;

/** 整类最终 JUnit 闭环停止原因。 */
public enum ClassJUnitStopReason {
    NONE,
    INITIAL_PASS,
    REPAIRED,
    REPAIR_BUDGET_EXHAUSTED,
    COMPILE_REPAIR_EXHAUSTED,
    NO_PROGRESS,
    REPEATED_CODE,
    TEST_DESIGN_ERROR,
    SUSPECTED_SUT_BUG,
    ORACLE_INCONCLUSIVE,
    MODEL_ERROR,
    ENVIRONMENT_ERROR,
    NO_TESTS_FOUND,
    TIMEOUT,
    CANCELLED
}
