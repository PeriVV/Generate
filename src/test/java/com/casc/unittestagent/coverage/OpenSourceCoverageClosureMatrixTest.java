package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 开源项目覆盖率闭环矩阵测试。
 * 需要先运行 evaluation/opensource/prepare-oss-matrix.sh 克隆并构建真实仓库；
 * 未准备的仓库自动跳过，不影响普通 ./gradlew test。
 *
 * 每个用例模拟 IDE 内“运行覆盖率”闭环的两个关键阶段：
 * 1. 初始候选测试执行并采集方法级行/分支覆盖率；
 * 2. 补测候选执行并断言覆盖率真实提升。
 * 任何一步失败都会带出沙箱编译、JUnit 执行或 JaCoCo 采集的原始错误，
 * 用来复现用户在真实项目上运行覆盖率补测时的报错。
 */
public class OpenSourceCoverageClosureMatrixTest extends TestCase {

    public void testCoverageClosureMatrix() throws Exception {
        List<OpenSourceMatrixCases.Case> cases = OpenSourceMatrixCases.all();
        StringBuilder report = new StringBuilder("# UnitMind 开源项目覆盖率闭环矩阵\n\n");
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (OpenSourceMatrixCases.Case matrixCase : cases) {
            if (!matrixCase.isPrepared()) {
                report.append("- ").append(matrixCase.repo).append("：未准备，跳过（先运行 prepare-oss-matrix.sh）\n");
                skipped++;
                continue;
            }
            report.append("## ").append(matrixCase.repo).append(" `")
                    .append(matrixCase.targetClass).append("#")
                    .append(matrixCase.methodDeclaration.trim()).append("`\n");
            try {
                runCase(matrixCase, report);
                report.append("\n");
            } catch (AssertionError | Exception error) {
                failures.add(matrixCase.repo + ": " + error);
                report.append("\n**失败**：").append(String.valueOf(error.getMessage()).replace("\n", "\n\n"))
                        .append("\n\n");
            }
        }
        report.append("\n共 ").append(cases.size()).append(" 个用例，跳过 ").append(skipped)
                .append(" 个，失败 ").append(failures.size()).append(" 个。\n");
        Path output = Path.of("build/reports/unitmind/oss-coverage-matrix.md").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        if (!failures.isEmpty()) {
            fail("覆盖率闭环矩阵存在失败用例，详见 " + output + "\n" + String.join("\n---\n", failures));
        }
    }

    private void runCase(OpenSourceMatrixCases.Case matrixCase, StringBuilder report) throws Exception {
        String sourceText = Files.readString(matrixCase.sourcePathFile(), StandardCharsets.UTF_8);
        MethodModel method = LocalCoverageClosureSupport.method(sourceText, matrixCase.methodDeclaration);
        List<java.io.File> dependencies = LocalCoverageClosureSupport.dependencies(matrixCase.classpathFilePath());

        CoverageRunResult initial = runCandidate(matrixCase, dependencies, sourceText, method,
                matrixCase.testPackage + ".UnitMindMatrixInitialTest", matrixCase.targetClass,
                matrixCase.initialTest);
        assertTrue("初始候选覆盖率不可用：\n" + LocalCoverageClosureSupport.details(initial),
                initial.getSnapshot().isAvailable());
        report.append("- 初始候选：").append(LocalCoverageClosureSupport.metric(initial.getSnapshot()))
                .append("，测试 ")
                .append(initial.getSnapshot().getExecution().getSucceeded()).append("/")
                .append(initial.getSnapshot().getExecution().getFound()).append(" 通过\n");

        CoverageRunResult improved = runCandidate(matrixCase, dependencies, sourceText, method,
                matrixCase.testPackage + ".UnitMindMatrixImprovedTest", matrixCase.targetClass,
                matrixCase.improvedTest);
        assertTrue("补测候选覆盖率不可用：\n" + LocalCoverageClosureSupport.details(improved),
                improved.getSnapshot().isAvailable());
        report.append("- 补测候选：").append(LocalCoverageClosureSupport.metric(improved.getSnapshot()))
                .append("，测试 ")
                .append(improved.getSnapshot().getExecution().getSucceeded()).append("/")
                .append(improved.getSnapshot().getExecution().getFound()).append(" 通过\n");
        assertTrue("补测候选应在真实仓库上提升覆盖率\ninitial="
                        + LocalCoverageClosureSupport.metric(initial.getSnapshot())
                        + "\nimproved=" + LocalCoverageClosureSupport.metric(improved.getSnapshot()),
                CoverageLoopPolicy.improved(initial.getSnapshot(), improved.getSnapshot()));
        report.append("- 覆盖率有增益：`true`\n");
    }

    private CoverageRunResult runCandidate(OpenSourceMatrixCases.Case matrixCase,
                                           List<java.io.File> dependencies,
                                           String sourceText,
                                           MethodModel method,
                                           String testClass,
                                           String targetClass,
                                           String testCode) throws Exception {
        Path root = Files.createTempDirectory("unitmind-matrix-");
        Path classes = root.resolve("classes");
        LocalCoverageClosureSupport.copyTree(matrixCase.classesPath(), classes);
        Path sourceFile = root.resolve("source").resolve(testClass.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, testCode, StandardCharsets.UTF_8);
        LocalCoverageClosureSupport.compile(sourceFile, classes, dependencies);
        return LocalCoverageClosureSupport.execute(root, classes, dependencies, sourceText, method, testClass, targetClass);
    }

}
