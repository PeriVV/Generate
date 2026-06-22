package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析大模型返回的结构化 JSON。
 */
public class LlmResponseParser {
    public LlmGenerationResponse parse(String rawText) {
        String jsonText = extractJson(rawText);
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("大模型返回格式无法解析，请重试。");
        }
        JSONObject root;
        try {
            root = new JSONObject(jsonText);
        } catch (Exception ex) {
            throw new IllegalArgumentException("大模型返回格式无法解析，请重试。", ex);
        }

        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRawResponse(rawText);
        response.setTestCode(root.optString("testCode", ""));
        response.setWarnings(warnings(root.optJSONArray("warnings")));
        response.setMockStrategy(mockStrategy(root.optJSONArray("mockStrategies")));
        if (response.getTestCode() == null || response.getTestCode().isBlank()) {
            throw new IllegalArgumentException("大模型返回格式无法解析，请重试。");
        }
        return response;
    }

    private MockStrategy mockStrategy(JSONArray array) {
        MockStrategy strategy = new MockStrategy();
        List<MockStrategyItem> items = new ArrayList<>();
        List<String> mockFields = new ArrayList<>();
        if (array == null) {
            strategy.setStrategyItems(items);
            return strategy;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            boolean enabled = object.optBoolean("enabled", true);
            String target = object.optString("target", "");
            String mockitoStrategy = object.optString("mockitoStrategy", "");
            items.add(new MockStrategyItem(enabled,
                    object.optString("dependencyType", ""),
                    target,
                    object.optString("method", ""),
                    object.optString("source", ""),
                    mockitoStrategy,
                    object.optString("note", "")));
            if (enabled && "mock".equalsIgnoreCase(mockitoStrategy) && !target.isBlank()) {
                mockFields.add(target);
            }
        }
        strategy.setStrategyItems(items);
        strategy.setMockFields(mockFields);
        return strategy;
    }

    private List<String> warnings(JSONArray array) {
        List<String> warnings = new ArrayList<>();
        if (array == null) {
            return warnings;
        }
        for (int i = 0; i < array.length(); i++) {
            String warning = array.optString(i, "");
            if (!warning.isBlank()) {
                warnings.add(warning);
            }
        }
        return warnings;
    }

    private String extractJson(String rawText) {
        if (rawText == null) {
            return "";
        }
        String trimmed = rawText.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        String fenced = extractFence(trimmed, "```json");
        if (!fenced.isBlank()) {
            return fenced;
        }
        fenced = extractFence(trimmed, "```");
        if (!fenced.isBlank()) {
            return fenced;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "";
    }

    private String extractFence(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int contentStart = start + marker.length();
        int end = text.indexOf("```", contentStart);
        if (end <= contentStart) {
            return "";
        }
        return text.substring(contentStart, end).trim();
    }
}
