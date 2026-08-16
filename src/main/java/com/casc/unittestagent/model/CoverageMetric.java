package com.casc.unittestagent.model;

/** 一类 JaCoCo 计数器的覆盖统计。 */
public class CoverageMetric {
    private int covered;
    private int missed;

    public CoverageMetric() {}

    public CoverageMetric(int covered, int missed) {
        setCovered(covered);
        setMissed(missed);
    }

    public int getCovered() { return covered; }
    public void setCovered(int covered) { this.covered = Math.max(0, covered); }
    public int getMissed() { return missed; }
    public void setMissed(int missed) { this.missed = Math.max(0, missed); }
    public int getTotal() { return covered + missed; }
    public double getRate() { return getTotal() == 0 ? 1.0d : (double) covered / getTotal(); }
    public boolean isApplicable() { return getTotal() > 0; }
}
