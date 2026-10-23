package com.casc.unittestagent.llm;

/**
 * 默认 OpenAI-compatible 客户端工厂。
 */
public class DefaultLlmClientFactory implements LlmClientFactory {
    @Override
    public LlmClient create(LlmConfig config) {
        return new OpenAiCompatibleLlmClient(config);
    }

    @Override
    public LlmClient create(LlmConfig config, java.util.function.BooleanSupplier cancelled) {
        return new OpenAiCompatibleLlmClient(config, cancelled);
    }
}
