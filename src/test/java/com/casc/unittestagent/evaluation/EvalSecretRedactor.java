package com.casc.unittestagent.evaluation;

import org.json.JSONArray;

import java.util.List;
import java.util.regex.Pattern;

/** Last-line defense preventing credentials from entering evaluation artifacts. */
public final class EvalSecretRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private final String apiKey;

    public EvalSecretRedactor(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String safe(String value) {
        if (value == null) return "";
        String result = value;
        if (!apiKey.isBlank()) result = result.replace(apiKey, "[REDACTED]");
        result = BEARER.matcher(result).replaceAll("Bearer [REDACTED]");
        return OPENAI_KEY.matcher(result).replaceAll("[REDACTED]");
    }

    public JSONArray safeArray(List<String> values) {
        JSONArray result = new JSONArray();
        if (values != null) for (String value : values) result.put(safe(value));
        return result;
    }
}
