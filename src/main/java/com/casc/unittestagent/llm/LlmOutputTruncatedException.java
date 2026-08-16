package com.casc.unittestagent.llm;

/** 提供商明确表示输出因 token 上限被截断，不应按普通 JSON 格式错误盲目重试。 */
public class LlmOutputTruncatedException extends LlmResponseFormatException {
    public LlmOutputTruncatedException(String message) { super(message); }
}
