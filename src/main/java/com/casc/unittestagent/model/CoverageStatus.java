package com.casc.unittestagent.model;

/** 当前候选测试相对于被测方法的覆盖率状态。 */
public enum CoverageStatus {
    NOT_RUN, RUNNING, PASSED, BELOW_THRESHOLD, TEST_FAILED, SUSPECTED_SUT_BUG, NEEDS_REVIEW,
    ENVIRONMENT_ERROR, STALE, CANCELLED
}
