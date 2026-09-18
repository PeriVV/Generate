package com.casc.unittestagent.model;

/** Oracle 结论的证据来源。只有独立契约证据可以授权自动修改 Oracle。 */
public enum OracleAuthority {
    USER_REQUIREMENT,
    EXISTING_TEST,
    CONTRACT_FACT,
    PRODUCTION_SOURCE,
    SCENARIO,
    RUNTIME_ACTUAL,
    NONE;

    public boolean permitsAutomaticOracleCorrection() {
        return this == USER_REQUIREMENT || this == EXISTING_TEST || this == CONTRACT_FACT;
    }
}
