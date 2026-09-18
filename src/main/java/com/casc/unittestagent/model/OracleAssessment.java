package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 可展示、可持久传递的运行失败 Oracle 仲裁结果。 */
public class OracleAssessment {
    private RuntimeRepairDisposition disposition = RuntimeRepairDisposition.INCONCLUSIVE;
    private OracleDecision decision = OracleDecision.KEEP;
    private OracleAuthority authority = OracleAuthority.NONE;
    private String authorityRef = "";
    private String rootCauseCategory = "";
    private List<String> evidence = new ArrayList<>();

    public RuntimeRepairDisposition getDisposition() { return disposition; }
    public void setDisposition(RuntimeRepairDisposition value) {
        disposition = value == null ? RuntimeRepairDisposition.INCONCLUSIVE : value;
    }
    public OracleDecision getDecision() { return decision; }
    public void setDecision(OracleDecision value) { decision = value == null ? OracleDecision.KEEP : value; }
    public OracleAuthority getAuthority() { return authority; }
    public void setAuthority(OracleAuthority value) { authority = value == null ? OracleAuthority.NONE : value; }
    public String getAuthorityRef() { return authorityRef; }
    public void setAuthorityRef(String value) { authorityRef = value == null ? "" : value; }
    public String getRootCauseCategory() { return rootCauseCategory; }
    public void setRootCauseCategory(String value) { rootCauseCategory = value == null ? "" : value; }
    public List<String> getEvidence() { return new ArrayList<>(evidence); }
    public void setEvidence(List<String> value) {
        evidence = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
