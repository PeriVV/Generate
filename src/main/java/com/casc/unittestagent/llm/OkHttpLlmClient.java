package com.casc.unittestagent.llm;

/**
 * 旧名称兼容类。
 * 新调用逻辑已迁移到 OpenAiCompatibleLlmClient，保留该类避免外部引用失效。
 */
public class OkHttpLlmClient extends OpenAiCompatibleLlmClient {
    public OkHttpLlmClient() {
        super(LlmConfigManager.load());
    }

    public OkHttpLlmClient(LlmConfig config) {
        super(config);
    }
}
