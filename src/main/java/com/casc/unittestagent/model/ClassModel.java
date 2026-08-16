package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 整类单元测试生成所需的静态类模型。 */
public class ClassModel {
    private String packageName;
    private String className;
    private String qualifiedName;
    private int sourceStartLine = -1;
    private int sourceEndLine = -1;
    private List<String> imports = new ArrayList<>();
    private List<String> classFields = new ArrayList<>();
    private List<MethodModel> methods = new ArrayList<>();
    private List<SkippedMethodModel> skippedMethods = new ArrayList<>();

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }
    public int getSourceStartLine() { return sourceStartLine; }
    public void setSourceStartLine(int sourceStartLine) { this.sourceStartLine = sourceStartLine; }
    public int getSourceEndLine() { return sourceEndLine; }
    public void setSourceEndLine(int sourceEndLine) { this.sourceEndLine = sourceEndLine; }
    public List<String> getImports() { return imports; }
    public void setImports(List<String> imports) { this.imports = imports == null ? new ArrayList<>() : imports; }
    public List<String> getClassFields() { return classFields; }
    public void setClassFields(List<String> classFields) { this.classFields = classFields == null ? new ArrayList<>() : classFields; }
    public List<MethodModel> getMethods() { return methods; }
    public void setMethods(List<MethodModel> methods) { this.methods = methods == null ? new ArrayList<>() : methods; }
    public List<SkippedMethodModel> getSkippedMethods() { return skippedMethods; }
    public void setSkippedMethods(List<SkippedMethodModel> skippedMethods) { this.skippedMethods = skippedMethods == null ? new ArrayList<>() : skippedMethods; }
}
