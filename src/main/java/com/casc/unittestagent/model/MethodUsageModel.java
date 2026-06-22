package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述被测方法在源码中的调用点。
 * 该模型用于把“谁调用了被测方法、如何传参、通过什么接收者调用”补充给大模型。
 */
public class MethodUsageModel {
    /** 调用点所在类名。 */
    private String callerClassName;
    /** 调用点所在文件路径。 */
    private String callerFilePath;
    /** 调用点所在方法名；字段初始化等非方法上下文使用占位文本。 */
    private String callerMethodName;
    /** 调用点所在方法签名。 */
    private String callerSignature;
    /** 调用表达式，例如 this.query(id) 或 query(id)。 */
    private String callExpression;
    /** 调用接收者；无接收者表示当前类内直接调用。 */
    private String receiver;
    /** 调用风格，例如 unqualified、this、class、externalReceiver。 */
    private String callStyle;
    /** 实参表达式列表。 */
    private List<String> argumentExpressions = new ArrayList<>();
    /** 调用点行号，无法获取时为 -1。 */
    private int lineNumber = -1;
    /** 调用关系置信度。 */
    private String confidence;
    /** 调用点所在语句或表达式片段。 */
    private String sourceSnippet;

    public String getCallerClassName() {
        return callerClassName;
    }

    public void setCallerClassName(String callerClassName) {
        this.callerClassName = callerClassName;
    }

    public String getCallerFilePath() {
        return callerFilePath;
    }

    public void setCallerFilePath(String callerFilePath) {
        this.callerFilePath = callerFilePath;
    }

    public String getCallerMethodName() {
        return callerMethodName;
    }

    public void setCallerMethodName(String callerMethodName) {
        this.callerMethodName = callerMethodName;
    }

    public String getCallerSignature() {
        return callerSignature;
    }

    public void setCallerSignature(String callerSignature) {
        this.callerSignature = callerSignature;
    }

    public String getCallExpression() {
        return callExpression;
    }

    public void setCallExpression(String callExpression) {
        this.callExpression = callExpression;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getCallStyle() {
        return callStyle;
    }

    public void setCallStyle(String callStyle) {
        this.callStyle = callStyle;
    }

    public List<String> getArgumentExpressions() {
        return argumentExpressions;
    }

    public void setArgumentExpressions(List<String> argumentExpressions) {
        this.argumentExpressions = argumentExpressions;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getSourceSnippet() {
        return sourceSnippet;
    }

    public void setSourceSnippet(String sourceSnippet) {
        this.sourceSnippet = sourceSnippet;
    }
}
