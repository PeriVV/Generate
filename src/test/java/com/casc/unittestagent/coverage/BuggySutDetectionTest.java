package com.casc.unittestagent.coverage;

import com.casc.unittestagent.generator.RuntimeOracleGate;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.OracleDecision;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 缺陷被测代码（SUT）的检测与准出验证。
 *
 * Part A 真实执行：三段含已知缺陷的代码配正确期望的测试，在隔离 JVM 中运行，
 * 断言插件拿到完整失败证据（expected/actual、异常类型、堆栈），而不是把失败吞掉
 * 或误判为环境错误——这是“测试发现了产品 bug”的证据基础。
 *
 * Part B 掩盖性修复拦截：对同一失败模拟模型的各类修复响应，验证 Oracle Gate：
 * 改断言（KEEP 补丁偷改 / CORRECT 低权威）必须被拒；严格准出下高权威 CORRECT 也被拦；
 * 合法的 fixture 修复不被一刀切。
 */
public class BuggySutDetectionTest extends TestCase {
    public void testBuggySutDetectionAndOracleGate() throws Exception {
        StringBuilder report = new StringBuilder("# UnitMind 缺陷检测与准出验证\n\n");
        List<String> failures = new java.util.ArrayList<>();

        try {
            detectBoundaryBug(report);
            detectNullGuardBug(report);
            detectOffByOneBug(report);
        } catch (AssertionError | Exception error) {
            failures.add("缺陷检测: " + error);
            report.append("\n**缺陷检测失败**：").append(error).append("\n");
        }
        try {
            verifyOracleGateVerdicts(report);
        } catch (AssertionError | Exception error) {
            failures.add("Oracle Gate: " + error);
            report.append("\n**Oracle Gate 验证失败**：").append(error).append("\n");
        }

        Path output = Path.of("build/reports/unitmind/buggy-sut-detection.md").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        if (!failures.isEmpty()) {
            fail("缺陷检测/准出验证失败，详见 " + output + "\n" + String.join("\n---\n", failures));
        }
    }

    // ---------- Part A：缺陷检测（真实执行） ----------

    /** 缺陷 1：比较写反，max 实际返回了较小值。 */
    private void detectBoundaryBug(StringBuilder report) throws Exception {
        String sut = """
                package sample.buggy;
                public class BoundaryBug {
                    public static int max(int left, int right) {
                        if (left < right) {
                            return left;
                        }
                        return right;
                    }
                }
                """;
        String test = """
                package sample.buggy;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class BoundaryBugTest {
                    @Test void returnsTheLargerValue() {
                        assertEquals(3, BoundaryBug.max(2, 3));
                    }
                }
                """;
        CoverageRunResult run = runInSandbox("sample.buggy.BoundaryBug", sut,
                "public static int max(int left, int right)", test, "sample.buggy.BoundaryBugTest");

        report.append("## 缺陷 1：max 写反（返回较小值）\n\n");
        report.append("```java\n").append(sut).append("```\n");
        assertFailureEvidence(run, report,
                diagnostic -> {
                    assertEquals("断言失败应解析出期望值", "3", diagnostic.getExpectedValue());
                    assertEquals("断言失败应解析出实际值", "2", diagnostic.getActualValue());
                });
    }

    /** 缺陷 2：缺少 null 防护，直接 NPE。 */
    private void detectNullGuardBug(StringBuilder report) throws Exception {
        String sut = """
                package sample.buggy;
                public class NullGuardBug {
                    public String describe(String value) {
                        return "value:" + value.length();
                    }
                }
                """;
        String test = """
                package sample.buggy;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class NullGuardBugTest {
                    @Test void nullInputShouldNotExplode() {
                        assertEquals("value:0", new NullGuardBug().describe(null));
                    }
                }
                """;
        CoverageRunResult run = runInSandbox("sample.buggy.NullGuardBug", sut,
                "public String describe(String value)", test, "sample.buggy.NullGuardBugTest");

        report.append("## 缺陷 2：缺少 null 防护（NPE）\n\n");
        report.append("```java\n").append(sut).append("```\n");
        assertFailureEvidence(run, report,
                diagnostic -> assertTrue("NPE 应进入异常证据，实际：" + diagnostic.getExceptionType(),
                        diagnostic.getExceptionType().contains("NullPointerException")));
    }

    /** 缺陷 3：截断差一，少返回一位。 */
    private void detectOffByOneBug(StringBuilder report) throws Exception {
        String sut = """
                package sample.buggy;
                public class OffByOneBug {
                    public static String trimLast(String value) {
                        return value.substring(0, value.length() - 1);
                    }
                }
                """;
        // 语义按方法名应去掉最后一位 —— 该实现是对的？不：差一缺陷放在调用方约定上更直观。
        // 换成更明确的缺陷：应保留首字母大写，但索引错位。
        sut = """
                package sample.buggy;
                public class OffByOneBug {
                    public static char secondChar(String value) {
                        return value.charAt(2);
                    }
                }
                """;
        String test = """
                package sample.buggy;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class OffByOneBugTest {
                    @Test void returnsTheSecondCharacter() {
                        assertEquals('n', OffByOneBug.secondChar("UnitMind"));
                    }
                }
                """;
        CoverageRunResult run = runInSandbox("sample.buggy.OffByOneBug", sut,
                "public static char secondChar(String value)", test, "sample.buggy.OffByOneBugTest");

        report.append("## 缺陷 3：索引差一（charAt(2) 取到第三位）\n\n");
        report.append("```java\n").append(sut).append("```\n");
        assertFailureEvidence(run, report,
                diagnostic -> assertEquals("期望/实际应完整保留证据", "n", diagnostic.getExpectedValue()));
    }

    /** 在隔离 JVM 中编译执行“缺陷 SUT + 正确期望测试”，并断言失败证据被完整采集。 */
    private CoverageRunResult runInSandbox(String targetClass, String sutSource,
                                           String methodDeclaration, String testCode,
                                           String testClass) throws Exception {
        Path root = Files.createTempDirectory("unitmind-buggy-sut-");
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        Path sutFile = root.resolve("source").resolve(targetClass.replace('.', '/') + ".java");
        Files.createDirectories(sutFile.getParent());
        Files.writeString(sutFile, sutSource, StandardCharsets.UTF_8);
        LocalCoverageClosureSupport.compile(sutFile, classes, List.of());
        Path testFile = root.resolve("source").resolve(testClass.replace('.', '/') + ".java");
        Files.writeString(testFile, testCode, StandardCharsets.UTF_8);
        LocalCoverageClosureSupport.compile(testFile, classes, List.of());

        MethodModel method = LocalCoverageClosureSupport.method(sutSource, methodDeclaration);
        return LocalCoverageClosureSupport.execute(root, classes, List.of(), sutSource,
                method, testClass, targetClass);
    }

    private interface DiagnosticCheck {
        void check(RuntimeDiagnostic diagnostic);
    }

    private void assertFailureEvidence(CoverageRunResult run, StringBuilder report,
                                       DiagnosticCheck check) {
        report.append("- 执行结论：").append(run.getSnapshot().getMessage()).append("\n");
        assertFalse("缺陷导致的失败不应被当成环境错误（那会把产品 bug 洗成测试环境问题）",
                run.isEnvironmentError());
        assertTrue("暴露缺陷的测试必须被判定为执行失败，实际：" + run.getSnapshot().getMessage(),
                run.getSnapshot().getExecution().getFailed() > 0);
        List<RuntimeDiagnostic> diagnostics = run.getSnapshot().getExecution().getDiagnostics();
        assertFalse("失败诊断证据不能为空", diagnostics.isEmpty());
        RuntimeDiagnostic diagnostic = diagnostics.get(0);
        check.check(diagnostic);
        report.append("- 证据：testId=").append(diagnostic.getTestId())
                .append("，expected=").append(diagnostic.getExpectedValue())
                .append("，actual=").append(diagnostic.getActualValue())
                .append("，exception=").append(diagnostic.getExceptionType())
                .append("，message=").append(diagnostic.getMessage()).append("\n\n");
    }

    // ---------- Part B：掩盖性修复拦截（Oracle Gate 判定表） ----------

    private void verifyOracleGateVerdicts(StringBuilder report) {
        report.append("## Oracle Gate 判定表（针对缺陷 1 的失败）\n\n");
        report.append("| 修复尝试 | 判定 |\n|---|---|\n");
        String currentCode = """
                package sample.buggy;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class BoundaryBugTest {
                    @Test void returnsTheLargerValue() {
                        assertEquals(3, BoundaryBug.max(2, 3));
                    }
                }
                """;
        RuntimeOracleGate gate = new RuntimeOracleGate();

        // 1. KEEP 补丁偷改断言：assertEquals(3, ...) -> assertEquals(2, ...)，必须被 AST 指纹拒绝。
        String maskedAssertion = currentCode.replace("assertEquals(3,", "assertEquals(2,");
        try {
            gate.validateRepair(assessment(RuntimeRepairDisposition.REPAIR_TEST,
                            OracleDecision.KEEP, OracleAuthority.NONE),
                    currentCode, maskedAssertion, Set.of("returnsTheLargerValue"));
            fail("KEEP 决策下修改断言必须被拒绝");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException ex) {
            report.append("| KEEP+NONE 改断言值 3→2 | **拒绝**（AST 指纹） |\n");
        }

        // 2. CORRECT + RUNTIME_ACTUAL（“实际就是 2”）：不能授权把断言改成当前错误结果。
        OracleAssessment runtimeActual = assessment(RuntimeRepairDisposition.REPAIR_TEST,
                OracleDecision.CORRECT, OracleAuthority.RUNTIME_ACTUAL);
        assertTrue("RUNTIME_ACTUAL 不得授权自动改断言",
                RuntimeOracleGate.automaticOracleCorrectionBlocked(runtimeActual, false));
        report.append("| CORRECT+RUNTIME_ACTUAL | **拒绝**（权威不足，标准模式即拦） |\n");

        // 3. CORRECT + CONTRACT_FACT：标准模式放行（高权威契约证据），严格准出模式下必须拦截。
        OracleAssessment contractFact = assessment(RuntimeRepairDisposition.REPAIR_TEST,
                OracleDecision.CORRECT, OracleAuthority.CONTRACT_FACT);
        assertFalse("标准模式下高权威契约证据允许自动修正（设计使然）",
                RuntimeOracleGate.automaticOracleCorrectionBlocked(contractFact, false));
        assertTrue("严格准出模式下 CORRECT 一律拦截",
                RuntimeOracleGate.automaticOracleCorrectionBlocked(contractFact, true));
        String strictMessage = RuntimeOracleGate.strictOracleGateMessage(contractFact);
        assertTrue("严格模式拒绝文案必须指向人工确认", strictMessage.contains("人工确认"));
        report.append("| CORRECT+CONTRACT_FACT 标准模式 | 放行（高权威契约证据） |\n");
        report.append("| CORRECT+CONTRACT_FACT 严格准出 | **拦截并转人工确认** |\n");

        // 4. KEEP + 合法 fixture 修复（只在断言前新增与断言无关的准备，断言语句原样保留）：
        //    不应被一刀切拒绝。注意 gate 按断言调用完整文本做指纹，把 max(2, 3) 等价重写成
        //    max(left, right) 也会被拒——保守设计，宁可误拒不可误放，防止语义绕过。
        String fixtureRepair = currentCode.replace(
                "@Test void returnsTheLargerValue() {",
                "@Test void returnsTheLargerValue() {\n            int scale = 1; // 新增的 Arrange 准备，不触碰断言");
        gate.validateRepair(assessment(RuntimeRepairDisposition.REPAIR_TEST,
                        OracleDecision.KEEP, OracleAuthority.NONE),
                currentCode, fixtureRepair, Set.of("returnsTheLargerValue"));
        report.append("| KEEP+NONE 合法新增 Arrange（断言原样） | 放行 |\n");
        report.append("\n结论：改断言类修复被 AST 指纹与权威分级双重拦截，严格准出下全部转人工；"
                + "普通修复不受影响。\n\n");
    }

    private static OracleAssessment assessment(RuntimeRepairDisposition disposition,
                                               OracleDecision decision,
                                               OracleAuthority authority) {
        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRepairDisposition(disposition.name());
        response.setOracleDecision(decision.name());
        response.setOracleAuthority(authority.name());
        response.setOracleAuthorityRef(authority.permitsAutomaticOracleCorrection()
                ? authority.name() + ":0" : "NONE");
        response.setDiagnosisRootCauseCategory("ASSERTION_VALUE_MISMATCH");
        response.setDiagnosisEvidence(List.of("expected 3 but was 2"));
        return new RuntimeOracleGate().assessment(response);
    }
}
