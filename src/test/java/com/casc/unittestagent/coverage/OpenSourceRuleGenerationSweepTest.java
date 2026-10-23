package com.casc.unittestagent.coverage;

import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.generator.TestScenarioPlanner;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 批量扫描：对 14 个仓库、每仓库多个类的全部可调用方法跑完整规则生成流水线。
 * 本测试是"多试多发现"的编目器——单个目标的任何失败都不让构建失败，
 * 全部结果写入 build/reports/unitmind/oss-rule-sweep.md 供归因修复；
 * 只保留两条底线断言：确实扫到了足量目标、没有环境级错误。
 */
public class OpenSourceRuleGenerationSweepTest extends TestCase {
    private static final String TEST_CODE_ANCHOR = "class SubjectTest { @Test void subject() { } }";

    public void testSweepAcrossProjectsAndMethods() throws Exception {
        List<OpenSourceSweepTargets.Target> targets = OpenSourceSweepTargets.extract();
        if (targets.isEmpty()) {
            // 全新克隆未运行 prepare-oss-matrix.sh：与矩阵契约测试同语义地跳过，
            // 不让默认 ./gradlew test 在未准备环境上变红。
            System.out.println("[sweep] 没有已准备的开源仓库（tmp/oss/*），跳过批量扫描。"
                    + " 先运行 ./evaluation/opensource/prepare-oss-matrix.sh");
            return;
        }
        assertTrue("扫描目标不足（准备项目太少？）：" + targets.size(), targets.size() >= 30);

        StringBuilder report = new StringBuilder("# UnitMind 多项目多方法规则生成扫描\n\n");
        report.append("目标 ").append(targets.size()).append(" 个（含重载/throws/静态/void 等真实形态）。\n\n");
        List<String[]> rows = new ArrayList<>();
        int analyzed = 0;
        int compiled = 0;
        int executed = 0;
        int allPass = 0;
        List<String> envErrors = new ArrayList<>();
        for (OpenSourceSweepTargets.Target target : targets) {
            String[] row = new String[6];
            row[0] = target.repo;
            row[1] = target.className + "#" + target.signature;
            try {
                SweepOutcome outcome = runPipeline(target);
                row[2] = switch (outcome.stage) {
                    case ANALYZE -> "分析失败";
                    case PLAN -> "场景规划失败";
                    case GENERATE -> "生成失败";
                    case COMPILE -> "编译失败";
                    case EXECUTE -> outcome.environmentError ? "环境错误" : "执行完成";
                    default -> "?";
                };
                if (outcome.stage == Stage.ANALYZE || outcome.stage == Stage.PLAN
                        || outcome.stage == Stage.GENERATE) {
                    row[3] = outcome.detail;
                } else if (outcome.stage == Stage.COMPILE) {
                    row[3] = firstLines(outcome.detail, 3);
                } else {
                    analyzed++;
                    if (outcome.stage == Stage.EXECUTE && outcome.environmentError) {
                        envErrors.add(target.repo + " " + row[1] + "：\n" + outcome.detail);
                        row[3] = firstLines(outcome.detail, 3);
                    } else {
                        executed++;
                        row[3] = outcome.found + " 测试 / " + outcome.succeeded + " 通过"
                                + (outcome.coverage == null ? "" : " / " + outcome.coverage);
                        row[4] = outcome.found > 0 && outcome.succeeded == outcome.found ? "✓" : "部分失败";
                        if (outcome.found > 0 && outcome.succeeded == outcome.found) allPass++;
                        row[5] = summarizeFailures(outcome.failures);
                    }
                }
                if (outcome.stage == Stage.COMPILE) {
                    // 编译失败是硬契约问题，编目但不断言（本轮是发现阶段）。
                }
            } catch (Exception unexpected) {
                row[2] = "异常";
                row[3] = unexpected.getClass().getSimpleName() + ": "
                        + String.valueOf(unexpected.getMessage()).replace("\n", " ");
            }
            rows.add(row);
            report.append("| ").append(row[0]).append(" | `").append(row[1]).append("` | ")
                    .append(row[2]).append(" | ")
                    .append(String.valueOf(row[3]).replace("\n", " ⏎ ").replace("|", "\\|"))
                    .append(" | ").append(row[4] == null ? "" : row[4]).append(" | ")
                    .append(row[5] == null ? "" : String.valueOf(row[5]).replace("\n", " ⏎ ").replace("|", "\\|"))
                    .append(" |\n");
        }

        long repositories = targets.stream().map(target -> target.repo).distinct().count();
        report.insert(0, summaryHeader(targets.size(), repositories, analyzed, compiled, executed, allPass));
        Path output = Path.of(System.getProperty("unitmind.test.reportRoot", "build/reports/unitmind"),
                "oss-rule-sweep.md").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        assertTrue("扫描出现环境级错误（沙箱/JVM 层），先修复环境再谈生成质量：\n"
                        + String.join("\n---\n", envErrors),
                envErrors.isEmpty());
    }

    private String summaryHeader(int targets, long repositories, int analyzed, int compiled, int executed, int allPass) {
        return "# UnitMind 多项目多方法规则生成扫描\n\n"
                + "- 扫描目标：" + targets + " 个方法（" + repositories + " 个仓库）\n"
                + "- 完整执行到沙箱运行：" + executed + " 个\n"
                + "- 全部生成测试通过：" + allPass + " 个\n"
                + "- 未全部通过的目标（含分析/编译/执行失败）：" + (targets - allPass) + " 个（编目见下表）\n\n"
                + "| 仓库 | 目标 | 阶段 | 结果 | 状态 | 失败明细 |\n|---|---|---|---|---|---|\n";
    }

    private enum Stage { ANALYZE, PLAN, GENERATE, COMPILE, EXECUTE }

    private static final class SweepOutcome {
        Stage stage;
        boolean environmentError;
        int found;
        int succeeded;
        String coverage;
        String detail;
        List<RuntimeDiagnostic> failures = new ArrayList<>();
    }

    private SweepOutcome runPipeline(OpenSourceSweepTargets.Target target) throws Exception {
        SweepOutcome outcome = new SweepOutcome();
        MethodModel method = new JavaSourceAnalyzer().analyze(
                target.sourceText, target.methodName, target.declarationLine);
        if (method == null) {
            outcome.stage = Stage.ANALYZE;
            outcome.detail = "静态分析未定位到方法（line=" + target.declarationLine + "）";
            return outcome;
        }
        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method);
        List<TestScenario> executable = scenarios.stream()
                .filter(s -> s.getType() != null && !s.getType().endsWith("_REASON")).toList();
        if (executable.isEmpty()) {
            outcome.stage = Stage.PLAN;
            outcome.detail = "无可执行场景（共 " + scenarios.size() + " 个）";
            return outcome;
        }
        MockStrategy mockStrategy = new MockStrategyPlanner().plan(method, executable);

        Path moduleDir = Path.of("tmp/oss").toAbsolutePath().resolve(target.repo)
                .resolve(moduleDirOf(target.repo));
        Path mavenClasses = moduleDir.resolve("target/classes");
        Path gradleClasses = moduleDir.resolve("build/classes/java/main");
        // classpath 文件与 classes 都按 Maven → Gradle 双布局探测（junit-pioneer 等 Gradle 项目）。
        Path mavenClasspath = moduleDir.resolve("target/unitmind.classpath");
        Path gradleClasspath = moduleDir.resolve("build/unitmind.classpath");
        List<java.io.File> dependencies = LocalCoverageClosureSupport.dependencies(
                Files.isRegularFile(mavenClasspath) ? mavenClasspath : gradleClasspath);
        List<java.io.File> probeClasspath = new ArrayList<>(dependencies);
        if (Files.isDirectory(gradleClasses) && !Files.isDirectory(mavenClasses)) probeClasspath.add(gradleClasses.toFile());
        else probeClasspath.add(mavenClasses.toFile());
        JUnitMockitoTestGenerator generator = new JUnitMockitoTestGenerator();
        generator.setTypeInstantiabilityProbe(
                com.casc.unittestagent.generator.ClasspathTypeProbe.from(probeClasspath));
        // 项目 classpath 无 mockito 时（joda-time/gson/eclipse-collections），生成代码
        // 不得引用 org.mockito：编译期被插件 classpath 掩盖、执行期整类 NoClassDefFoundError。
        generator.setMockitoAvailable(dependencies.stream()
                .anyMatch(file -> file.getName().startsWith("mockito-core")));
        GeneratedTestFile generated = generator.generate(method, scenarios, mockStrategy);
        if (generated == null || generated.getContent() == null || generated.getContent().isBlank()) {
            outcome.stage = Stage.GENERATE;
            outcome.detail = "生成器返回空内容";
            return outcome;
        }
        String testCode = generated.getContent();
        String testClassName = generated.getPackageName() == null || generated.getPackageName().isBlank()
                ? generated.getClassName() : generated.getPackageName() + "." + generated.getClassName();

        Path root = Files.createTempDirectory("unitmind-sweep-");
        Path classes = root.resolve("classes");
        LocalCoverageClosureSupport.copyTree(
                Files.isDirectory(mavenClasses) ? mavenClasses : gradleClasses, classes);
        Path sourceFile = root.resolve("source").resolve(testClassName.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, testCode, StandardCharsets.UTF_8);
        try {
            LocalCoverageClosureSupport.compile(sourceFile, classes, dependencies);
        } catch (AssertionError compileFailure) {
            outcome.stage = Stage.COMPILE;
            outcome.detail = compileFailure.getMessage();
            return outcome;
        }
        com.casc.unittestagent.coverage.CoverageRunResult run = LocalCoverageClosureSupport.execute(
                root, classes, dependencies, target.sourceText, method, testClassName, target.targetClass);
        outcome.stage = Stage.EXECUTE;
        outcome.environmentError = run.isEnvironmentError();
        outcome.found = run.getSnapshot().getExecution().getFound();
        outcome.succeeded = run.getSnapshot().getExecution().getSucceeded();
        outcome.failures.addAll(run.getSnapshot().getExecution().getDiagnostics());
        if (run.getSnapshot().isAvailable()) {
            outcome.coverage = LocalCoverageClosureSupport.metric(run.getSnapshot());
        }
        outcome.detail = run.getSnapshot().getMessage();
        return outcome;
    }

    private String moduleDirOf(String repo) {
        return "guava".equals(repo) || "gson".equals(repo) || "eclipse-collections".equals(repo)
                ? ("eclipse-collections".equals(repo) ? "eclipse-collections" : repo)
                : "";
    }

    private String summarizeFailures(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            builder.append("`").append(diagnostic.getTestId() == null ? "?" : diagnostic.getTestId()
                            .substring(Math.max(0, String.valueOf(diagnostic.getTestId()).indexOf('#'))))
                    .append("` ").append(diagnostic.getExceptionType()).append(": ")
                    .append(String.valueOf(diagnostic.getMessage()).replace("\n", " ⏎ ")).append("；");
        }
        return builder.length() > 500 ? builder.substring(0, 500) + "…" : builder.toString();
    }

    private String firstLines(String text, int lines) {
        String value = String.valueOf(text);
        String[] split = value.split("\\R");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < Math.min(lines, split.length); index++) {
            builder.append(split[index]).append(' ');
        }
        return builder.toString();
    }
}
