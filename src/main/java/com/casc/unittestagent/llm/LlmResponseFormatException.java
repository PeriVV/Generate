package com.casc.unittestagent.llm;

/** 大模型请求成功，但响应为空或不符合 UnitMind 约定格式。 */
public class LlmResponseFormatException extends IllegalArgumentException {
    public LlmResponseFormatException(String message) {
        super(message);
    }

    public LlmResponseFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
