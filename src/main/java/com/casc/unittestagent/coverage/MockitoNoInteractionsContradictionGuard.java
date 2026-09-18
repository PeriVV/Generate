package com.casc.unittestagent.coverage;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 修复 Mockito 中确定性自相矛盾的交互断言：同一测试已 verify(mock).method()，
 * 随后又 verifyNoInteractions(mock)。已发生的交互不会因 verify 而消失，因此后者永远无法通过。
 */
public final class MockitoNoInteractionsContradictionGuard {
    private MockitoNoInteractionsContradictionGuard() { }

    public static Result inspectAndRepair(String testCode, boolean pluginOwnedCandidate) {
        if (!pluginOwnedCandidate || testCode == null || testCode.isBlank()) {
            return new Result(testCode, List.of());
        }
        try {
            CompilationUnit unit = StaticJavaParser.parse(testCode);
            List<String> repairedMethods = new ArrayList<>();
            boolean needsStaticImport = false;
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                if (!isTest(method)) continue;
                Set<String> explicitlyVerified = new LinkedHashSet<>();
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (!"verify".equals(call.getNameAsString()) || call.getArguments().isEmpty()) continue;
                    explicitlyVerified.add(normalize(call.getArgument(0)));
                }
                boolean changed = false;
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (!"verifyNoInteractions".equals(call.getNameAsString())) continue;
                    boolean contradicts = call.getArguments().stream()
                            .map(MockitoNoInteractionsContradictionGuard::normalize)
                            .anyMatch(explicitlyVerified::contains);
                    if (!contradicts) continue;
                    call.setName("verifyNoMoreInteractions");
                    if (call.getScope().isEmpty()) needsStaticImport = true;
                    changed = true;
                }
                if (changed) repairedMethods.add(method.getNameAsString());
            }
            if (repairedMethods.isEmpty()) return new Result(testCode, List.of());
            if (needsStaticImport && unit.getImports().stream().noneMatch(item -> item.isStatic()
                    && (item.isAsterisk() && "org.mockito.Mockito".equals(item.getNameAsString())
                    || "org.mockito.Mockito.verifyNoMoreInteractions".equals(item.getNameAsString())))) {
                unit.addImport("org.mockito.Mockito.verifyNoMoreInteractions", true, false);
            }
            return new Result(unit.toString(), repairedMethods);
        } catch (RuntimeException ignored) {
            return new Result(testCode, List.of());
        }
    }

    /** 仅用于运行失败分类，不改动用户手写候选。 */
    public static boolean hasContradiction(String testCode, String testMethodName) {
        if (testCode == null || testCode.isBlank()) return false;
        try {
            CompilationUnit unit = StaticJavaParser.parse(testCode);
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                if (!method.getNameAsString().equals(testMethodName)) continue;
                Set<String> explicitlyVerified = new LinkedHashSet<>();
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if ("verify".equals(call.getNameAsString()) && !call.getArguments().isEmpty()) {
                        explicitlyVerified.add(normalize(call.getArgument(0)));
                    }
                }
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if ("verifyNoInteractions".equals(call.getNameAsString())
                            && call.getArguments().stream().map(MockitoNoInteractionsContradictionGuard::normalize)
                            .anyMatch(explicitlyVerified::contains)) return true;
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static boolean isTest(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(annotation -> {
            String name = annotation.getName().getIdentifier();
            return "Test".equals(name) || "ParameterizedTest".equals(name)
                    || "RepeatedTest".equals(name) || "TestFactory".equals(name)
                    || "TestTemplate".equals(name);
        });
    }

    private static String normalize(Expression expression) {
        return expression == null ? "" : expression.toString().replaceAll("\\s+", "");
    }

    public static final class Result {
        private final String code;
        private final List<String> repairedMethods;

        private Result(String code, List<String> repairedMethods) {
            this.code = code == null ? "" : code;
            this.repairedMethods = new ArrayList<>(repairedMethods);
        }

        public String getCode() { return code; }
        public List<String> getRepairedMethods() { return new ArrayList<>(repairedMethods); }
        public boolean isChanged() { return !repairedMethods.isEmpty(); }
        public String message() {
            return "已将 " + repairedMethods + " 中 verify(...) 后自相矛盾的 verifyNoInteractions(...) "
                    + "改为 verifyNoMoreInteractions(...)；保留已验证交互，同时禁止额外交互。";
        }
    }
}
