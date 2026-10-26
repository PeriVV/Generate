package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.SourceBugAnalysis;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
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
    /** 思考模式下源码归因同样要为推理内容留预算，上限相应放宽。 */
    private static final int THINKING_ANALYSIS_MAX_TOKENS = 4000;
    /** 思考模型连通性探测预算：容纳推理内容后再回答一个极小 JSON。 */
    private static final int TEST_CONNECTION_THINKING_MAX_TOKENS = 1024;
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
    private final java.util.function.BooleanSupplier cancelled;
    private final LlmPromptBuilder promptBuilder = new LlmPromptBuilder();
    private final LlmResponseParser responseParser = new LlmResponseParser();
    private final OkHttpClient httpClient;
    private final LlmRequestTrace diagnosticTrace;
    private final okhttp3.ConnectionPool diagnosticPool;
    private boolean loggedModelRewrite;

    public OpenAiCompatibleLlmClient(LlmConfig config) {
        this(config, null);
    }

    public OpenAiCompatibleLlmClient(LlmConfig config, java.util.function.BooleanSupplier cancelled) {
        this(config, cancelled, null);
    }

    OpenAiCompatibleLlmClient(LlmConfig config, java.util.function.BooleanSupplier cancelled, LlmRequestTrace trace) {
        this(config, cancelled, trace, false);
    }

    OpenAiCompatibleLlmClient(LlmConfig config, java.util.function.BooleanSupplier cancelled, LlmRequestTrace trace, boolean freshConnection) {
        this.config = config;
        this.cancelled = cancelled;
        this.diagnosticTrace = trace;
        this.diagnosticPool = freshConnection ? new okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS) : null;
        var builder = SHARED_HTTP_CLIENT.newBuilder()
                // Preserve connect/write/read timeout types. A total budget equal to one phase
                // races the read deadline and turns SocketTimeoutException into a call timeout.
                .callTimeout(3L * config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .connectTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (diagnosticPool != null) builder.connectionPool(diagnosticPool);
        // Expose first-attempt failures rather than silently hiding instability during diagnosis.
        if (trace != null) builder.retryOnConnectionFailure(false).followRedirects(false);
        this.httpClient = builder.build();
    }

    @Override
    public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) throws Exception {
        validateConfig();
        String systemPrompt = promptBuilder.systemPrompt();
        String userPrompt = promptBuilder.userPrompt(request);
        return responseParser.parse(executeChat(systemPrompt, userPrompt, effectiveMaxTokens()));
    }

    @Override
    public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) throws Exception {
        validateConfig();
        return responseParser.parse(executeChat(promptBuilder.repairSystemPrompt(), promptBuilder.repairPrompt(request),
                effectiveMaxTokens()));
    }

    @Override
    public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) throws Exception {
        validateConfig();
        return responseParser.parseCoverage(executeChat(
                promptBuilder.coverageSystemPrompt(), promptBuilder.coveragePrompt(request),
                effectiveMaxTokens()));
    }

    @Override
    public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) throws Exception {
        validateConfig();
        return responseParser.parseClassRepair(executeChat(
                promptBuilder.classRepairSystemPrompt(), promptBuilder.classRepairPrompt(request),
                effectiveMaxTokens()));
    }

    @Override
    public SourceBugAnalysis analyzeSuspectedSutBug(LlmGenerationRequest request,
                                                    OracleAssessment assessment) throws Exception {
        validateConfig();
        return responseParser.parseSourceBugAnalysis(executeChat(
                promptBuilder.sourceBugAnalysisSystemPrompt(),
                promptBuilder.sourceBugAnalysisPrompt(request, assessment),
                Math.min(effectiveMaxTokens(), sourceBugAnalysisMaxTokens())));
    }

    @Override
    public LlmGenerationResponse generateMockStrategy(LlmGenerationRequest request) throws Exception {
        validateConfig();
        String systemPrompt = promptBuilder.mockStrategySystemPrompt();
        String userPrompt = promptBuilder.mockStrategyPrompt(request);
        return responseParser.parseMockStrategyOnly(executeChat(systemPrompt, userPrompt));
    }

    private String executeChat(String systemPrompt, String userPrompt) throws IOException {
        return executeChat(systemPrompt, userPrompt, effectiveMaxTokens());
    }

    /**
     * 思考模式下推理内容与最终代码共享输出预算，用户配置低于下限时自动抬高，
     * 避免生成以 finish_reason=length 截断；非思考模式原样使用用户配置。
     */
    int effectiveMaxTokens() {
        if (!config.isThinkingMode()) {
            return config.getMaxTokens();
        }
        return Math.max(config.getMaxTokens(), LlmConfig.THINKING_MIN_OUTPUT_TOKENS);
    }

    private int sourceBugAnalysisMaxTokens() {
        return config.isThinkingMode() ? THINKING_ANALYSIS_MAX_TOKENS : SOURCE_BUG_ANALYSIS_MAX_TOKENS;
    }

    private String executeChat(String systemPrompt, String userPrompt, int maxTokens) throws IOException {
        return extractMessageContent(executeRequest(requestBody(systemPrompt, userPrompt, maxTokens)), maxTokens);
    }

    private String executeRequest(JSONObject payload) throws IOException {
        RequestBody postBody = RequestBody.create(payload.toString(), JSON);
        if (diagnosticTrace != null) {
            RequestBody original = postBody;
            postBody = new RequestBody() {
                @Override public MediaType contentType() { return original.contentType(); }
                @Override public long contentLength() throws IOException { return original.contentLength(); }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException { original.writeTo(sink); }
                @Override public boolean isOneShot() { return true; }
            };
        }
        Request httpRequest = new Request.Builder()
                .url(endpointUrl())
                .post(postBody)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        var operation = com.casc.unittestagent.util.OperationContext.capture(cancelled);
        operation.checkCancelled();
        var trace = diagnosticTrace == null ? new LlmRequestTrace(null) : diagnosticTrace;
        var call = httpClient.newBuilder().eventListener(trace).build().newCall(httpRequest);
        boolean completed = false;
        try (var registration = operation.watch(call::cancel); Response response = call.execute()) {
            operation.checkCancelled();
            ResponseBody observedBody = response.body() == null ? null : observedBody(response.body(), trace,
                    response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT).contains("text/event-stream"));
            if (!response.isSuccessful()) {
                String body = observedBody == null ? "" : observedBody.string();
                var failure = new IOException("LLM request failed: " + response.code() + " " + response.message() + responseSnippet(body));
                var diagnosis = LlmFailureDiagnosis.classify(failure, trace.snapshot());
                var redactor = new DiagnosticRedactor(config.getApiKey());
                throw new IOException("[" + diagnosis.code() + "] " + redactor.detail(failure.getMessage())
                        + "；" + diagnosis.advice() + " 请求 ID：" + redactor.detail(trace.snapshot().requestId()), failure);
            }
            if (observedBody == null) { completed = true; return ""; }
            String type = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
            if (type.contains("text/event-stream")) {
                String body = new ChatCompletionStream(trace::sseEvent).read(observedBody.source(), operation::checkCancelled);
                operation.checkCancelled();
                completed = true;
                return body;
            }
            String body = observedBody.string();
            operation.checkCancelled();
            trace.jsonCompletion(body);
            completed = true;
            return body;
        } catch (IOException ex) {
            operation.checkCancelled();
            if (ex instanceof java.io.InterruptedIOException
                    && (ex instanceof java.net.SocketTimeoutException || "timeout".equalsIgnoreCase(ex.getMessage()))) {
                var snapshot = trace.snapshot();
                var diagnosis = LlmFailureDiagnosis.classify(ex, snapshot);
                String message = "[" + diagnosis.code() + "] 大模型请求超时：" + snapshot.stage().label + "，已用 "
                        + snapshot.elapsedMs() / 1000 + " 秒；"
                        + "读写空闲上限 " + config.getTimeoutSeconds() + " 秒，单次总上限 "
                        + 3L * config.getTimeoutSeconds() + " 秒；"
                        + (config.isStreaming() ? "已请求流式返回。" : "当前使用非流式返回。")
                        + "连接测试仅验证小请求；长代码/推理可能更慢。"
                        + diagnosis.advice() + " 请求 ID：" + new DiagnosticRedactor(config.getApiKey()).detail(snapshot.requestId());
                java.io.InterruptedIOException explained = ex instanceof java.net.SocketTimeoutException
                        ? new java.net.SocketTimeoutException(message) : new java.io.InterruptedIOException(message);
                explained.initCause(ex);
                throw explained;
            }
            throw ex;
        } finally {
            trace.finish(completed);
            if (diagnosticPool != null) diagnosticPool.evictAll();
        }
    }

    private ResponseBody observedBody(ResponseBody body, LlmRequestTrace trace, boolean streaming) {
        long limit = (streaming ? 64L : 8L) * 1024 * 1024;
        var observed = okio.Okio.buffer(new okio.ForwardingSource(body.source()) {
            private long count;
            @Override public long read(okio.Buffer sink, long byteCount) throws IOException {
                long read = super.read(sink, byteCount);
                if (read > 0) {
                    count += read; trace.bodyBytes(read);
                    if (count > limit) throw new LlmResponseFormatException("大模型响应超过大小限制。");
                }
                return read;
            }
        });
        return new ResponseBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }
            @Override public okio.BufferedSource source() { return observed; }
        };
    }

    @Override
    public int promptLength(LlmGenerationRequest request) {
        return promptBuilder.systemPrompt().length() + promptBuilder.userPrompt(request).length();
    }

    public String testConnection() throws Exception {
        validateConfig();
        String content = connectivityReply(executeRequest(testConnectionBody()));
        return content == null || content.isBlank() ? "连接成功。" : "连接成功：" + content.trim();
    }

    /**
     * 连通性探测请求体。
     * 思考模型（开启思考模式或网关托管的第三方推理模型）即使只回答一个 JSON 也会先输出
     * 推理内容，16 token 必然被截断；给足探测预算让回答有机会落盘。
     */
    JSONObject testConnectionBody() {
        // JSON 模式要求消息中显式要求 JSON，否则部分提供商会输出空白直到 token 上限。
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system")
                .put("content", "You are a connectivity checker. Always reply in JSON."));
        messages.put(new JSONObject().put("role", "user")
                .put("content", "Reply with {\"status\":\"ok\"} only."));
        boolean thinkingCapable = config.isThinkingMode() || isHostedThirdPartyModel()
                || modelName().toLowerCase(java.util.Locale.ROOT).contains("minimax");
        JSONObject body = new JSONObject()
                .put("model", modelName())
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", thinkingCapable ? TEST_CONNECTION_THINKING_MAX_TOKENS : 16);
        applyProviderOptions(body);
        return body;
    }

    /** 连通性视角的响应解析：合法结构即成功，返回可展示的回复文本（可能为空）。 */
    String connectivityReply(String body) {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (org.json.JSONException ex) {
            throw new LlmResponseFormatException("大模型响应不是合法 JSON：" + responseSnippet(body));
        }
        rejectProviderError(root);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new LlmResponseFormatException("大模型响应中没有 choices。");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) {
            return "";
        }
        Object content = message.opt("content");
        if (content instanceof String) {
            return (String) content;
        }
        // 分段 content 等复杂结构对连通性探测不重要，空文本等价于“连接成功”。
        return "";
    }

    JSONObject requestBody(String systemPrompt, String userPrompt) {
        return requestBody(systemPrompt, userPrompt, effectiveMaxTokens());
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
        if (config.isStreaming()) body.put("stream", true);
        // 百炼上的第三方模型（如 MiniMax/MiniMax-M2.7）参数透传给原厂：
        // enable_thinking 与 response_format 是 Qwen 家族参数，第三方思考模型可能直接拒绝。
        // 最小请求体最稳；思考内容由 extractMessageContent 的 reasoning_content 分支兜底。
        boolean dashScopeNativeModel = isDashScope() && !isHostedThirdPartyModel();
        // 思考模式：用户显式选择让推理模型按默认思考行为工作，不再注入任何
        // “关闭思考”参数；JSON mode 与思考模式在多数网关互斥，同样不发送。
        if (config.isThinkingMode()) {
            return;
        }
        if (supportsJsonMode()) {
            body.put("response_format", new JSONObject().put("type", "json_object"));
        }
        // DashScope JSON mode conflicts with thinking mode. Coverage repair needs a short,
        // machine-readable final answer rather than provider-specific reasoning_content.
        if (dashScopeNativeModel) {
            body.put("enable_thinking", false);
        }
        // DeepSeek V4 思考模式默认开启：推理内容会挤占 max_tokens 预算（finish_reason=length），
        // 甚至只返回 reasoning_content 而没有最终测试代码。结构化生成必须显式关闭。
        if (isDeepSeek()) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
    }

    /** 百炼兼容模式里的第三方模型名带厂商前缀（MiniMax/MiniMax-M2.7），自家模型无斜杠。 */
    private boolean isHostedThirdPartyModel() {
        String model = config.getModel() == null ? "" : config.getModel().trim();
        return model.contains("/");
    }

    private boolean supportsJsonMode() {
        if (isDashScope() && isHostedThirdPartyModel()) {
            return false;
        }
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
     * 本客户端直接 POST，因此为 base_url 自动补全 /chat/completions。
     * 判断依据是路径以 /v1 结尾而非恰好等于 /v1——阿里云百炼等网关的路径形如
     * /compatible-mode/v1，同样需要在末尾追加 /chat/completions。
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
        if (path.matches(".*/v1/?")) {
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
        rejectProviderError(root);
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

    private void rejectProviderError(JSONObject root) {
        String code = "", detail = "";
        boolean failed = root.has("error") && !root.isNull("error");
        if (failed) {
            JSONObject error = root.optJSONObject("error");
            code = error == null ? "provider_error" : error.optString("code", error.optString("type", "provider_error"));
            detail = error == null ? String.valueOf(root.opt("error")) : error.optString("message", "服务返回 error 对象");
        }
        var base = root.optJSONObject("base_resp");
        if (!failed && base != null && base.optInt("status_code", 0) != 0) {
            failed = true; code = String.valueOf(base.optInt("status_code")); detail = base.optString("status_msg", "服务返回非零状态");
        }
        if (failed) {
            var redactor = new DiagnosticRedactor(config.getApiKey());
            throw new LlmProviderResponseException(redactor.detail(code), redactor.detail(detail));
        }
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
