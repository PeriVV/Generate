package com.casc.unittestagent.model;

/** 失败类别的修复策略，决定停止文案与是否提供证据续修入口。 */
public enum RepairStrategy {
    /** 测试侧结构错误，无需用户证据即可确定性修复；停止只说明自动修复轮次耗尽。 */
    DETERMINISTIC,
    /** Oracle 判定需要独立权威（用户需求/已有测试/合同事实）才能继续。 */
    EVIDENCE_REQUIRED,
    /** 项目/IDE 环境不可用，插件无法修复。 */
    ENVIRONMENT,
    /** 证据倾向被测代码缺陷，已停止并保留失败测试。 */
    SUSPECTED_SUT_BUG,
    /** 无法归入封闭分类。 */
    UNCLASSIFIED
}
