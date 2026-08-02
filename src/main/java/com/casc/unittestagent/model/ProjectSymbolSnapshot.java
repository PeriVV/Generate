package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 被测方法在当前 IntelliJ 项目索引中的不可变语义快照。
 */
public class ProjectSymbolSnapshot {
    private String moduleName = "";
    private String languageLevel = "";
    private String targetType = "";
    private CallableSymbol targetMethod;
    private List<TypeSymbol> types = new ArrayList<>();
    private List<String> unresolvedSymbols = new ArrayList<>();

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getLanguageLevel() {
        return languageLevel;
    }

    public void setLanguageLevel(String languageLevel) {
        this.languageLevel = languageLevel;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public CallableSymbol getTargetMethod() {
        return targetMethod;
    }

    public void setTargetMethod(CallableSymbol targetMethod) {
        this.targetMethod = targetMethod;
    }

    public List<TypeSymbol> getTypes() {
        return types;
    }

    public void setTypes(List<TypeSymbol> types) {
        this.types = types == null ? new ArrayList<>() : types;
    }

    public List<String> getUnresolvedSymbols() {
        return unresolvedSymbols;
    }

    public void setUnresolvedSymbols(List<String> unresolvedSymbols) {
        this.unresolvedSymbols = unresolvedSymbols == null ? new ArrayList<>() : unresolvedSymbols;
    }

    public boolean isUsable() {
        return targetMethod != null && targetMethod.isResolved();
    }
}
