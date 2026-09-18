package com.casc.unittestagent.model;

/** 运行失败仲裁后的动作；终止类结果不得携带测试代码修改。 */
public enum RuntimeRepairDisposition {
    REPAIR_TEST,
    SUSPECTED_SUT_BUG,
    INCONCLUSIVE
}
