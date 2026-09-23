package com.casc.unittestagent.llm;

/**
 * UnitMind 大模型调用配置。
 * API Key 不应硬编码；运行时优先从 UNITMIND_API_KEY 读取，其次从 IDEA PasswordSafe 读取。
 */
public class LlmConfig {
    /** 整类测试代码负载可能超过 12000 token（见 real track 的 class-real-004 截断），默认给足输出预算。 */
    public static final int DEFAULT_MAX_TOKENS = 24000;
    public static final int MAX_TOKENS_UPPER_BOUND = 65536;
    /**
     * 思考模式下输出预算下限：推理内容会与最终代码共享 max_tokens 预算，
     * 低于该值时请求会以 finish_reason=length 截断，代码负载不完整。
     */
    public static final int THINKING_MIN_OUTPUT_TOKENS = 32768;

    private boolean enabled;
    private String apiUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 180;
    private int maxTokens = DEFAULT_MAX_TOKENS;
    private int maxCompileRepairRounds = 5;
    private double temperature = 0.2;
    private boolean fallbackToRuleBased = true;
    /** 思考模式：不再强制关闭模型思考、不发送 JSON 模式参数，并抬高输出预算下限。 */
    private boolean thinkingMode;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        // OkHttp 中 0 表示无限超时（连接挂死会拖满整轮生成），负数会在构造客户端时抛异常；
        // 与 UI spinner 的 1-600 下限保持一致。
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(256, Math.min(MAX_TOKENS_UPPER_BOUND, maxTokens));
    }

    public int getMaxCompileRepairRounds() {
        return maxCompileRepairRounds;
    }

    public void setMaxCompileRepairRounds(int maxCompileRepairRounds) {
        this.maxCompileRepairRounds = Math.max(0, Math.min(30, maxCompileRepairRounds));
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isFallbackToRuleBased() {
        return fallbackToRuleBased;
    }

    public void setFallbackToRuleBased(boolean fallbackToRuleBased) {
        this.fallbackToRuleBased = fallbackToRuleBased;
    }

    public boolean isThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(boolean thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    public String maskedApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String key = apiKey.trim();
        if (key.length() <= 4) {
            return "****";
        }
        return "sk-****" + key.substring(key.length() - 4);
    }
}
