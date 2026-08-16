package com.casc.unittestagent.coverage;

/** UnitMind 覆盖率闭环配置。 */
public class CoverageConfig {
    private double lineThreshold = 0.90d;
    private double branchThreshold = 0.80d;
    /** 只用于产生新覆盖目标的模型调用预算。 */
    private int maxRounds = 5;
    /** 编译/运行失败的修复预算，不挤占覆盖率探索轮次。 */
    private int maxRepairRounds = 5;
    private int executionTimeoutSeconds = 60;
    private boolean existingBaselineEnabled = true;

    public double getLineThreshold() { return lineThreshold; }
    public void setLineThreshold(double value) { lineThreshold = clamp(value); }
    public double getBranchThreshold() { return branchThreshold; }
    public void setBranchThreshold(double value) { branchThreshold = clamp(value); }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int value) { maxRounds = Math.max(0, Math.min(30, value)); }
    public int getMaxRepairRounds() { return maxRepairRounds; }
    public void setMaxRepairRounds(int value) { maxRepairRounds = Math.max(0, Math.min(30, value)); }
    public int getExecutionTimeoutSeconds() { return executionTimeoutSeconds; }
    public void setExecutionTimeoutSeconds(int value) { executionTimeoutSeconds = Math.max(5, Math.min(600, value)); }
    public boolean isExistingBaselineEnabled() { return existingBaselineEnabled; }
    public void setExistingBaselineEnabled(boolean value) { existingBaselineEnabled = value; }

    private double clamp(double value) { return Math.max(0d, Math.min(1d, value)); }
}
