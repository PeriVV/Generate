package com.casc.unittestagent.model;

/** 覆盖率闭环停止原因。 */
public enum CoverageStopReason {
    NONE, TARGET_REACHED, MAX_ROUNDS, NO_PROGRESS, REPEATED_CODE, REPAIR_BUDGET_EXHAUSTED,
    TEST_FAILURE, MODEL_ERROR, ENVIRONMENT_ERROR, CANCELLED
}
