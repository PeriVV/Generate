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
    private List<RuntimeDiagnostic> diagnostics = new ArrayList<>();
    private List<TestCaseExecution> testCases = new ArrayList<>();

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
}
