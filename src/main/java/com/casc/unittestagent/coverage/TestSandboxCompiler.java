package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.intellij.openapi.compiler.CompilationException;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 将当前被测源码和某个候选测试类编译到独立临时目录。 */
public class TestSandboxCompiler {
    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(TestSandboxCompiler.class);
    public CompiledTestArtifact compileGeneratedCandidate(ProjectTestEnvironment environment, GeneratedTestFile testFile,
                                                           String code, String target, List<String> sources) {
        List<CompileDiagnostic> diagnostics = new ArrayList<>(MockWiringAnalyzer.preflight(sources, code));
        diagnostics.addAll(SpringFixtureAnalyzer.preflight(sources, code));
        if (!diagnostics.isEmpty()) return new CompiledTestArtifact(null, null, "", target, "", List.of(), diagnostics);
        return compile(environment, testFile, code, target);
    }

    public CompiledTestArtifact compile(ProjectTestEnvironment environment,
                                        GeneratedTestFile testFile,
                                        String testCode,
                                        String targetClassName) {
        Path root = null;
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        try {
            if (environment == null || !environment.isUsable()) {
                if (environment != null) diagnostics.addAll(environment.getDiagnostics());
                if (diagnostics.isEmpty()) diagnostics.add(environmentError("PROJECT_CONTEXT_MISSING", "缺少测试编译环境。"));
                return new CompiledTestArtifact(null, null, "", targetClassName, "", Collections.emptyList(), diagnostics);
            }
            root = Files.createTempDirectory("unitmind-coverage-");
            Path sourceRoot = root.resolve("source");
            Path classes = root.resolve("classes");
            Files.createDirectories(sourceRoot);
            Files.createDirectories(classes);

            String testPackage = value(testFile == null ? null : testFile.getPackageName());
            String testSimpleName = value(testFile == null ? null : testFile.getClassName());
            if (testSimpleName.isBlank()) testSimpleName = "GeneratedTest";
            String testFileName = testSimpleName + ".java";
            Path testSource = packageDirectory(sourceRoot, testPackage).resolve(testFileName);
            Files.createDirectories(testSource.getParent());
            Files.writeString(testSource, value(testCode), StandardCharsets.UTF_8);

            JavaTestSourceInspector.Inspection inspection = JavaTestSourceInspector.inspect(testCode);
            if (inspection.isParsed() && !testPackage.equals(inspection.getPackageName())) {
                diagnostics.add(repairableError("TEST_PACKAGE_MISMATCH",
                        "候选测试声明的包名为 '" + inspection.getPackageName()
                                + "'，但期望包名为 '" + testPackage + "'。请修正 package 声明。"));
            }
            if (inspection.isParsed() && !inspection.declares(testSimpleName)) {
                diagnostics.add(repairableError("TEST_CLASS_NAME_MISMATCH",
                        "候选测试未声明顶层类 " + testSimpleName + "；实际顶层类："
                                + inspection.getTopLevelTypes() + "。请返回完整的 " + testSimpleName + " 测试类。"));
            }
            MockitoStaticMockSupport staticMockSupport =
                    MockitoStaticMockSupport.detect(environment.getRuntimeClasspath());
            if (MockitoStaticMockSupport.usesStaticMocking(testCode) && !staticMockSupport.isSupported()) {
                diagnostics.add(repairableError("MOCKITO_STATIC_MOCK_UNSUPPORTED",
                        "当前项目不支持 Mockito 静态 Mock：" + staticMockSupport.getDescription()
                                + "。请删除 mockStatic/MockedStatic，直接执行真实的无副作用静态方法，"
                                + "或通过可注入依赖设计测试；不要修改项目构建依赖。"));
            }
            diagnostics.addAll(CoverageCandidatePreflight.inspectGeneratedRegions(testCode));
            if (!diagnostics.isEmpty()) {
                return new CompiledTestArtifact(root, classes, qualifiedName(testPackage, testSimpleName),
                        targetClassName, environment.javaExecutable, Collections.emptyList(), diagnostics);
            }

            Path targetSource = packageDirectory(sourceRoot, environment.sourcePackage)
                    .resolve(environment.sourceFileName);
            Files.createDirectories(targetSource.getParent());
            if (!targetSource.equals(testSource)) {
                Files.writeString(targetSource, environment.sourceText, StandardCharsets.UTF_8);
            }

            List<File> sourcePath = new ArrayList<>();
            sourcePath.add(sourceRoot.toFile());
            sourcePath.addAll(environment.sourcePath);
            List<File> sources = new ArrayList<>();
            for (File dependency : ProjectSourceDependencyResolver.resolve(
                    environment.sourcePath, environment.sourceText, testCode)) {
                if (!ProjectSourceDependencyResolver.isSourceFile(dependency,
                        environment.sourcePath, environment.sourcePackage, environment.sourceFileName)
                        && !ProjectSourceDependencyResolver.isSourceFile(dependency,
                        environment.sourcePath, testPackage, testFileName)) {
                    sources.add(copySourceToSandbox(dependency, sourceRoot));
                }
            }
            sources.add(targetSource.toFile());
            sources.add(testSource.toFile());
            try {
                CompilerManager.getInstance(environment.project).compileJavaCode(
                        compilerOptions(environment),
                        environment.platformClasspath,
                        environment.classpath,
                        Collections.emptyList(),
                        environment.modulePath,
                        sourcePath,
                        sources,
                        classes.toFile());
            } catch (CompilationException ex) {
                diagnostics.addAll(diagnostics(
                        ex, testCode, environment.sourceText, testSource, targetSource));
            }

            // CompilerManager may report success while satisfying references from project outputs
            // without emitting one or both explicitly supplied sources into the sandbox. Coverage
            // must execute this round's exact target and test code, so verify both class files and
            // use the module JDK compiler as a deterministic fallback.
            String testFqn = qualifiedName(testPackage, testSimpleName);
            if (diagnostics.isEmpty()
                    && (!classExists(classes, targetClassName) || !classExists(classes, testFqn))) {
                CompileDiagnostic fallbackDiagnostic = compileSandboxWithModuleJdk(
                        environment, sourceRoot, classes, targetSource, testSource,
                        targetClassName, testFqn);
                if (fallbackDiagnostic != null) diagnostics.add(fallbackDiagnostic);
            }

            isolateCascadingSourceErrors(environment, root, targetSource, testSource,
                    targetClassName, sources, diagnostics);

            List<File> runtime = new ArrayList<>();
            runtime.add(classes.toFile());
            runtime.addAll(environment.getRuntimeClasspath());
            return new CompiledTestArtifact(root, classes, testFqn, targetClassName,
                    environment.javaExecutable, runtime, diagnostics, environment.previewLanguage,
                    environment.workingDirectory, environment.getRuntimeConfiguration());
        } catch (IOException ex) {
            diagnostics.add(environmentError("TEMP_IO_FAILURE", safeMessage(ex)));
            return new CompiledTestArtifact(root, root == null ? null : root.resolve("classes"), "",
                    targetClassName, environment == null ? "" : environment.javaExecutable,
                    Collections.emptyList(), diagnostics);
        } catch (RuntimeException | Error ex) {
            // 取消（ProcessCanceledException）或 IDE 编译器异常发生时调用方拿不到 artifact，
            // try-with-resources 无从清理；必须在此删除沙箱目录后原样抛出，避免 unitmind-coverage-* 泄漏。
            if (root != null) {
                CompiledTestArtifact.deleteRecursively(root);
            }
            throw ex;
        }
    }

    /** A candidate's enter/type errors can prevent annotation processors from running at all. */
    void isolateCascadingSourceErrors(ProjectTestEnvironment environment, Path sandboxRoot,
                                      Path targetSource, Path testSource, String targetClassName,
                                      List<File> jointSources, List<CompileDiagnostic> diagnostics) throws IOException {
        boolean targetErrors = diagnostics.stream().anyMatch(d -> "TARGET_SOURCE".equals(d.getSourceOrigin()));
        boolean testErrors = diagnostics.stream().anyMatch(d -> "TEST_SOURCE".equals(d.getSourceOrigin()));
        boolean compoundErrors = diagnostics.stream().anyMatch(d -> "TARGET_SOURCE".equals(d.getSourceOrigin())
                && ("MIXED_COMPILE_FAILURE".equals(d.getCode()) || mentionsFile(d.getMessage(), testSource)));
        if (!targetErrors || !testErrors && !compoundErrors) return;

        // Keep the candidate completely outside the baseline source path; no stale project class
        // may substitute for the explicitly supplied current production source.
        Path baseline = Files.createTempDirectory(sandboxRoot, "production-baseline-");
        Path baselineSources = Files.createDirectories(baseline.resolve("source"));
        Path baselineClasses = Files.createDirectories(baseline.resolve("classes"));
        Path independentTarget = packageDirectory(baselineSources, environment.sourcePackage)
                .resolve(targetSource.getFileName());
        Files.createDirectories(independentTarget.getParent());
        Files.copy(targetSource, independentTarget);
        List<Path> production = new ArrayList<>(); production.add(independentTarget);
        for (File source : jointSources) {
            Path path = source.toPath();
            if (!path.equals(targetSource) && !path.equals(testSource)) production.add(path);
        }
        CompileDiagnostic baselineError = runModuleJavac(environment, baselineSources, baselineClasses,
                production, List.of(targetClassName), false);
        if (baselineError != null) {
            LOG.info("UnitMind compile attribution: production baseline failed; preserving source diagnostics, code="
                    + baselineError.getCode());
            return;
        }
        int before = diagnostics.size();
        if (testErrors) {
            diagnostics.removeIf(d -> "TARGET_SOURCE".equals(d.getSourceOrigin()));
        } else {
            // The module-javac fallback currently returns a compound diagnostic. Keep it as a
            // failure: an independent production pass is never candidate compilation proof.
            for (CompileDiagnostic diagnostic : diagnostics) {
                if (!"TARGET_SOURCE".equals(diagnostic.getSourceOrigin())
                        || !"MIXED_COMPILE_FAILURE".equals(diagnostic.getCode())
                        && !mentionsFile(diagnostic.getMessage(), testSource)) continue;
                diagnostic.setSourceOrigin("TEST_SOURCE"); diagnostic.setSourcePath(testSource.getFileName().toString());
                diagnostic.setRepairable(true);
                diagnostic.setMessage("生产源码已独立编译通过；请修复候选测试，联合编译中的生产源码诊断属于级联信息：\n"
                        + diagnostic.getMessage());
            }
        }
        LOG.info("UnitMind compile attribution: production baseline passed; candidate remains failed; suppressed="
                + (before - diagnostics.size()) + ", candidateDiagnostics=" + diagnostics.size());
    }

    private List<CompileDiagnostic> diagnostics(CompilationException ex,
                                                String testCode,
                                                String targetCode,
                                                Path testSource,
                                                Path targetSource) {
        List<CompileDiagnostic> result = new ArrayList<>();
        for (CompilationException.Message message : ex.getMessages()) {
            if (message.getCategory() != CompilerMessageCategory.ERROR) continue;
            String text = value(message.getText());
            String url = message.getUrl();
            boolean targetFailure = sourceMatches(url, targetSource)
                    || (!sourceMatches(url, testSource) && mentionsFile(text, targetSource));
            Path diagnosticSource = targetFailure ? targetSource : testSource;
            result.add(new CompileDiagnostic("ERROR", diagnosticCode(text), sanitize(text),
                    message.getLine(), message.getColumn(),
                    sourceLine(targetFailure ? targetCode : testCode, message.getLine()),
                    !targetFailure && isRepairable(text),
                    targetFailure ? "TARGET_SOURCE" : "TEST_SOURCE",
                    diagnosticSource == null ? "" : diagnosticSource.getFileName().toString()));
        }
        if (result.isEmpty()) {
            result.add(new CompileDiagnostic(
                    "ERROR", "JAVA_COMPILE", safeMessage(ex), -1, -1, "", false, "UNKNOWN", ""));
        }
        return result;
    }

    private List<String> compilerOptions(ProjectTestEnvironment environment) {
        List<String> options = new ArrayList<>();
        Collections.addAll(options, "-encoding", "UTF-8", "-g");
        if (environment.languageFeature > 0) {
            String feature = Integer.toString(environment.languageFeature);
            Collections.addAll(options, "-source", feature, "-target", feature);
        }
        if (environment.previewLanguage) options.add("--enable-preview");
        appendAnnotationProcessorOptions(options, environment);
        if (environment.jpms && !environment.modulePath.isEmpty()) {
            Collections.addAll(options, "--add-modules", "ALL-MODULE-PATH");
        }
        return options;
    }

    private boolean targetClassExists(Path classes, String targetClassName) {
        return classExists(classes, targetClassName);
    }

    private boolean classExists(Path classes, String className) {
        return classes != null && className != null && !className.isBlank() && Files.isRegularFile(
                classes.resolve(JavaBinaryName.classFileRelativePath(className)));
    }

    CompileDiagnostic compileTargetWithModuleJdk(ProjectTestEnvironment environment,
                                                 Path sourceRoot,
                                                 Path classes,
                                                 Path targetSource,
                                                 String targetClassName) {
        return runModuleJavac(environment, sourceRoot, classes,
                explicitSources(environment, Collections.singletonList(targetSource), sourceRoot),
                Collections.singletonList(targetClassName), false);
    }

    CompileDiagnostic compileSandboxWithModuleJdk(ProjectTestEnvironment environment,
                                                  Path sourceRoot,
                                                  Path classes,
                                                  Path targetSource,
                                                  Path testSource,
                                                  String targetClassName,
                                                  String testClassName) {
        List<Path> sources = explicitSources(environment, List.of(targetSource, testSource), sourceRoot);
        List<String> expectedClasses = new ArrayList<>();
        expectedClasses.add(targetClassName);
        expectedClasses.add(testClassName);
        return runModuleJavac(environment, sourceRoot, classes, sources, expectedClasses, true);
    }

    private List<Path> explicitSources(ProjectTestEnvironment environment, List<Path> primarySources, Path sourceRoot) {
        List<String> texts = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        for (Path source : primarySources) {
            try {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                texts.add(text);
                packages.add(JavaTestSourceInspector.inspect(text).getPackageName());
            } catch (IOException ignored) {
                // javac will report an unreadable explicitly supplied source below.
                packages.add("");
            }
        }
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        result.addAll(primarySources);
        for (File dependency : ProjectSourceDependencyResolver.resolve(
                environment.sourcePath, texts.toArray(new String[0]))) {
            boolean primary = false;
            for (int index = 0; index < primarySources.size(); index++) {
                if (ProjectSourceDependencyResolver.isSourceFile(dependency,
                        environment.sourcePath, packages.get(index),
                        primarySources.get(index).getFileName().toString())) {
                    primary = true;
                    break;
                }
            }
            if (!primary) {
                try { result.add(copySourceToSandbox(dependency, sourceRoot).toPath()); }
                catch (IOException ex) { result.add(dependency.toPath()); }
            }
        }
        return new ArrayList<>(result);
    }

    private File copySourceToSandbox(File source, Path root) throws IOException {
        String text = com.casc.unittestagent.util.ProjectFileText.read(source.toPath());
        String pkg = JavaTestSourceInspector.inspect(text).getPackageName();
        Path copy = packageDirectory(root, pkg).resolve(source.getName());
        if (!Files.exists(copy)) {
            Files.createDirectories(copy.getParent());
            Files.writeString(copy, text, StandardCharsets.UTF_8);
        }
        return copy.toFile();
    }

    private CompileDiagnostic runModuleJavac(ProjectTestEnvironment environment,
                                             Path sourceRoot,
                                             Path classes,
                                             List<Path> sources,
                                             List<String> expectedClasses,
                                             boolean repairableFailure) {
        File java = new File(environment.javaExecutable);
        File javac = new File(java.getParentFile(), isWindows() ? "javac.exe" : "javac");
        if (!javac.isFile()) {
            return environmentError("JDK_COMPILER_NOT_FOUND",
                    "模块 JDK 中未找到 javac，无法生成本轮被测类字节码：" + javac.getAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add(javac.getAbsolutePath());
        Collections.addAll(command, "-J-Dfile.encoding=UTF-8", "-J-Dsun.stdout.encoding=UTF-8", "-J-Dsun.stderr.encoding=UTF-8",
                "-encoding", "UTF-8", "-g", "-d", classes.toAbsolutePath().toString());
        if (environment.languageFeature > 0) {
            String feature = Integer.toString(environment.languageFeature);
            Collections.addAll(command, "-source", feature, "-target", feature);
        }
        if (environment.previewLanguage) command.add("--enable-preview");
        appendAnnotationProcessorOptions(command, environment);

        LinkedHashSet<File> compileClasspath = new LinkedHashSet<>();
        compileClasspath.add(classes.toFile());
        compileClasspath.addAll(environment.classpath);
        if (!compileClasspath.isEmpty()) {
            Collections.addAll(command, "-classpath", joinPaths(compileClasspath));
        }
        if (environment.jpms && !environment.modulePath.isEmpty()) {
            Collections.addAll(command, "--module-path", joinPaths(environment.modulePath),
                    "--add-modules", "ALL-MODULE-PATH");
        }
        LinkedHashSet<File> sourcePath = new LinkedHashSet<>();
        sourcePath.add(sourceRoot.toFile());
        sourcePath.addAll(environment.sourcePath);
        Collections.addAll(command, "-sourcepath", joinPaths(sourcePath));
        for (Path source : sources) command.add(source.toAbsolutePath().toString());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Process process = null;
        try {
            // Windows 命令行约 32K 上限：大项目 classpath/processorpath 拼接超限时
            // 直接 start() 会以“文件名或扩展名太长”失败，超限时自动改写为 @argfile。
            process = LongCommandLineLauncher.start(
                    new ProcessBuilder(command)
                            .directory(sourceRoot.toFile())
                            .redirectErrorStream(true),
                    sourceRoot.getParent());
            process.getOutputStream().close();
            Process worker = process;
            Thread drain = new Thread(() -> drain(worker, output), "unitmind-target-javac-output");
            drain.setDaemon(true);
            drain.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                ProcessCleanup.terminate(process);
                drain.join(1000);
                return environmentError("SANDBOX_COMPILE_TIMEOUT", "补编译本轮源码超时（60 秒）。");
            }
            drain.join(1000);
            String compilerOutput = output.toString(StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                String message = compilerOutput.isEmpty()
                        ? "无法将本轮源码编译到隔离目录。" : sanitize(compilerOutput);
                boolean targetFailure = !repairableFailure
                        || (!sources.isEmpty() && mentionsFile(compilerOutput, sources.get(0)));
                Path diagnosticSource = targetFailure || sources.size() < 2 ? sources.get(0) : sources.get(1);
                return new CompileDiagnostic(
                        "ERROR",
                        repairableFailure && sources.size() >= 2 && mentionsFile(compilerOutput, sources.get(0))
                                && mentionsFile(compilerOutput, sources.get(1)) ? "MIXED_COMPILE_FAILURE"
                                : targetFailure ? "TARGET_COMPILE_FAILED" : "SANDBOX_JAVAC_FAILED",
                        message,
                        -1,
                        -1,
                        "",
                        !targetFailure,
                        targetFailure ? "TARGET_SOURCE" : "TEST_SOURCE",
                        diagnosticSource == null ? "" : diagnosticSource.getFileName().toString());
            }
            for (String expectedClass : expectedClasses) {
                if (!classExists(classes, expectedClass)) {
                    return environmentError("SANDBOX_BYTECODE_MISSING",
                            "模块 JDK 编译成功，但隔离目录中仍未找到 class：" + expectedClass);
                }
            }
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.intellij.openapi.progress.ProcessCanceledException();
        } catch (IOException ex) {
            return environmentError("TARGET_COMPILE_IO_FAILURE", safeMessage(ex));
        } finally {
            ProcessCleanup.terminate(process);
        }
    }

    private static void drain(Process process, ByteArrayOutputStream output) {
        try (java.io.InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() < 256 * 1024) {
                    output.write(buffer, 0, Math.min(read, 256 * 1024 - output.size()));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String joinPaths(Iterable<File> paths) {
        List<String> values = new ArrayList<>();
        for (File path : paths) {
            if (path != null && path.exists()) values.add(path.getAbsolutePath());
        }
        return String.join(File.pathSeparator, values);
    }

    private void appendAnnotationProcessorOptions(List<String> options,
                                                  ProjectTestEnvironment environment) {
        LinkedHashSet<File> processors = new LinkedHashSet<>(environment.annotationProcessorPath);
        if (!processors.isEmpty() && environment.processorsFromClasspath) {
            processors.addAll(environment.classpath);
            processors.addAll(environment.modulePath);
        }
        if (!processors.isEmpty()) {
            Collections.addAll(options,
                    environment.processorModulePath ? "--processor-module-path" : "-processorpath",
                    joinPaths(processors));
        }
        if (!environment.annotationProcessors.isEmpty()) {
            Collections.addAll(options, "-processor", String.join(",", environment.annotationProcessors));
        } else if (!processors.isEmpty() && javacMajorVersion(environment) >= 23) {
            // JDK 23 起默认禁用 classpath 隐式注解发现；Lombok 等依赖该机制的项目若不显式
            // 声明 -proc:full，处理器不会运行，符号缺失会被误判为可修复的测试编译错误。
            options.add("-proc:full");
        }
        for (java.util.Map.Entry<String, String> entry : environment.annotationProcessorOptions.entrySet()) {
            options.add("-A" + entry.getKey()
                    + (entry.getValue() == null || entry.getValue().isBlank() ? "" : "=" + entry.getValue()));
        }
    }

    /** 每个 javac 路径只探测一次版本，避免每次沙箱编译都 fork 进程。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> JAVAC_VERSION_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private int javacMajorVersion(ProjectTestEnvironment environment) {
        File java = new File(environment.javaExecutable);
        File javac = new File(java.getParentFile(), isWindows() ? "javac.exe" : "javac");
        if (!javac.isFile()) {
            return 0;
        }
        return JAVAC_VERSION_CACHE.computeIfAbsent(javac.getAbsolutePath(),
                TestSandboxCompiler::detectJavacMajorVersion);
    }

    /** 探测 javac 主版本；失败返回 0 表示未知，调用方按不支持处理。 */
    static int detectJavacMajorVersion(String javacPath) {
        Process process = null;
        try {
            process = new ProcessBuilder(javacPath, "-version")
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process worker = process;
            Thread drain = new Thread(() -> drain(worker, output), "unitmind-javac-version");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                return 0;
            }
            drain.join(1000);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)javac(?:\\s+version)?\\s+([0-9]+)(?:\\.([0-9]+))?")
                    .matcher(output.toString(StandardCharsets.UTF_8));
            if (!matcher.find()) {
                return 0;
            }
            int first = Integer.parseInt(matcher.group(1));
            if (first == 1 && matcher.group(2) != null) {
                return Integer.parseInt(matcher.group(2));
            }
            return first;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.intellij.openapi.progress.ProcessCanceledException();
        } catch (Exception ex) {
            return 0;
        } finally {
            ProcessCleanup.terminate(process);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private Path packageDirectory(Path root, String packageName) {
        return packageName == null || packageName.isBlank()
                ? root : root.resolve(packageName.replace('.', File.separatorChar));
    }

    private String diagnosticCode(String message) {
        String text = value(message).toLowerCase(Locale.ROOT);
        if (text.contains("interface expected") || text.contains("此处需要接口")) return "CLASS_USED_AS_INTERFACE";
        if (text.contains("unreported exception") || text.contains("未报告的异常")) return "UNREPORTED_EXCEPTION";
        if (text.contains("cannot find symbol") || text.contains("找不到符号")) return "CANNOT_FIND_SYMBOL";
        if (text.contains("no suitable method") || text.contains("不适用于") || text.contains("应用到给定类型")) return "NO_SUITABLE_METHOD";
        if (text.contains("incompatible types") || text.contains("不兼容的类型")) return "INCOMPATIBLE_TYPES";
        if (text.contains("private access") || text.contains("has private access")
                || text.contains("是 private 访问控制")) return "INACCESSIBLE_MEMBER";
        if ((text.contains("reference to") && text.contains("is ambiguous"))
                || text.contains("引用不明确")) return "AMBIGUOUS_REFERENCE";
        if ((text.contains("non-static") && text.contains("static context"))
                || text.contains("无法从静态上下文")) return "STATIC_CONTEXT";
        if ((text.contains("package") && text.contains("does not exist"))
                || (text.contains("程序包") && text.contains("不存在"))) return "PACKAGE_NOT_FOUND";
        if (text.contains("module") && (text.contains("not found")
                || text.contains("not in the module graph"))) return "MODULE_PATH_ERROR";
        if (text.contains("invalid source release")
                || (text.contains("source option") && text.contains("is no longer supported"))
                || (text.contains("target option") && text.contains("is no longer supported"))
                || (text.contains("release version") && text.contains("not supported"))
                || text.contains("不再支持源选项") || text.contains("不再支持目标选项")) {
            return "UNSUPPORTED_LANGUAGE_LEVEL";
        }
        return "JAVA_COMPILE";
    }

    private boolean isRepairable(String message) {
        String code = diagnosticCode(message);
        return !"MODULE_PATH_ERROR".equals(code)
                && !"UNSUPPORTED_LANGUAGE_LEVEL".equals(code)
                && !value(message).toLowerCase(Locale.ROOT).contains("bad class file")
                && !value(message).toLowerCase(Locale.ROOT).contains("unsupported class file");
    }

    private CompileDiagnostic environmentError(String code, String message) {
        return new CompileDiagnostic(
                "ERROR", code, message, -1, -1, "", false, "ENVIRONMENT", "");
    }

    private CompileDiagnostic repairableError(String code, String message) {
        return new CompileDiagnostic("ERROR", code, message, -1, -1, "", true);
    }

    private boolean sourceMatches(String url, Path source) {
        if (url == null || url.isBlank() || source == null) return false;
        Path expected = source.toAbsolutePath().normalize();
        try {
            if (url.startsWith("file:")) {
                return Path.of(java.net.URI.create(url)).toAbsolutePath().normalize().equals(expected);
            }
        } catch (IllegalArgumentException ignored) {
        }
        String normalizedUrl = url.replace('\\', '/');
        String normalizedPath = expected.toString().replace('\\', '/');
        return normalizedUrl.equals(normalizedPath) || normalizedUrl.endsWith(normalizedPath);
    }

    private boolean mentionsFile(String text, Path source) {
        return source != null && source.getFileName() != null && text != null
                && text.contains(source.getFileName().toString());
    }

    private String qualifiedName(String packageName, String className) {
        return packageName == null || packageName.isBlank() ? className : packageName + "." + className;
    }

    private String sourceLine(String code, long line) {
        if (code == null || line <= 0) return "";
        String[] lines = code.split("\\R", -1);
        int index = (int) line - 1;
        return index >= 0 && index < lines.length ? lines[index].strip() : "";
    }

    private String sanitize(String text) {
        return value(text).replaceAll("(?i)(?:[A-Z]:)?[/\\\\][^\\s:]+(?:[/\\\\][^\\s:]+)+", "<path>");
    }

    private String safeMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
    private String value(String text) { return text == null ? "" : text; }
}
