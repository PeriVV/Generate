package com.casc.unittestagent.model;

/**
 * 描述测试代码生成状态。
 * 测试代码页使用该状态在尚未生成、生成中、成功和失败之间切换，避免“生成中”长期停留。
 */
public enum GenerationStatus {
    /** 尚未触发生成。 */
    IDLE,
    /** 正在生成。 */
    RUNNING,
    /** 生成成功。 */
    SUCCESS,
    /** 生成失败。 */
    FAILED
}
