package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述被测方法及一层内部调用中的方法调用上下文。
 * 该模型用于告诉大模型哪些对象方法、内部方法、静态方法和链式调用会影响测试路径。
 */
public class CallContextModel {
    private String kind;
    private String owner;
    private String methodName;
    private String expression;
    private String returnType;
    private String signature;
    private String sourceCode;
    private String parameterUsageSummary;
    private List<String> nestedCalls = new ArrayList<>();

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getParameterUsageSummary() {
        return parameterUsageSummary;
    }

    public void setParameterUsageSummary(String parameterUsageSummary) {
        this.parameterUsageSummary = parameterUsageSummary;
    }

    public List<String> getNestedCalls() {
        return nestedCalls;
    }

    public void setNestedCalls(List<String> nestedCalls) {
        this.nestedCalls = nestedCalls;
    }
}
