package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageGap;
import com.intellij.openapi.diagnostic.Logger;
import com.casc.unittestagent.model.CoverageMetric;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestExecutionSummary;
import com.casc.unittestagent.model.TestCaseExecution;
import org.jacoco.agent.AgentJar;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.tools.ExecFileLoader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.util.PathUtil;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 在隔离 JVM 中运行一个测试类并用 JaCoCo 分析当前方法。 */
public class JacocoCoverageExecutor {
    private static final Logger LOG = Logger.getInstance(JacocoCoverageExecutor.class);
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    /**
     * OpenTest4J wraps display values in an extra pair of angle brackets. The values themselves may
     * contain angle brackets (HTML, XML, generics), so a [^>]+ parser truncates valid diagnostics.
     */
    private static final Pattern ASSERTION_VALUE_MISMATCH = Pattern.compile(
            "expected:\\s*<(.*)>\\s*but was:\\s*<(.*)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EXPECTED_EXCEPTION_NOT_THROWN = Pattern.compile(
            "expected\\s+([\\w.$]+)\\s+to be thrown,?\\s*but nothing was thrown\\.?",
            Pattern.CASE_INSENSITIVE);

    /** 只执行完整 JUnit 测试类，不计算任何方法或类级覆盖率。 */
    public CoverageRunResult executeTestsOnly(CompiledTestArtifact artifact,
                                              CoverageConfig config,
                                              BooleanSupplier cancelled) {
        CoverageRunResult result = new CoverageRunResult();
        CoverageSnapshot snapshot = new CoverageSnapshot();
        result.setSnapshot(snapshot);
        if (artifact == null || !artifact.isSuccessful()) {
            result.setEnvironmentError(true);
            snapshot.setMessage("候选测试尚未成功编译。");
            return result;
        }
        try {
            Path reports = Files.createTempDirectory(artifact.getRoot(), "junit-only-reports-");
            TestExecutionSummary execution = runProcess(artifact, null, reports, null,
                    config == null ? new CoverageConfig() : config, cancelled);
            snapshot.setExecution(execution);
            if (cancelled != null && cancelled.getAsBoolean()) {
                result.setCancelled(true);
                snapshot.setMessage("JUnit 执行已取消。");
            } else if (!execution.isSuccessful()) {
                if (isExecutionEnvironmentFailure(execution)) result.setEnvironmentError(true);
                snapshot.setMessage(execution.isTimedOut() ? "候选测试执行超时。"
                        : execution.getFound() == 0 ? "未发现可执行的 JUnit 5 测试。"
                        : "候选测试执行失败。");
            } else if (!execution.isFullySuccessful()) {
                snapshot.setMessage("JUnit 进程已完成，但逐用例执行证据不完整。");
            } else {
                snapshot.setMessage("完整 JUnit 测试类执行通过。");
            }
            return result;
        } catch (ProcessCanceledException ex) {
            // 用户取消不是环境错误，必须保持 CANCELLED 语义向上传递。
            result.setCancelled(true);
            snapshot.setMessage("JUnit 执行已取消。");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result.setCancelled(true);
            snapshot.setMessage("JUnit 执行被中断。");
            return result;
        } catch (Exception ex) {
            result.setEnvironmentError(true);
            snapshot.setMessage("JUnit 执行环境错误：" + safeMessage(ex));
            return result;
        }
    }

    public CoverageRunResult execute(CompiledTestArtifact artifact,
                                     MethodModel method,
                                     String sourceText,
                                     CoverageConfig config,
                                     BooleanSupplier cancelled) {
        CoverageRunResult result = new CoverageRunResult();
        CoverageSnapshot snapshot = new CoverageSnapshot();
        result.setSnapshot(snapshot);
        if (artifact == null || !artifact.isSuccessful()) {
            result.setEnvironmentError(true);
            snapshot.setMessage("候选测试尚未成功编译。");
            return result;
        }
        try {
            Path runDirectory = Files.createTempDirectory(artifact.getRoot(), "coverage-run-");
            Path execFile = runDirectory.resolve("jacoco.exec");
            Path reports = runDirectory.resolve("junit-reports");
            Path agent = artifact.getRoot().resolve("jacocoagent.jar");
            Files.createDirectories(reports);
            AgentJar.extractTo(agent.toFile());

            TestExecutionSummary execution = runProcess(artifact, execFile, reports, agent, config, cancelled);
            snapshot.setExecution(execution);
            if (cancelled != null && cancelled.getAsBoolean()) {
                result.setCancelled(true);
                snapshot.setMessage("覆盖率执行已取消。");
                return result;
            }
            if (!execution.isSuccessful()) {
                if (isExecutionEnvironmentFailure(execution)) {
                    result.setEnvironmentError(true);
                    snapshot.setMessage("JUnit 测试引擎环境错误："
                            + execution.getDiagnostics().get(0).getMessage());
                    return result;
                }
                snapshot.setMessage(execution.isTimedOut()
                        ? "候选测试执行超时。" : execution.getFound() == 0
                        ? "未发现可执行的 JUnit 5 测试。" : "候选测试执行失败。");
                return result;
            }
            if (!execution.isFullySuccessful()) {
                snapshot.setMessage("存在跳过、未完成或不一致的逐用例记录，覆盖率不计入完整运行证明。");
                return result;
            }
            if (!Files.isRegularFile(execFile)) {
                result.setEnvironmentError(true);
                snapshot.setMessage("JaCoCo 未生成执行数据。");
                return result;
            }
            analyze(artifact, method, sourceText, execFile, result);
            snapshot.setAvailable(true);
            snapshot.setMessage("覆盖率分析完成。");
            return result;
        } catch (ProcessCanceledException ex) {
            // 用户取消不是环境错误，必须保持 CANCELLED 语义向上传递。
            result.setCancelled(true);
            snapshot.setMessage("覆盖率执行已取消。");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result.setCancelled(true);
            snapshot.setMessage("覆盖率执行被中断。");
            return result;
        } catch (Exception ex) {
            result.setEnvironmentError(true);
            snapshot.setMessage("覆盖率环境错误：" + safeMessage(ex));
            return result;
        }
    }

    private TestExecutionSummary runProcess(CompiledTestArtifact artifact,
                                            Path execFile,
                                            Path reports,
                                            Path agent,
                                            CoverageConfig config,
                                            BooleanSupplier cancelled) throws Exception {
        if (cancelled != null && cancelled.getAsBoolean()) throw new ProcessCanceledException();
        List<String> command = new ArrayList<>();
        TestRuntimeConfiguration runtime = artifact.getRuntimeConfiguration();
        File workingDirectory = runtime.getWorkingDirectory();
        if (workingDirectory == null) workingDirectory = artifact.getRoot().toFile();
        if (!workingDirectory.isDirectory()) throw new IOException("项目测试 workingDirectory 不可用。");
        command.add(runtime.getJavaExecutable().isBlank() ? artifact.getJavaExecutable() : runtime.getJavaExecutable());
        // Keep redirected diagnostics readable on Windows without changing the SUT's default charset.
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        List<String> projectArguments = new ArrayList<>(runtime.getJvmArguments());
        boolean reusedJacoco = reuseProjectJacocoAgent(projectArguments, artifact,
                agent != null ? execFile : reports.resolve("project-agent.exec"));
        command.addAll(projectArguments);
        runtime.getSystemProperties().forEach((name, value) -> command.add("-D" + name + "=" + value));
        if (artifact.isPreviewLanguage()) command.add("--enable-preview");
        if (agent != null && !reusedJacoco) {
            AgentOptions agentOptions = new AgentOptions();
            agentOptions.setDestfile(runtimePath(execFile, workingDirectory));
            agentOptions.setAppend(false);
            agentOptions.setDumpOnExit(true);
            agentOptions.setIncludes(artifact.getTargetClassName() + "*");
            command.add(agentOptions.getVMArgument(new File(runtimePath(agent, workingDirectory))));
        }
        command.add("-cp");
        command.add(runtimeClasspath(artifact));
        command.add("org.junit.platform.console.ConsoleLauncher");
        command.add("execute");
        command.add("--disable-banner");
        command.add("--disable-ansi-colors");
        command.add("--details=none");
        command.add("--reports-dir=" + runtimePath(reports, workingDirectory));
        // Generated tests are JUnit Jupiter tests. Restrict discovery to that engine so unrelated
        // suite/vintage engines from a standalone launcher cannot fail before the selected class
        // is discovered.
        command.add("--include-engine=junit-jupiter");
        command.add("--select-class=" + artifact.getTestClassName());

        // Windows 命令行约 32K 上限：项目运行时 classpath 整体传给 java -cp 时超限
        // 会以“文件名或扩展名太长”失败，超限时自动改写为 pathing jar 形式。
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory).redirectErrorStream(true)
                .redirectOutput(reports.resolve("process-output.log").toFile());
        configureEnvironment(builder.environment(), runtime, BuildConfigurationProcess.isWindows());
        Path stagedAgents = stageWindowsAgents(builder.command(), workingDirectory);
        Process process = null;
        try {
            process = LongCommandLineLauncher.start(builder, artifact.getRoot());
            long deadline = System.nanoTime() + Duration.ofSeconds(config.getExecutionTimeoutSeconds()).toNanos();
            boolean finished = false;
            while (!finished && System.nanoTime() < deadline && (cancelled == null || !cancelled.getAsBoolean())) {
                finished = process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            TestExecutionSummary summary = new TestExecutionSummary();
            if (!finished) {
                summary.setTimedOut(cancelled == null || !cancelled.getAsBoolean());
                ProcessCleanup.terminate(process);
            }
            summary.setExitCode(process.isAlive() ? -1 : process.exitValue());
            try (var stream = Files.newInputStream(reports.resolve("process-output.log"))) {
                summary.setOutput(new String(stream.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8));
            }
            readXmlReports(reports, summary);
            ensureFailureDiagnostic(summary, artifact.getTestClassName());
            SpringRuntimeFailureAnalyzer.enrich(summary.getDiagnostics());
            if (!summary.isFullySuccessful()) RuntimeEvidenceArchive.preserve(reports, summary);
            return summary;
        } finally {
            // Cancellation/interruption must not leave a JVM writing into a deleted sandbox.
            ProcessCleanup.terminate(process);
            CompiledTestArtifact.deleteRecursively(stagedAgents);
        }
    }

    private static Path stageWindowsAgents(List<String> command, File directory) throws IOException {
        if (!BuildConfigurationProcess.isWindows()) return null;
        Path stage = null;
        try {
            for (int index = 1; index < command.size(); index++) {
                String argument = command.get(index);
                if (!argument.startsWith("-javaagent:")) continue;
                int separator = argument.indexOf('=', "-javaagent:".length());
                String path = argument.substring("-javaagent:".length(), separator < 0 ? argument.length() : separator);
                Path jar = directory.toPath().resolve(path).toAbsolutePath().normalize();
                if (jar.toString().chars().noneMatch(value -> value > 127)) continue;
                // libinstrument's native classpath append can reject a Unicode agent path even
                // when the command uses a relative name. Preserve the exact agent in a temp copy.
                if (stage == null) stage = Files.createTempDirectory("unitmind-jvm-agents-");
                Path copy = stage.resolve("agent-" + index + ".jar");
                Files.copy(jar, copy);
                command.set(index, "-javaagent:" + copy + (separator < 0 ? "" : argument.substring(separator)));
            }
            return stage;
        } catch (IOException | RuntimeException error) {
            CompiledTestArtifact.deleteRecursively(stage);
            throw error;
        }
    }

    static void configureEnvironment(Map<String, String> environment, TestRuntimeConfiguration runtime, boolean windows) {
        if (!runtime.isInheritEnvironment()) environment.clear();
        for (String name : runtime.getExcludedEnvironment()) {
            if (windows) environment.keySet().removeIf(key -> key.equalsIgnoreCase(name));
            else environment.remove(name);
        }
        runtime.getEnvironment().forEach((name, value) -> {
            if (windows) environment.keySet().removeIf(key -> key.equalsIgnoreCase(name));
            environment.put(name, value);
        });
    }

    /** 覆盖率模式保留项目代理版本与过滤选项，只改数据出口；避免两个 JaCoCo 代理重复插桩。 */
    private static boolean reuseProjectJacocoAgent(List<String> arguments, CompiledTestArtifact artifact,
                                                   Path execFile) throws IOException {
        boolean found = false;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (!argument.startsWith("-javaagent:")) continue;
            String spec = argument.substring("-javaagent:".length());
            int separator = spec.indexOf('=');
            String path = separator < 0 ? spec : spec.substring(0, separator);
            File jar = new File(path);
            if (!jar.isAbsolute()) {
                File directory = artifact.getRuntimeConfiguration().getWorkingDirectory();
                jar = new File(directory == null ? artifact.getRoot().toFile() : directory, path);
            }
            String premain = "";
            try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar)) {
                if (archive.getManifest() != null) premain = archive.getManifest().getMainAttributes().getValue("Premain-Class");
            } catch (IOException ignored) { continue; }
            if (premain == null || !premain.startsWith("org.jacoco.agent.rt.")) continue;
            if (found) throw new IOException("项目测试配置包含多个 JaCoCo 代理，无法执行覆盖率采集。");
            AgentOptions options = new AgentOptions(separator < 0 ? "" : spec.substring(separator + 1));
            File directory = artifact.getRuntimeConfiguration().getWorkingDirectory();
            if (directory == null) directory = artifact.getRoot().toFile();
            options.setDestfile(runtimePath(execFile, directory));
            options.setAppend(false);
            options.setOutput("file");
            arguments.set(index, options.getVMArgument(new File(runtimePath(jar.toPath(), directory))));
            found = true;
        }
        return found;
    }

    private static String runtimePath(Path file, File directory) {
        return BuildConfigurationProcess.isWindows() ? LongCommandLineLauncher.commandPath(file, directory)
                : file.toAbsolutePath().toString();
    }

    /**
     * JUnit may fail during engine discovery or JVM startup before an XML testcase is written.
     * Preserve a compact diagnostic from console output so the coverage repair prompt does not
     * receive an unexplained TEST_FAILED result.
     */
    static void ensureFailureDiagnostic(TestExecutionSummary summary, String testClassName) {
        if (summary == null || summary.isSuccessful() || !summary.getDiagnostics().isEmpty()) return;

        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId(testClassName == null ? "" : testClassName);
        if (summary.isTimedOut()) {
            diagnostic.setExceptionType("java.util.concurrent.TimeoutException");
            diagnostic.setMessage("候选测试执行超时。");
        } else if (summary.getFound() == 0) {
            diagnostic.setExceptionType(summary.getExitCode() == 0
                    ? "JUnitTestDiscoveryError" : "JUnitProcessError");
            diagnostic.setMessage(failureMessage(summary.getOutput(),
                    "JUnit 未发现可执行测试，子进程退出码 " + summary.getExitCode() + "。"));
        } else {
            diagnostic.setExceptionType("JUnitTestExecutionError");
            diagnostic.setMessage(failureMessage(summary.getOutput(),
                    "JUnit 测试执行失败，子进程退出码 " + summary.getExitCode() + "。"));
        }
        diagnostic.setStackTrace(failureStack(summary.getOutput()));
        summary.getDiagnostics().add(diagnostic);
    }

    static boolean isExecutionEnvironmentFailure(TestExecutionSummary summary) {
        if (summary == null || summary.getFound() > 0) return false;
        StringBuilder evidence = new StringBuilder(summary.getOutput() == null ? "" : summary.getOutput());
        for (RuntimeDiagnostic diagnostic : summary.getDiagnostics()) {
            evidence.append('\n').append(diagnostic.getExceptionType())
                    .append('\n').append(diagnostic.getMessage());
            for (String line : diagnostic.getStackTrace()) evidence.append('\n').append(line);
        }
        String text = evidence.toString().toLowerCase(Locale.ROOT);
        return (text.contains("testengine with id") && text.contains("failed to discover tests"))
                || text.contains("serviceconfigurationerror")
                || text.contains("org/junit/platform") && (text.contains("noclassdeffounderror")
                || text.contains("nosuchmethoderror") || text.contains("linkageerror"));
    }

    private static String failureMessage(String output, String fallback) {
        if (output == null || output.isBlank()) return fallback;
        String firstError = "";
        String rootCause = "";
        for (String line : output.replaceAll("\\u001B\\[[;\\d]*m", "").split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("=>")) return limit(trimmed.substring(2).trim(), 500);
            if (trimmed.startsWith("Caused by:")) rootCause = trimmed;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (firstError.isBlank() && (lower.contains("exception") || lower.contains("error")
                    || lower.contains("failed to discover") || lower.contains("could not"))) {
                firstError = trimmed;
            }
        }
        if (!rootCause.isBlank()) {
            return limit((firstError.isBlank() ? "" : firstError + " | ") + rootCause, 500);
        }
        return firstError.isBlank() ? fallback : limit(firstError, 500);
    }

    private static List<String> failureStack(String output) {
        List<String> stack = new ArrayList<>();
        if (output == null || output.isBlank()) return stack;
        for (String line : output.replaceAll("\\u001B\\[[;\\d]*m", "").split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at ") || trimmed.startsWith("Caused by:")
                    || trimmed.startsWith("Suppressed:")) {
                stack.add(limit(trimmed, 500));
                if (stack.size() >= 8) break;
            }
        }
        return stack;
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + "...";
    }

    private String runtimeClasspath(CompiledTestArtifact artifact) throws ClassNotFoundException, IOException {
        Class<?> console = Class.forName("org.junit.platform.console.ConsoleLauncher");
        String consolePath = PathUtil.getJarPathForClass(console);
        if (consolePath == null || consolePath.isBlank()) {
            throw new IOException("无法定位内置 JUnit Platform Console 运行时。");
        }
        Set<String> paths = new LinkedHashSet<>();
        paths.add(artifact.getClasses().toAbsolutePath().toString());
        // classpath 首位优先。ConsoleLauncher 只存在于内置 console-standalone，而
        // junit-platform-commons 等基础库在两边都有：用户版本不高于内置时必须让内置
        // standalone 整体前置，保证 platform 各包同源，否则 1.10.x 的 commons 配 1.12 的
        // console 会在启动期抛 NoSuchMethodError（真实项目普遍自带 JUnit 5，这是覆盖率
        // 闭环的高频报错来源）。用户的 JUnit Platform 比内置新且自带 launcher
        // （5.13+/6.x）时保持用户优先，候选测试继续由项目自身的新版 engine 执行。
        boolean userJunitFirst = prefersUserJunitFirst(artifact.getRuntimeClasspath(), consolePath);
        if (userJunitFirst && hasUserPlatformLauncher(artifact.getRuntimeClasspath())) {
            addAllExisting(paths, artifact.getRuntimeClasspath());
            paths.add(consolePath);
        } else if (userJunitFirst) {
            // 用户平台比内置新（如 JUnit 6）但没有 junit-platform-launcher：launcher
            // 只能来自内置 standalone（1.12），engine 与 launcher 版本错配是 JUnit 的
            // 硬性约束，spring-petclinic（JUnit 6.0.3 无 launcher）实测在发现阶段抛
            // "OutputDirectoryCreator not available"。此时整体对齐到内置全套：
            // standalone 前置并剔除用户 junit 平台/引擎 jar，console、launcher、
            // engine、commons 全部同源 1.12；生成的测试只用稳定的 Jupiter 基础
            // API，可由内置引擎执行。mockito-junit-jupiter 等第三方适配 jar 不在
            // 剔除范围（按 org.junit 官方 artifact 前缀精确匹配）。
            paths.add(consolePath);
            addAllExisting(paths, artifact.getRuntimeClasspath(), JacocoCoverageExecutor::isUserJUnitStackJar);
        } else {
            paths.add(consolePath);
            addAllExisting(paths, artifact.getRuntimeClasspath());
        }
        return String.join(File.pathSeparator, paths);
    }

    /** 用户运行时是否自带 junit-platform-launcher（engine↔launcher 对齐的必要条件）。 */
    static boolean hasUserPlatformLauncher(List<File> runtimeClasspath) {
        if (runtimeClasspath == null) return false;
        for (File file : runtimeClasspath) {
            if (file != null && file.getName().startsWith("junit-platform-launcher")) return true;
        }
        return false;
    }

    /** org.junit 官方 JUnit 平台/引擎 artifact 的文件名前缀；第三方 junit-jupiter 适配 jar 不匹配。 */
    static boolean isUserJUnitStackJar(String jarName) {
        if (jarName == null) return false;
        return jarName.startsWith("junit-platform-") || jarName.startsWith("junit-jupiter-")
                || jarName.startsWith("junit-vintage-");
    }

    private static void addAllExisting(Set<String> paths, List<File> files) {
        addAllExisting(paths, files, excluded -> false);
    }

    private static void addAllExisting(Set<String> paths, List<File> files, java.util.function.Predicate<String> excludeByName) {
        for (File file : files) {
            if (file == null || !file.exists() || excludeByName.test(file.getName())) continue;
            paths.add(file.getAbsolutePath());
        }
    }

    /**
     * 判断用户运行时里的 junit-platform 版本是否高于内置 console-standalone。
     * 找不到可识别的用户 platform jar（项目没带 JUnit）时返回 false，
     * 使用内置全套运行时，不存在版本冲突。
     */
    static boolean prefersUserJunitFirst(List<File> runtimeClasspath, String consoleJarPath) {
        String bundled = jarVersion(consoleJarPath, "console-standalone");
        String userPlatform = null;
        if (runtimeClasspath != null) {
            for (File file : runtimeClasspath) {
                if (file == null) continue;
                String name = file.getName();
                if (name.startsWith("junit-platform-commons-") && name.endsWith(".jar")
                        && userPlatform == null) {
                    userPlatform = jarVersion(file.getAbsolutePath(), "junit-platform-commons-");
                }
            }
        }
        if (userPlatform == null || bundled == null) return false;
        return compareVersions(userPlatform, bundled) > 0;
    }

    /** 从 jar 文件名提取紧跟在 marker 之后的版本号；解析失败返回 null。 */
    static String jarVersion(String jarPath, String marker) {
        if (jarPath == null) return null;
        String name = new File(jarPath).getName();
        int index = name.indexOf(marker);
        if (index < 0) return null;
        String rest = name.substring(index + marker.length());
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)*)").matcher(rest);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 按数字分段比较版本号，如 1.10.1 < 1.12.0 < 2.0。 */
    static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private void analyze(CompiledTestArtifact artifact, MethodModel method, String sourceText,
                         Path execFile, CoverageRunResult result) throws IOException {
        if (method != null && !method.getAnalyzedSourceHash().isBlank()
                && !method.getAnalyzedSourceHash().equals(com.casc.unittestagent.util.CodeFingerprint.sha256(sourceText))) {
            throw new IOException("方法分析与被测源码版本不一致，请重新分析后测量覆盖率。");
        }
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile.toFile());
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(loader.getExecutionDataStore(), builder).analyzeAll(artifact.getClasses().toFile());

        String target = artifact.getTargetClassName().replace('.', '/');
        int start = method == null ? -1 : method.getSourceStartLine();
        int end = method == null ? -1 : method.getSourceEndLine();
        if (start <= 0 || end < start) throw new IOException("无法确定被测方法源码范围。");
        Map<Integer, LineData> lines = new HashMap<>();
        var scope = MethodCoverageScope.resolve(builder, target, method, artifact.getClasses());
        IClassCoverage targetCoverage = scope.owner();
        if (targetCoverage.isNoMatch()) {
            throw new IOException("JaCoCo 执行数据与被测类字节码版本不一致，覆盖率不可用。");
        }
        var classSnapshot = result.getClassSnapshot();
        classSnapshot.setLine(new CoverageMetric(targetCoverage.getLineCounter().getCoveredCount(),
                targetCoverage.getLineCounter().getMissedCount()));
        classSnapshot.setBranch(new CoverageMetric(targetCoverage.getBranchCounter().getCoveredCount(),
                targetCoverage.getBranchCounter().getMissedCount()));
        classSnapshot.setExecution(result.getSnapshot().getExecution());
        classSnapshot.setAvailable(true);
        classSnapshot.setMeasurementScope("SELECTED_BUSINESS_CLASS");
        classSnapshot.setMessage("当前测试集合对所选业务类的覆盖率（不包含其他业务类）。");
        for (IMethodCoverage executable : scope.methods()) {
            collectLines(executable, start, end, lines);
        }
        if (lines.isEmpty()) throw new IOException("所选方法没有可统计的可执行行，不能报告 100%。");
        result.getSnapshot().setMeasurementScope("SELECTED_METHOD_WITH_LAMBDAS");
        result.getSnapshot().setMeasuredMethods(scope.methods().stream()
                .map(m -> targetCoverage.getName() + "#" + m.getName() + m.getDesc()).toList());

        int coveredLines = 0;
        int missedLines = 0;
        int coveredBranches = 0;
        int missedBranches = 0;
        List<CoverageGap> gaps = new ArrayList<>();
        for (Map.Entry<Integer, LineData> entry : lines.entrySet()) {
            int line = entry.getKey();
            LineData data = entry.getValue();
            if (data.instructionCovered > 0) coveredLines++; else {
                missedLines++;
                gaps.add(gap(CoverageGap.Kind.LINE, line, sourceText, 1, 1, method));
            }
            coveredBranches += data.branchCovered;
            missedBranches += data.branchMissed;
            if (data.branchMissed > 0) {
                gaps.add(gap(CoverageGap.Kind.BRANCH, line, sourceText,
                        data.branchMissed, data.branchCovered + data.branchMissed, method));
            }
        }
        gaps.sort(Comparator.comparingInt(CoverageGap::getLineNumber)
                .thenComparing(gap -> gap.getKind() == CoverageGap.Kind.BRANCH ? 0 : 1));
        result.setGaps(gaps);
        result.getSnapshot().setLine(new CoverageMetric(coveredLines, missedLines));
        result.getSnapshot().setBranch(new CoverageMetric(coveredBranches, missedBranches));
    }

    private void collectLines(org.jacoco.core.analysis.ISourceNode source,
                              int sourceStart,
                              int sourceEnd,
                              Map<Integer, LineData> lines) {
        int first = Math.max(sourceStart, source.getFirstLine());
        int last = Math.min(sourceEnd, source.getLastLine());
        for (int line = first; line <= last; line++) {
            ILine item = source.getLine(line);
            if (item.getInstructionCounter().getTotalCount() == 0) continue;
            LineData data = lines.computeIfAbsent(line, ignored -> new LineData());
            data.instructionCovered += item.getInstructionCounter().getCoveredCount();
            data.instructionMissed += item.getInstructionCounter().getMissedCount();
            data.branchCovered += item.getBranchCounter().getCoveredCount();
            data.branchMissed += item.getBranchCounter().getMissedCount();
        }
    }

    private CoverageGap gap(CoverageGap.Kind kind, int line, String sourceText, int missed, int total,
                            MethodModel method) {
        CoverageGap gap = new CoverageGap();
        gap.setKind(kind);
        gap.setLineNumber(line);
        gap.setMissedCount(missed);
        gap.setTotalCount(total);
        String[] lines = sourceText == null ? new String[0] : sourceText.split("\\R", -1);
        gap.setSource(line > 0 && line <= lines.length ? lines[line - 1].strip() : "");
        StringBuilder context = new StringBuilder();
        for (int current = Math.max(1, line - 2); current <= Math.min(lines.length, line + 2); current++) {
            if (context.length() > 0) context.append('\n');
            context.append(current).append(": ").append(lines[current - 1].strip());
        }
        gap.setContext(context.toString());
        if (kind == CoverageGap.Kind.BRANCH && method != null) {
            method.getBranches().stream()
                    .filter(branch -> branch.getSourceStartLine() == line
                            || line >= branch.getSourceStartLine() && line <= branch.getSourceEndLine()
                            && branch.getCondition() != null && gap.getSource().contains(branch.getCondition()))
                    .findFirst()
                    .ifPresent(branch -> {
                        gap.setBranchId(branch.getBranchId());
                        gap.setPredicate(branch.getCondition());
                        gap.setMissingDirection("UNKNOWN");
                        gap.setPathConstraints(branch.getPreconditions());
                    });
        }
        return gap;
    }

    private void readXmlReports(Path reports, TestExecutionSummary summary) {
        if (!Files.isDirectory(reports)) return;
        List<Path> reportFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.walk(reports)) {
            files.filter(path -> path.getFileName().toString().startsWith("TEST-")
                            && path.getFileName().toString().endsWith(".xml"))
                    .forEach(reportFiles::add);
        } catch (Exception ex) {
            LOG.warn("UnitMind: 读取 JUnit XML 报告目录失败：" + reports, ex);
            com.casc.unittestagent.validation.JUnitExecutionEvidence.reject(summary,
                    "JUnitReportReadFailure", "JUnit XML 报告目录无法完整读取。");
            return;
        }
        if (reportFiles.isEmpty()) {
            LOG.warn("UnitMind: JUnit 已执行但报告目录中没有 TEST-*.xml，请检查子进程输出：" + reports);
            return;
        }
        for (Path report : reportFiles) {
            readXml(report, summary);
        }
    }

    private void readXml(Path report, TestExecutionSummary summary) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document document = factory.newDocumentBuilder().parse(report.toFile());
            Element suite = document.getDocumentElement();
            int tests = number(suite.getAttribute("tests"));
            int failures = number(suite.getAttribute("failures")) + number(suite.getAttribute("errors"));
            int skipped = number(suite.getAttribute("skipped"));
            summary.setFound(summary.getFound() + tests);
            summary.setFailed(summary.getFailed() + failures);
            summary.setSkipped(summary.getSkipped() + skipped);
            summary.setSucceeded(summary.getSucceeded() + Math.max(0, tests - failures - skipped));
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int index = 0; index < cases.getLength(); index++) {
                Element test = (Element) cases.item(index);
                Element failure = child(test, "failure");
                if (failure == null) failure = child(test, "error");
                boolean caseSkipped = child(test, "skipped") != null;
                String testId = test.getAttribute("classname") + "#" + test.getAttribute("name");
                summary.getTestCases().add(new TestCaseExecution(testId, failure == null && !caseSkipped, caseSkipped));
                if (failure != null) {
                    summary.getDiagnostics().add(runtimeDiagnostic(test, failure));
                }
            }
        } catch (Exception ex) {
            // 解析失败不能静默吞掉：此时 found 保持 0，上层会误判“未发现可执行测试”，
            // 必须在日志里留下真实原因（文件被占用、编码、结构异常等）。
            LOG.warn("UnitMind: 解析 JUnit XML 报告失败：" + report, ex);
            com.casc.unittestagent.validation.JUnitExecutionEvidence.reject(summary, "JUnitReportReadFailure",
                    "JUnit XML 报告解析失败：" + report.getFileName() + "；不能用其他报告的通过结果代替。");
        }
    }

    private RuntimeDiagnostic runtimeDiagnostic(Element test, Element failure) {
        return runtimeDiagnostic(
                test.getAttribute("classname") + "#" + test.getAttribute("name"),
                failure.getAttribute("type"), failure.getAttribute("message"), failure.getTextContent());
    }

    static RuntimeDiagnostic runtimeDiagnostic(String testId,
                                               String exceptionType,
                                               String message,
                                               String failureText) {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId(testId);
        diagnostic.setExceptionType(exceptionType);
        diagnostic.setMessage(message);
        diagnostic.setFailureDetails(failureText);
        Matcher mismatch = ASSERTION_VALUE_MISMATCH.matcher(diagnostic.getMessage());
        if (mismatch.find()) {
            String expected = mismatch.group(1);
            String actual = mismatch.group(2);
            diagnostic.setExpectedValue(expected);
            diagnostic.setActualValue(actual);
            // Only the dedicated JUnit assertThrows wording represents exception types. A normal
            // assertEquals message uses the same expected/but-was envelope for arbitrary values.
            String lowerMessage = diagnostic.getMessage().toLowerCase(Locale.ROOT);
            if (lowerMessage.contains("unexpected exception type thrown")) {
                diagnostic.setExpectedExceptionType(expected);
                diagnostic.setActualExceptionType(actual);
            }
        }
        Matcher missingException = EXPECTED_EXCEPTION_NOT_THROWN.matcher(diagnostic.getMessage());
        if (missingException.find()) {
            diagnostic.setExpectedExceptionType(missingException.group(1));
            diagnostic.setActualExceptionType("");
        }
        RuntimeExceptionEvidence.populate(diagnostic);
        return diagnostic;
    }

    private Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element && name.equals(node.getNodeName())) return (Element) node;
        }
        return null;
    }

    private int number(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private String safeMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }

    private static class LineData {
        int instructionCovered;
        int instructionMissed;
        int branchCovered;
        int branchMissed;
    }
}
