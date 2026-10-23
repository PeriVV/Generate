package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.TestExecutionSummary;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.json.*;
import java.nio.file.*;
import java.io.IOException;

/** Failed JVM evidence outlives the disposable compilation sandbox. Local files only. */
public final class RuntimeEvidenceArchive {
    private static final Logger LOG = Logger.getInstance(RuntimeEvidenceArchive.class);
    private RuntimeEvidenceArchive() { }
    static void preserve(Path reports, TestExecutionSummary execution) {
        try { preserve(reports, execution, Path.of(PathManager.getSystemPath(), "unitmind", "runtime-evidence")); }
        catch (RuntimeException ex) { failed(execution, ex); }
    }
    static void preserve(Path reports, TestExecutionSummary execution, Path parent) {
        try {
            Files.createDirectories(parent);
            Path archive = Files.createTempDirectory(parent, "failure-");
            try (var paths = Files.walk(reports)) {
                for (Path source : paths.filter(Files::isRegularFile).toList()) {
                    Path target = archive.resolve(reports.relativize(source));
                    Files.createDirectories(target.getParent()); Files.copy(source, target);
                }
            }
            if (!Files.exists(archive.resolve("process-output.log"))) Files.writeString(archive.resolve("process-output.log"), execution.getOutput());
            JSONArray diagnostics = new JSONArray();
            for (var diagnostic : execution.getDiagnostics()) {
                diagnostic.setEvidenceArchivePath(archive.toString());
                diagnostics.put(new JSONObject().put("testId", diagnostic.getTestId()).put("exceptionType", diagnostic.getExceptionType())
                        .put("message", diagnostic.getMessage()).put("failureDetails", diagnostic.getFailureDetails())
                        .put("causeChain", diagnostic.getCauseChain()).put("causeDetails", diagnostic.getCauseDetails())
                        .put("rootCauseType", diagnostic.getRootCauseType()).put("rootCauseMessage", diagnostic.getRootCauseMessage())
                        .put("stackTrace", diagnostic.getStackTrace()).put("category", diagnostic.getCategory())
                        .put("failurePhase", diagnostic.getFailurePhase()).put("sharedContextFailure", diagnostic.isSharedContextFailure())
                        .put("contextKey", diagnostic.getContextKey()).put("secondary", diagnostic.isContextFailureSecondary())
                        .put("primaryFailureTestId", diagnostic.getPrimaryFailureTestId()));
            }
            Files.writeString(archive.resolve("execution.json"), new JSONObject().put("schemaVersion", 1)
                    .put("found", execution.getFound()).put("succeeded", execution.getSucceeded()).put("failed", execution.getFailed())
                    .put("skipped", execution.getSkipped()).put("exitCode", execution.getExitCode()).put("timedOut", execution.isTimedOut())
                    .put("diagnostics", diagnostics).toString(2));
            execution.setEvidenceArchivePath(archive.toString());
            execution.setEvidenceArchiveError("");
            LOG.info("UnitMind runtime evidence: archive=" + archive + ", diagnostics=" + diagnostics.length());
        } catch (IOException | RuntimeException ex) {
            failed(execution, ex);
        }
    }
    public static void preserveSourceAnalysis(TestExecutionSummary execution) {
        if (execution.getEvidenceArchivePath().isBlank()) return;
        try {
            var diagnostics = new JSONArray();
            for (var d : execution.getDiagnostics()) diagnostics.put(new JSONObject().put("testId", d.getTestId())
                    .put("category", d.getCategory()).put("failurePhase", d.getFailurePhase()).put("sharedContextFailure", d.isSharedContextFailure())
                    .put("contextKey", d.getContextKey()).put("primaryFailureTestId", d.getPrimaryFailureTestId())
                    .put("lifecycleEvidence", d.getLifecycleEvidence()).put("fixtureField", d.getFixtureField()));
            Files.writeString(Path.of(execution.getEvidenceArchivePath(), "source-analysis.json"), new JSONObject().put("schemaVersion", 1).put("diagnostics", diagnostics).toString(2));
        } catch (IOException | RuntimeException ex) {
            // The immutable raw archive is still usable; only the additional analysis could not be saved.
            execution.setEvidenceArchiveError("源码归因归档失败：" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            LOG.warn("UnitMind source analysis archive failed", ex);
        }
    }

    private static void failed(TestExecutionSummary execution, Exception ex) {
        execution.setEvidenceArchivePath("");
        execution.setEvidenceArchiveError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        execution.getDiagnostics().forEach(d -> d.setEvidenceArchivePath(""));
        LOG.warn("UnitMind runtime evidence archive failed", ex);
    }
}
