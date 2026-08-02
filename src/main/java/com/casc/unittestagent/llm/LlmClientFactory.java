package com.casc.unittestagent.llm;

/**
 * 按当前配置创建大模型客户端。
 */
public interface LlmClientFactory {
    LlmClient create(LlmConfig config);
}
