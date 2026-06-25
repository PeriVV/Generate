package com.casc.unittestagent.llm;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible chat completions 客户端。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final LlmConfig config;
    private final LlmPromptBuilder promptBuilder = new LlmPromptBuilder();
    private final LlmResponseParser responseParser = new LlmResponseParser();

    public OpenAiCompatibleLlmClient(LlmConfig config) {
        this.config = config;
    }

    @Override
    public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) throws Exception {
        validateConfig();
        String systemPrompt = promptBuilder.systemPrompt();
        String userPrompt = promptBuilder.userPrompt(request);
        return responseParser.parse(executeChat(systemPrompt, userPrompt));
    }

    public LlmGenerationResponse generateMockStrategy(LlmGenerationRequest request) throws Exception {
        validateConfig();
        String systemPrompt = promptBuilder.mockStrategySystemPrompt();
        String userPrompt = promptBuilder.mockStrategyPrompt(request);
        return responseParser.parseMockStrategyOnly(executeChat(systemPrompt, userPrompt));
    }

    private String executeChat(String systemPrompt, String userPrompt) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
        Request httpRequest = new Request.Builder()
                .url(config.getApiUrl())
                .post(RequestBody.create(requestBody(systemPrompt, userPrompt).toString(), JSON))
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("LLM request failed: " + response.code() + " " + response.message() + responseSnippet(body));
            }
            return extractMessageContent(body);
        }
    }

    public int promptLength(LlmGenerationRequest request) {
        return promptBuilder.systemPrompt().length() + promptBuilder.userPrompt(request).length();
    }

    public String testConnection() throws Exception {
        validateConfig();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "You are a connectivity checker."));
        messages.put(new JSONObject().put("role", "user").put("content", "Reply with OK only."));
        JSONObject body = new JSONObject()
                .put("model", config.getModel())
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", 16);
        Request httpRequest = new Request.Builder()
                .url(config.getApiUrl())
                .post(RequestBody.create(body.toString(), JSON))
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("LLM request failed: " + response.code() + " " + response.message() + responseSnippet(responseBody));
            }
            String content = extractMessageContent(responseBody);
            return content == null || content.isBlank() ? "连接成功。" : "连接成功：" + content.trim();
        }
    }

    private JSONObject requestBody(String systemPrompt, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        return new JSONObject()
                .put("model", config.getModel())
                .put("messages", messages)
                .put("temperature", config.getTemperature())
                .put("max_tokens", config.getMaxTokens());
    }

    private String extractMessageContent(String body) {
        JSONObject root = new JSONObject(body);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalArgumentException("大模型返回格式无法解析，请重试。");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) {
            throw new IllegalArgumentException("大模型返回格式无法解析，请重试。");
        }
        return message.optString("content", "");
    }

    private void validateConfig() {
        if (config.getApiUrl() == null || config.getApiUrl().isBlank()) {
            throw new IllegalStateException("大模型 API 地址未配置。");
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("大模型 API Key 未配置，请设置 UNITMIND_API_KEY 或在设置中保存。");
        }
        if (config.getModel() == null || config.getModel().isBlank()) {
            throw new IllegalStateException("大模型名称未配置。");
        }
    }

    private String responseSnippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replace('\n', ' ').replace('\r', ' ').trim();
        return "，响应：" + compact.substring(0, Math.min(300, compact.length()));
    }
}
