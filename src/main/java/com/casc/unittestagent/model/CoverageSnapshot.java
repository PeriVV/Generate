package com.casc.unittestagent.model;

/** 单组测试对当前方法产生的覆盖率。 */
public class CoverageSnapshot {
    private boolean available;
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
