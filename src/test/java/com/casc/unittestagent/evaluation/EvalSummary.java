package com.casc.unittestagent.evaluation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.OptionalDouble;
import java.util.function.Predicate;

/** Deterministic aggregation over checkpoint JSON records. */
public final class EvalSummary {
    private EvalSummary() {}

    public static JSONObject aggregate(List<JSONObject> attempts,
                                       EvalRunMetadata metadata,
                                       int selectedCases) {
        JSONObject summary = new JSONObject();
        summary.put("metadata", metadata.toJson());
        summary.put("selectedCases", selectedCases);
        summary.put("attempts", attempts.size());
        summary.put("overall", metrics(attempts, ignored -> true));
        summary.put("method", metrics(attempts, item -> item.optString("scope").equals("METHOD")));
        summary.put("class", metrics(attempts, item -> item.optString("scope").equals("CLASS")));
        summary.put("byDifficulty", grouped(attempts, "difficulty"));
        summary.put("bySourceKind", grouped(attempts, "sourceKind"));
        summary.put("byTag", tagGroups(attempts));
        summary.put("stability", stability(attempts));
        summary.put("failureReasons", failureReasons(attempts));
        summary.put("compileDiagnosticDistribution", arrayValueCounts(attempts, "compileDiagnosticCodes"));
        summary.put("runtimeDiagnosticDistribution", arrayValueCounts(attempts, "runtimeDiagnosticTypes"));
        return summary;
    }

    private static JSONObject metrics(List<JSONObject> source, Predicate<JSONObject> filter) {
        List<JSONObject> items = source.stream().filter(filter).toList();
        List<JSONObject> methodItems = items.stream()
                .filter(item -> item.optString("scope").equals("METHOD")).toList();
        int total = items.size();
        JSONObject result = new JSONObject();
        result.put("attempts", total);
        result.put("environmentErrors", count(items, item -> item.optBoolean("environmentError")));
        result.put("llmGenerated", rate(items, item -> item.optBoolean("llmGenerated")));
        result.put("deliveredCode", rate(items, item -> item.optBoolean("deliveredCode")));
        result.put("ruleFallback", rate(items, item -> item.optBoolean("ruleFallback")));
        result.put("compileAtOne", rate(items, item -> item.optBoolean("compileAtOne")));
        result.put("compileAtBudget", rate(items, item -> item.optBoolean("compileAtBudget")));
        result.put("compileRepairGain", rate(items, item -> !item.optBoolean("compileAtOne")
                && item.optBoolean("compileAtBudget")));
        result.put("runSuccessful", rate(items, item -> item.optBoolean("runSuccessful")));
        result.put("conditionalCompileAtOne", conditionalRate(items,
                item -> !item.optBoolean("environmentError"), item -> item.optBoolean("compileAtOne")));
        result.put("conditionalCompileAtBudget", conditionalRate(items,
                item -> !item.optBoolean("environmentError"), item -> item.optBoolean("compileAtBudget")));
        result.put("conditionalRunSuccessful", conditionalRate(items,
                item -> item.optBoolean("compileAtBudget"), item -> item.optBoolean("runSuccessful")));
        // CLASS coverage is deliberately NOT_RUN; exclude it instead of reporting a false failure.
        result.put("coverageAvailable", rate(methodItems, item -> item.optBoolean("coverageAvailable")));
        result.put("coverageTargetReached", rate(methodItems,
                item -> item.optString("coverageStatus").equals("PASSED")));
        result.put("classComplete", rate(items, item -> item.optBoolean("classComplete")));
        result.put("lineRateMacro", average(methodItems, item -> item.optBoolean("coverageAvailable"), "lineRate"));
        result.put("branchRateMacro", average(methodItems, item -> item.optBoolean("branchApplicable"), "branchRate"));
        result.put("line90Rate", rate(methodItems, item -> item.optBoolean("coverageAvailable")
                && item.optDouble("lineRate", 0d) >= 0.90d));
        result.put("branch80Rate", conditionalRate(methodItems,
                item -> !item.optString("branchBand").equals("NONE"),
                item -> item.optBoolean("branchApplicable") && item.optDouble("branchRate", 0d) >= 0.80d));
        result.put("lineCoverageGainMacro", averageDelta(methodItems,
                "firstRunnableLineRate", "lineRate"));
        result.put("branchCoverageGainMacro", averageDelta(methodItems,
                "firstRunnableBranchRate", "branchRate"));
        result.put("averageCompileRepairs", averageNumber(items, "compileRepairAttempts"));
        result.put("averageModelRequests", averageNumber(items, "modelRequests"));
        result.put("averageModelDurationMillis", averageNumber(items, "modelDurationMillis"));
        result.put("totalModelRequests", items.stream().mapToInt(item -> item.optInt("modelRequests")).sum());
        for (String phase : List.of("generation", "compileRepair", "coverageExploration",
                "coverageCompileRepair", "runtimeRepair", "classRuntimeRepair", "mockPlanning")) {
            result.put(phase + "Requests", items.stream()
                    .mapToInt(item -> item.optInt(phase + "Requests")).sum());
            result.put(phase + "DurationMillis", items.stream()
                    .mapToLong(item -> item.optLong(phase + "DurationMillis")).sum());
        }
        List<Long> durations = items.stream().map(item -> item.optLong("durationMillis"))
                .sorted().toList();
        result.put("durationP50Millis", percentile(durations, 0.50d));
        result.put("durationP95Millis", percentile(durations, 0.95d));
        List<Long> modelDurations = items.stream().map(item -> item.optLong("modelDurationMillis"))
                .sorted().toList();
        result.put("modelDurationP50Millis", percentile(modelDurations, 0.50d));
        result.put("modelDurationP95Millis", percentile(modelDurations, 0.95d));
        result.put("coverageCompileFailures", sum(items, "coverageCompileFailures"));
        result.put("coverageRuntimeFailures", sum(items, "coverageRuntimeFailures"));
        result.put("coverageNoProgressCandidates", sum(items, "coverageNoProgressCandidates"));
        result.put("coverageRuntimeNoProgressCandidates", sum(items, "coverageRuntimeNoProgressCandidates"));
        result.put("coverageUnchangedResponses", sum(items, "coverageUnchangedResponses"));
        result.put("coverageTransportRetries", sum(items, "coverageTransportRetries"));
        result.put("coverageFormatRetries", sum(items, "coverageFormatRetries"));
        result.put("coverageModelUnchangedResponses", sum(items, "coverageModelUnchangedResponses"));
        result.put("coverageDiscardedChanges", sum(items, "coverageDiscardedChanges"));
        result.put("coverageStopReasons", groupedCounts(items, "coverageStopReason"));
        List<JSONObject> classItems = items.stream()
                .filter(item -> item.optString("scope").equals("CLASS")).toList();
        result.put("classJUnitAtOne", rate(classItems, item -> item.optBoolean("classJUnitAtOne")));
        result.put("classJUnitAtBudget", rate(classItems, item -> item.optBoolean("runSuccessful")));
        result.put("classJUnitRepairGain", rate(classItems, item -> !item.optBoolean("classJUnitAtOne")
                && item.optBoolean("runSuccessful")));
        result.put("classJUnitStopReasons", groupedCounts(classItems, "classJUnitStopReason"));
        result.put("classRuntimeRepairRounds", sum(classItems, "classRuntimeRepairRounds"));
        result.put("classCompileRepairRounds", sum(classItems, "classCompileRepairRounds"));
        result.put("classFixtureRepairRounds", sum(classItems, "classFixtureRepairRounds"));
        result.put("classRegressionRejections", sum(classItems, "classRegressionRejections"));
        int methods = items.stream().mapToInt(item -> item.optInt("classMethods")).sum();
        result.put("classMethods", methods);
        result.put("classMethodAcceptanceRate", methods == 0 ? JSONObject.NULL
                : (double) items.stream().mapToInt(item -> item.optInt("classAcceptedMethods")).sum() / methods);
        result.put("classLlmMethodRate", methods == 0 ? JSONObject.NULL
                : (double) items.stream().mapToInt(item -> item.optInt("classLlmMethods")).sum() / methods);
        result.put("classFallbackMethodRate", methods == 0 ? JSONObject.NULL
                : (double) items.stream().mapToInt(item -> item.optInt("classFallbackMethods")).sum() / methods);
        result.put("classMethodIsolationFailureRate", methods == 0 ? JSONObject.NULL
                : (double) items.stream().mapToInt(item -> item.optInt("classFailedMethods")).sum() / methods);
        result.put("averageClassDurationMillis", classItems.isEmpty() ? JSONObject.NULL
                : classItems.stream().mapToLong(item -> item.optLong("durationMillis")).average().orElse(0d));
        return result;
    }

    private static JSONObject grouped(List<JSONObject> attempts, String field) {
        Map<String, List<JSONObject>> groups = new TreeMap<>();
        for (JSONObject item : attempts) {
            groups.computeIfAbsent(item.optString(field, "UNKNOWN"), ignored -> new ArrayList<>()).add(item);
        }
        JSONObject result = new JSONObject();
        groups.forEach((key, value) -> result.put(key, metrics(value, ignored -> true)));
        return result;
    }

    private static JSONObject tagGroups(List<JSONObject> attempts) {
        Map<String, List<JSONObject>> groups = new TreeMap<>();
        for (JSONObject item : attempts) {
            JSONArray tags = item.optJSONArray("tags");
            if (tags == null) continue;
            for (int i = 0; i < tags.length(); i++) {
                groups.computeIfAbsent(tags.getString(i), ignored -> new ArrayList<>()).add(item);
            }
        }
        JSONObject result = new JSONObject();
        groups.forEach((key, value) -> result.put(key, metrics(value, ignored -> true)));
        return result;
    }

    private static JSONObject stability(List<JSONObject> attempts) {
        Map<String, int[]> grouped = new LinkedHashMap<>();
        for (JSONObject item : attempts) {
            int[] counts = grouped.computeIfAbsent(item.getString("caseId"), ignored -> new int[2]);
            counts[0]++;
            if (item.optBoolean("successful")) counts[1]++;
        }
        JSONObject distribution = new JSONObject();
        JSONArray cases = new JSONArray();
        for (Map.Entry<String, int[]> entry : grouped.entrySet()) {
            String key = entry.getValue()[1] + "/" + entry.getValue()[0];
            distribution.put(key, distribution.optInt(key) + 1);
            cases.put(new JSONObject().put("caseId", entry.getKey())
                    .put("successfulAttempts", entry.getValue()[1])
                    .put("attempts", entry.getValue()[0]));
        }
        return new JSONObject().put("distribution", distribution).put("cases", cases);
    }

    private static JSONObject failureReasons(List<JSONObject> attempts) {
        Map<String, Integer> counts = new TreeMap<>();
        for (JSONObject item : attempts) {
            if (!item.optString("fatalError").isBlank()) increment(counts, "RUNNER_ERROR");
            else if (item.optBoolean("environmentError")) increment(counts, "ENVIRONMENT_ERROR");
            else if (!item.optBoolean("deliveredCode")) increment(counts, "NO_CODE");
            else if (!item.optBoolean("compileAtBudget")) increment(counts, "COMPILE_FAILED");
            else if (!item.optBoolean("runSuccessful")) increment(counts, "TEST_FAILED");
            else if (item.optString("scope").equals("METHOD") && !item.optBoolean("coverageAvailable"))
                increment(counts, "COVERAGE_UNAVAILABLE");
            else if (item.optString("scope").equals("METHOD")
                    && !item.optString("coverageStatus").equals("PASSED"))
                increment(counts, "BELOW_THRESHOLD");
        }
        return new JSONObject(counts);
    }

    private static JSONObject rate(List<JSONObject> items, Predicate<JSONObject> predicate) {
        int count = count(items, predicate);
        return new JSONObject().put("count", count)
                .put("total", items.size())
                .put("rate", items.isEmpty() ? JSONObject.NULL : (double) count / items.size());
    }

    private static JSONObject conditionalRate(List<JSONObject> items,
                                              Predicate<JSONObject> eligible,
                                              Predicate<JSONObject> passed) {
        List<JSONObject> selected = items.stream().filter(eligible).toList();
        return rate(selected, passed);
    }

    private static int count(List<JSONObject> items, Predicate<JSONObject> predicate) {
        return (int) items.stream().filter(predicate).count();
    }

    private static Object average(List<JSONObject> items,
                                  Predicate<JSONObject> eligible,
                                  String field) {
        List<JSONObject> selected = items.stream().filter(eligible).toList();
        return selected.isEmpty() ? JSONObject.NULL
                : selected.stream().mapToDouble(item -> item.optDouble(field)).average().orElse(0d);
    }

    private static Object averageNumber(List<JSONObject> items, String field) {
        return items.isEmpty() ? JSONObject.NULL
                : items.stream().mapToDouble(item -> item.optDouble(field)).average().orElse(0d);
    }

    private static Object averageDelta(List<JSONObject> items, String firstField, String finalField) {
        OptionalDouble average = items.stream()
                .filter(item -> item.opt(firstField) instanceof Number && item.opt(finalField) instanceof Number)
                .mapToDouble(item -> ((Number) item.get(finalField)).doubleValue()
                        - ((Number) item.get(firstField)).doubleValue())
                .average();
        return average.isPresent() ? average.getAsDouble() : JSONObject.NULL;
    }

    private static long sum(List<JSONObject> items, String field) {
        return items.stream().mapToLong(item -> item.optLong(field)).sum();
    }

    private static JSONObject groupedCounts(List<JSONObject> items, String field) {
        Map<String, Integer> counts = new TreeMap<>();
        for (JSONObject item : items) {
            String value = item.optString(field, "");
            if (!value.isBlank() && !value.equals("NONE")) increment(counts, value);
        }
        return new JSONObject(counts);
    }

    private static JSONObject arrayValueCounts(List<JSONObject> items, String field) {
        Map<String, Integer> counts = new TreeMap<>();
        for (JSONObject item : items) {
            JSONArray values = item.optJSONArray(field);
            if (values == null) continue;
            for (int index = 0; index < values.length(); index++) {
                String value = values.optString(index, "");
                if (!value.isBlank()) increment(counts, value);
            }
        }
        return new JSONObject(counts);
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0L;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }
}
