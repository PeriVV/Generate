package com.casc.unittestagent.model;

/**
 * 生成测试代码相对于当前项目 classpath 的验证状态。
 */
public enum ValidationStatus {
    NOT_RUN,
    RUNNING,
    PASSED,
    FAILED,
    ENVIRONMENT_ERROR,
    STALE
}
