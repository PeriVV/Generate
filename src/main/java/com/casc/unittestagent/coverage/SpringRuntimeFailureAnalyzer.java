package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RuntimeFailurePhase;
import com.casc.unittestagent.util.CodeFingerprint;
import java.util.*;

/** A failed shared context is not evidence that each blocked test has a business precondition bug. */
public final class SpringRuntimeFailureAnalyzer {
    public static final String CATEGORY = "FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE";
    private SpringRuntimeFailureAnalyzer() { }
    public static void enrich(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null) return;
        for (var diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            String evidence = diagnostic.getMessage() + "\n" + diagnostic.getFailureDetails();
            boolean threshold = evidence.contains("ApplicationContext failure threshold") && evidence.contains("exceeded");
            boolean load = evidence.contains("Failed to load ApplicationContext");
            // Require the actual Spring test loader frame. A test assertion/business message alone
            // (including assertThrows wrapping a context failure) is not shared initialization proof.
            boolean loader = diagnostic.getStackTrace().stream().anyMatch(f -> f.startsWith("at org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate.loadContext("));
            if ((!load && !threshold) || !loader || !diagnostic.getExceptionType().equals("java.lang.IllegalStateException")) continue;
            if (!SpringFixtureAnalyzer.BINDING_FAILURE.equals(diagnostic.getCategory())) diagnostic.setCategory(CATEGORY); diagnostic.setFailurePhase(RuntimeFailurePhase.TEST_SETUP);
            diagnostic.setSharedContextFailure(true); diagnostic.setContextFailureSecondary(threshold);
            diagnostic.setContextKey(contextKey(diagnostic.getMessage()));
            diagnostic.setPrimaryFailureTestId(threshold ? "" : diagnostic.getTestId());
            diagnostic.setSuggestion("共享 Spring 上下文在测试正文执行前失败；先检查真实属性键、Bean 依赖和容器对象生命周期。"
                    + (threshold ? "此条为失败阈值后的跳过，不是新的根因；需查看同一上下文的首次启动异常。" : "以完整 Caused by 的最内层异常为准。")
                    + "不要逐个删除被阻断的测试，也不能据此更改业务 Oracle。");
        }
        // Correlate only when the complete normalized context descriptions match and one primary
        // exists. Never borrow a root from an arbitrary first failed method or another nested context.
        for (var secondary : diagnostics) {
            if (secondary == null || !secondary.isSharedContextFailure() || !secondary.isContextFailureSecondary() || secondary.getContextKey().isBlank()) continue;
            var primaries = diagnostics.stream().filter(Objects::nonNull).filter(d -> d.isSharedContextFailure()
                    && !d.isContextFailureSecondary() && d.getContextKey().equals(secondary.getContextKey())).toList();
            if (primaries.size() == 1) secondary.setPrimaryFailureTestId(primaries.get(0).getTestId());
        }
    }
    /** Minimal programmatic contexts can also fail in a shared @BeforeEach, without SpringExtension. */
    public static void enrich(List<RuntimeDiagnostic> diagnostics, String code) {
        enrich(diagnostics);
        if (diagnostics == null || code == null || code.isBlank()) return;
        try {
            var unit = com.github.javaparser.StaticJavaParser.parse(code);
            for (var d : diagnostics) {
                if (d == null || d.isSharedContextFailure()) continue;
                if (d.getStackTrace().stream().noneMatch(f -> f.startsWith("at org.springframework.context.support.AbstractApplicationContext.refresh("))) continue;
                String binaryOwner = d.getTestId().split("#", 2)[0];
                String ownerName = binaryOwner.replace('$', '.');
                var owners = unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                        .filter(c -> c.getFullyQualifiedName().orElse(c.getNameAsString()).equals(ownerName)).toList();
                if (owners.size() != 1) continue;
                var owner = owners.get(0);
                // A test which explicitly invokes a helper/context is a body failure, not shared setup.
                boolean body = owner.getMethods().stream().filter(m -> m.getAnnotations().stream().anyMatch(a ->
                        java.util.Set.of("org.junit.jupiter.api.Test", "org.junit.jupiter.params.ParameterizedTest", "org.junit.jupiter.api.RepeatedTest", "org.junit.jupiter.api.TestFactory", "org.junit.jupiter.api.TestTemplate")
                                .stream().anyMatch(type -> SpringFixtureAnalyzer.annotationIs(a, unit, type))))
                        .anyMatch(m -> d.getStackTrace().stream().anyMatch(f -> f.startsWith("at " + binaryOwner + "." + m.getNameAsString() + "(")));
                if (body) continue;
                var fixtures = owner.getMethods().stream().filter(m -> m.getAnnotations().stream().anyMatch(a ->
                        java.util.Set.of("org.junit.jupiter.api.BeforeEach", "org.junit.jupiter.api.BeforeAll", "org.junit.Before", "org.junit.BeforeClass")
                                .stream().anyMatch(type -> SpringFixtureAnalyzer.annotationIs(a, unit, type))))
                        .filter(m -> d.getStackTrace().stream().anyMatch(f -> f.startsWith("at " + binaryOwner + "." + m.getNameAsString() + "("))).toList();
                if (fixtures.size() != 1) continue;
                d.setCategory(CATEGORY); d.setFailurePhase(RuntimeFailurePhase.TEST_SETUP); d.setSharedContextFailure(true);
                d.setPrimaryFailureTestId(d.getTestId());
                d.setContextKey("fixture:" + CodeFingerprint.sha256(code + "#" + ownerName + "#" + fixtures.get(0).getNameAsString()));
                d.setSuggestion("共享初始化方法内的 Spring context.refresh 失败，测试正文尚未执行。以完整最内层异常检查实际 PropertySource 和 Bean 注册；"
                        + "普通 TestPropertySource 注解不会自动配置手动创建的上下文，不得逐个排除受阻用例或修改业务 Oracle。");
            }
        } catch (RuntimeException ignored) { /* Unknown source is not proof of a shared fixture. */ }
    }

    private static String contextKey(String message) {
        int start = message.indexOf("MergedContextConfiguration@");
        if (start < 0) return "";
        // Normalize only the descriptor's own identity. Broad @hex replacements can corrupt
        // literal property values and incorrectly correlate two different contexts.
        String context = message.substring(start).replaceFirst("^MergedContextConfiguration@[0-9a-fA-F]+", "MergedContextConfiguration");
        return CodeFingerprint.sha256(context);
    }
    public static boolean hasSharedFailure(List<RuntimeDiagnostic> diagnostics) {
        enrich(diagnostics);
        return diagnostics != null && diagnostics.stream().filter(Objects::nonNull).anyMatch(RuntimeDiagnostic::isSharedContextFailure);
    }
    public static String summary(List<RuntimeDiagnostic> diagnostics) {
        return diagnostics.stream().filter(Objects::nonNull).filter(RuntimeDiagnostic::isSharedContextFailure)
                .sorted(Comparator.comparing(RuntimeDiagnostic::isContextFailureSecondary))
                .findFirst().map(d -> d.isContextFailureSecondary() ? "首次启动根因未出现在本次报告中；" + d.getMessage()
                        : d.getRootCauseType() + ": " + d.getRootCauseMessage())
                .orElse("共享上下文初始化失败");
    }
}
