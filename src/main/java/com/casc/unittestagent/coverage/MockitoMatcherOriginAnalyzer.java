package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RuntimeFailureOrigin;
import com.casc.unittestagent.model.RuntimeFailurePhase;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Reads Mockito's registration locations without reassigning JUnit results or granting repair scope. */
final class MockitoMatcherOriginAnalyzer {
    private static final Pattern REGISTRATION = Pattern.compile(
            "->\\s+at\\s+([\\w.$]+)\\.([\\w$]+)\\([^():]+\\.java:(\\d+)\\)");

    private MockitoMatcherOriginAnalyzer() { }

    static void enrich(List<RuntimeDiagnostic> diagnostics, String code) {
        var matcherFailures = diagnostics.stream().filter(d -> d != null
                && "org.mockito.exceptions.misusing.InvalidUseOfMatchersException".equals(d.getExceptionType())).toList();
        if (matcherFailures.isEmpty()) return;
        try {
            var unit = StaticJavaParser.parse(code);
            String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            for (var diagnostic : matcherFailures) {
                var origins = new LinkedHashMap<String, RuntimeFailureOrigin>();
                var registration = REGISTRATION.matcher(diagnostic.getMessage() + "\n" + diagnostic.getFailureDetails());
                while (registration.find()) {
                    String owner = registration.group(1).replace('$', '.');
                    String method = registration.group(2);
                    int line = Integer.parseInt(registration.group(3));
                    var declaration = unit.findAll(MethodDeclaration.class).stream().filter(m ->
                            method.equals(m.getNameAsString()) && m.getRange().map(r ->
                                    r.begin.line <= line && r.end.line >= line).orElse(false)
                                    && owner.equals(pkg + ownerName(m))).findFirst().orElse(null);
                    if (declaration == null) continue; // A stale line/foreign class is not source evidence.
                    String id = registration.group(1) + "#" + method + "()";
                    String[] lines = code.split("\\R", -1);
                    origins.putIfAbsent(id + ":" + line, new RuntimeFailureOrigin(id, line,
                            lines[line - 1].strip(), registration.group()));
                }
                diagnostic.setOriginLocations(new ArrayList<>(origins.values()));
                for (String frame : diagnostic.getStackTrace()) {
                    if (frame == null || !frame.strip().startsWith("at ")) continue;
                    if (frame.contains("org.mockito.junit.jupiter.MockitoExtension.beforeEach(")) {
                        diagnostic.setFailurePhase(RuntimeFailurePhase.TEST_SETUP); break;
                    }
                    if (frame.contains("org.mockito.junit.jupiter.MockitoExtension.afterEach(")) {
                        diagnostic.setFailurePhase(RuntimeFailurePhase.TEST_TEARDOWN); break;
                    }
                }
                if (!origins.isEmpty()) {
                    if (diagnostic.getTestFrame().isBlank()) {
                        diagnostic.setSourceLine(-1);
                        diagnostic.setSourceSnippet("");
                    }
                    diagnostic.setSuggestion(diagnostic.getSuggestion()
                            + " originLocations 是 Mockito 报告且已与候选源码核对的 matcher 注册位置；"
                            + "testId 保留 JUnit 实际报错测试，二者可能不同，不能据此断定整个类初始化失败。"
                            + "先检查注册位置的真实接收者/静态 stub；不要改无关测试的业务断言，"
                            + "不要用清空 Mockito 状态掩盖错误。修复后执行完整测试类回归；既有修复范围和 Oracle 门禁仍然适用。");
                }
            }
        } catch (RuntimeException ignored) {
            // Unparseable or mismatched source cannot establish additional origin evidence.
        }
    }

    private static String ownerName(MethodDeclaration method) {
        List<String> owners = new ArrayList<>();
        var parent = method.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof TypeDeclaration<?> type) owners.add(0, type.getNameAsString());
            parent = parent.getParentNode().orElse(null);
        }
        return String.join(".", owners);
    }
}
