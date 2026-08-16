package com.casc.unittestagent.llm;

import junit.framework.TestCase;
import org.json.JSONObject;

public class OpenAiCompatibleLlmClientTest extends TestCase {
    public void testExtractsSegmentedChatCompletionContent() {
        String body = """
                {
                  "choices": [{
                    "message": {
                      "content": [
                        {"type":"text","text":"{\\\"mockStrategies\\\":[],"},
                        {"type":"text","text":"\\\"testCode\\\":\\\"class SubjectTest {}\\\",\\\"warnings\\\":[]}"}
                      ]
                    }
                  }]
                }
                """;

        String content = new OpenAiCompatibleLlmClient(new LlmConfig()).extractMessageContent(body);

        assertTrue(content.contains("mockStrategies"));
        assertTrue(content.contains("class SubjectTest"));
        assertNotNull(new LlmResponseParser().parse(content).getTestCode());
    }

    public void testRejectsCompletionTruncatedByMaxTokens() {
        LlmConfig config = new LlmConfig();
        config.setMaxTokens(1234);
        String body = """
                {"choices":[{"finish_reason":"length","message":{"content":"class SubjectTest {}"}}]}
                """;

        try {
            new OpenAiCompatibleLlmClient(config).extractMessageContent(body);
            fail("应拒绝因 max_tokens 截断的响应");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("1234"));
            assertTrue(ex.getMessage().contains("截断"));
        }
    }

    public void testExplainsReasoningOnlyResponse() {
        String body = """
                {"choices":[{"finish_reason":"stop","message":{"content":"","reasoning_content":"thinking"}}]}
                """;

        try {
            new OpenAiCompatibleLlmClient(new LlmConfig()).extractMessageContent(body);
            fail("应拒绝仅推理内容的响应");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("推理内容"));
        }
    }

    public void testDashScopeRequestsJsonModeWithoutThinking() {
        LlmConfig config = new LlmConfig();
        config.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        config.setModel("qwen-test");

        JSONObject body = new OpenAiCompatibleLlmClient(config)
                .requestBody("return JSON", "generate JSON");

        assertEquals("json_object", body.getJSONObject("response_format").getString("type"));
        assertFalse(body.getBoolean("enable_thinking"));
    }

    public void testUnknownCompatibleEndpointKeepsLegacyRequestShape() {
        LlmConfig config = new LlmConfig();
        config.setApiUrl("https://llm.internal.example/v1/chat/completions");
        config.setModel("internal-model");

        JSONObject body = new OpenAiCompatibleLlmClient(config)
                .requestBody("return JSON", "generate JSON");

        assertFalse(body.has("response_format"));
        assertFalse(body.has("enable_thinking"));
    }
}
