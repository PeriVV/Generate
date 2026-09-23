package com.casc.unittestagent.llm;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;

/**
 * 读取和保存 UnitMind 大模型配置。
 * 普通配置保存到 PropertiesComponent，API Key 保存到 PasswordSafe 或环境变量。
 */
public final class LlmConfigManager {
    private static final String PREFIX = "unitmind.llm.";
    private static final String ENABLED = PREFIX + "enabled";
    private static final String API_URL = PREFIX + "apiUrl";
    private static final String MODEL = PREFIX + "model";
    private static final String TIMEOUT_SECONDS = PREFIX + "timeoutSeconds";
    private static final String MAX_TOKENS = PREFIX + "maxTokens";
    private static final String MAX_COMPILE_REPAIR_ROUNDS = PREFIX + "maxCompileRepairRounds";
    private static final String TEMPERATURE = PREFIX + "temperature";
    private static final String FALLBACK = PREFIX + "fallbackToRuleBased";
    private static final String THINKING_MODE = PREFIX + "thinkingMode";
    private static final String PRIVACY_CONFIRMED = PREFIX + "privacyConfirmed";
    private static final String API_KEY_ENV = "UNITMIND_API_KEY";
    private static final String API_KEY_SERVICE = "UnitMind LLM API Key";

    private LlmConfigManager() {
    }

    public static LlmConfig load() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        LlmConfig config = new LlmConfig();
        config.setEnabled(properties.getBoolean(ENABLED, false));
        config.setApiUrl(properties.getValue(API_URL, ""));
        config.setModel(properties.getValue(MODEL, "gpt-4o-mini"));
        config.setTimeoutSeconds(parseInt(properties.getValue(TIMEOUT_SECONDS), 180));
        config.setMaxTokens(parseInt(properties.getValue(MAX_TOKENS), LlmConfig.DEFAULT_MAX_TOKENS));
        config.setMaxCompileRepairRounds(parseInt(properties.getValue(MAX_COMPILE_REPAIR_ROUNDS), 5));
        config.setTemperature(parseDouble(properties.getValue(TEMPERATURE), 0.2));
        config.setFallbackToRuleBased(properties.getBoolean(FALLBACK, true));
        config.setThinkingMode(properties.getBoolean(THINKING_MODE, false));
        config.setApiKey(resolveApiKey());
        return config;
    }

    public static void save(LlmConfig config, String apiKeyToStore) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.setValue(ENABLED, Boolean.toString(config.isEnabled()));
        properties.setValue(API_URL, nullToEmpty(config.getApiUrl()));
        properties.setValue(MODEL, nullToEmpty(config.getModel()));
        properties.setValue(TIMEOUT_SECONDS, Integer.toString(config.getTimeoutSeconds()));
        properties.setValue(MAX_TOKENS, Integer.toString(config.getMaxTokens()));
        properties.setValue(MAX_COMPILE_REPAIR_ROUNDS,
                Integer.toString(config.getMaxCompileRepairRounds()));
        properties.setValue(TEMPERATURE, Double.toString(config.getTemperature()));
        properties.setValue(FALLBACK, Boolean.toString(config.isFallbackToRuleBased()));
        properties.setValue(THINKING_MODE, Boolean.toString(config.isThinkingMode()));
        if (apiKeyToStore != null && !apiKeyToStore.isBlank()) {
            PasswordSafe.getInstance().set(credentialsAttributes(), new Credentials("UnitMind", apiKeyToStore.trim()));
        }
    }

    public static boolean isPrivacyConfirmed() {
        return PropertiesComponent.getInstance().getBoolean(PRIVACY_CONFIRMED, false);
    }

    public static void setPrivacyConfirmed(boolean confirmed) {
        PropertiesComponent.getInstance().setValue(PRIVACY_CONFIRMED, Boolean.toString(confirmed));
    }

    public static String apiKeyEnvironmentName() {
        return API_KEY_ENV;
    }

    private static String resolveApiKey() {
        String fromEnv = System.getenv(API_KEY_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        Credentials credentials = PasswordSafe.getInstance().get(credentialsAttributes());
        return credentials == null ? "" : nullToEmpty(credentials.getPasswordAsString());
    }

    private static CredentialAttributes credentialsAttributes() {
        return new CredentialAttributes(API_KEY_SERVICE);
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static double parseDouble(String value, double defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
