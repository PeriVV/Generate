package com.casc.unittestagent.model;

/** 整类生成中单个方法的执行状态。 */
public enum MethodGenerationStatus {
    NOT_STARTED,
    ANALYZED,
    PLANNED,
    GENERATING,
    SUCCESS,
    RULE_FALLBACK,
    FAILED,
    CANCELLED
}
