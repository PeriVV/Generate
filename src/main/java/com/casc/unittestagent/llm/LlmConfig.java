package com.casc.unittestagent.llm;

/**
 * UnitMind 大模型调用配置。
 * API Key 不应硬编码；运行时优先从 UNITMIND_API_KEY 读取，其次从 IDEA PasswordSafe 读取。
 */
public class LlmConfig {
    private boolean enabled;
    private String apiUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 180;
    private int maxTokens = 12000;
    private int maxCompileRepairRounds = 5;
    private double temperature = 0.2;
    private boolean fallbackToRuleBased = true;

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
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
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
