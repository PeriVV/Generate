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
        assertFalse(body.has("thinking"));
    }

    public void testDeepSeekDisablesThinkingAndRequestsJsonMode() {
        LlmConfig config = new LlmConfig();
        config.setApiUrl("https://api.deepseek.com");
        config.setModel("deepseek-v4-flash");

        JSONObject body = new OpenAiCompatibleLlmClient(config)
                .requestBody("return JSON", "generate JSON");

        assertEquals("json_object", body.getJSONObject("response_format").getString("type"));
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertEquals("deepseek-v4-flash", body.getString("model"));
    }

    public void testDeepSeekLegacyModelNamesMapToV4Flash() {
        LlmConfig config = new LlmConfig();
        config.setApiUrl("https://api.deepseek.com/v1");
        config.setModel("deepseek-chat");

        JSONObject body = new OpenAiCompatibleLlmClient(config)
                .requestBody("return JSON", "generate JSON");

        assertEquals("deepseek-v4-flash", body.getString("model"));
    }

    public void testUserConfiguredMaxTokensIsHonoredVerbatim() {
        // 用户在设置界面显式调小/调大输出上限时，请求体必须原样使用该值；
        // 客户端曾用 Math.max(config, 12000) 硬底线覆盖用户配置，导致 UI 设置不生效。
        LlmConfig small = new LlmConfig();
        small.setApiUrl("https://api.deepseek.com");
        small.setModel("deepseek-v4-flash");
        small.setMaxTokens(4096);
        assertEquals(4096, new OpenAiCompatibleLlmClient(small)
                .requestBody("return JSON", "generate JSON").getInt("max_tokens"));

        LlmConfig large = new LlmConfig();
        large.setApiUrl("https://api.deepseek.com");
        large.setModel("deepseek-v4-flash");
        large.setMaxTokens(32768);
        assertEquals(32768, new OpenAiCompatibleLlmClient(large)
                .requestBody("return JSON", "generate JSON").getInt("max_tokens"));
    }

    public void testMaxTokensIsClampedToValidRange() {
        LlmConfig config = new LlmConfig();
        config.setMaxTokens(10);
        assertEquals(256, config.getMaxTokens());
        config.setMaxTokens(1_000_000);
        assertEquals(LlmConfig.MAX_TOKENS_UPPER_BOUND, config.getMaxTokens());
        assertEquals(LlmConfig.DEFAULT_MAX_TOKENS, new LlmConfig().getMaxTokens());
    }

    public void testEndpointUrlAppendsChatCompletionsPath() {
        LlmConfig config = new LlmConfig();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);

        config.setApiUrl("https://api.deepseek.com");
        assertEquals("https://api.deepseek.com/chat/completions", client.endpointUrl());

        config.setApiUrl("https://api.deepseek.com/");
        assertEquals("https://api.deepseek.com/chat/completions", client.endpointUrl());

        config.setApiUrl("https://api.deepseek.com/v1");
        assertEquals("https://api.deepseek.com/v1/chat/completions", client.endpointUrl());

        config.setApiUrl("https://api.deepseek.com/v1/");
        assertEquals("https://api.deepseek.com/v1/chat/completions", client.endpointUrl());

        config.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", client.endpointUrl());
    }

    public void testRejectsContentFilteredCompletion() {
        String body = """
                {"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}
                """;

        try {
            new OpenAiCompatibleLlmClient(new LlmConfig()).extractMessageContent(body);
            fail("应拒绝被内容过滤的响应");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("内容安全"));
        }
    }

    public void testRejectsInsufficientResourceCompletion() {
        String body = """
                {"choices":[{"finish_reason":"insufficient_system_resource","message":{"content":""}}]}
                """;

        try {
            new OpenAiCompatibleLlmClient(new LlmConfig()).extractMessageContent(body);
            fail("应拒绝推理资源不足的响应");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("推理资源不足"));
        }
    }
}
