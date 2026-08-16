package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 从 IntelliJ 项目模型解析出的候选测试编译、执行环境。 */
public class ProjectTestEnvironment {
    Project project;
    Module module;
    String javaExecutable = "";
    File workingDirectory;
    String sourceFileName = "";
    String sourceText = "";
    String sourcePackage = "";
    int languageFeature;
    boolean previewLanguage;
    boolean jpms;
    final Set<File> platformClasspath = new LinkedHashSet<>();
    final Set<File> classpath = new LinkedHashSet<>();
    final Set<File> modulePath = new LinkedHashSet<>();
    final Set<File> sourcePath = new LinkedHashSet<>();
    final List<CompileDiagnostic> diagnostics = new ArrayList<>();

    public Project getProject() { return project; }
    public Module getModule() { return module; }
    public String getJavaExecutable() { return javaExecutable; }
    public File getWorkingDirectory() { return workingDirectory; }
    public String getSourceFileName() { return sourceFileName; }
    public String getSourceText() { return sourceText; }
    public String getSourcePackage() { return sourcePackage; }
    public int getLanguageFeature() { return languageFeature; }
    public boolean isPreviewLanguage() { return previewLanguage; }
    public boolean isJpms() { return jpms; }
    public Set<File> getPlatformClasspath() { return new LinkedHashSet<>(platformClasspath); }
    public Set<File> getClasspath() { return new LinkedHashSet<>(classpath); }
    public Set<File> getModulePath() { return new LinkedHashSet<>(modulePath); }
    public Set<File> getSourcePath() { return new LinkedHashSet<>(sourcePath); }
    public List<File> getRuntimeClasspath() {
        LinkedHashSet<File> result = new LinkedHashSet<>(classpath);
        result.addAll(modulePath);
        return new ArrayList<>(result);
    }
    public List<CompileDiagnostic> getDiagnostics() { return diagnostics; }
    public boolean isUsable() { return diagnostics.isEmpty(); }

    void error(String code, String message) {
        diagnostics.add(new CompileDiagnostic(
                "ERROR", code, message, -1, -1, "", false, "ENVIRONMENT", ""));
    }
}
