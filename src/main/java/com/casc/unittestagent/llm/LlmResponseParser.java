package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.SourceBugAnalysis;
import com.casc.unittestagent.model.SourceBugLocation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析大模型返回的结构化 JSON。
 */
public class LlmResponseParser {
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "(?s).*\\b(class|interface|record|enum)\\s+[A-Za-z_$][A-Za-z0-9_$]*.*");

    public LlmGenerationResponse parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new LlmResponseFormatException("大模型返回了空内容。");
        }
        JSONObject root = findJsonObject(rawText, "testCode");

        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRawResponse(rawText);
        if (root != null) {
            response.setTestCode(root.optString("testCode", ""));
            response.setWarnings(warnings(root.optJSONArray("warnings")));
            response.setMockStrategy(mockStrategy(root.optJSONArray("mockStrategies")));
            response.setRepairScope(root.optString("repairScope", "METHOD_ONLY"));
            parseDiagnosis(root.optJSONObject("diagnosis"), response);
            response.setEdits(edits(root.optJSONArray("edits")));
        } else {
            response.setTestCode(extractJavaSource(rawText));
        }
        if (response.getTestCode() == null || response.getTestCode().isBlank()) {
            throw new LlmResponseFormatException("大模型未返回可解析的 JSON 或完整 Java 测试类。");
        }
        return response;
    }

    /**
     * 覆盖率闭环的响应协议。运行修复可以只返回 methodPatches，
     * 编译修复与覆盖探索仍返回完整 testCode。
     */
    public LlmGenerationResponse parseCoverage(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new LlmResponseFormatException("大模型返回了空内容。");
        }
        JSONObject root = findJsonObject(rawText, null);
        if (root == null || (!root.has("testCode") && !root.has("methodPatches")
                && !hasTerminalRuntimeDisposition(root))) {
            throw new LlmResponseFormatException("覆盖率修复未返回 testCode 或 methodPatches。");
        }
        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRawResponse(rawText);
        response.setTestCode(root.optString("testCode", ""));
        response.setWarnings(warnings(root.optJSONArray("warnings")));
        response.setRepairScope(root.optString("repairScope", "METHOD_ONLY"));
        parseDiagnosis(root.optJSONObject("diagnosis"), response);
        response.setEdits(edits(root.optJSONArray("edits")));
        response.setMethodPatches(methodPatches(root.optJSONArray("methodPatches")));
        if ((response.getTestCode() == null || response.getTestCode().isBlank())
                && response.getMethodPatches().isEmpty()
                && !isTerminalRuntimeDisposition(response.getRepairDisposition())) {
            throw new LlmResponseFormatException("覆盖率修复的代码负载为空。");
        }
        if (response.hasMethodPatches() || !response.getRepairDisposition().isBlank()) {
            validateCoverageRuntimeContract(response);
        }
        return response;
    }

    /** 整类运行修复专用协议，允许疑似 SUT Bug 时只返回诊断而不改代码。 */
    public LlmGenerationResponse parseClassRepair(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new LlmResponseFormatException("大模型返回了空内容。");
        }
        JSONObject root = findJsonObject(rawText, null);
        if (root == null || (!root.has("testCode") && !hasTerminalRuntimeDisposition(root))) {
            throw new LlmResponseFormatException("整类运行修复未返回 testCode 或终止诊断。");
        }
        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRawResponse(rawText);
        response.setTestCode(root.optString("testCode", ""));
        response.setWarnings(warnings(root.optJSONArray("warnings")));
        response.setRepairScope(root.optString("repairScope", "METHOD_ONLY"));
        parseDiagnosis(root.optJSONObject("diagnosis"), response);
        response.setEdits(edits(root.optJSONArray("edits")));
        validateRuntimeDiagnosis(response);
        if ("REPAIR_TEST".equals(response.getRepairDisposition())
                && response.getTestCode().isBlank()) {
            throw new LlmResponseFormatException("REPAIR_TEST 必须返回完整 testCode。");
        }
        if (isTerminalRuntimeDisposition(response.getRepairDisposition())
                && !response.getTestCode().isBlank()) {
            throw new LlmResponseFormatException("终止诊断不得携带 testCode 修改。");
        }
        return response;
    }

    /** 严格解析只读的疑似源码缺陷归因响应。 */
    public SourceBugAnalysis parseSourceBugAnalysis(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new LlmResponseFormatException("源码缺陷归因返回了空内容。");
        }
        JSONObject root = findJsonObject(rawText, null);
        if (root == null) {
            throw new LlmResponseFormatException("源码缺陷归因未返回 JSON 对象。");
        }
        java.util.Set<String> allowed = java.util.Set.of("summary", "confidence",
                "suspectedLocations", "hypotheses", "evidence", "recommendedChecks");
        for (String key : root.keySet()) {
            if (!allowed.contains(key)) {
                throw new LlmResponseFormatException("源码缺陷归因包含禁止字段：" + key);
            }
        }
        String summary = root.optString("summary", "").strip();
        String confidence = root.optString("confidence", "").strip().toUpperCase(java.util.Locale.ROOT);
        List<String> evidence = limitedStrings(root.optJSONArray("evidence"), 8);
        List<String> recommendedChecks = limitedStrings(root.optJSONArray("recommendedChecks"), 8);
        if (summary.isBlank() || !java.util.Set.of("HIGH", "MEDIUM", "LOW").contains(confidence)
                || evidence.isEmpty() || recommendedChecks.isEmpty()) {
            throw new LlmResponseFormatException("源码缺陷归因缺少 summary、合法 confidence、evidence 或 recommendedChecks。");
        }
        List<SourceBugLocation> locations = new ArrayList<>();
        JSONArray locationArray = root.optJSONArray("suspectedLocations");
        if (locationArray != null) {
            for (int index = 0; index < Math.min(5, locationArray.length()); index++) {
                JSONObject item = locationArray.optJSONObject(index);
                if (item == null) continue;
                for (String key : item.keySet()) {
                    if (!java.util.Set.of("file", "line", "symbol", "reason").contains(key)) {
                        throw new LlmResponseFormatException("源码缺陷位置包含禁止字段：" + key);
                    }
                }
                SourceBugLocation location = new SourceBugLocation();
                location.setFile(item.optString("file", ""));
                location.setLine(item.optInt("line", -1));
                location.setSymbol(item.optString("symbol", ""));
                location.setReason(item.optString("reason", ""));
                if (!location.getFile().isBlank() || !location.getSymbol().isBlank()
                        || !location.getReason().isBlank()) locations.add(location);
            }
        }
        SourceBugAnalysis analysis = new SourceBugAnalysis();
        analysis.setAttempted(true);
        analysis.setAvailable(true);
        analysis.setSummary(summary);
        analysis.setConfidence(confidence);
        analysis.setSuspectedLocations(locations);
        analysis.setHypotheses(limitedStrings(root.optJSONArray("hypotheses"), 5));
        analysis.setEvidence(evidence);
        analysis.setRecommendedChecks(recommendedChecks);
        return analysis;
    }

    private List<String> limitedStrings(JSONArray array, int limit) {
        List<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int index = 0; index < Math.min(limit, array.length()); index++) {
            String value = array.optString(index, "").strip();
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }

    private void parseDiagnosis(JSONObject diagnosis, LlmGenerationResponse response) {
        if (diagnosis == null) return;
        response.setDiagnosisRootCauseCategory(diagnosis.optString("rootCauseCategory",
                diagnosis.optString("diagnosticCode", "")));
        response.setDiagnosisEvidence(warnings(diagnosis.optJSONArray("evidence")));
        response.setOracleDecision(diagnosis.optString("oracleDecision", ""));
        response.setOracleAuthority(diagnosis.optString("oracleAuthority", ""));
        response.setOracleAuthorityRef(diagnosis.optString("oracleAuthorityRef", ""));
        response.setRepairDisposition(diagnosis.optString("repairDisposition", ""));
    }

    private List<LlmCodeEdit> edits(JSONArray array) {
        List<LlmCodeEdit> values = new ArrayList<>();
        if (array == null) return values;
        for (int index = 0; index < array.length(); index++) {
            JSONObject object = array.optJSONObject(index);
            if (object == null) continue;
            LlmCodeEdit edit = new LlmCodeEdit();
            edit.setTargetMethod(object.optString("targetMethod", object.optString("target", "")));
            edit.setReason(object.optString("reason", ""));
            edit.setExpectedEffect(object.optString("expectedEffect", ""));
            values.add(edit);
        }
        return values;
    }

    private List<LlmMethodPatch> methodPatches(JSONArray array) {
        List<LlmMethodPatch> values = new ArrayList<>();
        if (array == null) return values;
        for (int index = 0; index < array.length(); index++) {
            JSONObject object = array.optJSONObject(index);
            if (object == null) continue;
            LlmMethodPatch patch = new LlmMethodPatch();
            patch.setMethodName(object.optString("methodName", ""));
            patch.setMethodCode(object.optString("methodCode", ""));
            values.add(patch);
        }
        return values;
    }

    private void validateCoverageRuntimeContract(LlmGenerationResponse response) {
        validateRuntimeDiagnosis(response);
        boolean terminal = isTerminalRuntimeDisposition(response.getRepairDisposition());
        if (terminal && response.hasMethodPatches()) {
            throw new LlmResponseFormatException("终止诊断不得携带 methodPatches。");
        }
        if ("REPAIR_TEST".equals(response.getRepairDisposition()) && !response.hasMethodPatches()) {
            throw new LlmResponseFormatException("REPAIR_TEST 必须返回非空 methodPatches。");
        }
        if (!response.hasMethodPatches()) return;
        validateCoveragePatchContract(response);
    }

    private void validateRuntimeDiagnosis(LlmGenerationResponse response) {
        if (response.getDiagnosisRootCauseCategory().isBlank()
                || response.getOracleDecision().isBlank()
                || response.getOracleAuthority().isBlank()
                || response.getOracleAuthorityRef().isBlank()
                || response.getRepairDisposition().isBlank()
                || response.getDiagnosisEvidence().isEmpty()) {
            throw new LlmResponseFormatException("运行修复缺少完整 diagnosis（rootCauseCategory/evidence/"
                    + "oracleDecision/oracleAuthority/oracleAuthorityRef/repairDisposition）。");
        }
        if (!java.util.Set.of("KEEP", "CORRECT").contains(response.getOracleDecision())) {
            throw new LlmResponseFormatException("oracleDecision 只能是 KEEP 或 CORRECT。");
        }
        if (!java.util.Set.of("USER_REQUIREMENT", "EXISTING_TEST", "CONTRACT_FACT",
                "PRODUCTION_SOURCE", "SCENARIO", "RUNTIME_ACTUAL", "NONE")
                .contains(response.getOracleAuthority())) {
            throw new LlmResponseFormatException("oracleAuthority 不在允许枚举中。");
        }
        if (!java.util.Set.of("REPAIR_TEST", "SUSPECTED_SUT_BUG", "INCONCLUSIVE")
                .contains(response.getRepairDisposition())) {
            throw new LlmResponseFormatException("repairDisposition 不在允许枚举中。");
        }
        if (isTerminalRuntimeDisposition(response.getRepairDisposition())
                && !"KEEP".equals(response.getOracleDecision())) {
            throw new LlmResponseFormatException("终止诊断必须 KEEP 原 Oracle。");
        }
        if ("SUSPECTED_SUT_BUG".equals(response.getRepairDisposition())
                && !java.util.Set.of("USER_REQUIREMENT", "EXISTING_TEST", "CONTRACT_FACT")
                .contains(response.getOracleAuthority())) {
            throw new LlmResponseFormatException("SUSPECTED_SUT_BUG 必须有独立契约权威来源。");
        }
        if ("CORRECT".equals(response.getOracleDecision())
                && !"REPAIR_TEST".equals(response.getRepairDisposition())) {
            throw new LlmResponseFormatException("CORRECT 只能与 REPAIR_TEST 一起返回。");
        }
    }

    private void validateCoveragePatchContract(LlmGenerationResponse response) {
        java.util.Set<String> patched = new java.util.LinkedHashSet<>();
        for (LlmMethodPatch patch : response.getMethodPatches()) {
            if (patch.getMethodName().isBlank() || patch.getMethodCode().isBlank()) {
                throw new LlmResponseFormatException("methodPatches 必须同时包含 methodName 和 methodCode。");
            }
            if (!patched.add(patch.getMethodName())) {
                throw new LlmResponseFormatException("methodPatches 包含重复目标：" + patch.getMethodName());
            }
        }
        java.util.Set<String> edited = new java.util.LinkedHashSet<>();
        for (LlmCodeEdit edit : response.getEdits()) {
            if (edit.getTargetMethod().isBlank() || edit.getReason().isBlank()
                    || edit.getExpectedEffect().isBlank()) {
                throw new LlmResponseFormatException("edits 必须完整说明 targetMethod/reason/expectedEffect。");
            }
            edited.add(edit.getTargetMethod());
        }
        if (!edited.containsAll(patched)) {
            throw new LlmResponseFormatException("每个 methodPatch 都必须有同名 edits 项。");
        }
    }

    private boolean hasTerminalRuntimeDisposition(JSONObject root) {
        JSONObject diagnosis = root == null ? null : root.optJSONObject("diagnosis");
        return diagnosis != null && isTerminalRuntimeDisposition(
                diagnosis.optString("repairDisposition", ""));
    }

    private boolean isTerminalRuntimeDisposition(String value) {
        return "SUSPECTED_SUT_BUG".equals(value) || "INCONCLUSIVE".equals(value);
    }

    public LlmGenerationResponse parseMockStrategyOnly(String rawText) {
        if (rawText == null || rawText.isBlank()) throw new LlmResponseFormatException("大模型返回了空内容。");
        JSONObject root = findJsonObject(rawText, "mockStrategies");
        if (root == null) throw new LlmResponseFormatException("大模型未返回可解析的 Mock 策略 JSON。");

        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRawResponse(rawText);
        response.setWarnings(warnings(root.optJSONArray("warnings")));
        response.setMockStrategy(mockStrategy(root.optJSONArray("mockStrategies")));
        if (response.getMockStrategy() == null
                || response.getMockStrategy().getStrategyItems() == null
                || response.getMockStrategy().getStrategyItems().isEmpty()) {
            throw new LlmResponseFormatException("大模型未返回可展示的 Mock 策略。");
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
            if (enabled && isFieldMock(object.optString("dependencyType", ""), mockitoStrategy) && !target.isBlank()) {
                mockFields.add(target);
            }
        }
        strategy.setStrategyItems(items);
        strategy.setMockFields(mockFields);
        return strategy;
    }

    private boolean isFieldMock(String dependencyType, String mockitoStrategy) {
        if (!"mock".equalsIgnoreCase(mockitoStrategy)) {
            return false;
        }
        String type = dependencyType == null ? "" : dependencyType.toLowerCase();
        return !type.contains("参数") && !type.contains("dto") && !type.contains("数据对象") && !type.contains("局部");
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

    private JSONObject findJsonObject(String text, String requiredKey) {
        for (String candidate : balancedObjects(text)) {
            try {
                JSONObject object = new JSONObject(candidate);
                if (requiredKey == null || object.has(requiredKey)) return object;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 提取所有平衡的 JSON 对象，忽略字符串字面量中的大括号。 */
    private List<String> balancedObjects(String text) {
        List<String> objects = new ArrayList<>();
        if (text == null) return objects;
        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '{') continue;
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int index = start; index < text.length(); index++) {
                char current = text.charAt(index);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (current == '\\') escaped = true;
                    else if (current == '"') quoted = false;
                    continue;
                }
                if (current == '"') quoted = true;
                else if (current == '{') depth++;
                else if (current == '}' && --depth == 0) {
                    objects.add(text.substring(start, index + 1));
                    start = index;
                    break;
                }
            }
        }
        return objects;
    }

    private String extractJavaSource(String text) {
        int cursor = 0;
        while (cursor < text.length()) {
            int fence = text.indexOf("```", cursor);
            if (fence < 0) break;
            int lineEnd = text.indexOf('\n', fence + 3);
            if (lineEnd < 0) break;
            int end = text.indexOf("```", lineEnd + 1);
            if (end < 0) break;
            String language = text.substring(fence + 3, lineEnd).trim();
            String content = text.substring(lineEnd + 1, end).trim();
            if ((language.isBlank() || "java".equalsIgnoreCase(language)) && looksLikeJava(content)) {
                return content;
            }
            cursor = end + 3;
        }
        String trimmed = text.trim();
        return looksLikeJava(trimmed) ? trimmed : "";
    }

    private boolean looksLikeJava(String text) {
        return text != null && text.indexOf('{') >= 0 && text.lastIndexOf('}') > text.indexOf('{')
                && JAVA_TYPE.matcher(text).matches();
    }
}
