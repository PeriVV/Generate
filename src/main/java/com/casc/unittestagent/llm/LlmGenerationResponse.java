package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.MockStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型生成结果。
 */
public class LlmGenerationResponse {
    private MockStrategy mockStrategy = new MockStrategy();
    private String testCode;
    private List<String> warnings = new ArrayList<>();
    private String rawResponse;
    private String diagnosisRootCauseCategory = "";
    private List<String> diagnosisEvidence = new ArrayList<>();
    private String oracleDecision = "";
    private String oracleAuthority = "";
    private String oracleAuthorityRef = "";
    private String repairDisposition = "";
    private List<LlmCodeEdit> edits = new ArrayList<>();
    private List<LlmMethodPatch> methodPatches = new ArrayList<>();
    private String repairScope = "METHOD_ONLY";

    public MockStrategy getMockStrategy() {
        return mockStrategy;
    }

    public void setMockStrategy(MockStrategy mockStrategy) {
        this.mockStrategy = mockStrategy;
    }

    public String getTestCode() {
        return testCode;
    }

    public void setTestCode(String testCode) {
        this.testCode = testCode;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public String getDiagnosisRootCauseCategory() { return diagnosisRootCauseCategory; }
    public void setDiagnosisRootCauseCategory(String value) {
        diagnosisRootCauseCategory = value == null ? "" : value;
    }
    public List<String> getDiagnosisEvidence() { return diagnosisEvidence; }
    public void setDiagnosisEvidence(List<String> value) {
        diagnosisEvidence = value == null ? new ArrayList<>() : value;
    }
    public String getOracleDecision() { return oracleDecision; }
    public void setOracleDecision(String value) { oracleDecision = value == null ? "" : value; }
    public String getOracleAuthority() { return oracleAuthority; }
    public void setOracleAuthority(String value) { oracleAuthority = value == null ? "" : value; }
    public String getOracleAuthorityRef() { return oracleAuthorityRef; }
    public void setOracleAuthorityRef(String value) { oracleAuthorityRef = value == null ? "" : value; }
    public String getRepairDisposition() { return repairDisposition; }
    public void setRepairDisposition(String value) { repairDisposition = value == null ? "" : value; }
    public List<LlmCodeEdit> getEdits() { return edits; }
    public void setEdits(List<LlmCodeEdit> value) { edits = value == null ? new ArrayList<>() : value; }
    public List<LlmMethodPatch> getMethodPatches() { return methodPatches; }
    public void setMethodPatches(List<LlmMethodPatch> value) {
        methodPatches = value == null ? new ArrayList<>() : value;
    }
    public boolean hasMethodPatches() { return methodPatches != null && !methodPatches.isEmpty(); }
    public String getRepairScope() { return repairScope; }
    public void setRepairScope(String value) {
        repairScope = value == null || value.isBlank() ? "METHOD_ONLY" : value;
    }
}
