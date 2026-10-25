package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 一次隔离 JUnit 执行的摘要。 */
public class TestExecutionSummary {
    private int found;
    private int succeeded;
    private int failed;
    private int skipped;
    private boolean timedOut;
    private int exitCode = -1;
    private String output = "";
    private String evidenceArchivePath = "";
    private String evidenceArchiveError = "";
    public String getEvidenceArchivePath() { return evidenceArchivePath; }
    public void setEvidenceArchivePath(String value) { evidenceArchivePath = value == null ? "" : value; }
    public String getEvidenceArchiveError() { return evidenceArchiveError; }
    public void setEvidenceArchiveError(String value) { evidenceArchiveError = value == null ? "" : value; }
    private List<RuntimeDiagnostic> diagnostics = new ArrayList<>();
    private List<TestCaseExecution> testCases = new ArrayList<>();
    private final List<String> evidenceFailures = new ArrayList<>();

    public TestExecutionSummary snapshotForDisplay() {
        var copy = new TestExecutionSummary();
        copy.found = found; copy.succeeded = succeeded; copy.failed = failed; copy.skipped = skipped;
        copy.timedOut = timedOut; copy.exitCode = exitCode; copy.output = output;
        copy.evidenceArchivePath = evidenceArchivePath; copy.evidenceArchiveError = evidenceArchiveError;
        copy.diagnostics = List.copyOf(diagnostics); copy.testCases = List.copyOf(testCases);
        copy.evidenceFailures.addAll(evidenceFailures);
        return copy;
    }

    public int getFound() { return found; }
    public void setFound(int found) { this.found = found; }
    public int getSucceeded() { return succeeded; }
    public void setSucceeded(int succeeded) { this.succeeded = succeeded; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public boolean isTimedOut() { return timedOut; }
    public void setTimedOut(boolean timedOut) { this.timedOut = timedOut; }
    public int getExitCode() { return exitCode; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output == null ? "" : output; }
    public List<RuntimeDiagnostic> getDiagnostics() { return diagnostics; }
    public void setDiagnostics(List<RuntimeDiagnostic> diagnostics) { this.diagnostics = diagnostics == null ? new ArrayList<>() : diagnostics; }
    public List<TestCaseExecution> getTestCases() { return testCases; }
    public void setTestCases(List<TestCaseExecution> testCases) {
        this.testCases = testCases == null ? new ArrayList<>() : testCases;
    }
    public boolean isSuccessful() { return !timedOut && exitCode == 0 && found > 0 && failed == 0; }

    /** Raw process outcome stays intact; verification failures are a separate concern. */
    public List<String> getEvidenceFailures() { return List.copyOf(evidenceFailures); }
    public void rejectEvidence(String reason) {
        if (reason != null && !reason.isBlank() && !evidenceFailures.contains(reason)) evidenceFailures.add(reason);
    }
    /** A complete run requires per-test records consistent with its aggregate counters. */
    public boolean isFullySuccessful() {
        return isSuccessful() && succeeded == found && skipped == 0 && evidenceFailures.isEmpty()
                && testCases.size() == found && testCases.stream().allMatch(t -> t != null
                && t.isSuccessful() && !t.isSkipped() && !t.getTestId().isBlank());
    }
}
