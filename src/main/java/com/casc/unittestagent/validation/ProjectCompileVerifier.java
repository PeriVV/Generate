package com.casc.unittestagent.validation;

import com.casc.unittestagent.coverage.ProjectTestEnvironment;
import com.casc.unittestagent.coverage.ProjectTestEnvironmentResolver;
import com.casc.unittestagent.coverage.MockitoStaticMockSupport;
import com.casc.unittestagent.coverage.CoverageCandidatePreflight;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.util.CodeFingerprint;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilationException;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 使用 IntelliJ 项目模型和内置 Java 编译能力验证候选测试源码。
 */
public class ProjectCompileVerifier implements TestCodeValidator {
    private final ProjectTestEnvironmentResolver environmentResolver = new ProjectTestEnvironmentResolver();
    @Override
    public ValidationResult validate(Project project, PsiFile sourceFile, GeneratedTestFile generatedFile, String code) {
        long started = System.currentTimeMillis();
        ValidationResult result = new ValidationResult();
        result.setCodeHash(CodeFingerprint.sha256(code));
        try {
            List<CompileDiagnostic> syntaxDiagnostics = syntaxDiagnostics(project, generatedFile, code);
            syntaxDiagnostics.addAll(CoverageCandidatePreflight.inspect(code));
            if (!syntaxDiagnostics.isEmpty()) {
                result.setStatus(ValidationStatus.FAILED);
                result.setDiagnostics(syntaxDiagnostics);
                return result;
            }

            CompilationEnvironment environment = readEnvironment(project, sourceFile);
            if (!environment.diagnostics.isEmpty()) {
                result.setStatus(ValidationStatus.ENVIRONMENT_ERROR);
                result.setDiagnostics(environment.diagnostics);
                return result;
            }
            MockitoStaticMockSupport staticMockSupport = MockitoStaticMockSupport.detect(environment.classpath);
            if (MockitoStaticMockSupport.usesStaticMocking(code) && !staticMockSupport.isSupported()) {
                result.setStatus(ValidationStatus.FAILED);
                result.setDiagnostics(Collections.singletonList(new CompileDiagnostic(
                        "ERROR", "MOCKITO_STATIC_MOCK_UNSUPPORTED",
                        "当前项目不支持 Mockito 静态 Mock：" + staticMockSupport.getDescription()
                                + "。请删除 mockStatic/MockedStatic，不要通过修改项目依赖规避。",
                        -1, -1, "", true)));
                return result;
            }
            return compile(environment, generatedFile, code, result);
        } catch (Exception ex) {
            result.setStatus(ValidationStatus.ENVIRONMENT_ERROR);
            result.setDiagnostics(Collections.singletonList(new CompileDiagnostic(
                    "ERROR", "VALIDATOR_FAILURE", safeMessage(ex), -1, -1, "", false,
                    "ENVIRONMENT", "")));
            return result;
        } finally {
            result.setDurationMillis(System.currentTimeMillis() - started);
        }
    }

    private List<CompileDiagnostic> syntaxDiagnostics(Project project, GeneratedTestFile generatedFile, String code) {
        if (project == null || code == null || code.isBlank()) {
            return Collections.singletonList(new CompileDiagnostic(
                    "ERROR", "EMPTY_SOURCE", "测试源码为空。", -1, -1, "", true));
        }
        return ApplicationManager.getApplication().runReadAction((Computable<List<CompileDiagnostic>>) () -> {
            String fileName = generatedFile == null || generatedFile.getFileName() == null
                    || generatedFile.getFileName().isBlank() ? "GeneratedTest.java" : generatedFile.getFileName();
            PsiFile candidate = PsiFileFactory.getInstance(project)
                    .createFileFromText(fileName, JavaFileType.INSTANCE, code);
            List<CompileDiagnostic> diagnostics = new ArrayList<>();
            for (PsiErrorElement error : PsiTreeUtil.findChildrenOfType(candidate, PsiErrorElement.class)) {
                int line = lineNumber(code, error.getTextOffset());
                diagnostics.add(new CompileDiagnostic(
                        "ERROR", "JAVA_SYNTAX", error.getErrorDescription(), line, -1,
                        sourceLine(code, line), true));
            }
            return diagnostics;
        });
    }

    private CompilationEnvironment readEnvironment(Project project, PsiFile sourceFile) {
        CompilationEnvironment environment = new CompilationEnvironment();
        ProjectTestEnvironment resolved = environmentResolver.resolve(project, sourceFile);
        environment.project = resolved.getProject();
        environment.module = resolved.getModule();
        environment.sourceFileName = resolved.getSourceFileName();
        environment.sourceText = resolved.getSourceText();
        environment.sourcePackage = resolved.getSourcePackage();
        environment.languageFeature = resolved.getLanguageFeature();
        environment.previewLanguage = resolved.isPreviewLanguage();
        environment.jpms = resolved.isJpms();
        environment.platformClasspath.addAll(resolved.getPlatformClasspath());
        environment.classpath.addAll(resolved.getClasspath());
        environment.modulePath.addAll(resolved.getModulePath());
        environment.sourcePath.addAll(resolved.getSourcePath());
        environment.diagnostics.addAll(resolved.getDiagnostics());
        return environment;
    }

    /**
     * Gradle projects can be imported as separate {@code *.main} and {@code *.test}
     * IntelliJ modules. Test-only dependencies then belong to the test module rather
     * than to the module containing the selected production source file.
     */
    private List<Module> testClasspathModules(Project project, Module sourceModule) {
        List<Module> modules = new ArrayList<>();
        modules.add(sourceModule);
        for (Module candidate : ModuleManager.getInstance(project).getModules()) {
            if (candidate == sourceModule || !moduleDependsOn(candidate, sourceModule)) {
                continue;
            }
            if (hasTestSourceRoot(project, candidate) || looksLikeTestModule(candidate)) {
                modules.add(candidate);
            }
        }
        return modules;
    }

    private boolean moduleDependsOn(Module candidate, Module sourceModule) {
        Set<Module> visited = new LinkedHashSet<>();
        Deque<Module> pending = new ArrayDeque<>();
        pending.add(candidate);
        while (!pending.isEmpty()) {
            Module current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (Module dependency : ModuleRootManager.getInstance(current).getDependencies()) {
                if (dependency == sourceModule) {
                    return true;
                }
                pending.addLast(dependency);
            }
        }
        return false;
    }

    private boolean hasTestSourceRoot(Project project, Module module) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(true)) {
            if (index.isInTestSourceContent(root)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeTestModule(Module module) {
        String name = module.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".test")
                || name.endsWith("-test")
                || name.endsWith("_test")
                || name.endsWith(":test");
    }

    private boolean hasClass(JavaPsiFacade facade, List<Module> modules, String qualifiedName) {
        String classFile = qualifiedName.replace('.', '/') + ".class";
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true);
            if (facade.findClass(qualifiedName, scope) != null) {
                return true;
            }
            for (VirtualFile root : ModuleRootManager.getInstance(module)
                    .orderEntries().recursively().classes().getRoots()) {
                if (root.findFileByRelativePath(classFile) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private ValidationResult compile(CompilationEnvironment environment,
                                     GeneratedTestFile generatedFile,
                                     String code,
                                     ValidationResult result) {
        Path tempRoot = null;
        try {
            tempRoot = Files.createTempDirectory("unitmind-compile-");
            Path sourceDir = tempRoot.resolve("source");
            Path outputDir = tempRoot.resolve("classes");
            Files.createDirectories(sourceDir);
            Files.createDirectories(outputDir);
            Path packageDir = packageDirectory(sourceDir,
                    generatedFile == null ? "" : generatedFile.getPackageName());
            Files.createDirectories(packageDir);
            String fileName = generatedFile == null || generatedFile.getFileName() == null
                    || generatedFile.getFileName().isBlank() ? "GeneratedTest.java" : generatedFile.getFileName();
            Path source = packageDir.resolve(fileName);
            Files.writeString(source, code, StandardCharsets.UTF_8);
            Path targetSource = materializeCurrentSource(environment, sourceDir, source);
            environment.sourcePath.add(sourceDir.toFile());

            List<File> compilationSources = new ArrayList<>();
            if (targetSource != null && !targetSource.equals(source)) {
                compilationSources.add(targetSource.toFile());
            }
            compilationSources.add(source.toFile());

            try {
                CompilerManager.getInstance(environment.project).compileJavaCode(
                        compilerOptions(environment),
                        environment.platformClasspath,
                        environment.classpath,
                        Collections.emptyList(),
                        environment.modulePath,
                        environment.sourcePath,
                        compilationSources,
                        outputDir.toFile());
                result.setStatus(ValidationStatus.PASSED);
                result.setDiagnostics(new ArrayList<>());
                return result;
            } catch (CompilationException ex) {
                List<CompileDiagnostic> diagnostics = new ArrayList<>();
                for (CompilationException.Message message : ex.getMessages()) {
                    if (message.getCategory() != CompilerMessageCategory.ERROR) {
                        continue;
                    }
                    String text = message.getText() == null ? "" : message.getText();
                    diagnostics.add(compilerDiagnostic(
                            message, text, code, environment.sourceText, source, targetSource));
                }
                if (diagnostics.isEmpty()) {
                    diagnostics.add(new CompileDiagnostic(
                            "ERROR", "JAVA_COMPILE", sanitize(safeMessage(ex)), -1, -1, "", false,
                            "UNKNOWN", ""));
                }
                result.setDiagnostics(diagnostics);
                result.setStatus(diagnostics.stream().anyMatch(diagnostic -> !diagnostic.isRepairable())
                        ? ValidationStatus.ENVIRONMENT_ERROR : ValidationStatus.FAILED);
                return result;
            }
        } catch (IOException ex) {
            result.setStatus(ValidationStatus.ENVIRONMENT_ERROR);
            result.setDiagnostics(Collections.singletonList(new CompileDiagnostic(
                    "ERROR", "TEMP_IO_FAILURE", safeMessage(ex), -1, -1, "", false,
                    "ENVIRONMENT", "")));
            return result;
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private String diagnosticCode(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("cannot find symbol") || text.contains("找不到符号")) {
            return "CANNOT_FIND_SYMBOL";
        }
        if (text.contains("no suitable method")
                || text.contains("无法将方法")
                || text.contains("应用到给定类型")
                || text.contains("不适用于")) {
            return "NO_SUITABLE_METHOD";
        }
        if (text.contains("incompatible types") || text.contains("不兼容的类型")) {
            return "INCOMPATIBLE_TYPES";
        }
        if (text.contains("private access") || text.contains("has private access") || text.contains("是 private 访问控制")) {
            return "INACCESSIBLE_MEMBER";
        }
        if (text.contains("reference to") && text.contains("is ambiguous") || text.contains("引用不明确")) {
            return "AMBIGUOUS_REFERENCE";
        }
        if (text.contains("non-static") && text.contains("static context") || text.contains("无法从静态上下文")) {
            return "STATIC_CONTEXT";
        }
        if (text.contains("package") && text.contains("does not exist") || text.contains("程序包") && text.contains("不存在")) {
            return "PACKAGE_NOT_FOUND";
        }
        if (text.contains("module") && (text.contains("not found") || text.contains("not in the module graph"))) {
            return "MODULE_PATH_ERROR";
        }
        if (text.contains("invalid source release")
                || text.contains("source option") && text.contains("is no longer supported")
                || text.contains("target option") && text.contains("is no longer supported")
                || text.contains("release version") && text.contains("not supported")
                || text.contains("不再支持源选项")
                || text.contains("不再支持目标选项")) {
            return "UNSUPPORTED_LANGUAGE_LEVEL";
        }
        return "JAVA_COMPILE";
    }

    private boolean isRepairable(String message) {
        String code = diagnosticCode(message);
        return !"MODULE_PATH_ERROR".equals(code)
                && !"UNSUPPORTED_LANGUAGE_LEVEL".equals(code)
                && !message.toLowerCase(Locale.ROOT).contains("bad class file")
                && !message.toLowerCase(Locale.ROOT).contains("unsupported class file");
    }

    private void addRoots(Set<File> target, VirtualFile[] roots) {
        if (roots == null) {
            return;
        }
        for (VirtualFile root : roots) {
            addRoot(target, root);
        }
    }

    private Path materializeCurrentSource(CompilationEnvironment environment,
                                          Path sourceDir,
                                          Path candidateSource) throws IOException {
        if (environment.sourceText == null || environment.sourceText.isBlank()
                || environment.sourceFileName == null || environment.sourceFileName.isBlank()) {
            return null;
        }
        Path packageDir = packageDirectory(sourceDir, environment.sourcePackage);
        Files.createDirectories(packageDir);
        Path source = packageDir.resolve(environment.sourceFileName);
        if (!source.equals(candidateSource)) {
            Files.writeString(source, environment.sourceText, StandardCharsets.UTF_8);
        }
        return source;
    }

    private CompileDiagnostic compilerDiagnostic(CompilationException.Message message,
                                                 String text,
                                                 String testCode,
                                                 String targetCode,
                                                 Path testSource,
                                                 Path targetSource) {
        String url = message.getUrl();
        boolean targetFailure = sourceMatches(url, targetSource)
                || (!sourceMatches(url, testSource) && mentionsFile(text, targetSource));
        String origin = targetFailure ? "TARGET_SOURCE" : "TEST_SOURCE";
        Path diagnosticSource = targetFailure ? targetSource : testSource;
        String snippet = sourceLine(targetFailure ? targetCode : testCode, message.getLine());
        return new CompileDiagnostic(
                "ERROR",
                diagnosticCode(text),
                sanitize(text),
                message.getLine(),
                message.getColumn(),
                snippet,
                !targetFailure && isRepairable(text),
                origin,
                diagnosticSource == null ? "" : diagnosticSource.getFileName().toString());
    }

    private boolean sourceMatches(String url, Path source) {
        if (url == null || url.isBlank() || source == null) {
            return false;
        }
        Path expected = source.toAbsolutePath().normalize();
        try {
            if (url.startsWith("file:")) {
                return Path.of(java.net.URI.create(url)).toAbsolutePath().normalize().equals(expected);
            }
        } catch (IllegalArgumentException ignored) {
            // 部分编译器返回的 URL 不是严格 URI，继续使用规范化字符串兜底。
        }
        String normalizedUrl = url.replace('\\', '/');
        String normalizedPath = expected.toString().replace('\\', '/');
        return normalizedUrl.equals(normalizedPath) || normalizedUrl.endsWith(normalizedPath);
    }

    private boolean mentionsFile(String text, Path source) {
        return source != null && source.getFileName() != null && text != null
                && text.contains(source.getFileName().toString());
    }

    private List<String> compilerOptions(CompilationEnvironment environment) {
        List<String> options = new ArrayList<>();
        options.add("-encoding");
        options.add("UTF-8");
        if (environment.languageFeature > 0) {
            String feature = Integer.toString(environment.languageFeature);
            options.add("-source");
            options.add(feature);
            options.add("-target");
            options.add(feature);
        }
        if (environment.previewLanguage) {
            options.add("--enable-preview");
        }
        if (environment.jpms && !environment.modulePath.isEmpty()) {
            options.add("--add-modules");
            options.add("ALL-MODULE-PATH");
        }
        return options;
    }

    private Path packageDirectory(Path sourceRoot, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return sourceRoot;
        }
        return sourceRoot.resolve(packageName.replace('.', File.separatorChar));
    }

    private void addRoot(Set<File> target, VirtualFile root) {
        if (root == null) {
            return;
        }
        VirtualFile local = root.getFileSystem() instanceof JarFileSystem
                ? JarFileSystem.getInstance().getVirtualFileForJar(root)
                : root;
        if (local != null && local.isInLocalFileSystem()) {
            target.add(VfsUtilCore.virtualToIoFile(local));
        }
    }

    private int lineNumber(String code, int offset) {
        int line = 1;
        for (int index = 0; index < Math.min(offset, code.length()); index++) {
            if (code.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String sourceLine(String code, long lineNumber) {
        if (code == null || lineNumber <= 0) {
            return "";
        }
        String[] lines = code.split("\\R", -1);
        int index = (int) lineNumber - 1;
        return index >= 0 && index < lines.length ? lines[index].strip() : "";
    }

    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?i)(?:[A-Z]:)?[/\\\\][^\\s:]+(?:[/\\\\][^\\s:]+)+", "<path>");
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件清理失败不覆盖真实编译结果。
                }
            });
        } catch (IOException ignored) {
            // 临时目录位于系统目录，不影响用户项目内容。
        }
    }

    private static class CompilationEnvironment {
        private Project project;
        private Module module;
        private String sourceFileName;
        private String sourceText;
        private String sourcePackage;
        private int languageFeature;
        private boolean previewLanguage;
        private boolean jpms;
        private final Set<File> platformClasspath = new LinkedHashSet<>();
        private final Set<File> classpath = new LinkedHashSet<>();
        private final Set<File> modulePath = new LinkedHashSet<>();
        private final Set<File> sourcePath = new LinkedHashSet<>();
        private final List<CompileDiagnostic> diagnostics = new ArrayList<>();

        private void error(String code, String message) {
            diagnostics.add(new CompileDiagnostic(
                    "ERROR", code, message, -1, -1, "", false, "ENVIRONMENT", ""));
        }
    }
}
