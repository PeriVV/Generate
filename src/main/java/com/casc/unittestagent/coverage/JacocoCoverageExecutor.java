package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageGap;
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
import com.intellij.util.PathUtil;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_DIAGNOSTICS = 20;
    /**
     * OpenTest4J wraps display values in an extra pair of angle brackets. The values themselves may
     * contain angle brackets (HTML, XML, generics), so a [^>]+ parser truncates valid diagnostics.
     */
    private static final Pattern ASSERTION_VALUE_MISMATCH = Pattern.compile(
            "expected:\\s*<(.*)>\\s*but was:\\s*<(.*)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by:\\s*([\\w.$]+)");

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
            Path execFile = artifact.getRoot().resolve("junit-only.exec");
            Path reports = artifact.getRoot().resolve("junit-only-reports");
            Path agent = artifact.getRoot().resolve("junit-only-agent.jar");
            Files.createDirectories(reports);
            AgentJar.extractTo(agent.toFile());
            TestExecutionSummary execution = runProcess(artifact, execFile, reports, agent,
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
            } else {
                snapshot.setMessage("完整 JUnit 测试类执行通过。");
            }
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
            Path execFile = artifact.getRoot().resolve("jacoco.exec");
            Path reports = artifact.getRoot().resolve("junit-reports");
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
            if (!Files.isRegularFile(execFile)) {
                result.setEnvironmentError(true);
                snapshot.setMessage("JaCoCo 未生成执行数据。");
                return result;
            }
            analyze(artifact, method, sourceText, execFile, result);
            snapshot.setAvailable(true);
            snapshot.setMessage("覆盖率分析完成。");
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
        AgentOptions agentOptions = new AgentOptions();
        agentOptions.setDestfile(execFile.toAbsolutePath().toString());
        agentOptions.setAppend(false);
        agentOptions.setDumpOnExit(true);
        agentOptions.setIncludes(artifact.getTargetClassName() + "*");

        List<String> command = new ArrayList<>();
        command.add(artifact.getJavaExecutable());
        if (artifact.isPreviewLanguage()) command.add("--enable-preview");
        command.add(agentOptions.getVMArgument(agent.toFile()));
        command.add("-cp");
        command.add(runtimeClasspath(artifact));
        command.add("org.junit.platform.console.ConsoleLauncher");
        command.add("execute");
        command.add("--disable-banner");
        command.add("--disable-ansi-colors");
        command.add("--details=none");
        command.add("--reports-dir=" + reports.toAbsolutePath());
        // Generated tests are JUnit Jupiter tests. Restrict discovery to that engine so unrelated
        // suite/vintage engines from a standalone launcher cannot fail before the selected class
        // is discovered.
        command.add("--include-engine=junit-jupiter");
        command.add("--select-class=" + artifact.getTestClassName());

        File workingDirectory = artifact.getWorkingDirectory();
        if (workingDirectory == null || !workingDirectory.isDirectory()) {
            workingDirectory = artifact.getRoot().toFile();
        }
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> drain(process.getInputStream(), output), "unitmind-coverage-output");
        drain.setDaemon(true);
        drain.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(config.getExecutionTimeoutSeconds()).toNanos();
        boolean finished = false;
        while (!finished && System.nanoTime() < deadline && (cancelled == null || !cancelled.getAsBoolean())) {
            finished = process.waitFor(100, TimeUnit.MILLISECONDS);
        }
        TestExecutionSummary summary = new TestExecutionSummary();
        if (!finished) {
            summary.setTimedOut(cancelled == null || !cancelled.getAsBoolean());
            process.destroy();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
        drain.join(1000);
        summary.setExitCode(process.isAlive() ? -1 : process.exitValue());
        summary.setOutput(output.toString(StandardCharsets.UTF_8));
        readXmlReports(reports, summary);
        ensureFailureDiagnostic(summary, artifact.getTestClassName());
        return summary;
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
        Set<String> paths = new LinkedHashSet<>();
        paths.add(artifact.getClasses().toAbsolutePath().toString());
        Class<?> console = Class.forName("org.junit.platform.console.ConsoleLauncher");
        String consolePath = PathUtil.getJarPathForClass(console);
        if (consolePath == null || consolePath.isBlank()) {
            throw new IOException("无法定位内置 JUnit Platform Console 运行时。");
        }
        paths.add(new File(consolePath).getAbsolutePath());
        for (File file : artifact.getRuntimeClasspath()) {
            if (file == null || !file.exists() || isDuplicateJunitRuntime(file.getName())) continue;
            paths.add(file.getAbsolutePath());
        }
        return String.join(File.pathSeparator, paths);
    }

    private boolean isDuplicateJunitRuntime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("junit-platform-") || lower.startsWith("junit-jupiter-")
                || lower.startsWith("junit-vintage-") || lower.startsWith("opentest4j-")
                || lower.startsWith("apiguardian-api-");
    }

    private void analyze(CompiledTestArtifact artifact, MethodModel method, String sourceText,
                         Path execFile, CoverageRunResult result) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile.toFile());
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(loader.getExecutionDataStore(), builder).analyzeAll(artifact.getClasses().toFile());

        String target = artifact.getTargetClassName().replace('.', '/');
        int start = method == null ? -1 : method.getSourceStartLine();
        int end = method == null ? -1 : method.getSourceEndLine();
        if (start <= 0 || end < start) throw new IOException("无法确定被测方法源码范围。");
        Map<Integer, LineData> lines = new HashMap<>();
        IClassCoverage targetCoverage = null;
        for (IClassCoverage coverage : builder.getClasses()) {
            if (coverage.getName().equals(target)) {
                targetCoverage = coverage;
                break;
            }
        }
        if (targetCoverage == null) throw new IOException("编译产物中未找到被测类：" + artifact.getTargetClassName());

        IMethodCoverage exactMethod = selectMethodCoverage(targetCoverage, method, start, end);
        if (exactMethod != null) {
            collectLines(exactMethod, start, end, lines);
        } else {
            // Compatibility fallback for unusual compiler output. Still use the exact target class;
            // never aggregate sibling/anonymous inner classes that happen to share source lines.
            collectLines(targetCoverage, start, end, lines);
        }
        if (lines.isEmpty()) {
            IMethodCoverage nested = selectNestedMethodCoverage(
                    builder, targetCoverage, target, method, start, end);
            if (nested != null) {
                collectLines(nested, start, end, lines);
            }
        }
        if (lines.isEmpty()) {
            if (method != null && (method.getMethodBodyText() == null || method.getMethodBodyText().isBlank())) {
                throw new IOException("当前选择的方法是抽象或 native 声明，本身没有可执行字节码；"
                        + "请选择具体实现方法后重新生成测试。");
            }
            throw new IOException("被测类已找到，但源码第 " + start + "-" + end
                    + " 行没有对应的可执行字节码；请将光标放在目标方法体内并重新分析。");
        }

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

    private IMethodCoverage selectMethodCoverage(IClassCoverage targetCoverage,
                                                  MethodModel method,
                                                  int sourceStart,
                                                  int sourceEnd) {
        if (targetCoverage == null || method == null || method.getMethodName() == null) return null;
        IMethodCoverage selected = null;
        int bestDistance = Integer.MAX_VALUE;
        for (IMethodCoverage candidate : targetCoverage.getMethods()) {
            if (!method.getMethodName().equals(candidate.getName())) continue;
            int first = candidate.getFirstLine();
            int last = candidate.getLastLine();
            if (first < 0 || last < sourceStart || first > sourceEnd) continue;
            int distance = Math.abs(first - sourceStart) + Math.abs(last - sourceEnd);
            if (distance < bestDistance) {
                selected = candidate;
                bestDistance = distance;
            }
        }
        return selected;
    }

    /**
     * 符号快照偶尔会把内部类具体方法错配到同签名的外部抽象方法。JaCoCo 已分析同一
     * 源文件的所有 class，因此可用方法名和源码行范围确定性地恢复真正的嵌套二进制类。
     */
    private IMethodCoverage selectNestedMethodCoverage(CoverageBuilder builder,
                                                       IClassCoverage requestedOwner,
                                                       String requestedBinaryName,
                                                       MethodModel method,
                                                       int sourceStart,
                                                       int sourceEnd) {
        if (builder == null || requestedOwner == null || requestedBinaryName == null || method == null) return null;
        IMethodCoverage selected = null;
        int bestDistance = Integer.MAX_VALUE;
        for (IClassCoverage candidateOwner : builder.getClasses()) {
            if (candidateOwner == requestedOwner
                    || !candidateOwner.getName().startsWith(requestedBinaryName + "$")) {
                continue;
            }
            if (requestedOwner.getSourceFileName() != null
                    && candidateOwner.getSourceFileName() != null
                    && !requestedOwner.getSourceFileName().equals(candidateOwner.getSourceFileName())) {
                continue;
            }
            IMethodCoverage candidateMethod = selectMethodCoverage(
                    candidateOwner, method, sourceStart, sourceEnd);
            if (candidateMethod == null) continue;
            int distance = Math.abs(candidateMethod.getFirstLine() - sourceStart)
                    + Math.abs(candidateMethod.getLastLine() - sourceEnd);
            if (distance < bestDistance) {
                selected = candidateMethod;
                bestDistance = distance;
            }
        }
        return selected;
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
        try (java.util.stream.Stream<Path> files = Files.walk(reports)) {
            files.filter(path -> path.getFileName().toString().startsWith("TEST-")
                            && path.getFileName().toString().endsWith(".xml"))
                    .forEach(path -> readXml(path, summary));
        } catch (Exception ignored) { }
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
                if (failure != null && summary.getDiagnostics().size() < MAX_DIAGNOSTICS) {
                    summary.getDiagnostics().add(runtimeDiagnostic(test, failure));
                }
            }
        } catch (Exception ignored) { }
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
        diagnostic.setFailureDetails(limitFailureDetails(failureText));
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
        List<String> stack = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        for (String line : (failureText == null ? "" : failureText).split("\\R")) {
            String trimmed = line.trim();
            Matcher causedBy = CAUSED_BY.matcher(trimmed);
            if (causedBy.find() && causes.size() < 8) {
                String cause = causedBy.group(1);
                causes.add(cause);
                if (stack.size() < 24) stack.add("Caused by: " + cause);
            } else if (trimmed.startsWith("at ") && stack.size() < 24) {
                stack.add(trimmed);
            } else if (trimmed.startsWith("-> at ") && stack.size() < 24) {
                // Mockito verification diagnostics use arrow-prefixed source locations. Keep them as
                // ordinary frames so the runtime enricher can locate both the verify and real calls.
                stack.add(trimmed.substring(3));
            }
        }
        diagnostic.setCauseChain(causes);
        if (!causes.isEmpty()) diagnostic.setRootCauseType(causes.get(causes.size() - 1));
        diagnostic.setStackTrace(stack);
        return diagnostic;
    }

    private static String limitFailureDetails(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= 12_000 ? normalized : normalized.substring(0, 12_000) + "\n...";
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

    private void drain(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = MAX_OUTPUT_BYTES - output.size();
                if (remaining > 0) output.write(buffer, 0, Math.min(remaining, read));
            }
        } catch (IOException ignored) { }
    }

    private String safeMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }

    private static class LineData {
        int instructionCovered;
        int instructionMissed;
        int branchCovered;
        int branchMissed;
    }
}
