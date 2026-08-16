package com.casc.unittestagent.coverage;

import com.intellij.ide.util.PropertiesComponent;

/** 持久化覆盖率阈值和执行限制。 */
public final class CoverageConfigManager {
    private static final String PREFIX = "unitmind.coverage.";
    private static final String LINE = PREFIX + "lineThreshold";
    private static final String BRANCH = PREFIX + "branchThreshold";
    private static final String ROUNDS = PREFIX + "maxRounds";
    private static final String REPAIR_ROUNDS = PREFIX + "maxRepairRounds";
    private static final String TIMEOUT = PREFIX + "executionTimeoutSeconds";
    private static final String BASELINE = PREFIX + "existingBaselineEnabled";
    private static final String EXECUTION_CONFIRMED = PREFIX + "executionConfirmed";

    private CoverageConfigManager() {}

    public static CoverageConfig load() {
        PropertiesComponent p = PropertiesComponent.getInstance();
        CoverageConfig config = new CoverageConfig();
        config.setLineThreshold(parseDouble(p.getValue(LINE), 0.90d));
        config.setBranchThreshold(parseDouble(p.getValue(BRANCH), 0.80d));
        config.setMaxRounds(parseInt(p.getValue(ROUNDS), 5));
        config.setMaxRepairRounds(parseInt(p.getValue(REPAIR_ROUNDS), 5));
        config.setExecutionTimeoutSeconds(parseInt(p.getValue(TIMEOUT), 60));
        config.setExistingBaselineEnabled(p.getBoolean(BASELINE, true));
        return config;
    }

    public static void save(CoverageConfig config) {
        PropertiesComponent p = PropertiesComponent.getInstance();
        p.setValue(LINE, Double.toString(config.getLineThreshold()));
        p.setValue(BRANCH, Double.toString(config.getBranchThreshold()));
        p.setValue(ROUNDS, Integer.toString(config.getMaxRounds()));
        p.setValue(REPAIR_ROUNDS, Integer.toString(config.getMaxRepairRounds()));
        p.setValue(TIMEOUT, Integer.toString(config.getExecutionTimeoutSeconds()));
        p.setValue(BASELINE, Boolean.toString(config.isExistingBaselineEnabled()));
    }

    public static boolean isExecutionConfirmed() {
        return PropertiesComponent.getInstance().getBoolean(EXECUTION_CONFIRMED, false);
    }

    public static void setExecutionConfirmed(boolean confirmed) {
        PropertiesComponent.getInstance().setValue(EXECUTION_CONFIRMED, Boolean.toString(confirmed));
    }

    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static double parseDouble(String value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
