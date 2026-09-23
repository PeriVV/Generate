package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.FailureCategory;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 防枚举漂移：所有诊断码产生点新引入的全大写码必须同步登记进 FailureCategory，
 * 否则元数据缺失会静默降级为 UNKNOWN。新增诊断码时先加枚举成员，再让本测试通过。
 */
public class FailureCategoryCoverageGuardTest extends TestCase {
    private static final Pattern CAPS_LITERAL = Pattern.compile("\"([A-Z][A-Z0-9_]{4,})\"");
    private static final Set<String> DENYLIST = Set.of(
            "ERROR", "ENVIRONMENT", "TARGET_SOURCE", "TEST_SOURCE", "UNKNOWN", "NONE");
    private static final List<String> PRODUCERS = List.of(
            "src/main/java/com/casc/unittestagent/coverage/MockitoRuntimeDiagnosticEnricher.java",
            "src/main/java/com/casc/unittestagent/coverage/MissingFixtureDependencyDetector.java",
            "src/main/java/com/casc/unittestagent/coverage/Slf4jMockVerificationGuard.java",
            "src/main/java/com/casc/unittestagent/coverage/CoverageCandidatePreflight.java",
            "src/main/java/com/casc/unittestagent/coverage/ProjectTestEnvironmentResolver.java",
            "src/main/java/com/casc/unittestagent/coverage/TestSandboxCompiler.java",
            "src/main/java/com/casc/unittestagent/coverage/ProjectTestEnvironment.java",
            "src/main/java/com/casc/unittestagent/validation/ProjectCompileVerifier.java",
            "src/main/java/com/casc/unittestagent/service/UnitTestGenerationService.java");

    public void testEveryProducedDiagnosticCodeIsRegisteredInFailureCategory() throws Exception {
        StringBuilder unregistered = new StringBuilder();
        for (String producer : PRODUCERS) {
            Path path = Path.of(producer);
            assertTrue("缺少产生点源码：" + producer, Files.isRegularFile(path));
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!isCategoryProducingLine(producer, line)) continue;
                Matcher matcher = CAPS_LITERAL.matcher(line);
                while (matcher.find()) {
                    String code = matcher.group(1);
                    if (DENYLIST.contains(code) || FailureCategory.isKnownCode(code)) continue;
                    unregistered.append(producer).append(" -> ").append(code).append('\n');
                }
            }
        }
        assertTrue("以下诊断码未登记进 FailureCategory（新增码必须同步加枚举成员）：\n" + unregistered,
                unregistered.length() == 0);
    }

    private boolean isCategoryProducingLine(String producer, String line) {
        if (line.contains("setCategory(")
                || line.contains("setRootCauseCategory(")
                || line.contains("repairableError(")
                || line.contains("environmentError(")
                || line.contains(".error(")
                || line.contains("new CompileDiagnostic(")) {
            return true;
        }
        // TestSandboxCompiler.diagnosticCode() 以 return "CODE"; 形式返回编译错误码。
        return producer.endsWith("TestSandboxCompiler.java")
                && line.strip().startsWith("return \"")
                && line.strip().endsWith("\";");
    }

    public void testArbitrationCodesCoverAllRuntimeProducers() {
        // 运行期分类器产生的每个类别都必须在仲裁封闭列表内，模型才能合法引用。
        for (String producer : List.of(
                "src/main/java/com/casc/unittestagent/coverage/MockitoRuntimeDiagnosticEnricher.java",
                "src/main/java/com/casc/unittestagent/coverage/MissingFixtureDependencyDetector.java",
                "src/main/java/com/casc/unittestagent/coverage/Slf4jMockVerificationGuard.java")) {
            try {
                for (String line : Files.readAllLines(Path.of(producer), StandardCharsets.UTF_8)) {
                    if (!line.contains("setCategory(")) continue;
                    Matcher matcher = CAPS_LITERAL.matcher(line);
                    while (matcher.find()) {
                        String code = matcher.group(1);
                        if (DENYLIST.contains(code)) continue;
                        assertTrue(producer + " 的 " + code + " 不在 runtimeArbitrationCodes 中",
                                FailureCategory.runtimeArbitrationCodes().contains(code));
                    }
                }
            } catch (Exception ex) {
                fail("读取 " + producer + " 失败：" + ex.getMessage());
            }
        }
    }

    public void testAllCapsCodesAreStableEnglishIdentifiers() {
        for (FailureCategory category : FailureCategory.values()) {
            assertEquals(category.name(), category.name().toUpperCase(Locale.ROOT));
            assertFalse(category.getLabelCn().isBlank());
            assertNotNull(category.getRepairStrategy());
        }
    }
}
