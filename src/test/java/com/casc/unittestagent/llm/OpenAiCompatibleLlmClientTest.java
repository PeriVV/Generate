package com.casc.unittestagent.llm;

import junit.framework.TestCase;
import org.json.JSONObject;

public class OpenAiCompatibleLlmClientTest extends TestCase {
    public void testProbeAndSavedGenerationUseTheSameEnvironmentKeyPriority() {
        assertEquals("environment", LlmConfigManager.selectApiKey(" environment ", "typed", () -> { throw new AssertionError(); }));
        assertEquals("typed", LlmConfigManager.selectApiKey(" ", " typed ", () -> { throw new AssertionError(); }));
        assertEquals("stored", LlmConfigManager.selectApiKey(null, "", () -> " stored "));
        assertEquals("", LlmConfigManager.selectApiKey(null, null, () -> null));
    }
    public void testStreamPreferenceMatchesProbeAndGenerationAndCanBeDisabled() {
        LlmConfig config = new LlmConfig();
        assertTrue(config.isStreaming());
        var client = new OpenAiCompatibleLlmClient(config);
        assertTrue(client.testConnectionBody().getBoolean("stream"));
        assertTrue(client.requestBody("JSON", "JSON").getBoolean("stream"));
        config.setStreaming(false);
        assertFalse(client.testConnectionBody().has("stream"));
        assertFalse(client.requestBody("JSON", "JSON").has("stream"));
    }

    public void testRepairConfigCopyRetainsThinkingStreamAndOtherSettings() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(true); config.setApiUrl("http://private-fixture/v1"); config.setApiKey("local-fixture");
        config.setModel("minimax"); config.setTimeoutSeconds(420); config.setMaxTokens(40000);
        config.setMaxCompileRepairRounds(8); config.setTemperature(0.6);
        config.setFallbackToRuleBased(false); config.setThinkingMode(true); config.setStreaming(false);
        LlmConfig copy = config.copy();
        assertTrue(copy.isEnabled()); assertTrue(copy.isThinkingMode()); assertFalse(copy.isStreaming());
        assertEquals(config.getApiUrl(), copy.getApiUrl()); assertEquals(config.getApiKey(), copy.getApiKey());
        assertEquals(config.getModel(), copy.getModel()); assertEquals(420, copy.getTimeoutSeconds());
        assertEquals(40000, copy.getMaxTokens()); assertEquals(8, copy.getMaxCompileRepairRounds());
        assertEquals(0.6, copy.getTemperature()); assertFalse(copy.isFallbackToRuleBased());
        copy.setThinkingMode(false); assertTrue(config.isThinkingMode());
    }
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

        // 百炼兼容模式的 base_url 以 /v1 结尾但路径不止 /v1，同样必须补全 /chat/completions。
        config.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", client.endpointUrl());

        config.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/");
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", client.endpointUrl());
    }

    public void testDashScopeHostedThirdPartyModelKeepsMinimalRequestBody() {
        // 百炼上的第三方模型（如 MiniMax/MiniMax-M2.7）参数透传原厂：
        // enable_thinking 与 response_format 是 Qwen 家族参数，不能注入第三方思考模型。
        LlmConfig config = new LlmConfig();
        config.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setModel("MiniMax/MiniMax-M2.7");

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", client.endpointUrl());

        JSONObject body = client.requestBody("return JSON", "generate JSON");
        assertEquals("MiniMax/MiniMax-M2.7", body.getString("model"));
        assertFalse(body.has("response_format"));
        assertFalse(body.has("enable_thinking"));
        assertFalse(body.has("thinking"));
    }

    public void testThinkingModeSkipsSuppressorsAndRaisesOutputBudget() {
        // 思考模式：不再注入任何“关闭思考”参数、不发 JSON 模式参数，输出预算抬到下限以上。
        LlmConfig config = new LlmConfig();
        config.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setModel("qwen-plus");
        config.setMaxTokens(4096);
        config.setThinkingMode(true);

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        JSONObject body = client.requestBody("return JSON", "generate JSON");
        assertFalse(body.has("response_format"));
        assertFalse(body.has("enable_thinking"));
        assertEquals(LlmConfig.THINKING_MIN_OUTPUT_TOKENS, body.getInt("max_tokens"));

        LlmConfig deepSeek = new LlmConfig();
        deepSeek.setApiUrl("https://api.deepseek.com");
        deepSeek.setModel("deepseek-v4-flash");
        deepSeek.setThinkingMode(true);
        JSONObject deepSeekBody = new OpenAiCompatibleLlmClient(deepSeek)
                .requestBody("return JSON", "generate JSON");
        assertFalse(deepSeekBody.has("thinking"));
        assertFalse(deepSeekBody.has("response_format"));
    }

    public void testThinkingModeKeepsUserBudgetAboveMinimum() {
        LlmConfig config = new LlmConfig();
        config.setApiUrl("https://api.deepseek.com");
        config.setModel("deepseek-v4-flash");
        config.setMaxTokens(49152);
        config.setThinkingMode(true);
        assertEquals(49152, new OpenAiCompatibleLlmClient(config).effectiveMaxTokens());

        config.setThinkingMode(false);
        assertEquals(49152, new OpenAiCompatibleLlmClient(config).effectiveMaxTokens());

        config.setMaxTokens(4096);
        config.setThinkingMode(false);
        assertEquals(4096, new OpenAiCompatibleLlmClient(config).effectiveMaxTokens());
    }

    public void testConnectionBudgetGrowsForThinkingCapableModels() {
        // 思考模型连通性探测：第三方推理模型或开启思考模式时，16 token 会被推理内容耗尽
        // 必然 finish_reason=length，必须给足探测预算。
        LlmConfig hosted = new LlmConfig();
        hosted.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        hosted.setModel("MiniMax/MiniMax-M2.7");
        assertEquals(1024, new OpenAiCompatibleLlmClient(hosted).testConnectionBody().getInt("max_tokens"));

        LlmConfig intranet = new LlmConfig();
        intranet.setApiUrl("http://private-fixture/v1"); intranet.setModel("MiniMax-M2.5");
        assertEquals(1024, new OpenAiCompatibleLlmClient(intranet).testConnectionBody().getInt("max_tokens"));

        LlmConfig thinking = new LlmConfig();
        thinking.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        thinking.setModel("qwen-plus");
        thinking.setThinkingMode(true);
        assertEquals(1024, new OpenAiCompatibleLlmClient(thinking).testConnectionBody().getInt("max_tokens"));

        LlmConfig plain = new LlmConfig();
        plain.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        plain.setModel("qwen-plus");
        assertEquals(16, new OpenAiCompatibleLlmClient(plain).testConnectionBody().getInt("max_tokens"));
    }

    public void testConnectionTreatsTruncatedReplyAsSuccess() {
        // 连通性视角：HTTP 200 + 合法结构即连接成功，finish_reason=length 不是失败。
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new LlmConfig());
        assertEquals("{\"status\":\"ok\"}", client.connectivityReply(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"status\\\":\\\"ok\\\"}\"}}]}"));
        assertEquals("", client.connectivityReply(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\",\"reasoning_content\":\"thinking...\"}}]}"));
        try {
            client.connectivityReply("{\"error\":{}}");
            fail("无 choices 的响应应视为格式错误");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("choices"));
        }
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
