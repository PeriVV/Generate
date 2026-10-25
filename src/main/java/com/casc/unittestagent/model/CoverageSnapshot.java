package com.casc.unittestagent.model;

/** 单组测试对当前方法产生的覆盖率。 */
public class CoverageSnapshot {
    public CoverageSnapshot snapshotForDisplay() {
        var copy = new CoverageSnapshot();
        copy.candidateEvidence = candidateEvidence; copy.available = available;
        copy.measurementScope = measurementScope; copy.measuredMethods = java.util.List.copyOf(measuredMethods);
        copy.message = message;
        copy.line = new CoverageMetric(line.getCovered(), line.getMissed());
        copy.branch = new CoverageMetric(branch.getCovered(), branch.getMissed());
        copy.execution = execution.snapshotForDisplay();
        return copy;
    }
    private CandidateEvidence candidateEvidence;
    public CandidateEvidence getCandidateEvidence() { return candidateEvidence; }
    public void setCandidateEvidence(CandidateEvidence value) { candidateEvidence = value; }

    private boolean available;
    private String measurementScope = "";
    private java.util.List<String> measuredMethods = java.util.List.of();
    public String getMeasurementScope() { return measurementScope; }
    public void setMeasurementScope(String value) { measurementScope = value == null ? "" : value; }
    public java.util.List<String> getMeasuredMethods() { return measuredMethods; }
    public void setMeasuredMethods(java.util.List<String> value) { measuredMethods = value == null ? java.util.List.of() : java.util.List.copyOf(value); }

    private String message = "";
    private CoverageMetric line = new CoverageMetric();
    private CoverageMetric branch = new CoverageMetric();
    private TestExecutionSummary execution = new TestExecutionSummary();

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? "" : message; }
    public CoverageMetric getLine() { return line; }
    public void setLine(CoverageMetric line) { this.line = line == null ? new CoverageMetric() : line; }
    public CoverageMetric getBranch() { return branch; }
    public void setBranch(CoverageMetric branch) { this.branch = branch == null ? new CoverageMetric() : branch; }
    public TestExecutionSummary getExecution() { return execution; }
    public void setExecution(TestExecutionSummary execution) { this.execution = execution == null ? new TestExecutionSummary() : execution; }
}
