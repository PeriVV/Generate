package com.casc.unittestagent.evaluation;

import com.casc.unittestagent.llm.LlmClient;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.ClassJUnitStopReason;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MethodGenerationStatus;
import com.casc.unittestagent.model.TestExecutionSummary;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class EvalInfrastructureTest extends TestCase {
    public void testFrozenDatasetContractAndPreflight() throws Exception {
        List<EvalCaseManifest> cases = EvalCaseLoader.load(projectRoot().resolve("evaluation/cases"));

        EvalDatasetContract.verify(cases);
        EvalPreflight.verifyAll(cases);

        assertEquals(60, cases.size());
        assertEquals(12, EvalCaseLoader.select(cases, "smoke", "").size());
        assertEquals(60, EvalCaseLoader.select(cases, "regular", "").size());
        assertEquals(1, EvalCaseLoader.select(cases, "regular", "mock-007").size());
        assertEquals(64, EvalCaseLoader.fingerprint(cases).length());
    }

    public void testSummaryUsesEndToEndDenominatorsAndExcludesNonApplicableBranches() {
        JSONObject first = attempt("a", true, 0.80d, true, 0.50d);
        JSONObject second = attempt("b", false, 1.0d, false, 0d);
        EvalRunMetadata metadata = new EvalRunMetadata("run", "regular", 1, "model", "https://api.example/v1",
                "manifest", "config", "git");

        JSONObject summary = EvalSummary.aggregate(List.of(first, second), metadata, 2);
        JSONObject method = summary.getJSONObject("method");

        assertEquals(2, method.getInt("attempts"));
        assertEquals(1, method.getJSONObject("compileAtBudget").getInt("count"));
        assertEquals(0.5d, method.getJSONObject("compileAtBudget").getDouble("rate"));
        assertEquals(0.90d, method.getDouble("lineRateMacro"), 0.0001d);
        assertEquals(0.50d, method.getDouble("branchRateMacro"), 0.0001d);
        assertEquals(1, method.getJSONObject("branch80Rate").getInt("total"));
        assertEquals(2, summary.getJSONObject("overall").getInt("attempts"));
        assertEquals(1.0d, method.getJSONObject("conditionalRunSuccessful").getDouble("rate"));
    }

    public void testBreakdownsExcludeClassAttemptsFromCoverageDenominator() {
        JSONObject method = attempt("method", true, 1.0d, true, 1.0d)
                .put("difficulty", "HARD").put("tags", new JSONArray().put("mixed"));
        JSONObject classAttempt = attempt("class", true, 0d, false, 0d)
                .put("scope", "CLASS").put("difficulty", "HARD")
                .put("tags", new JSONArray().put("mixed").put("class-only"))
                .put("coverageAvailable", false).put("coverageStatus", "NOT_RUN");
        EvalRunMetadata metadata = new EvalRunMetadata("run", "regular", 1, "model",
                "https://api.example/v1", "manifest", "config", "git");

        JSONObject summary = EvalSummary.aggregate(List.of(method, classAttempt), metadata, 2);
        JSONObject hard = summary.getJSONObject("byDifficulty").getJSONObject("HARD");
        JSONObject classOnly = summary.getJSONObject("byTag").getJSONObject("class-only");

        assertEquals(1, hard.getJSONObject("coverageTargetReached").getInt("total"));
        assertEquals(1, hard.getJSONObject("coverageTargetReached").getInt("count"));
        assertEquals(0, classOnly.getJSONObject("coverageTargetReached").getInt("total"));
        assertSame(JSONObject.NULL, classOnly.getJSONObject("coverageTargetReached").get("rate"));
    }

    public void testCheckpointResumeAndReportRedactSecrets() throws Exception {
        EvalCaseManifest manifest = EvalCaseLoader.load(projectRoot().resolve("evaluation/cases")).get(0);
        Path reports = Files.createTempDirectory("unitmind-eval-store-");
        String secret = "sk-evaluation-secret-123456";
        EvalSecretRedactor redactor = new EvalSecretRedactor(secret);
        EvalResultStore store = new EvalResultStore(reports, "run-1", redactor);
        EvalAttemptResult attempt = new EvalAttemptResult(manifest, 1);
        attempt.setFatalError("Bearer " + secret + " failed");

        store.checkpoint(attempt, "class Initial { String key = \"" + secret + "\"; }",
                "class Final { String auth = \"Bearer " + secret + "\"; }");

        assertTrue(store.completedKeys().contains(EvalResultStore.key(manifest.getId(), 1)));
        String checkpoint = Files.readString(store.getRunDirectory().resolve("attempts.jsonl"),
                StandardCharsets.UTF_8);
        assertFalse(checkpoint, checkpoint.contains(secret));
        assertFalse(checkpoint, checkpoint.contains("Bearer sk-"));
        assertFalse(Files.readString(store.getRunDirectory().resolve("cases")
                .resolve(manifest.getId()).resolve("1/initial-test.java")).contains(secret));
        assertFalse(Files.readString(store.getRunDirectory().resolve("cases")
                .resolve(manifest.getId()).resolve("1/final-test.java")).contains(secret));

        EvalRunMetadata metadata = new EvalRunMetadata("run-1", "regular", 1, "model",
                "https://api.example/v1", "manifest", "config", "git");
        JSONObject summary = EvalSummary.aggregate(store.loadAttempts(), metadata, 1);
        EvalReportWriter.attachTrend(summary, reports.resolve("missing-baseline.json"));
        EvalReportWriter.write(store, summary);
        assertTrue(Files.isRegularFile(store.getRunDirectory().resolve("summary.json")));
        String markdown = Files.readString(store.getRunDirectory().resolve("summary.md"));
        String html = Files.readString(store.getRunDirectory().resolve("summary.html"));
        assertTrue(markdown.contains("Method track"));
        assertTrue(html.contains("UnitMind evaluation"));
        assertEquals(0d, summary.getJSONObject("method").getJSONObject("llmGenerated").getDouble("rate"));
        assertTrue(markdown.contains("0/1 (0.00%)"));
        assertTrue(html.contains("0/1 (0.00%)"));
    }

    public void testTelemetryCountsStubCallsAndCapturesInitialCode() throws Exception {
        LlmClient stub = request -> {
            LlmGenerationResponse response = new LlmGenerationResponse();
            response.setTestCode("class GeneratedTest {}");
            return response;
        };
        EvalLlmTelemetry telemetry = new EvalLlmTelemetry(config -> stub);

        telemetry.create(new LlmConfig()).generateUnitTest(new LlmGenerationRequest());
        telemetry.create(new LlmConfig()).repairUnitTest(new LlmGenerationRequest());

        assertEquals(2, telemetry.getRequests());
        assertEquals(0, telemetry.getErrors());
        assertEquals(1, telemetry.getGenerationRequests());
        assertEquals(1, telemetry.getCompileRepairRequests());
        assertEquals("class GeneratedTest {}", telemetry.getInitialTestCode());

        EvalCaseManifest manifest = EvalCaseLoader.load(projectRoot().resolve("evaluation/cases")).stream()
                .filter(item -> item.getScope() == EvalCaseManifest.Scope.METHOD).findFirst().orElseThrow();
        EvalAttemptResult attempt = new EvalAttemptResult(manifest, 1);
        attempt.setFinalValidation(ValidationResult.status(ValidationStatus.PASSED));
        attempt.setTelemetry(telemetry);
        assertEquals(1, attempt.toJson(new EvalSecretRedactor(""))
                .getInt("compileRepairAttempts"));
    }

    public void testClassAttemptKeepsMethodStatusesButDoesNotReportCoverage() throws Exception {
        EvalCaseManifest manifest = EvalCaseLoader.load(projectRoot().resolve("evaluation/cases")).stream()
                .filter(item -> item.getScope() == EvalCaseManifest.Scope.CLASS).findFirst().orElseThrow();
        GenerationResult methodResult = new GenerationResult();
        methodResult.setGeneratedByLlm(true);
        methodResult.setValidationResult(ValidationResult.status(ValidationStatus.PASSED));
        MethodGenerationItem method = new MethodGenerationItem();
        method.setMethodId("value()");
        method.setSignature("value()");
        method.setStatus(MethodGenerationStatus.SUCCESS);
        method.setResult(methodResult);
        ClassGenerationResult classResult = new ClassGenerationResult();
        classResult.setMethodItems(List.of(method));
        classResult.setComplete(true);
        classResult.setValidationResult(ValidationResult.status(ValidationStatus.PASSED));

        TestExecutionSummary execution = new TestExecutionSummary();
        execution.setFound(1);
        execution.setSucceeded(1);
        execution.setExitCode(0);
        ClassJUnitRepairReport executionReport = ClassJUnitRepairReport.status(ClassJUnitStatus.PASSED);
        executionReport.setStopReason(ClassJUnitStopReason.INITIAL_PASS);
        executionReport.setInitialExecution(execution);
        executionReport.setFinalExecution(execution);
        executionReport.setMessage("完整测试类首次 JUnit 执行通过。");

        EvalAttemptResult attempt = new EvalAttemptResult(manifest, 1);
        attempt.captureClassGeneration(classResult);
        attempt.setFinalValidation(classResult.getValidationResult());
        attempt.captureClassExecution(executionReport);
        JSONObject json = attempt.toJson(new EvalSecretRedactor(""));

        assertTrue(json.getBoolean("runSuccessful"));
        assertTrue(json.getBoolean("classJUnitAtOne"));
        assertEquals("INITIAL_PASS", json.getString("classJUnitStopReason"));
        assertTrue(json.getJSONArray("diagnostics").toString().contains("首次 JUnit 执行通过"));
        assertFalse(json.getBoolean("coverageAvailable"));
        assertEquals(1, json.getJSONArray("classMethodResults").length());
        assertEquals("SUCCESS", json.getJSONArray("classMethodResults")
                .getJSONObject(0).getString("status"));
    }

    public void testLoaderRejectsDuplicateTargets() throws Exception {
        Path root = Files.createTempDirectory("unitmind-eval-duplicate-");
        createMinimalCase(root.resolve("one"), "one", "sample.Subject");
        createMinimalCase(root.resolve("two"), "two", "sample.Subject");

        try {
            EvalCaseLoader.load(root);
            fail("Expected duplicate target validation to fail");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains("Duplicate evaluation target"));
        }
    }

    private JSONObject attempt(String id,
                               boolean successful,
                               double lineRate,
                               boolean branchApplicable,
                               double branchRate) {
        return new JSONObject()
                .put("caseId", id).put("scope", "METHOD").put("difficulty", "EASY")
                .put("sourceKind", "SELF")
                .put("branchBand", branchApplicable ? "HIGH" : "NONE")
                .put("tags", new JSONArray().put("sample")).put("attempt", 1)
                .put("environmentError", false).put("fatalError", "")
                .put("llmGenerated", true).put("deliveredCode", true).put("ruleFallback", false)
                .put("compileAtOne", successful).put("compileAtBudget", successful)
                .put("runSuccessful", successful).put("coverageAvailable", true)
                .put("coverageStatus", successful ? "PASSED" : "BELOW_THRESHOLD")
                .put("lineRate", lineRate).put("branchApplicable", branchApplicable)
                .put("branchRate", branchRate).put("compileRepairAttempts", 0)
                .put("modelRequests", 1).put("durationMillis", 100)
                .put("modelDurationMillis", 50)
                .put("classComplete", false).put("classMethods", 0).put("successful", successful);
    }

    private void createMinimalCase(Path directory, String id, String targetClass) throws Exception {
        Path source = directory.resolve("src/main/java/sample/Subject.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package sample; public class Subject { public int value(){ return 1; } }");
        JSONObject manifest = new JSONObject()
                .put("id", id).put("scope", "METHOD").put("sourceKind", "SELF")
                .put("difficulty", "EASY").put("branchBand", "NONE")
                .put("targetSource", "src/main/java/sample/Subject.java")
                .put("targetClass", targetClass).put("targetMethod", "value()")
                .put("sourceVersion", "test").put("jdkVersion", "17").put("license", "test")
                .put("tags", new JSONArray().put("test"));
        Files.writeString(directory.resolve("case.json"), manifest.toString());
    }

    private Path projectRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
