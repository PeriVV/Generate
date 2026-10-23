package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从 IntelliJ 项目模型解析出的候选测试编译、执行环境。 */
public class ProjectTestEnvironment {
    Project project;
    Module module;
    String javaExecutable = "";
    File workingDirectory;
    TestRuntimeConfiguration runtimeConfiguration;
    public TestRuntimeConfiguration getRuntimeConfiguration() {
        return runtimeConfiguration == null ? TestRuntimeConfiguration.defaults(workingDirectory) : runtimeConfiguration;
    }
    String sourceFileName = "";
    String sourceFilePath = "";
    public String getSourceFilePath() { return sourceFilePath; }
    String sourceText = "";
    String sourcePackage = "";
    int languageFeature;
    boolean previewLanguage;
    boolean jpms;
    boolean bundledJunit;
    boolean bundledMockito;
    boolean offlineTestLibrary;
    File offlineMockitoAgent;
    final List<File> bundledMockitoLibraries = new ArrayList<>();
    String bundledMockitoVersion = "";
    final Set<File> platformClasspath = new LinkedHashSet<>();
    final Set<File> classpath = new LinkedHashSet<>();
    final Set<File> modulePath = new LinkedHashSet<>();
    final Set<File> sourcePath = new LinkedHashSet<>();
    final Set<File> annotationProcessorPath = new LinkedHashSet<>();
    final Set<String> annotationProcessors = new LinkedHashSet<>();
    final Map<String, String> annotationProcessorOptions = new LinkedHashMap<>();
    boolean processorsFromClasspath;
    boolean processorModulePath;
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
    public boolean isBundledJunit() { return bundledJunit; }
    public boolean isBundledMockito() { return bundledMockito; }
    public boolean hasOfflineTestLibrary() { return offlineTestLibrary; }
    public String getTestEnvironmentNote() {
        if (!bundledJunit && !bundledMockito && !offlineTestLibrary) return "";
        return (bundledJunit ? "已补齐插件内置 JUnit 5。" : "")
                + (bundledMockito ? "已补齐插件内置 " + bundledMockitoVersion + " 及配套依赖。" : "")
                + (offlineTestLibrary ? "已配置 IDEA 离线测试库。" : "")
                + "测试在沙箱中验证；纳入 Maven/Gradle 或 CI 前仍需配置项目测试依赖。";
    }
    public Set<File> getPlatformClasspath() { return new LinkedHashSet<>(platformClasspath); }
    public Set<File> getClasspath() { return new LinkedHashSet<>(classpath); }
    public Set<File> getModulePath() { return new LinkedHashSet<>(modulePath); }
    public Set<File> getSourcePath() { return new LinkedHashSet<>(sourcePath); }
    public Set<File> getAnnotationProcessorPath() { return new LinkedHashSet<>(annotationProcessorPath); }
    public Set<String> getAnnotationProcessors() { return new LinkedHashSet<>(annotationProcessors); }
    public Map<String, String> getAnnotationProcessorOptions() {
        return new LinkedHashMap<>(annotationProcessorOptions);
    }
    public boolean isProcessorsFromClasspath() { return processorsFromClasspath; }
    public boolean isProcessorModulePath() { return processorModulePath; }
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
