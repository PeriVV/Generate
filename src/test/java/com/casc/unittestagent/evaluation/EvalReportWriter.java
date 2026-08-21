package com.casc.unittestagent.evaluation;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Writes machine-readable and compact human-readable evaluation reports. */
public final class EvalReportWriter {
    private EvalReportWriter() {}

    public static JSONObject attachTrend(JSONObject summary, Path baselineFile) throws Exception {
        JSONObject trend = new JSONObject().put("comparable", false).put("reason", "baseline not found");
        if (Files.isRegularFile(baselineFile)) {
            JSONObject baseline = new JSONObject(Files.readString(baselineFile, StandardCharsets.UTF_8));
            JSONObject currentMetadata = summary.getJSONObject("metadata");
            JSONObject baselineMetadata = baseline.optJSONObject("metadata");
            boolean comparable = baselineMetadata != null
                    && baselineMetadata.optString("model").equals(currentMetadata.optString("model"))
                    && baselineMetadata.optString("manifestHash").equals(currentMetadata.optString("manifestHash"))
                    && baselineMetadata.optString("selectionHash")
                    .equals(currentMetadata.optString("selectionHash"))
                    && baselineMetadata.optString("configHash").equals(currentMetadata.optString("configHash"));
            if (comparable) {
                trend = new JSONObject().put("comparable", true)
                        .put("method", deltas(summary.getJSONObject("method"), baseline.getJSONObject("method")))
                        .put("class", deltas(summary.getJSONObject("class"), baseline.getJSONObject("class")));
            } else {
                trend = new JSONObject().put("comparable", false)
                        .put("reason", "model, manifest, case selection, or evaluation configuration changed");
            }
        }
        summary.put("trend", trend);
        return summary;
    }

    public static void write(EvalResultStore store, JSONObject summary) throws Exception {
        store.write("summary.json", summary.toString(2));
        String markdown = markdown(summary);
        store.write("summary.md", markdown);
        store.write("summary.html", html(summary, markdown));
    }

    public static void promote(JSONObject summary, Path baselineFile) throws Exception {
        Files.createDirectories(baselineFile.getParent());
        Files.writeString(baselineFile, summary.toString(2), StandardCharsets.UTF_8);
    }

    static String markdown(JSONObject summary) {
        JSONObject metadata = summary.getJSONObject("metadata");
        StringBuilder out = new StringBuilder();
        out.append("# UnitMind evaluation\n\n")
                .append("- Run: `").append(metadata.optString("runId")).append("`\n")
                .append("- Profile: `").append(metadata.optString("profile")).append("`\n")
                .append("- Model: `").append(metadata.optString("model")).append("`\n")
                .append("- Cases: ").append(summary.optInt("selectedCases"))
                .append(", attempts: ").append(summary.optInt("attempts")).append("\n\n");
        out.append("## Overall funnel\n\n");
        appendFunnel(out, summary.getJSONObject("overall"));
        out.append("\n## Method track\n\n");
        appendTrack(out, summary.getJSONObject("method"), true);
        out.append("\n## Class track\n\n");
        appendTrack(out, summary.getJSONObject("class"), false);
        out.append("\n## Difficulty breakdown\n\n");
        appendGroups(out, summary.getJSONObject("byDifficulty"));
        out.append("\n## Capability tag breakdown\n\n");
        appendGroups(out, summary.getJSONObject("byTag"));
        out.append("\n## Failure reasons\n\n");
        JSONObject failures = summary.getJSONObject("failureReasons");
        if (failures.isEmpty()) out.append("No failures recorded.\n");
        else appendRanking(out, failures);
        JSONObject compileDiagnostics = summary.getJSONObject("compileDiagnosticDistribution");
        if (!compileDiagnostics.isEmpty()) {
            out.append("\n### Compile diagnostics\n\n");
            appendRanking(out, compileDiagnostics);
        }
        JSONObject runtimeDiagnostics = summary.getJSONObject("runtimeDiagnosticDistribution");
        if (!runtimeDiagnostics.isEmpty()) {
            out.append("\n### Runtime diagnostics\n\n");
            appendRanking(out, runtimeDiagnostics);
        }
        if (metadata.optInt("attemptsPerCase") == 3) {
            out.append("\n## Release stability\n\n");
            JSONObject distribution = summary.getJSONObject("stability").getJSONObject("distribution");
            for (String key : List.of("3/3", "2/3", "1/3", "0/3")) {
                out.append("- ").append(key).append(": ").append(distribution.optInt(key)).append(" cases\n");
            }
        }
        JSONObject trend = summary.getJSONObject("trend");
        out.append("\n## Trend\n\n");
        if (!trend.optBoolean("comparable")) {
            out.append("Not comparable: ").append(trend.optString("reason")).append(".\n");
        } else {
            out.append("Comparable with the frozen baseline. See `summary.json` for all deltas.\n");
        }
        return out.toString();
    }

    private static void appendFunnel(StringBuilder out, JSONObject track) {
        out.append("| Stage | Result |\n|---|---:|\n")
                .append(row("LLM generated", rate(track, "llmGenerated")))
                .append(row("Delivered code", rate(track, "deliveredCode")))
                .append(row("Final compile", rate(track, "compileAtBudget")))
                .append(row("JUnit successful", rate(track, "runSuccessful")));
    }

    private static void appendTrack(StringBuilder out, JSONObject track, boolean method) {
        out.append("| Metric | Result |\n|---|---:|\n")
                .append(row("Attempts", Integer.toString(track.optInt("attempts"))))
                .append(row("LLM generated", rate(track, "llmGenerated")))
                .append(row("Delivered code", rate(track, "deliveredCode")))
                .append(row("Rule fallback", rate(track, "ruleFallback")));
        if (method) {
            out.append(row("Compile@1", rate(track, "compileAtOne")))
                    .append(row("Compile@Budget", rate(track, "compileAtBudget")))
                    .append(row("Compile repair gain", rate(track, "compileRepairGain")))
                    .append(row("Conditional Compile@Budget", rate(track, "conditionalCompileAtBudget")))
                    .append(row("JUnit successful", rate(track, "runSuccessful")))
                    .append(row("Conditional JUnit successful", rate(track, "conditionalRunSuccessful")))
                    .append(row("Coverage available", rate(track, "coverageAvailable")))
                    .append(row("Coverage target reached", rate(track, "coverageTargetReached")))
                    .append(row("Line coverage macro", decimal(track, "lineRateMacro")))
                    .append(row("Branch coverage macro", decimal(track, "branchRateMacro")))
                    .append(row("90% line target", rate(track, "line90Rate")))
                    .append(row("80% branch target", rate(track, "branch80Rate")))
                    .append(row("Line gain after first runnable", signedPercent(track, "lineCoverageGainMacro")))
                    .append(row("Branch gain after first runnable", signedPercent(track, "branchCoverageGainMacro")))
                    .append(row("Coverage compile failures", Long.toString(track.optLong("coverageCompileFailures"))))
                    .append(row("Coverage runtime failures", Long.toString(track.optLong("coverageRuntimeFailures"))))
                    .append(row("No-progress candidates", Long.toString(track.optLong("coverageNoProgressCandidates"))))
                    .append(row("Repeated responses", Long.toString(track.optLong("coverageUnchangedResponses"))))
                    .append(row("Transport / format retries", track.optLong("coverageTransportRetries")
                            + " / " + track.optLong("coverageFormatRetries")));
        } else {
            out.append(row("Final class compile", rate(track, "compileAtBudget")))
                    .append(row("JUnit@1", rate(track, "classJUnitAtOne")))
                    .append(row("JUnit@Budget", rate(track, "classJUnitAtBudget")))
                    .append(row("JUnit repair gain", rate(track, "classJUnitRepairGain")))
                    .append(row("Complete class", rate(track, "classComplete")))
                    .append(row("Method acceptance", decimal(track, "classMethodAcceptanceRate")))
                    .append(row("LLM method rate", decimal(track, "classLlmMethodRate")))
                    .append(row("Fallback method rate", decimal(track, "classFallbackMethodRate")))
                    .append(row("Method isolation failure", decimal(track, "classMethodIsolationFailureRate")))
                    .append(row("Class runtime repair rounds", Long.toString(track.optLong("classRuntimeRepairRounds"))))
                    .append(row("Fixture repair rounds", Long.toString(track.optLong("classFixtureRepairRounds"))))
                    .append(row("Regression candidates rejected", Long.toString(track.optLong("classRegressionRejections"))));
        }
        out.append(row("Model requests", Integer.toString(track.optInt("totalModelRequests"))))
                .append(row("Request phases", requestPhases(track)))
                .append(row("Compile repair rounds (avg)", number(track, "averageCompileRepairs")))
                .append(row("Duration p50", track.optLong("durationP50Millis") + " ms"))
                .append(row("Duration p95", track.optLong("durationP95Millis") + " ms"))
                .append(row("Model duration p50", track.optLong("modelDurationP50Millis") + " ms"))
                .append(row("Model duration p95", track.optLong("modelDurationP95Millis") + " ms"));
    }

    private static void appendGroups(StringBuilder out, JSONObject groups) {
        out.append("| Group | Attempts | Compile@Budget | JUnit | Coverage target |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (String key : groups.keySet().stream().sorted().toList()) {
            JSONObject value = groups.getJSONObject(key);
            out.append("| ").append(key).append(" | ").append(value.optInt("attempts"))
                    .append(" | ").append(rate(value, "compileAtBudget"))
                    .append(" | ").append(rate(value, "runSuccessful"))
                    .append(" | ").append(rate(value, "coverageTargetReached"))
                    .append(" |\n");
        }
    }

    private static void appendRanking(StringBuilder out, JSONObject values) {
        values.keySet().stream()
                .sorted(Comparator.<String>comparingInt(values::getInt).reversed().thenComparing(key -> key))
                .forEach(key -> out.append("- ").append(key).append(": ")
                        .append(values.getInt(key)).append("\n"));
    }

    private static String row(String name, String value) {
        return "| " + name + " | " + value + " |\n";
    }

    private static String rate(JSONObject track, String name) {
        JSONObject value = track.getJSONObject(name);
        Object rawRate = value.opt("rate");
        String formatted = rawRate == null || rawRate == JSONObject.NULL
                ? "n/a" : percent(((Number) rawRate).doubleValue());
        return value.optInt("count") + "/" + value.optInt("total") + " (" + formatted + ")";
    }

    private static String decimal(JSONObject json, String name) {
        Object value = json.opt(name);
        return value == null || value == JSONObject.NULL ? "n/a" : percent(((Number) value).doubleValue());
    }

    private static String signedPercent(JSONObject json, String name) {
        Object value = json.opt(name);
        return value == null || value == JSONObject.NULL ? "n/a"
                : String.format(Locale.ROOT, "%+.2f pp", ((Number) value).doubleValue() * 100d);
    }

    private static String number(JSONObject json, String name) {
        Object value = json.opt(name);
        return value == null || value == JSONObject.NULL ? "n/a"
                : String.format(Locale.ROOT, "%.2f", ((Number) value).doubleValue());
    }

    private static String requestPhases(JSONObject track) {
        return "generate=" + track.optInt("generationRequests")
                + ", compile-fix=" + track.optInt("compileRepairRequests")
                + ", explore=" + track.optInt("coverageExplorationRequests")
                + ", coverage-compile-fix=" + track.optInt("coverageCompileRepairRequests")
                + ", run-fix=" + track.optInt("runtimeRepairRequests")
                + ", class-run-fix=" + track.optInt("classRuntimeRepairRequests");
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100d);
    }

    private static JSONObject deltas(JSONObject current, JSONObject baseline) {
        JSONObject result = new JSONObject();
        for (String key : List.of("llmGenerated", "deliveredCode", "ruleFallback", "compileAtOne",
                "compileAtBudget", "runSuccessful", "coverageAvailable", "coverageTargetReached", "classComplete")) {
            JSONObject now = current.optJSONObject(key);
            JSONObject before = baseline.optJSONObject(key);
            if (now != null && before != null && now.opt("rate") instanceof Number
                    && before.opt("rate") instanceof Number) {
                result.put(key, ((Number) now.get("rate")).doubleValue()
                        - ((Number) before.get("rate")).doubleValue());
            }
        }
        for (String key : List.of("lineRateMacro", "branchRateMacro", "classMethodAcceptanceRate",
                "classLlmMethodRate", "classFallbackMethodRate")) {
            if (current.opt(key) instanceof Number && baseline.opt(key) instanceof Number) {
                result.put(key, ((Number) current.get(key)).doubleValue()
                        - ((Number) baseline.get(key)).doubleValue());
            }
        }
        return result;
    }

    private static String html(JSONObject summary, String markdown) {
        String escapedJson = escape(summary.toString(2));
        String escapedMarkdown = escape(markdown);
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <title>UnitMind evaluation</title>
                <style>body{font:14px system-ui;margin:32px;max-width:1100px;color:#17202a}
                pre{white-space:pre-wrap;background:#f6f8fa;padding:18px;border-radius:8px}
                details{margin-top:24px}h1{font-size:24px}</style></head>
                <body><h1>UnitMind evaluation</h1><pre>%s</pre>
                <details><summary>Machine-readable summary</summary><pre>%s</pre></details></body></html>
                """.formatted(escapedMarkdown, escapedJson);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
