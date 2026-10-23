package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** Explicit subset selection; exclusions are not repairs or evidence of a production defect. */
public class RunnableTestSelectionReport {
    public enum Status { NOT_RUN, RUNNING, PASSED, NO_RUNNABLE_TESTS, BLOCKED, CANCELLED, STALE }
    public record ExcludedTest(String testId, String reason) { }
    public record ExclusionOrigin(String originalCode, List<ExcludedTest> tests) {
        public ExclusionOrigin { tests = List.copyOf(tests); }
    }
    private Status status = Status.NOT_RUN;
    private String originalCode = "";
    private String selectedCode = "";
    private String sourceHash = "";
    private String archivePath = "";
    private String message = "";
    private int originalMethods;
    private int executions;
    private List<String> runtimeEvidenceArchives = List.of();
    public List<String> getRuntimeEvidenceArchives() { return runtimeEvidenceArchives; }
    public void addRuntimeEvidenceArchive(String path) {
        if (path == null || path.isBlank() || runtimeEvidenceArchives.contains(path)) return;
        var paths = new ArrayList<>(runtimeEvidenceArchives); paths.add(path); runtimeEvidenceArchives = List.copyOf(paths);
    }
    private boolean exclusionsReleased;
    private List<ExclusionOrigin> inheritedExclusions = List.of();
    public void releaseExclusions() { exclusionsReleased = true; }
    public boolean hasActiveExclusions() {
        return !getActiveExclusionOrigins().isEmpty();
    }
    public List<ExclusionOrigin> getActiveExclusionOrigins() {
        if (exclusionsReleased) return List.of();
        var origins = new ArrayList<>(inheritedExclusions);
        if (!selectedCode.isBlank() && !excludedTests.isEmpty() && (status == Status.PASSED || status == Status.STALE)) {
            var own = new ExclusionOrigin(originalCode, excludedTests);
            if (!origins.contains(own)) origins.add(own);
        }
        return List.copyOf(origins);
    }
    public void inheritExclusions(List<ExclusionOrigin> previous) {
        inheritedExclusions = List.copyOf(previous);
    }
    private List<String> removedHelpers = List.of();
    public List<String> getRemovedHelpers() { return removedHelpers; }
    public void setRemovedHelpers(List<String> value) { removedHelpers = List.copyOf(value); }
    private List<String> retainedTests = List.of();
    private final List<ExcludedTest> excludedTests = new ArrayList<>();

    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public String getOriginalCode() { return originalCode; }
    public void setOriginalCode(String value) { originalCode = value == null ? "" : value; }
    public String getSelectedCode() { return selectedCode; }
    public void setSelectedCode(String value) { selectedCode = value == null ? "" : value; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String value) { sourceHash = value == null ? "" : value; }
    public String getArchivePath() { return archivePath; }
    public void setArchivePath(String value) { archivePath = value == null ? "" : value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value == null ? "" : value; }
    public int getOriginalMethods() { return originalMethods; }
    public void setOriginalMethods(int value) { originalMethods = value; }
    public int getExecutions() { return executions; }
    public void incrementExecutions() { executions++; }
    public List<String> getRetainedTests() { return retainedTests; }
    public void setRetainedTests(List<String> value) { retainedTests = List.copyOf(value); }
    public List<ExcludedTest> getExcludedTests() { return List.copyOf(excludedTests); }
    public void exclude(String testId, String reason) { excludedTests.add(new ExcludedTest(testId, reason)); }
    public RunnableTestSelectionReport copy() {
        var copy = new RunnableTestSelectionReport();
        copy.status = status; copy.originalCode = originalCode; copy.selectedCode = selectedCode;
        copy.sourceHash = sourceHash; copy.archivePath = archivePath; copy.message = message;
        copy.runtimeEvidenceArchives = List.copyOf(runtimeEvidenceArchives);
        copy.originalMethods = originalMethods; copy.executions = executions;
        copy.exclusionsReleased = exclusionsReleased; copy.inheritedExclusions = List.copyOf(inheritedExclusions);
        copy.removedHelpers = List.copyOf(removedHelpers);
        copy.retainedTests = List.copyOf(retainedTests); copy.excludedTests.addAll(excludedTests);
        return copy;
    }
}
