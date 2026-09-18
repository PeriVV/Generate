package com.casc.unittestagent.model;

/** 覆盖率闭环当前正在执行的真实阶段。 */
public enum CoveragePhase {
    NOT_STARTED,
    PREPARING,
    COMPILING,
    EXECUTING,
    ANALYZING,
    IMPROVING,
    BASELINE,
    VALIDATING,
    DIAGNOSING_SUT_BUG,
    COMPLETED
}
