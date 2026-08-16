package com.casc.unittestagent.model;

/** 整类生成中单个方法的进度、候选代码和诊断。 */
public class MethodGenerationItem {
    private String methodId;
    private String signature;
    private MethodGenerationStatus status = MethodGenerationStatus.NOT_STARTED;
    private GenerationResult result;
    private String message;
    private int generatedStartLine = -1;
    private int generatedEndLine = -1;

    public String getMethodId() { return methodId; }
    public void setMethodId(String methodId) { this.methodId = methodId; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public MethodGenerationStatus getStatus() { return status; }
    public void setStatus(MethodGenerationStatus status) { this.status = status; }
    public GenerationResult getResult() { return result; }
    public void setResult(GenerationResult result) { this.result = result; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public int getGeneratedStartLine() { return generatedStartLine; }
    public void setGeneratedStartLine(int generatedStartLine) { this.generatedStartLine = generatedStartLine; }
    public int getGeneratedEndLine() { return generatedEndLine; }
    public void setGeneratedEndLine(int generatedEndLine) { this.generatedEndLine = generatedEndLine; }
}
