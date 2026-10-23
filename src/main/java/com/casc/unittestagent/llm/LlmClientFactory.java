package com.casc.unittestagent.llm;

/**
 * 按当前配置创建大模型客户端。
 */
public interface LlmClientFactory {
    LlmClient create(LlmConfig config);

    /** Existing local/test implementations remain valid; transports can attach task cancellation. */
    default LlmClient create(LlmConfig config, java.util.function.BooleanSupplier cancelled) {
        return create(config);
    }
}
