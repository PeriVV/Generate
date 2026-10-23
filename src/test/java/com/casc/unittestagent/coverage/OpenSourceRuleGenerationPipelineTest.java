package com.casc.unittestagent.coverage;

import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.generator.TestScenarioPlanner;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 开源项目规则生成流水线测试。
 * 对每个已准备的真实仓库，完整执行插件的无大模型生成链路：
 * JavaSourceAnalyzer 静态分析 → 场景规划 → Mock 策略规划 → JUnitMockitoTestGenerator 生成
 * → 沙箱编译 → 隔离 JVM 执行。
 *
 * 断言契约（生成器必须满足的底线）：
 * - 目标方法能被静态分析定位；
 * - 规划出至少一个可执行场景；
 * - 生成的测试代码在真实项目 classpath 上编译通过；
 * - 生成的测试类能被 JUnit 5 发现并执行。
 * 执行通过率与覆盖率只写入报告作为质量趋势，不让测试失败（与冻结评测口径一致）。
 */
public class OpenSourceRuleGenerationPipelineTest extends TestCase {
    public void testRuleGenerationPipelineOnRealRepositories() throws Exception {
        StringBuilder report = new StringBuilder("# UnitMind 开源项目规则生成流水线报告\n\n");
        List<String> failures = new ArrayList<>();
        int executed = 0;
        for (OpenSourceMatrixCases.Case matrixCase : OpenSourceMatrixCases.all()) {
            if (!matrixCase.isPrepared()) continue;
            report.append("## ").append(matrixCase.repo).append(" `")
                    .append(matrixCase.targetClass).append("#")
                    .append(matrixCase.methodDeclaration.trim()).append("`\n");
            try {
                runPipeline(matrixCase, report);
                executed++;
            } catch (AssertionError | Exception error) {
                failures.add(matrixCase.repo + ": " + error);
                report.append("\n**失败**：").append(String.valueOf(error.getMessage()).replace("\n", "\n\n"))
                        .append("\n\n");
            }
        }
        // 已准备并进入流水线的项目数 = 通过 + 失败。
        report.append("\n进入流水线 ").append(executed + failures.size()).append(" 个项目，通过 ")
                .append(executed).append(" 个，失败 ").append(failures.size()).append(" 个。\n");
        Path output = Path.of("build/reports/unitmind/oss-rule-pipeline.md").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        if (!failures.isEmpty()) {
            fail("规则生成流水线存在失败项目，详见 " + output + "\n" + String.join("\n---\n", failures));
        }
        assertTrue("没有已准备的项目，先运行 prepare-oss-matrix.sh", executed + failures.size() > 0);
    }

    private void runPipeline(OpenSourceMatrixCases.Case matrixCase, StringBuilder report) throws Exception {
        String sourceText = Files.readString(matrixCase.sourcePathFile(), StandardCharsets.UTF_8);
        String methodName = methodName(matrixCase.methodDeclaration);
        // 用声明文本定位行号选择重载：jackson-core 的 inLongRange 有 2/4 参两个重载，
        // 仅按名称匹配会测错方法（历史上确实测成了 4 参重载）。
        MethodModel method = new JavaSourceAnalyzer().analyze(
                sourceText, methodName, declarationLine(sourceText, matrixCase.methodDeclaration));
        assertNotNull("静态分析未定位到方法 " + methodName, method);
        report.append("- 静态分析：定位到 `").append(method.getClassName()).append('#')
                .append(method.getMethodName()).append("`，参数 ")
                .append(method.getParameters().size()).append(" 个\n");

        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method);
        List<TestScenario> executable = scenarios.stream()
                .filter(scenario -> scenario.getType() != null && !scenario.getType().endsWith("_REASON"))
                .toList();
        assertFalse("场景规划未产出可执行场景", executable.isEmpty());
        report.append("- 场景规划：共 ").append(scenarios.size()).append(" 个，可执行 ")
                .append(executable.size()).append(" 个\n");

        MockStrategy mockStrategy = new MockStrategyPlanner().plan(method, executable);
        report.append("- Mock 策略：").append(mockStrategy.getStrategyItems().size()).append(" 项\n");

        // 用项目真实 classpath 判定未知类型能否 new：名称启发式认不出第三方接口
        // （petclinic 的 Errors），new Errors() 会直接编译失败。探针范围必须包含
        // 项目自身 classes 目录（Maven 导出的依赖 classpath 不含项目产物，否则 Pet
        // 这类项目内可构造类会被误判退 null）。
        List<java.io.File> probeClasspath = new ArrayList<>(
                LocalCoverageClosureSupport.dependencies(matrixCase.classpathFilePath()));
        probeClasspath.add(matrixCase.classesPath().toFile());
        JUnitMockitoTestGenerator generator = new JUnitMockitoTestGenerator();
        generator.setTypeInstantiabilityProbe(
                com.casc.unittestagent.generator.ClasspathTypeProbe.from(probeClasspath));
        GeneratedTestFile generated = generator.generate(method, scenarios, mockStrategy);
        assertNotNull("规则生成器未返回测试文件", generated);
        String testCode = generated.getContent();
        assertNotNull("规则生成器返回空内容", testCode);
        assertFalse("规则生成器返回空白内容", testCode.isBlank());
        String testClassName = generated.getPackageName() == null || generated.getPackageName().isBlank()
                ? generated.getClassName()
                : generated.getPackageName() + "." + generated.getClassName();
        report.append("- 生成测试类：`").append(testClassName).append("`，")
                .append(testCode.split("\\R").length).append(" 行\n");

        List<java.io.File> dependencies = LocalCoverageClosureSupport
                .dependencies(matrixCase.classpathFilePath());
        Path root = Files.createTempDirectory("unitmind-rule-pipeline-");
        Path classes = root.resolve("classes");
        LocalCoverageClosureSupport.copyTree(matrixCase.classesPath(), classes);
        Path sourceFile = root.resolve("source")
                .resolve(testClassName.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, testCode, StandardCharsets.UTF_8);
        try {
            LocalCoverageClosureSupport.compile(sourceFile, classes, dependencies);
        } catch (AssertionError error) {
            throw new AssertionError("生成的测试在真实项目 classpath 上编译失败：\n"
                    + error.getMessage() + "\n\n生成代码首 80 行：\n" + head(testCode, 80));
        }
        report.append("- 沙箱编译：通过\n");

        CoverageRunResult run = LocalCoverageClosureSupport.execute(
                root, classes, dependencies, sourceText, method, testClassName, matrixCase.targetClass);
        assertFalse("生成的测试执行环境错误：\n" + LocalCoverageClosureSupport.details(run),
                run.isEnvironmentError());
        int found = run.getSnapshot().getExecution().getFound();
        int succeeded = run.getSnapshot().getExecution().getSucceeded();
        assertTrue("生成的测试类未被发现为 JUnit 5 测试：\n" + LocalCoverageClosureSupport.details(run), found > 0);
        report.append("- 沙箱执行：发现 ").append(found).append(" 个测试，通过 ").append(succeeded)
                .append(" 个").append(succeeded == found ? "" : "（未全通过，仅记录趋势）").append("\n");
        if (succeeded < found) {
            // 失败测试的名称与异常摘要：生成质量问题定位必需，不能只记通过率。
            for (com.casc.unittestagent.model.RuntimeDiagnostic diagnostic
                    : run.getSnapshot().getExecution().getDiagnostics()) {
                report.append("  - 失败：`").append(diagnostic.getTestId()).append("` ")
                        .append(diagnostic.getExceptionType()).append(": ")
                        .append(String.valueOf(diagnostic.getMessage()).replace("\n", " ⏎ "))
                        .append("\n");
            }
        }
        if (run.getSnapshot().isAvailable()) {
            report.append("- 生成代码覆盖率：").append(LocalCoverageClosureSupport.metric(run.getSnapshot()))
                    .append("\n");
        }
    }

    /** 从声明文本提取方法名（最后一个非泛型标识符序列）。 */
    private String methodName(String declaration) {
        String beforeParen = declaration.substring(0, declaration.indexOf('('));
        String[] tokens = beforeParen.trim().split("\\s+");
        String nameToken = tokens[tokens.length - 1];
        return nameToken.replaceAll("<[^>]*>", "").trim();
    }

    /** 按声明文本在源码中定位 1 起始行号（空白归一后等值优先、包含兜底）；找不到返回 -1。 */
    private int declarationLine(String sourceText, String declaration) {
        String normalized = declaration.replaceAll("\\s+", " ").trim();
        java.util.List<String> lines = List.of(sourceText.split("\\R", -1));
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).replaceAll("\\s+", " ").trim().equals(normalized)) return index + 1;
        }
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).replaceAll("\\s+", " ").contains(normalized)) return index + 1;
        }
        return -1;
    }

    private String head(String code, int lines) {
        String[] split = code.split("\\R");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < Math.min(lines, split.length); index++) {
            builder.append(split[index]).append('\n');
        }
        return builder.toString();
    }
}
