package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CandidateEvidence;
import com.casc.unittestagent.model.CandidateIdentity;
import com.casc.unittestagent.util.CodeFingerprint;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.LocalFileSystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** Captures inputs before compilation and rechecks them before evidence is used for writing. */
public final class CandidateEvidenceFactory {
    private CandidateEvidenceFactory() { }

    public static CandidateEvidence capture(ProjectTestEnvironment environment, String code) {
        var inputs = new TreeMap<String, String>();
        if (!environment.getSourceFilePath().isBlank()) add(inputs, Path.of(environment.getSourceFilePath()));
        addFiles(inputs, environment.getPlatformClasspath());
        addFiles(inputs, environment.getRuntimeClasspath());
        addFiles(inputs, environment.getAnnotationProcessorPath());
        addFiles(inputs, ProjectSourceDependencyResolver.resolve(environment.getSourcePath(), environment.getSourceText(), code));
        addExecutable(inputs, environment.getJavaExecutable());
        var runtime = environment.getRuntimeConfiguration();
        addExecutable(inputs, runtime.getJavaExecutable());
        Path directory = !environment.getSourceFilePath().isBlank() ? Path.of(environment.getSourceFilePath()).getParent()
                : environment.getWorkingDirectory() == null ? null : environment.getWorkingDirectory().toPath();
        Path boundary = environment.getProject() == null || environment.getProject().getBasePath() == null
                ? directory : Path.of(environment.getProject().getBasePath());
        if (boundary != null && directory != null && !directory.startsWith(boundary)) boundary = null;
        for (Path current = directory; current != null; current = current.getParent()) {
            for (String name : new String[]{"pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
                    "settings.gradle.kts", "gradle.properties", ".unitmind/test-runtime.json", ".mvn/maven.config",
                    ".mvn/jvm.config", ".mvn/extensions.xml", "gradle/wrapper/gradle-wrapper.properties",
                    ".mvn/wrapper/maven-wrapper.properties"}) add(inputs, current.resolve(name));
            if (current.equals(boundary) || boundary != null && !current.startsWith(boundary)) break;
        }
        // An absent file is also an input: creating a local override invalidates old proof.
        if (boundary != null) add(inputs, boundary.resolve(".unitmind/test-runtime.json"));
        add(inputs, Path.of(System.getProperty("user.home"), ".m2", "settings.xml"));
        long revision = rootsRevision(environment.getProject());
        String settings = environment.getPlatformClasspath() + "\n" + environment.getRuntimeClasspath() + "\n"
                + environment.getAnnotationProcessorPath() + "\n" + environment.getSourcePath() + "\n"
                + environment.isProcessorsFromClasspath() + "\n" + environment.isProcessorModulePath() + "\n"
                + environment.getJavaExecutable() + "\n" + environment.getLanguageFeature() + "\n"
                + environment.isPreviewLanguage() + "\n" + environment.isJpms() + "\n"
                + environment.getAnnotationProcessors() + "\n" + new TreeMap<>(environment.getAnnotationProcessorOptions())
                + "\n" + runtime.getJvmArguments() + "\n" + new TreeMap<>(runtime.getSystemProperties())
                + "\n" + new TreeMap<>(runtime.getEnvironment()) + "\n" + new java.util.TreeSet<>(runtime.getExcludedEnvironment())
                + "\n" + runtime.isInheritEnvironment() + "\n" + runtime.getWorkingDirectory() + "\n" + runtime.getJavaExecutable();
        String environmentHash = CodeFingerprint.sha256(settings + "\n" + revision + "\n" + inputs);
        return new CandidateEvidence(new CandidateIdentity(CodeFingerprint.sha256(environment.getSourceText()),
                CodeFingerprint.sha256(code), environmentHash), revision, inputs);
    }

    public static boolean isCurrent(Project project, CandidateEvidence evidence) {
        if (evidence == null || project != null && project.isDisposed()
                || rootsRevision(project) != evidence.projectRootsRevision()) return false;
        return evidence.inputs().entrySet().stream().allMatch(entry -> entry.getValue().equals(stamp(Path.of(entry.getKey()))));
    }

    private static long rootsRevision(Project project) {
        return project == null ? 0 : ReadAction.compute(() -> ProjectRootModificationTracker.getInstance(project).getModificationCount());
    }

    private static void addExecutable(Map<String, String> inputs, String executable) {
        if (executable == null || executable.isBlank()) return;
        Path binary = Path.of(executable).toAbsolutePath().normalize();
        add(inputs, binary);
        if (binary.getParent() != null && binary.getParent().getParent() != null) {
            add(inputs, binary.getParent().getParent().resolve("release"));
            add(inputs, binary.getParent().resolve(binary.getFileName().toString().endsWith(".exe") ? "javac.exe" : "javac"));
        }
    }

    private static void addFiles(Map<String, String> inputs, Collection<File> files) {
        for (File file : files) {
            if (file == null) continue;
            Path path = file.toPath();
            add(inputs, path);
            if (Files.isDirectory(path)) {
                try (var contents = Files.walk(path)) {
                    contents.sorted().forEach(entry -> add(inputs, entry));
                } catch (IOException ex) { throw new IllegalStateException("无法捕获测试环境目录快照。", ex); }
            }
        }
    }

    private static void add(Map<String, String> inputs, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String value = stamp(absolute);
        if (value.equals("UNREADABLE")) throw new IllegalStateException("无法读取验证输入：" + absolute.getFileName());
        inputs.put(absolute.toString(), value);
    }

    private static String stamp(Path path) {
        try {
            if (!Files.exists(path)) return "ABSENT";
            if (Files.isDirectory(path)) return "DIRECTORY:" + Files.getLastModifiedTime(path);
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!(name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json")
                    || name.endsWith(".properties") || name.endsWith(".gradle") || name.endsWith(".kts")
                    || name.endsWith(".config") || name.endsWith(".txt") || name.equals("release")))
                return "BINARY:" + Files.size(path) + ":" + Files.getLastModifiedTime(path);
            String disk = CodeFingerprint.sha256(java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            String live = com.intellij.openapi.application.ApplicationManager.getApplication() == null ? "" : ReadAction.compute(() -> {
                var virtual = LocalFileSystem.getInstance().findFileByNioFile(path);
                var document = virtual == null ? null : FileDocumentManager.getInstance().getCachedDocument(virtual);
                return document == null || !FileDocumentManager.getInstance().isDocumentUnsaved(document)
                        ? "" : CodeFingerprint.sha256(document.getText());
            });
            return "TEXT:" + disk + ":" + live;
        } catch (IOException ex) { return "UNREADABLE"; }
    }
}
