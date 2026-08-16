package com.casc.unittestagent.llm;

/** 运行修复阶段返回的单个测试方法替换补丁。 */
public class LlmMethodPatch {
    private String methodName = "";
    private String methodCode = "";

    public String getMethodName() { return methodName; }
    public void setMethodName(String value) { methodName = value == null ? "" : value; }
    public String getMethodCode() { return methodCode; }
    public void setMethodCode(String value) { methodCode = value == null ? "" : value; }
}
