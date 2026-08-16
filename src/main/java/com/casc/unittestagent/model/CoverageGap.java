package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 反馈给用户和大模型的未覆盖源码目标。 */
public class CoverageGap {
    public enum Kind { LINE, BRANCH }

    private Kind kind = Kind.LINE;
    private int lineNumber;
    private String source = "";
    private String context = "";
    private int missedCount;
    private int totalCount;
    private String branchId = "";
    private String predicate = "";
    /** JaCoCo 行计数不能可靠区分 TRUE/FALSE，未知时明确标记 UNKNOWN。 */
    private String missingDirection = "UNKNOWN";
    private List<String> pathConstraints = new ArrayList<>();

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind == null ? Kind.LINE : kind; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source == null ? "" : source; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context == null ? "" : context; }
    public int getMissedCount() { return missedCount; }
    public void setMissedCount(int missedCount) { this.missedCount = Math.max(0, missedCount); }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = Math.max(0, totalCount); }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId == null ? "" : branchId; }
    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate == null ? "" : predicate; }
    public String getMissingDirection() { return missingDirection; }
    public void setMissingDirection(String missingDirection) {
        this.missingDirection = missingDirection == null || missingDirection.isBlank() ? "UNKNOWN" : missingDirection;
    }
    public List<String> getPathConstraints() { return pathConstraints; }
    public void setPathConstraints(List<String> pathConstraints) {
        this.pathConstraints = pathConstraints == null ? new ArrayList<>() : new ArrayList<>(pathConstraints);
    }
}
