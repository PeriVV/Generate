package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 疑似 SUT Bug 终态之后的只读归因结果。
 * 该对象只承载解释和人工核查建议，不能携带或应用任何代码修改。
 */
public class SourceBugAnalysis {
    private boolean attempted;
    private boolean available;
    private String summary = "";
    private String confidence = "LOW";
    private List<SourceBugLocation> suspectedLocations = new ArrayList<>();
    private List<String> hypotheses = new ArrayList<>();
    private List<String> evidence = new ArrayList<>();
    private List<String> recommendedChecks = new ArrayList<>();
    private String error = "";

    public static SourceBugAnalysis failed(String message) {
        SourceBugAnalysis analysis = new SourceBugAnalysis();
        analysis.setAttempted(true);
        analysis.setAvailable(false);
        analysis.setError(message);
        return analysis;
    }

    public boolean isAttempted() { return attempted; }
    public void setAttempted(boolean value) { attempted = value; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean value) { available = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value == null ? "" : value; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String value) {
        confidence = value == null || value.isBlank() ? "LOW" : value;
    }
    public List<SourceBugLocation> getSuspectedLocations() {
        return new ArrayList<>(suspectedLocations);
    }
    public void setSuspectedLocations(List<SourceBugLocation> value) {
        suspectedLocations = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getHypotheses() { return new ArrayList<>(hypotheses); }
    public void setHypotheses(List<String> value) {
        hypotheses = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getEvidence() { return new ArrayList<>(evidence); }
    public void setEvidence(List<String> value) {
        evidence = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getRecommendedChecks() { return new ArrayList<>(recommendedChecks); }
    public void setRecommendedChecks(List<String> value) {
        recommendedChecks = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public String getError() { return error; }
    public void setError(String value) { error = value == null ? "" : value; }
}
