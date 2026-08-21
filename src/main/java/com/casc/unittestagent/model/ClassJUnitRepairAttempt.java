package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 一轮整类 JUnit 修复候选的编译、执行和接纳结果。 */
public class ClassJUnitRepairAttempt {
    private int round;
    private ClassJUnitRepairScope repairScope = ClassJUnitRepairScope.METHOD_ONLY;
    private String candidateHash = "";
    private List<String> changedTestIds = new ArrayList<>();
    private boolean fixtureChanged;
    private ValidationResult compileResult = ValidationResult.status(ValidationStatus.NOT_RUN);
    private TestExecutionSummary execution = new TestExecutionSummary();
    private boolean accepted;
    private String rejectionReason = "";
    private String diagnosis = "";

    public int getRound() { return round; }
    public void setRound(int value) { round = Math.max(0, value); }
    public ClassJUnitRepairScope getRepairScope() { return repairScope; }
    public void setRepairScope(ClassJUnitRepairScope value) {
        repairScope = value == null ? ClassJUnitRepairScope.METHOD_ONLY : value;
    }
    public String getCandidateHash() { return candidateHash; }
    public void setCandidateHash(String value) { candidateHash = value == null ? "" : value; }
    public List<String> getChangedTestIds() { return changedTestIds; }
    public void setChangedTestIds(List<String> value) {
        changedTestIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public boolean isFixtureChanged() { return fixtureChanged; }
    public void setFixtureChanged(boolean value) { fixtureChanged = value; }
    public ValidationResult getCompileResult() { return compileResult; }
    public void setCompileResult(ValidationResult value) {
        compileResult = value == null ? ValidationResult.status(ValidationStatus.NOT_RUN) : value;
    }
    public TestExecutionSummary getExecution() { return execution; }
    public void setExecution(TestExecutionSummary value) {
        execution = value == null ? new TestExecutionSummary() : value;
    }
    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean value) { accepted = value; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String value) { rejectionReason = value == null ? "" : value; }
    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String value) { diagnosis = value == null ? "" : value; }
}
