package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 识别测试中永远无法观察到 @Slf4j/static logger 的 Mockito verify。
 * 这里只处理能由源码拓扑确定的错误实例，不猜测日志是否属于业务契约。
 */
public final class Slf4jMockVerificationGuard {
    private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "error");

    private Slf4jMockVerificationGuard() { }

    public static Result inspectAndRepair(String productionSource,
                                          String testCode,
                                          List<RuntimeDiagnostic> diagnostics,
                                          boolean allowAutomaticRepair) {
        if (productionSource == null || productionSource.isBlank()
                || testCode == null || testCode.isBlank()) return Result.none(testCode);
        try {
            CompilationUnit production = StaticJavaParser.parse(productionSource);
            if (!usesNonInjectableLogger(production)) return Result.none(testCode);
            CompilationUnit test = StaticJavaParser.parse(testCode);
            Set<String> mockLoggers = mockLoggerNames(test);
            if (mockLoggers.isEmpty()) return Result.none(testCode);
            if (explicitlyReplacesStaticLogger(test, mockLoggers)) return Result.none(testCode);

            Set<String> detectedMethods = new LinkedHashSet<>();
            Set<String> repairedMethods = new LinkedHashSet<>();
            Set<String> blockedMethods = new LinkedHashSet<>();
            int removed = 0;
            for (MethodDeclaration method : test.findAll(MethodDeclaration.class)) {
                List<MethodCallExpr> invalid = invalidLoggerVerifications(method, mockLoggers);
                if (invalid.isEmpty()) continue;
                detectedMethods.add(method.getNameAsString());
                boolean hasIndependentOracle = hasOtherOracle(method, invalid, mockLoggers);
                if (!allowAutomaticRepair || !hasIndependentOracle) {
                    blockedMethods.add(method.getNameAsString());
                    continue;
                }
                for (MethodCallExpr call : invalid) {
                    ExpressionStmt statement = call.findAncestor(ExpressionStmt.class).orElse(null);
                    if (statement != null && statement.remove()) removed++;
                }
                repairedMethods.add(method.getNameAsString());
            }
            if (detectedMethods.isEmpty()) return Result.none(testCode);
            classifyDiagnostics(diagnostics, detectedMethods);
            String repairedCode = removed == 0 ? testCode : test.toString();
            return new Result(repairedCode, true, removed > 0, removed,
                    new ArrayList<>(detectedMethods), new ArrayList<>(repairedMethods),
                    new ArrayList<>(blockedMethods));
        } catch (RuntimeException ignored) {
            return Result.none(testCode);
        }
    }

    private static boolean usesNonInjectableLogger(CompilationUnit production) {
        boolean slf4j = production.findAll(AnnotationExpr.class).stream()
                .anyMatch(annotation -> "Slf4j".equals(annotation.getName().getIdentifier()));
        boolean staticLogger = production.findAll(FieldDeclaration.class).stream()
                .anyMatch(field -> isLoggerType(field.getElementType().asString()) && field.isStatic());
        boolean injectableLogger = production.findAll(FieldDeclaration.class).stream()
                .anyMatch(field -> isLoggerType(field.getElementType().asString()) && !field.isStatic());
        boolean loggerParameter = production.findAll(Parameter.class).stream()
                .anyMatch(parameter -> isLoggerType(parameter.getTypeAsString()));
        return (slf4j || staticLogger) && !injectableLogger && !loggerParameter;
    }

    private static Set<String> mockLoggerNames(CompilationUnit test) {
        Set<String> names = new LinkedHashSet<>();
        for (FieldDeclaration field : test.findAll(FieldDeclaration.class)) {
            boolean annotatedMock = field.getAnnotations().stream()
                    .anyMatch(annotation -> Set.of("Mock", "MockBean", "MockitoBean")
                            .contains(annotation.getName().getIdentifier()));
            if (annotatedMock && isLoggerType(field.getElementType().asString())) {
                field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
            }
        }
        for (VariableDeclarator variable : test.findAll(VariableDeclarator.class)) {
            if (!isLoggerType(variable.getTypeAsString()) || variable.getInitializer().isEmpty()) continue;
            Expression initializer = variable.getInitializer().get();
            if (initializer.isMethodCallExpr()
                    && "mock".equals(initializer.asMethodCallExpr().getNameAsString())) {
                names.add(variable.getNameAsString());
            }
        }
        return names;
    }

    private static boolean explicitlyReplacesStaticLogger(CompilationUnit test,
                                                           Set<String> mockLoggers) {
        boolean locatesLoggerField = test.findAll(MethodCallExpr.class).stream()
                .filter(call -> "getDeclaredField".equals(call.getNameAsString()))
                .flatMap(call -> call.getArguments().stream())
                .map(Expression::toString)
                .anyMatch(value -> value.contains("\"log\"") || value.contains("\"logger\""));
        if (!locatesLoggerField) return false;
        return test.findAll(MethodCallExpr.class).stream()
                .filter(call -> "set".equals(call.getNameAsString()))
                .flatMap(call -> call.getArguments().stream())
                .map(Expression::toString)
                .map(value -> value.replace("this.", "").strip())
                .anyMatch(mockLoggers::contains);
    }

    private static List<MethodCallExpr> invalidLoggerVerifications(MethodDeclaration method,
                                                                   Set<String> mockLoggers) {
        List<MethodCallExpr> calls = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!LOG_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty()) continue;
            Expression scope = call.getScope().get();
            if (!scope.isMethodCallExpr()) continue;
            MethodCallExpr verify = scope.asMethodCallExpr();
            if (!"verify".equals(verify.getNameAsString()) || verify.getArguments().isEmpty()) continue;
            String receiver = verify.getArgument(0).toString().replace("this.", "").strip();
            if (mockLoggers.contains(receiver)) calls.add(call);
        }
        return calls;
    }

    private static boolean hasOtherOracle(MethodDeclaration method,
                                          List<MethodCallExpr> invalid,
                                          Set<String> mockLoggers) {
        Set<Node> invalidNodes = new LinkedHashSet<>(invalid);
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (invalidNodes.contains(call) || isInsideInvalidVerification(call, invalid)) continue;
            String name = call.getNameAsString().toLowerCase(Locale.ROOT);
            if (name.startsWith("assert") || "fail".equals(name)) return true;
            if (!"verify".equals(call.getNameAsString()) || call.getArguments().isEmpty()) continue;
            String receiver = call.getArgument(0).toString().replace("this.", "").strip();
            if (!mockLoggers.contains(receiver)) return true;
        }
        return false;
    }

    private static boolean isInsideInvalidVerification(MethodCallExpr call, List<MethodCallExpr> invalid) {
        for (MethodCallExpr outer : invalid) {
            Node current = call;
            while (current != null) {
                if (current == outer) return true;
                current = current.getParentNode().orElse(null);
            }
        }
        return false;
    }

    private static void classifyDiagnostics(List<RuntimeDiagnostic> diagnostics, Set<String> methods) {
        if (diagnostics == null) return;
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null || !methods.contains(testName(diagnostic.getTestId()))) continue;
            if (!"MOCKITO_WANTED_BUT_NOT_INVOKED".equals(diagnostic.getCategory())) continue;
            diagnostic.setCategory("MOCKITO_VERIFY_WRONG_INSTANCE");
            diagnostic.setSuggestion("测试验证的是 @Mock Logger，但生产代码使用 @Slf4j/static logger，"
                    + "两者不是同一实例。日志不是默认业务 Oracle；若已有其他业务断言，应删除该无效 verify；"
                    + "若日志是明确契约，请使用 Logback ListAppender 等真实日志捕获方式。");
        }
    }

    private static String testName(String testId) {
        String value = testId == null ? "" : testId;
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(hash + 1);
        int parenthesis = value.indexOf('(');
        if (parenthesis >= 0) value = value.substring(0, parenthesis);
        int bracket = value.indexOf('[');
        if (bracket >= 0) value = value.substring(0, bracket);
        return value.strip();
    }

    private static boolean isLoggerType(String type) {
        String value = type == null ? "" : type.replace(" ", "");
        return "Logger".equals(value) || value.endsWith(".Logger");
    }

    public static final class Result {
        private final String code;
        private final boolean detected;
        private final boolean changed;
        private final int removedVerifications;
        private final List<String> detectedMethods;
        private final List<String> repairedMethods;
        private final List<String> blockedMethods;

        private Result(String code, boolean detected, boolean changed, int removedVerifications,
                       List<String> detectedMethods, List<String> repairedMethods,
                       List<String> blockedMethods) {
            this.code = code == null ? "" : code;
            this.detected = detected;
            this.changed = changed;
            this.removedVerifications = removedVerifications;
            this.detectedMethods = detectedMethods;
            this.repairedMethods = repairedMethods;
            this.blockedMethods = blockedMethods;
        }

        private static Result none(String code) {
            return new Result(code, false, false, 0, List.of(), List.of(), List.of());
        }

        public String getCode() { return code; }
        public boolean isDetected() { return detected; }
        public boolean isChanged() { return changed; }
        public int getRemovedVerifications() { return removedVerifications; }
        public List<String> getDetectedMethods() { return detectedMethods; }
        public List<String> getRepairedMethods() { return repairedMethods; }
        public List<String> getBlockedMethods() { return blockedMethods; }
        public boolean hasBlockedMethods() { return !blockedMethods.isEmpty(); }

        public String repairMessage() {
            return "检测到 @Mock Logger 无法观察 @Slf4j/static logger；已从 "
                    + repairedMethods + " 删除 " + removedVerifications
                    + " 条必然无效的日志 verify，保留其余业务断言。";
        }

        public String blockedMessage() {
            return "检测到测试 Mock 接线错误：" + blockedMethods
                    + " 验证的是 @Mock Logger，但生产代码使用 @Slf4j/static logger，两个对象不是同一实例。"
                    + "日志验证是该测试唯一 Oracle，不能通过直接删除来伪造成功。"
                    + "请改为验证业务返回值/状态；若日志属于明确契约，请使用 Logback ListAppender 捕获真实日志。";
        }
    }
}
