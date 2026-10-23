package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 一次临时编译的产物；关闭后递归清理临时目录。 */
public class CompiledTestArtifact implements AutoCloseable {
    private final com.casc.unittestagent.model.CandidateEvidence candidateEvidence;
    public com.casc.unittestagent.model.CandidateEvidence getCandidateEvidence() { return candidateEvidence; }
    private final com.intellij.openapi.project.Project project;
    public com.intellij.openapi.project.Project getProject() { return project; }
    private final Path root;
    private final Path classes;
    private final String testClassName;
    private final String targetClassName;
    private final String javaExecutable;
    private final boolean previewLanguage;
    private final File workingDirectory;
    private final TestRuntimeConfiguration runtimeConfiguration;
    private final List<File> runtimeClasspath;
    private final List<CompileDiagnostic> diagnostics;

    CompiledTestArtifact(Path root, Path classes, String testClassName, String targetClassName,
                         String javaExecutable, List<File> runtimeClasspath,
                         List<CompileDiagnostic> diagnostics) {
        this(root, classes, testClassName, targetClassName, javaExecutable, runtimeClasspath,
                diagnostics, false, null);
    }

    CompiledTestArtifact(Path root, Path classes, String testClassName, String targetClassName,
                         String javaExecutable, List<File> runtimeClasspath,
                         List<CompileDiagnostic> diagnostics, boolean previewLanguage,
                         File workingDirectory) {
        this(root, classes, testClassName, targetClassName, javaExecutable, runtimeClasspath,
                diagnostics, previewLanguage, workingDirectory, TestRuntimeConfiguration.defaults(workingDirectory));
    }

    CompiledTestArtifact(Path root, Path classes, String testClassName, String targetClassName,
                         String javaExecutable, List<File> runtimeClasspath,
                         List<CompileDiagnostic> diagnostics, boolean previewLanguage,
                         File workingDirectory, TestRuntimeConfiguration runtimeConfiguration) {
        this(root, classes, testClassName, targetClassName, javaExecutable, runtimeClasspath, diagnostics,
                previewLanguage, workingDirectory, runtimeConfiguration, null, null);
    }

    CompiledTestArtifact(Path root, Path classes, String testClassName, String targetClassName,
                         String javaExecutable, List<File> runtimeClasspath, List<CompileDiagnostic> diagnostics,
                         boolean previewLanguage, File workingDirectory, TestRuntimeConfiguration runtimeConfiguration,
                         com.casc.unittestagent.model.CandidateEvidence candidateEvidence, com.intellij.openapi.project.Project project) {
        this.project = project;
        this.candidateEvidence = candidateEvidence;
        this.root = root;
        this.classes = classes;
        this.testClassName = testClassName;
        this.targetClassName = targetClassName;
        this.javaExecutable = javaExecutable;
        this.previewLanguage = previewLanguage;
        this.workingDirectory = workingDirectory;
        this.runtimeConfiguration = runtimeConfiguration;
        this.runtimeClasspath = runtimeClasspath == null ? new ArrayList<>() : runtimeClasspath;
        this.diagnostics = diagnostics == null ? new ArrayList<>() : diagnostics;
    }

    public boolean isSuccessful() { return diagnostics.isEmpty(); }
    public Path getRoot() { return root; }
    public Path getClasses() { return classes; }
    public String getTestClassName() { return testClassName; }
    public String getTargetClassName() { return targetClassName; }
    public String getJavaExecutable() { return javaExecutable; }
    public boolean isPreviewLanguage() { return previewLanguage; }
    public File getWorkingDirectory() { return workingDirectory; }
    public TestRuntimeConfiguration getRuntimeConfiguration() { return runtimeConfiguration; }
    public List<File> getRuntimeClasspath() { return runtimeClasspath; }
    public List<CompileDiagnostic> getDiagnostics() { return diagnostics; }

    @Override
    public void close() {
        deleteRecursively(root);
    }

    /** 递归删除目录；删除失败静默忽略，与 close 的清理语义一致。 */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
