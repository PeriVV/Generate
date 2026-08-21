package com.casc.unittestagent.evaluation;

import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.CoverageMetric;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodGenerationStatus;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestExecutionSummary;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Serializable outcome for one independent case attempt. */
public final class EvalAttemptResult {
    private final EvalCaseManifest manifest;
    private final int attempt;
    private final String startedAt = Instant.now().toString();
    private long durationMillis;
    private String fatalError = "";
    private boolean llmGenerated;
    private boolean deliveredCode;
    private boolean ruleFallback;
    private ValidationResult initialValidation = ValidationResult.status(ValidationStatus.NOT_RUN);
    private ValidationResult finalValidation = ValidationResult.status(ValidationStatus.NOT_RUN);
    private CoverageReport coverageReport;
    private boolean executionEnvironmentError;
    private ClassGenerationResult classGenerationResult;
    private boolean runSuccessful;
    private int testsFound;
    private int testsSucceeded;
    private int testsFailed;
    private int testsSkipped;
    private boolean classComplete;
    private int classMethods;
    private int classAcceptedMethods;
    private int classLlmMethods;
    private int classFallbackMethods;
    private int classFailedMethods;
    private int modelRequests;
    private int modelErrors;
    private long modelDurationMillis;
    private int generationRequests;
    private int compileRepairRequests;
    private int coverageExplorationRequests;
    private int coverageCompileRepairRequests;
    private int runtimeRepairRequests;
    private int classRuntimeRepairRequests;
    private int mockPlanningRequests;
    private long generationDurationMillis;
    private long compileRepairDurationMillis;
    private long coverageExplorationDurationMillis;
    private long coverageCompileRepairDurationMillis;
    private long runtimeRepairDurationMillis;
    private long classRuntimeRepairDurationMillis;
    private long mockPlanningDurationMillis;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final List<String> runtimeDiagnosticTypes = new ArrayList<>();
    private boolean classJUnitAtOne;
    private int classInitialTestsFailed;
    private int classRuntimeRepairRounds;
    private int classCompileRepairRounds;
    private int classFixtureRepairRounds;
    private int classRegressionRejections;
    private String classJUnitStopReason = "NONE";
    private ClassJUnitRepairReport classJUnitRepairReport;

    public EvalAttemptResult(EvalCaseManifest manifest, int attempt) {
        this.manifest = manifest;
        this.attempt = attempt;
    }

    public void captureGeneration(GenerationResult result) {
        if (result == null) return;
        llmGenerated = result.isGeneratedByLlm();
        ruleFallback = result.isRuleBasedFallback();
        deliveredCode = result.getGeneratedTestFile() != null
                && result.getGeneratedTestFile().getContent() != null
                && !result.getGeneratedTestFile().getContent().isBlank();
        warnings.addAll(result.getWarnings());
    }

    public void captureClassGeneration(ClassGenerationResult result) {
        captureGeneration(result);
        if (result == null) return;
        classGenerationResult = result;
        classComplete = result.isComplete();
        classMethods = result.getMethodItems().size();
        classAcceptedMethods = (int) result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.SUCCESS
                        || item.getStatus() == MethodGenerationStatus.RULE_FALLBACK).count();
        classLlmMethods = (int) result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.SUCCESS
                        && item.getResult() != null && item.getResult().isGeneratedByLlm()).count();
        classFallbackMethods = (int) result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.RULE_FALLBACK).count();
        classFailedMethods = (int) result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.FAILED).count();
    }

    public void captureExecution(CoverageReport report) {
        coverageReport = report;
        captureExecutionSummary(report);
    }

    /** Captures only the compile/run verdict for the class track; class coverage is intentionally excluded. */
    public void captureClassExecution(CoverageReport report) {
        executionEnvironmentError = report != null && report.getStatus().name().equals("ENVIRONMENT_ERROR");
        captureExecutionSummary(report);
    }

    public void captureClassExecution(ClassJUnitRepairReport report) {
        classJUnitRepairReport = report;
        if (report == null) return;
        executionEnvironmentError = report.getStatus() == ClassJUnitStatus.ENVIRONMENT_ERROR;
        classJUnitAtOne = report.getInitialExecution().isSuccessful();
        classInitialTestsFailed = report.getInitialExecution().getFailed();
        classRuntimeRepairRounds = report.getRuntimeRepairRounds();
        classCompileRepairRounds = report.getCompileRepairRounds();
        classFixtureRepairRounds = report.getFixtureRepairRounds();
        classRegressionRejections = report.getRegressionRejections();
        classJUnitStopReason = report.getStopReason().name();
        if (report.getMessage() != null && !report.getMessage().isBlank()) {
            diagnostics.add("class-junit: " + report.getMessage());
        }
        report.getAttempts().forEach(item -> {
            if (item.getRejectionReason() != null && !item.getRejectionReason().isBlank()) {
                diagnostics.add("class-junit round " + item.getRound() + ": " + item.getRejectionReason());
            }
        });
        captureExecutionSummary(report.getFinalExecution());
    }

    private void captureExecutionSummary(CoverageReport report) {
        if (report == null || report.getGenerated() == null) return;
        TestExecutionSummary execution = report.getGenerated().getExecution();
        captureExecutionSummary(execution);
    }

    private void captureExecutionSummary(TestExecutionSummary execution) {
        if (execution == null) return;
        runSuccessful = execution.isSuccessful();
        testsFound = execution.getFound();
        testsSucceeded = execution.getSucceeded();
        testsFailed = execution.getFailed();
        testsSkipped = execution.getSkipped();
        for (RuntimeDiagnostic item : execution.getDiagnostics()) {
            diagnostics.add(item.getExceptionType() + ": " + item.getMessage());
            if (item.getExceptionType() != null && !item.getExceptionType().isBlank()) {
                runtimeDiagnosticTypes.add(item.getExceptionType());
            }
        }
    }

    public JSONObject toJson(EvalSecretRedactor redactor) {
        JSONObject json = new JSONObject();
        json.put("caseId", manifest.getId());
        json.put("scope", manifest.getScope().name());
        json.put("sourceKind", manifest.getSourceKind().name());
        json.put("difficulty", manifest.getDifficulty().name());
        json.put("branchBand", manifest.getBranchBand().name());
        json.put("tags", new JSONArray(manifest.getTags()));
        json.put("attempt", attempt);
        json.put("startedAt", startedAt);
        json.put("durationMillis", durationMillis);
        json.put("fatalError", redactor.safe(fatalError));
        json.put("environmentError", environmentError());
        json.put("llmGenerated", llmGenerated);
        json.put("deliveredCode", deliveredCode);
        json.put("ruleFallback", ruleFallback);
        json.put("initialCompileStatus", initialValidation.getStatus().name());
        json.put("finalCompileStatus", finalValidation.getStatus().name());
        json.put("compileAtOne", initialValidation.getStatus() == ValidationStatus.PASSED);
        json.put("compileAtBudget", finalValidation.getStatus() == ValidationStatus.PASSED);
        json.put("initialValidationDurationMillis", initialValidation.getDurationMillis());
        json.put("compileRepairAttempts", Math.max(finalValidation.getRepairAttempts(), compileRepairRequests));
        json.put("validationDurationMillis", finalValidation.getDurationMillis());
        json.put("runSuccessful", runSuccessful);
        json.put("testsFound", testsFound);
        json.put("testsSucceeded", testsSucceeded);
        json.put("testsFailed", testsFailed);
        json.put("testsSkipped", testsSkipped);
        json.put("classComplete", classComplete);
        json.put("classMethods", classMethods);
        json.put("classAcceptedMethods", classAcceptedMethods);
        json.put("classLlmMethods", classLlmMethods);
        json.put("classFallbackMethods", classFallbackMethods);
        json.put("classFailedMethods", classFailedMethods);
        json.put("classMethodResults", classMethodResults(redactor));
        json.put("modelRequests", modelRequests);
        json.put("modelErrors", modelErrors);
        json.put("modelDurationMillis", modelDurationMillis);
        json.put("generationRequests", generationRequests);
        json.put("compileRepairRequests", compileRepairRequests);
        json.put("coverageExplorationRequests", coverageExplorationRequests);
        json.put("coverageCompileRepairRequests", coverageCompileRepairRequests);
        json.put("runtimeRepairRequests", runtimeRepairRequests);
        json.put("classRuntimeRepairRequests", classRuntimeRepairRequests);
        json.put("mockPlanningRequests", mockPlanningRequests);
        json.put("generationDurationMillis", generationDurationMillis);
        json.put("compileRepairDurationMillis", compileRepairDurationMillis);
        json.put("coverageExplorationDurationMillis", coverageExplorationDurationMillis);
        json.put("coverageCompileRepairDurationMillis", coverageCompileRepairDurationMillis);
        json.put("runtimeRepairDurationMillis", runtimeRepairDurationMillis);
        json.put("classRuntimeRepairDurationMillis", classRuntimeRepairDurationMillis);
        json.put("mockPlanningDurationMillis", mockPlanningDurationMillis);
        json.put("classJUnitAtOne", classJUnitAtOne);
        json.put("classInitialTestsFailed", classInitialTestsFailed);
        json.put("classRuntimeRepairRounds", classRuntimeRepairRounds);
        json.put("classCompileRepairRounds", classCompileRepairRounds);
        json.put("classFixtureRepairRounds", classFixtureRepairRounds);
        json.put("classRegressionRejections", classRegressionRejections);
        json.put("classJUnitStopReason", classJUnitStopReason);
        appendCoverage(json);
        json.put("warnings", redactor.safeArray(warnings));
        json.put("diagnostics", redactor.safeArray(allDiagnostics()));
        json.put("compileDiagnosticCodes", compileDiagnosticCodes());
        json.put("runtimeDiagnosticTypes", redactor.safeArray(
                new ArrayList<>(new LinkedHashSet<>(runtimeDiagnosticTypes))));
        json.put("successful", successful());
        return json;
    }

    private JSONArray classMethodResults(EvalSecretRedactor redactor) {
        JSONArray values = new JSONArray();
        if (classGenerationResult == null) return values;
        classGenerationResult.getMethodItems().forEach(item -> {
            JSONObject value = new JSONObject()
                    .put("methodId", item.getMethodId() == null ? "" : item.getMethodId())
                    .put("signature", item.getSignature() == null ? "" : item.getSignature())
                    .put("status", item.getStatus().name())
                    .put("message", redactor.safe(item.getMessage()));
            GenerationResult method = item.getResult();
            if (method != null) {
                ValidationResult validation = method.getValidationResult();
                value.put("llmGenerated", method.isGeneratedByLlm());
                value.put("ruleFallback", method.isRuleBasedFallback());
                value.put("compileStatus", (validation == null
                        ? ValidationStatus.NOT_RUN : validation.getStatus()).name());
                value.put("compileRepairAttempts", validation == null ? 0 : validation.getRepairAttempts());
            }
            values.put(value);
        });
        return values;
    }

    private void appendCoverage(JSONObject json) {
        if (coverageReport == null) {
            json.put("coverageStatus", "NOT_RUN");
            json.put("coverageStopReason", "NONE");
            json.put("coverageAvailable", false);
            return;
        }
        CoverageSnapshot generated = coverageReport.getGenerated();
        json.put("coverageStatus", coverageReport.getStatus().name());
        json.put("coverageStopReason", coverageReport.getStopReason().name());
        json.put("coverageAvailable", generated != null && generated.isAvailable());
        putMetric(json, "line", generated == null ? null : generated.getLine());
        putMetric(json, "branch", generated == null ? null : generated.getBranch());
        CoverageSnapshot first = coverageReport.getHistory().isEmpty() ? null : coverageReport.getHistory().get(0);
        json.put("firstRunnableLineRate", rate(first == null ? null : first.getLine()));
        json.put("firstRunnableBranchRate", applicableRate(first == null ? null : first.getBranch()));
        json.put("coverageModelRequests", coverageReport.getModelRequests());
        json.put("coverageExplorationRounds", coverageReport.getExplorationRounds());
        json.put("coverageRepairRounds", coverageReport.getRepairRounds());
        json.put("coverageTransportRetries", coverageReport.getTransportRetries());
        json.put("coverageFormatRetries", coverageReport.getFormatRetries());
        json.put("coverageAcceptedCandidates", coverageReport.getAcceptedCandidates());
        json.put("coverageCompileFailures", coverageReport.getCompileFailures());
        json.put("coverageRuntimeFailures", coverageReport.getRuntimeFailures());
        json.put("coverageNoProgressCandidates", coverageReport.getNoProgressCandidates());
        json.put("coverageRuntimeNoProgressCandidates", coverageReport.getRuntimeNoProgressCandidates());
        json.put("coverageUnchangedResponses", coverageReport.getUnchangedResponses());
        json.put("coverageModelUnchangedResponses", coverageReport.getModelUnchangedResponses());
        json.put("coverageDiscardedChanges", coverageReport.getDiscardedChanges());
        json.put("coverageDurationMillis", coverageReport.getDurationMillis());
    }

    private void putMetric(JSONObject json, String prefix, CoverageMetric metric) {
        int covered = metric == null ? 0 : metric.getCovered();
        int missed = metric == null ? 0 : metric.getMissed();
        json.put(prefix + "Covered", covered);
        json.put(prefix + "Missed", missed);
        json.put(prefix + "Applicable", metric != null && metric.isApplicable());
        json.put(prefix + "Rate", metric == null ? JSONObject.NULL : metric.getRate());
    }

    private Object applicableRate(CoverageMetric metric) {
        return metric != null && metric.isApplicable() ? metric.getRate() : JSONObject.NULL;
    }

    private Object rate(CoverageMetric metric) {
        return metric == null ? JSONObject.NULL : metric.getRate();
    }

    private List<String> allDiagnostics() {
        List<String> result = new ArrayList<>(diagnostics);
        appendCompileDiagnostics(result, initialValidation, "initial");
        appendCompileDiagnostics(result, finalValidation, "final");
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    private JSONArray compileDiagnosticCodes() {
        JSONArray result = new JSONArray();
        appendCompileCodes(result, initialValidation);
        appendCompileCodes(result, finalValidation);
        return result;
    }

    private void appendCompileCodes(JSONArray target, ValidationResult validation) {
        if (validation == null) return;
        validation.getDiagnostics().stream().map(CompileDiagnostic::getCode)
                .filter(code -> code != null && !code.isBlank()).distinct().forEach(target::put);
    }

    private void appendCompileDiagnostics(List<String> target, ValidationResult validation, String stage) {
        if (validation == null) return;
        for (CompileDiagnostic item : validation.getDiagnostics()) {
            target.add(stage + ": " + item.getCode() + " - " + item.getMessage());
        }
    }

    public boolean environmentError() {
        return initialValidation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                || finalValidation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                || executionEnvironmentError
                || classJUnitRepairReport != null
                && classJUnitRepairReport.getStatus() == ClassJUnitStatus.ENVIRONMENT_ERROR
                || coverageReport != null && coverageReport.getStatus().name().equals("ENVIRONMENT_ERROR");
    }

    public boolean successful() {
        boolean compiled = finalValidation.getStatus() == ValidationStatus.PASSED;
        if (manifest.getScope() == EvalCaseManifest.Scope.CLASS) {
            return fatalError.isBlank() && classComplete && compiled && runSuccessful;
        }
        return fatalError.isBlank() && compiled && runSuccessful
                && coverageReport != null && coverageReport.getGenerated() != null
                && coverageReport.getGenerated().isAvailable();
    }

    public EvalCaseManifest getManifest() { return manifest; }
    public int getAttempt() { return attempt; }
    public void setDurationMillis(long durationMillis) { this.durationMillis = Math.max(0, durationMillis); }
    public void setFatalError(String fatalError) { this.fatalError = fatalError == null ? "" : fatalError; }
    public void setInitialValidation(ValidationResult initialValidation) {
        this.initialValidation = initialValidation == null ? ValidationResult.status(ValidationStatus.NOT_RUN) : initialValidation;
    }
    public void inferInitialValidation(ValidationResult finalResult) {
        if (finalResult == null) {
            initialValidation = ValidationResult.status(ValidationStatus.NOT_RUN);
        } else if (finalResult.getRepairAttempts() == 0) {
            initialValidation = finalResult;
        } else {
            initialValidation = ValidationResult.status(ValidationStatus.FAILED);
        }
    }
    public void setFinalValidation(ValidationResult finalValidation) {
        this.finalValidation = finalValidation == null ? ValidationResult.status(ValidationStatus.NOT_RUN) : finalValidation;
    }
    public void setTelemetry(EvalLlmTelemetry telemetry) {
        if (telemetry == null) return;
        modelRequests = telemetry.getRequests();
        modelErrors = telemetry.getErrors();
        modelDurationMillis = telemetry.getDurationMillis();
        generationRequests = telemetry.getGenerationRequests();
        compileRepairRequests = telemetry.getCompileRepairRequests();
        coverageExplorationRequests = telemetry.getCoverageExplorationRequests();
        coverageCompileRepairRequests = telemetry.getCoverageCompileRepairRequests();
        runtimeRepairRequests = telemetry.getRuntimeRepairRequests();
        classRuntimeRepairRequests = telemetry.getClassRuntimeRepairRequests();
        mockPlanningRequests = telemetry.getMockPlanningRequests();
        generationDurationMillis = telemetry.getGenerationDurationMillis();
        compileRepairDurationMillis = telemetry.getCompileRepairDurationMillis();
        coverageExplorationDurationMillis = telemetry.getCoverageExplorationDurationMillis();
        coverageCompileRepairDurationMillis = telemetry.getCoverageCompileRepairDurationMillis();
        runtimeRepairDurationMillis = telemetry.getRuntimeRepairDurationMillis();
        classRuntimeRepairDurationMillis = telemetry.getClassRuntimeRepairDurationMillis();
        mockPlanningDurationMillis = telemetry.getMockPlanningDurationMillis();
    }
}
