package com.casc.unittestagent.llm;

/** 模型声明的一项有目标、可审计的代码修改。 */
public class LlmCodeEdit {
    private String targetMethod = "";
    private String reason = "";
    private String expectedEffect = "";

    public String getTargetMethod() { return targetMethod; }
    public void setTargetMethod(String value) { targetMethod = value == null ? "" : value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value == null ? "" : value; }
    public String getExpectedEffect() { return expectedEffect; }
    public void setExpectedEffect(String value) { expectedEffect = value == null ? "" : value; }
}
