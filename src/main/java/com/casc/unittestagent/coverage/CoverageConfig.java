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
    /**
     * 严格 Oracle 准出：模型判定“需要修改断言”（oracleDecision=CORRECT）的修复
     * 一律不自动应用，闭环停止并标记 NEEDS_REVIEW，保留原失败测试等待人工确认。
     * 防止自动修复掩盖被测代码的真实缺陷。
     */
    private boolean strictOracleGate;
    /** 每个生成结果允许的“补充证据并继续修复”次数，0 表示禁用证据续修。 */
    private int maxEvidenceResumes = 2;

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
    public boolean isStrictOracleGate() { return strictOracleGate; }
    public void setStrictOracleGate(boolean value) { strictOracleGate = value; }
    public int getMaxEvidenceResumes() { return maxEvidenceResumes; }
    public void setMaxEvidenceResumes(int value) { maxEvidenceResumes = Math.max(0, Math.min(5, value)); }

    /** 证据续修额度：0 表示禁用；已用次数达到上限后不再放行。 */
    public boolean evidenceResumeAllowed(int used) {
        return maxEvidenceResumes > 0 && used < maxEvidenceResumes;
    }

    private double clamp(double value) { return Math.max(0d, Math.min(1d, value)); }
}
