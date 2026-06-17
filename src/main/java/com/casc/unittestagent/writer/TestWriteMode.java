package com.casc.unittestagent.writer;

/**
 * 描述测试文件写入模式。
 * 写入服务根据该模式决定新建、追加、覆盖或取消，避免已有测试类被无提示覆盖。
 */
public enum TestWriteMode {
    /** 目标测试类不存在时创建新文件。 */
    CREATE_NEW,
    /** 目标测试类已存在时合并追加本次生成内容。 */
    APPEND_TO_EXISTING,
    /** 目标测试类已存在时整体覆盖为本次生成内容。 */
    OVERWRITE_EXISTING,
    /** 用户取消写入。 */
    CANCEL
}
