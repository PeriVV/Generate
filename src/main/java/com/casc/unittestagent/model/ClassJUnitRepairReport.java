package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 整类最终 JUnit 执行、模型修复和全量回归的完整报告。 */
public class ClassJUnitRepairReport {
    private ClassJUnitStatus status = ClassJUnitStatus.NOT_RUN;
    private ClassJUnitStopReason stopReason = ClassJUnitStopReason.NONE;
    private TestExecutionSummary initialExecution = new TestExecutionSummary();
    private TestExecutionSummary finalExecution = new TestExecutionSummary();
    private List<ClassJUnitRepairAttempt> attempts = new ArrayList<>();
    private int runtimeRepairRounds;
    private int compileRepairRounds;
    private int modelRequests;
    private int fixtureRepairRounds;
    private int regressionRejections;
    private long durationMillis;
    private String sourceHash = "";
    private String message = "";

    public static ClassJUnitRepairReport status(ClassJUnitStatus status) {
        ClassJUnitRepairReport report = new ClassJUnitRepairReport();
        report.setStatus(status);
        return report;
    }

    public ClassJUnitStatus getStatus() { return status; }
    public void setStatus(ClassJUnitStatus value) { status = value == null ? ClassJUnitStatus.NOT_RUN : value; }
    public ClassJUnitStopReason getStopReason() { return stopReason; }
    public void setStopReason(ClassJUnitStopReason value) {
        stopReason = value == null ? ClassJUnitStopReason.NONE : value;
    }
    public TestExecutionSummary getInitialExecution() { return initialExecution; }
    public void setInitialExecution(TestExecutionSummary value) {
        initialExecution = value == null ? new TestExecutionSummary() : value;
    }
    public TestExecutionSummary getFinalExecution() { return finalExecution; }
    public void setFinalExecution(TestExecutionSummary value) {
        finalExecution = value == null ? new TestExecutionSummary() : value;
    }
    public List<ClassJUnitRepairAttempt> getAttempts() { return attempts; }
    public void setAttempts(List<ClassJUnitRepairAttempt> value) {
        attempts = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public int getRuntimeRepairRounds() { return runtimeRepairRounds; }
    public void incrementRuntimeRepairRounds() { runtimeRepairRounds++; }
    public int getCompileRepairRounds() { return compileRepairRounds; }
    public void addCompileRepairRounds(int value) { compileRepairRounds += Math.max(0, value); }
    public int getModelRequests() { return modelRequests; }
    public void incrementModelRequests() { modelRequests++; }
    public int getFixtureRepairRounds() { return fixtureRepairRounds; }
    public void incrementFixtureRepairRounds() { fixtureRepairRounds++; }
    public int getRegressionRejections() { return regressionRejections; }
    public void incrementRegressionRejections() { regressionRejections++; }
    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long value) { durationMillis = Math.max(0L, value); }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String value) { sourceHash = value == null ? "" : value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value == null ? "" : value; }
}
