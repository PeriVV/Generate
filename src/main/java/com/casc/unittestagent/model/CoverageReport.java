package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 一次覆盖率闭环的完整、可展示结果。 */
public class CoverageReport {
    private CandidateEvidence candidateEvidence;
    public CandidateEvidence getCandidateEvidence() { return candidateEvidence; }
    public void setCandidateEvidence(CandidateEvidence value) { candidateEvidence = value; }

    private CoverageStatus status = CoverageStatus.NOT_RUN;
    private CoverageStopReason stopReason = CoverageStopReason.NONE;
    private CoverageSnapshot generated = new CoverageSnapshot();
    private List<CoverageGap> gaps = new ArrayList<>();
    private List<CoverageSnapshot> history = new ArrayList<>();
    private int rounds;
    private long durationMillis;
    private String codeHash = "";
    private String sourceHash = "";
    private String runtimeConfigurationOrigin = "IDE";
    public String getRuntimeConfigurationOrigin() { return runtimeConfigurationOrigin; }
    public void setRuntimeConfigurationOrigin(String value) { runtimeConfigurationOrigin = value == null ? "IDE" : value; }
    private String message = "";
    private CoveragePhase phase = CoveragePhase.NOT_STARTED;
    private double progressFraction;
    private String progressMessage = "";
    private int currentCandidate;
    private List<CoveragePhase> visitedPhases = new CopyOnWriteArrayList<>();
    /** 实际发出的模型请求数，包含格式重试和瞬时网络重试。 */
    private int modelRequests;
    private final List<RepairCandidateRejection> candidateRejections = new ArrayList<>();
    public List<RepairCandidateRejection> getCandidateRejections() { return List.copyOf(candidateRejections); }
    public void recordCandidateRejection(RepairCandidateRejection rejection) {
        if (candidateRejections.size() == 64) candidateRejections.remove(0);
        candidateRejections.add(rejection);
    }
    /** 已成功返回候选的覆盖率探索调用数。 */
    private int explorationRounds;
    /** 已成功返回候选的编译/运行修复调用数。 */
    private int repairRounds;
    /** 不占用语义补测轮次的瞬时网络重试次数。 */
    private int transportRetries;
    /** 不占用语义补测轮次的响应格式重试次数。 */
    private int formatRetries;
    /** 通过编译、运行且成为当前最佳版本的候选数量。 */
    private int acceptedCandidates;
    private int compileFailures;
    private int runtimeFailures;
    private int noProgressCandidates;
    /** 代码有变化但全量运行失败签名完全未变的修复候选数。 */
    private int runtimeNoProgressCandidates;
    /** 后处理完成后与当前测试完全相同的模型响应数，不计入语义修复轮次。 */
    private int unchangedResponses;
    /** 模型原始 testCode 就未变化的响应数。 */
    private int modelUnchangedResponses;
    /** 模型有改动，但后处理/测试保护后被还原的响应数。 */
    private int discardedChanges;
    private String lastUnchangedStage = "";
    private OracleAssessment oracleAssessment;
    private SourceBugAnalysis sourceBugAnalysis = new SourceBugAnalysis();
    /** Oracle 停止时仍在失败的测试方法名（有界），用于证据绑定与续修提示。 */
    private List<String> failingTestNamesAtStop = new ArrayList<>();

    public static CoverageReport status(CoverageStatus status) {
        CoverageReport report = new CoverageReport();
        report.setStatus(status);
        return report;
    }

    /** Capture on the task thread before queuing an EDT update; later rounds must not alter queued counters. */
    public CoverageReport snapshotForDisplay() {
        var copy = new CoverageReport();
        copy.candidateEvidence = candidateEvidence; copy.status = status; copy.stopReason = stopReason;
        copy.generated = generated.snapshotForDisplay(); copy.gaps = List.copyOf(gaps);
        copy.history = history.stream().map(CoverageSnapshot::snapshotForDisplay).toList();
        copy.rounds = rounds; copy.durationMillis = durationMillis;
        copy.codeHash = codeHash; copy.sourceHash = sourceHash;
        copy.runtimeConfigurationOrigin = runtimeConfigurationOrigin; copy.message = message;
        copy.phase = phase; copy.progressFraction = progressFraction;
        copy.progressMessage = progressMessage; copy.currentCandidate = currentCandidate;
        copy.visitedPhases = new CopyOnWriteArrayList<>(visitedPhases);
        copy.modelRequests = modelRequests; copy.candidateRejections.addAll(candidateRejections);
        copy.explorationRounds = explorationRounds; copy.repairRounds = repairRounds;
        copy.transportRetries = transportRetries; copy.formatRetries = formatRetries;
        copy.acceptedCandidates = acceptedCandidates; copy.compileFailures = compileFailures;
        copy.runtimeFailures = runtimeFailures; copy.noProgressCandidates = noProgressCandidates;
        copy.runtimeNoProgressCandidates = runtimeNoProgressCandidates;
        copy.unchangedResponses = unchangedResponses; copy.modelUnchangedResponses = modelUnchangedResponses;
        copy.discardedChanges = discardedChanges; copy.lastUnchangedStage = lastUnchangedStage;
        copy.oracleAssessment = oracleAssessment; copy.sourceBugAnalysis = sourceBugAnalysis;
        copy.failingTestNamesAtStop = List.copyOf(failingTestNamesAtStop);
        return copy;
    }

    public CoverageStatus getStatus() { return status; }
    public void setStatus(CoverageStatus status) { this.status = status == null ? CoverageStatus.NOT_RUN : status; }
    public CoverageStopReason getStopReason() { return stopReason; }
    public void setStopReason(CoverageStopReason stopReason) { this.stopReason = stopReason == null ? CoverageStopReason.NONE : stopReason; }
    public CoverageSnapshot getGenerated() { return generated; }
    public void setGenerated(CoverageSnapshot generated) { this.generated = generated == null ? new CoverageSnapshot() : generated; }
    public List<CoverageGap> getGaps() { return gaps; }
    public void setGaps(List<CoverageGap> gaps) { this.gaps = gaps == null ? new ArrayList<>() : gaps; }
    public List<CoverageSnapshot> getHistory() { return history; }
    public void setHistory(List<CoverageSnapshot> history) { this.history = history == null ? new ArrayList<>() : history; }
    public int getRounds() { return rounds; }
    public void setRounds(int rounds) { this.rounds = Math.max(0, rounds); }
    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long durationMillis) { this.durationMillis = Math.max(0, durationMillis); }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash == null ? "" : codeHash; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash == null ? "" : sourceHash; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? "" : message; }
    public CoveragePhase getPhase() { return phase; }
    public void setPhase(CoveragePhase phase) {
        this.phase = phase == null ? CoveragePhase.NOT_STARTED : phase;
        if (this.phase != CoveragePhase.NOT_STARTED && !visitedPhases.contains(this.phase)) {
            visitedPhases.add(this.phase);
        }
    }
    public double getProgressFraction() { return progressFraction; }
    public void setProgressFraction(double progressFraction) {
        double normalized = Math.max(0d, Math.min(1d, progressFraction));
        this.progressFraction = Math.max(this.progressFraction, normalized);
    }
    public String getProgressMessage() { return progressMessage; }
    public void setProgressMessage(String progressMessage) {
        this.progressMessage = progressMessage == null ? "" : progressMessage;
    }
    public int getCurrentCandidate() { return currentCandidate; }
    public void setCurrentCandidate(int currentCandidate) { this.currentCandidate = Math.max(0, currentCandidate); }
    public List<CoveragePhase> getVisitedPhases() { return visitedPhases; }
    public void setVisitedPhases(List<CoveragePhase> visitedPhases) {
        this.visitedPhases = new CopyOnWriteArrayList<>(visitedPhases == null ? List.of() : visitedPhases);
    }
    public int getModelRequests() { return modelRequests; }
    public void incrementModelRequests() { modelRequests++; }
    public int getExplorationRounds() { return explorationRounds; }
    public void incrementExplorationRounds() { explorationRounds++; }
    public int getRepairRounds() { return repairRounds; }
    public void incrementRepairRounds() { repairRounds++; }
    public int getTransportRetries() { return transportRetries; }
    public void incrementTransportRetries() { transportRetries++; }
    public int getFormatRetries() { return formatRetries; }
    public void incrementFormatRetries() { formatRetries++; }
    public int getAcceptedCandidates() { return acceptedCandidates; }
    public void incrementAcceptedCandidates() { acceptedCandidates++; }
    public int getCompileFailures() { return compileFailures; }
    public void incrementCompileFailures() { compileFailures++; }
    public int getRuntimeFailures() { return runtimeFailures; }
    public void incrementRuntimeFailures() { runtimeFailures++; }
    public int getNoProgressCandidates() { return noProgressCandidates; }
    public void incrementNoProgressCandidates() { noProgressCandidates++; }
    public int getRuntimeNoProgressCandidates() { return runtimeNoProgressCandidates; }
    public void incrementRuntimeNoProgressCandidates() { runtimeNoProgressCandidates++; }
    public int getUnchangedResponses() { return unchangedResponses; }
    public int getModelUnchangedResponses() { return modelUnchangedResponses; }
    public int getDiscardedChanges() { return discardedChanges; }
    public String getLastUnchangedStage() { return lastUnchangedStage; }
    public void recordUnchangedResponse(String stage) {
        unchangedResponses++;
        lastUnchangedStage = stage == null ? "" : stage;
        if ("MODEL_OUTPUT".equals(lastUnchangedStage)) modelUnchangedResponses++;
        else discardedChanges++;
    }
    public OracleAssessment getOracleAssessment() { return oracleAssessment; }
    public void setOracleAssessment(OracleAssessment value) { oracleAssessment = value; }
    public SourceBugAnalysis getSourceBugAnalysis() { return sourceBugAnalysis; }
    public void setSourceBugAnalysis(SourceBugAnalysis value) {
        sourceBugAnalysis = value == null ? new SourceBugAnalysis() : value;
    }
    public List<String> getFailingTestNamesAtStop() { return failingTestNamesAtStop; }
    public void setFailingTestNamesAtStop(List<String> names) {
        List<String> safe = names == null ? List.of() : names.stream()
                .filter(name -> name != null && !name.isBlank())
                .limit(20)
                .collect(java.util.stream.Collectors.toList());
        this.failingTestNamesAtStop = new ArrayList<>(safe);
    }

    /** 同步更新后台任务进度，供工具窗口实时渲染。 */
    public void updateProgress(CoveragePhase phase, int candidate, String text, double fraction) {
        setPhase(phase);
        if (candidate > 0) setCurrentCandidate(candidate);
        setProgressMessage(text);
        setProgressFraction(fraction);
    }
}
