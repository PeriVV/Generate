package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次候选测试源码编译验证的完整结果。
 */
public class ValidationResult {
    private CandidateEvidence candidateEvidence;
    public CandidateEvidence getCandidateEvidence() { return candidateEvidence; }
    public void setCandidateEvidence(CandidateEvidence value) { candidateEvidence = value; }

    private ValidationStatus status = ValidationStatus.NOT_RUN;
    private String codeHash = "";
    private List<CompileDiagnostic> diagnostics = new ArrayList<>();
    private int repairAttempts;
    private long durationMillis;
    private String testEnvironmentNote = "";

    public String getTestEnvironmentNote() { return testEnvironmentNote; }
    public void setTestEnvironmentNote(String note) { testEnvironmentNote = note == null ? "" : note; }

    public static ValidationResult status(ValidationStatus status) {
        ValidationResult result = new ValidationResult();
        result.setStatus(status);
        return result;
    }

    public ValidationStatus getStatus() {
        return status;
    }

    public void setStatus(ValidationStatus status) {
        this.status = status == null ? ValidationStatus.NOT_RUN : status;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public List<CompileDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(List<CompileDiagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? new ArrayList<>() : diagnostics;
    }

    public int getRepairAttempts() {
        return repairAttempts;
    }

    public void setRepairAttempts(int repairAttempts) {
        this.repairAttempts = repairAttempts;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public boolean isPassedFor(String hash) {
        return status == ValidationStatus.PASSED && codeHash != null && codeHash.equals(hash);
    }

    public boolean hasRepairableErrors() {
        return diagnostics.stream().anyMatch(CompileDiagnostic::isRepairable);
    }
}
