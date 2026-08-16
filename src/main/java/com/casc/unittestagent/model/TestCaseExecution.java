package com.casc.unittestagent.model;

/** 单个 JUnit 测试方法的执行结果，用于定点修复并保护已经通过的测试。 */
public class TestCaseExecution {
    private String testId = "";
    private boolean successful;
    private boolean skipped;

    public TestCaseExecution() { }

    public TestCaseExecution(String testId, boolean successful, boolean skipped) {
        this.testId = testId == null ? "" : testId;
        this.successful = successful;
        this.skipped = skipped;
    }

    public String getTestId() { return testId; }
    public void setTestId(String testId) { this.testId = testId == null ? "" : testId; }
    public boolean isSuccessful() { return successful; }
    public void setSuccessful(boolean successful) { this.successful = successful; }
    public boolean isSkipped() { return skipped; }
    public void setSkipped(boolean skipped) { this.skipped = skipped; }
}
