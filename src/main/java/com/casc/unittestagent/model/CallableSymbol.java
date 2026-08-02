package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述一个由 IntelliJ PSI 解析出的可调用 Java 符号。
 */
public class CallableSymbol {
    private String id = "";
    private String ownerQualifiedName = "";
    private String name = "";
    private String returnType = "";
    private List<String> parameterTypes = new ArrayList<>();
    private String visibility = "package-private";
    private boolean staticMethod;
    private boolean varArgs;
    private boolean constructor;
    private boolean resolved = true;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerQualifiedName() {
        return ownerQualifiedName;
    }

    public void setOwnerQualifiedName(String ownerQualifiedName) {
        this.ownerQualifiedName = ownerQualifiedName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(List<String> parameterTypes) {
        this.parameterTypes = parameterTypes == null ? new ArrayList<>() : parameterTypes;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public boolean isStaticMethod() {
        return staticMethod;
    }

    public void setStaticMethod(boolean staticMethod) {
        this.staticMethod = staticMethod;
    }

    public boolean isVarArgs() {
        return varArgs;
    }

    public void setVarArgs(boolean varArgs) {
        this.varArgs = varArgs;
    }

    public boolean isConstructor() {
        return constructor;
    }

    public void setConstructor(boolean constructor) {
        this.constructor = constructor;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }
}
