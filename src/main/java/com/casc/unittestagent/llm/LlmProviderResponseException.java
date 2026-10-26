package com.casc.unittestagent.llm;

/** A provider error payload can arrive with HTTP 200; keep it distinct from malformed JSON. */
final class LlmProviderResponseException extends LlmResponseFormatException {
    private final String code;
    LlmProviderResponseException(String code, String detail) {
        super("大模型服务返回错误负载（HTTP 成功不代表生成成功）：" + code + " " + detail);
        this.code = code;
    }
    String code() { return code; }
}
