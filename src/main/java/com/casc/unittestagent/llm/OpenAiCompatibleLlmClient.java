package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.SourceBugAnalysis;

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
    /**
     * 源码缺陷归因是只读分析请求，只返回结构化结论，不应输出代码；
     * 限制输出规模防止思考型模型把预算耗在长推理上。
     */
    private static final int SOURCE_BUG_ANALYSIS_MAX_TOKENS = 1800;
    /**
     * 全插件共享连接池：生成/修复/覆盖率各流程都会创建客户端实例，
     * 若每个实例独立建池会重复维护到同一端点的 TLS 连接且从不释放。
     * newBuilder 派生的客户端共享池与调度器，仅覆盖超时配置。
     */
    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private final LlmConfig config;
    private final LlmPromptBuilder promptBuilder = new LlmPromptBuilder();
    private final LlmResponseParser responseParser = new LlmResponseParser();
    private final OkHttpClient httpClient;
    private boolean loggedModelRewrite;

    public OpenAiCompatibleLlmClient(LlmConfig config) {
        this.config = config;
        this.httpClient = SHARED_HTTP_CLIENT.newBuilder()
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) throws Exception {
        validateConfig();
        String systemPrompt = promptBuilder.systemPrompt();
        String userPrompt = promptBuilder.userPrompt(request);
        return responseParser.parse(executeChat(systemPrompt, userPrompt, config.getMaxTokens()));
    }

    @Override
    public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) throws Exception {
        validateConfig();
        return responseParser.parse(executeChat(promptBuilder.repairSystemPrompt(), promptBuilder.repairPrompt(request),
                config.getMaxTokens()));
    }

    @Override
    public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) throws Exception {
        validateConfig();
        return responseParser.parseCoverage(executeChat(
                promptBuilder.coverageSystemPrompt(), promptBuilder.coveragePrompt(request),
                config.getMaxTokens()));
    }

    @Override
    public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) throws Exception {
        validateConfig();
        return responseParser.parseClassRepair(executeChat(
                promptBuilder.classRepairSystemPrompt(), promptBuilder.classRepairPrompt(request),
                config.getMaxTokens()));
    }

    @Override
    public SourceBugAnalysis analyzeSuspectedSutBug(LlmGenerationRequest request,
                                                    OracleAssessment assessment) throws Exception {
        validateConfig();
        return responseParser.parseSourceBugAnalysis(executeChat(
                promptBuilder.sourceBugAnalysisSystemPrompt(),
                promptBuilder.sourceBugAnalysisPrompt(request, assessment),
                Math.min(config.getMaxTokens(), SOURCE_BUG_ANALYSIS_MAX_TOKENS)));
    }

    @Override
    public LlmGenerationResponse generateMockStrategy(LlmGenerationRequest request) throws Exception {
        validateConfig();
        String systemPrompt = promptBuilder.mockStrategySystemPrompt();
        String userPrompt = promptBuilder.mockStrategyPrompt(request);
        return responseParser.parseMockStrategyOnly(executeChat(systemPrompt, userPrompt));
    }

    private String executeChat(String systemPrompt, String userPrompt) throws IOException {
        return executeChat(systemPrompt, userPrompt, config.getMaxTokens());
    }

    private String executeChat(String systemPrompt, String userPrompt, int maxTokens) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(endpointUrl())
                .post(RequestBody.create(requestBody(systemPrompt, userPrompt, maxTokens).toString(), JSON))
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("LLM request failed: " + response.code() + " " + response.message() + responseSnippet(body));
            }
            return extractMessageContent(body, maxTokens);
        }
    }

    @Override
    public int promptLength(LlmGenerationRequest request) {
        return promptBuilder.systemPrompt().length() + promptBuilder.userPrompt(request).length();
    }

    public String testConnection() throws Exception {
        validateConfig();
        // JSON 模式要求消息中显式要求 JSON，否则部分提供商会输出空白直到 token 上限。
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system")
                .put("content", "You are a connectivity checker. Always reply in JSON."));
        messages.put(new JSONObject().put("role", "user")
                .put("content", "Reply with {\"status\":\"ok\"} only."));
        JSONObject body = new JSONObject()
                .put("model", modelName())
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", 16);
        applyProviderOptions(body);
        Request httpRequest = new Request.Builder()
                .url(endpointUrl())
                .post(RequestBody.create(body.toString(), JSON))
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("LLM request failed: " + response.code() + " " + response.message() + responseSnippet(responseBody));
            }
            String content = extractMessageContent(responseBody);
            return content == null || content.isBlank() ? "连接成功。" : "连接成功：" + content.trim();
        }
    }

    JSONObject requestBody(String systemPrompt, String userPrompt) {
        return requestBody(systemPrompt, userPrompt, config.getMaxTokens());
    }

    private JSONObject requestBody(String systemPrompt, String userPrompt, int maxTokens) {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        JSONObject body = new JSONObject()
                .put("model", modelName())
                .put("messages", messages)
                .put("temperature", config.getTemperature())
                .put("max_tokens", maxTokens);
        applyProviderOptions(body);
        return body;
    }

    private void applyProviderOptions(JSONObject body) {
        if (supportsJsonMode()) {
            body.put("response_format", new JSONObject().put("type", "json_object"));
        }
        // DashScope JSON mode conflicts with thinking mode. Coverage repair needs a short,
        // machine-readable final answer rather than provider-specific reasoning_content.
        if (isDashScope()) {
            body.put("enable_thinking", false);
        }
        // DeepSeek V4 思考模式默认开启：推理内容会挤占 max_tokens 预算（finish_reason=length），
        // 甚至只返回 reasoning_content 而没有最终测试代码。结构化生成必须显式关闭。
        if (isDeepSeek()) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
    }

    private boolean supportsJsonMode() {
        String url = config.getApiUrl() == null ? "" : config.getApiUrl().toLowerCase();
        return isDashScope() || isDeepSeek() || url.contains("api.openai.com/");
    }

    private boolean isDashScope() {
        String url = config.getApiUrl() == null ? "" : config.getApiUrl().toLowerCase();
        return url.contains("dashscope.aliyuncs.com/");
    }

    private boolean isDeepSeek() {
        String url = config.getApiUrl() == null ? "" : config.getApiUrl().toLowerCase();
        return url.contains("api.deepseek.com");
    }

    /**
     * DeepSeek 旧模型名 deepseek-chat/deepseek-reasoner 已于 2026-07-24 下线，统一指向 V4-Flash。
     * 改写必须留痕：用户可能通过代理端点自建同名模型，静默替换会掩盖配置问题。
     */
    private String modelName() {
        String model = config.getModel() == null ? "" : config.getModel().trim();
        if (isDeepSeek() && ("deepseek-chat".equals(model) || "deepseek-reasoner".equals(model))) {
            if (!loggedModelRewrite) {
                loggedModelRewrite = true;
                com.intellij.openapi.diagnostic.Logger.getInstance(OpenAiCompatibleLlmClient.class)
                        .warn("UnitMind: DeepSeek 已下线模型 " + model + "，本次请求改用 deepseek-v4-flash。"
                                + "如需使用其他模型，请在设置中修改模型名称。");
            }
            return "deepseek-v4-flash";
        }
        return model;
    }

    /**
     * 官方文档只给 base_url（如 https://api.deepseek.com），完整路径由 SDK 拼接；
     * 本客户端直接 POST，因此为裸 base_url 自动补全 /chat/completions。
     */
    String endpointUrl() {
        String url = config.getApiUrl() == null ? "" : config.getApiUrl().trim();
        if (url.isEmpty()) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd < 0 ? 0 : url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return url + "/chat/completions";
        }
        String path = url.substring(pathStart);
        if (path.matches("/+")) {
            return url.substring(0, pathStart) + "/chat/completions";
        }
        if (path.matches("/v1/?")) {
            return url.replaceAll("/+$", "") + "/chat/completions";
        }
        return url;
    }

    String extractMessageContent(String body) {
        return extractMessageContent(body, config.getMaxTokens());
    }

    private String extractMessageContent(String body, int maxTokens) {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (org.json.JSONException ex) {
            // 网关返回 200 + HTML 时不应抛裸 org.json 异常，统一按响应格式错误分类。
            throw new LlmResponseFormatException("大模型响应不是合法 JSON：" + responseSnippet(body));
        }
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new LlmResponseFormatException("大模型响应中没有 choices。");
        }
        JSONObject choice = choices.getJSONObject(0);
        String finishReason = choice.optString("finish_reason", "");
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new LlmOutputTruncatedException("大模型输出达到 max_tokens 上限（"
                    + maxTokens + "），完整代码负载可能已被截断。");
        }
        if ("content_filter".equalsIgnoreCase(finishReason)) {
            throw new LlmResponseFormatException("大模型输出被内容安全策略过滤，请调整提示词后重试。");
        }
        if ("insufficient_system_resource".equalsIgnoreCase(finishReason)) {
            throw new LlmResponseFormatException("大模型服务推理资源不足，生成被打断，请稍后重试。");
        }
        JSONObject message = choice.optJSONObject("message");
        if (message == null) {
            String legacyText = choice.optString("text", "");
            if (!legacyText.isBlank()) return legacyText;
            throw new LlmResponseFormatException("大模型响应中没有 message。");
        }
        Object content = message.opt("content");
        if (content instanceof String && !((String) content).isBlank()) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder joined = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int index = 0; index < parts.length(); index++) {
                Object part = parts.opt(index);
                String value = textPart(part);
                if (!value.isBlank()) {
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(value);
                }
            }
            if (joined.length() > 0) return joined.toString();
        }
        if (content instanceof JSONObject) {
            String value = textPart(content);
            if (!value.isBlank()) return value;
        }
        String legacyText = choice.optString("text", "");
        if (!legacyText.isBlank()) return legacyText;
        if (!message.optString("reasoning_content", "").isBlank()) {
            throw new LlmResponseFormatException("大模型只返回了推理内容，没有返回可用的最终测试代码。");
        }
        return "";
    }

    private String textPart(Object part) {
        if (part instanceof String) return (String) part;
        if (!(part instanceof JSONObject)) return "";
        JSONObject object = (JSONObject) part;
        Object text = object.opt("text");
        if (text instanceof String) return (String) text;
        if (text instanceof JSONObject) return ((JSONObject) text).optString("value", "");
        return object.optString("content", "");
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
