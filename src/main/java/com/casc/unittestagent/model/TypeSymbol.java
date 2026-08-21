package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述测试生成所需的一个项目类型及其可调用成员。
 */
public class TypeSymbol {
    private String qualifiedName = "";
    private String kind = "class";
    private boolean finalType;
    private boolean abstractType;
    private boolean sealedType;
    private List<String> permittedSubtypes = new ArrayList<>();
    private List<String> concreteSubtypes = new ArrayList<>();
    private List<CallableSymbol> constructors = new ArrayList<>();
    private List<CallableSymbol> methods = new ArrayList<>();

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public boolean isFinalType() {
        return finalType;
    }

    public void setFinalType(boolean finalType) {
        this.finalType = finalType;
    }

    public boolean isAbstractType() { return abstractType; }
    public void setAbstractType(boolean abstractType) { this.abstractType = abstractType; }
    public boolean isSealedType() { return sealedType; }
    public void setSealedType(boolean sealedType) { this.sealedType = sealedType; }
    public List<String> getPermittedSubtypes() { return permittedSubtypes; }
    public void setPermittedSubtypes(List<String> values) {
        permittedSubtypes = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
    public List<String> getConcreteSubtypes() { return concreteSubtypes; }
    public void setConcreteSubtypes(List<String> values) {
        concreteSubtypes = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    public List<CallableSymbol> getConstructors() {
        return constructors;
    }

    public void setConstructors(List<CallableSymbol> constructors) {
        this.constructors = constructors == null ? new ArrayList<>() : constructors;
    }

    public List<CallableSymbol> getMethods() {
        return methods;
    }

    public void setMethods(List<CallableSymbol> methods) {
        this.methods = methods == null ? new ArrayList<>() : methods;
    }
}
