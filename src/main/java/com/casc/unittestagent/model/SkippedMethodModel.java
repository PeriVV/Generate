package com.casc.unittestagent.model;

/** 记录整类分析中被过滤的方法及原因。 */
public class SkippedMethodModel {
    private String signature;
    private String reason;

    public SkippedMethodModel() {
    }

    public SkippedMethodModel(String signature, String reason) {
        this.signature = signature;
        this.reason = reason;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
