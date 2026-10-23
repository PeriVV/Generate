package com.casc.unittestagent.model;

/** Coverage of the exact final merged file; independent from candidate/subset coverage. */
public class FinalTestFileReport {
    private String targetPath = "";
    private boolean written;
    private CoverageReport methodCoverage = new CoverageReport();
    private CoverageSnapshot classCoverage = new CoverageSnapshot();
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String value) { targetPath = value == null ? "" : value; }
    public boolean isWritten() { return written; }
    public void setWritten(boolean value) { written = value; }
    public CoverageReport getMethodCoverage() { return methodCoverage; }
    public CoverageSnapshot getClassCoverage() { return classCoverage; }
    public void setClassCoverage(CoverageSnapshot value) { classCoverage = value == null ? new CoverageSnapshot() : value; }
    public void markStale(String reason) {
        methodCoverage.setStatus(CoverageStatus.STALE); methodCoverage.setMessage(reason);
    }
}
