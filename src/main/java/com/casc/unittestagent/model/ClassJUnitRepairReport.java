package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 完整 JUnit 执行、模型修复和全量回归报告；单方法与整类生成共用。 */
public class ClassJUnitRepairReport {
    private CandidateEvidence candidateEvidence;
    public CandidateEvidence getCandidateEvidence() { return candidateEvidence; }
    public void setCandidateEvidence(CandidateEvidence value) { candidateEvidence = value; }

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
    private final List<RepairCandidateRejection> candidateRejections = new ArrayList<>();
    public List<RepairCandidateRejection> getCandidateRejections() { return List.copyOf(candidateRejections); }
    public void recordCandidateRejection(RepairCandidateRejection rejection) {
        if (candidateRejections.size() == 64) candidateRejections.remove(0);
        candidateRejections.add(rejection);
    }
    private long durationMillis;
    private String sourceHash = "";
    private String runtimeConfigurationOrigin = "IDE";
    public String getRuntimeConfigurationOrigin() { return runtimeConfigurationOrigin; }
    public void setRuntimeConfigurationOrigin(String value) { runtimeConfigurationOrigin = value == null ? "IDE" : value; }
    private String codeHash = "";
    private String message = "";
    private OracleAssessment oracleAssessment;
    private SourceBugAnalysis sourceBugAnalysis = new SourceBugAnalysis();

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
    /** 保留合并前候选的修复证据，最终状态、文本指纹和回归结果仍取最终执行。 */
    public void includeRepairHistory(ClassJUnitRepairReport candidate) {
        if (candidate == null || candidate == this) return;
        initialExecution = candidate.initialExecution;
        List<ClassJUnitRepairAttempt> combined = new ArrayList<>(candidate.attempts);
        combined.addAll(attempts);
        attempts = combined;
        runtimeRepairRounds += candidate.runtimeRepairRounds;
        compileRepairRounds += candidate.compileRepairRounds;
        modelRequests += candidate.modelRequests;
        fixtureRepairRounds += candidate.fixtureRepairRounds;
        regressionRejections += candidate.regressionRejections;
        var currentRejections = new ArrayList<>(candidateRejections);
        candidateRejections.clear();
        candidate.candidateRejections.forEach(this::recordCandidateRejection);
        currentRejections.forEach(this::recordCandidateRejection);
        durationMillis += candidate.durationMillis;
        if (oracleAssessment == null) oracleAssessment = candidate.oracleAssessment;
    }

    /** 最终完整文本与被测源码必须同时匹配，覆盖率不参与运行准出。 */
    public boolean isPassedFor(String expectedCodeHash, String expectedSourceHash) {
        return status == ClassJUnitStatus.PASSED
                && !codeHash.isBlank() && codeHash.equals(expectedCodeHash)
                && !sourceHash.isBlank() && sourceHash.equals(expectedSourceHash)
                && finalExecution.isFullySuccessful();
    }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String value) { codeHash = value == null ? "" : value; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String value) { sourceHash = value == null ? "" : value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value == null ? "" : value; }
    public OracleAssessment getOracleAssessment() { return oracleAssessment; }
    public void setOracleAssessment(OracleAssessment value) { oracleAssessment = value; }
    public SourceBugAnalysis getSourceBugAnalysis() { return sourceBugAnalysis; }
    public void setSourceBugAnalysis(SourceBugAnalysis value) {
        sourceBugAnalysis = value == null ? new SourceBugAnalysis() : value;
    }
}
