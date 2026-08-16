package com.casc.unittestagent.coverage;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;

/**
 * 把 assertThrows(Target.class, () -> Method.invoke(...)) 规范化为对目标异常的直接断言。
 * Method.invoke 的包装行为是 Java 反射的确定语义，不应浪费模型轮次去猜测修复。
 */
public final class ReflectiveAssertionNormalizer {
    private ReflectiveAssertionNormalizer() { }

    public static Result normalize(String code) {
        if (code == null || code.isBlank()) return new Result(code == null ? "" : code, 0);
        try {
            CompilationUnit unit = StaticJavaParser.parse(code);
            int changes = 0;
            for (MethodCallExpr call : new ArrayList<>(unit.findAll(MethodCallExpr.class))) {
                if (!isUnsafeAssertThrows(call)) continue;
                Expression executable = unwrap(call.getArgument(1));
                if (!executable.isLambdaExpr()) continue;
                LambdaExpr lambda = executable.asLambdaExpr();
                Statement originalBody = lambda.getBody().clone();
                BlockStmt tryBlock = new BlockStmt();
                if (originalBody.isBlockStmt()) {
                    originalBody.asBlockStmt().getStatements().forEach(statement ->
                            tryBlock.addStatement(statement.clone()));
                } else {
                    tryBlock.addStatement(originalBody);
                }
                Parameter exception = new Parameter(
                        new ClassOrInterfaceType(null, "InvocationTargetException"), "reflectionFailure");
                CatchClause unwrapCause = new CatchClause(exception,
                        StaticJavaParser.parseBlock("{ throw reflectionFailure.getCause(); }"));
                TryStmt tryStmt = new TryStmt();
                tryStmt.setTryBlock(tryBlock);
                tryStmt.setCatchClauses(new com.github.javaparser.ast.NodeList<>(unwrapCause));
                BlockStmt replacement = new BlockStmt();
                replacement.addStatement(tryStmt);
                lambda.setBody(replacement);
                changes++;
            }
            if (changes == 0) return new Result(code, 0);
            unit.addImport("java.lang.reflect.InvocationTargetException");
            return new Result(unit.toString(), changes);
        } catch (Exception ignored) {
            // 无法解析时保留原码，后续语法/编译诊断仍是真值来源。
            return new Result(code, 0);
        }
    }

    static boolean isUnsafeAssertThrows(MethodCallExpr call) {
        if (call == null || !"assertThrows".equals(call.getNameAsString())
                || call.getArguments().size() < 2) return false;
        Expression expected = unwrap(call.getArgument(0));
        if (!(expected instanceof ClassExpr)) return false;
        String expectedType = ((ClassExpr) expected).getType().asString();
        if (expectedType.endsWith("InvocationTargetException")) return false;
        Expression executable = unwrap(call.getArgument(1));
        boolean invokesReflectively = executable.findAll(MethodCallExpr.class).stream()
                .anyMatch(candidate -> "invoke".equals(candidate.getNameAsString())
                        && candidate.getScope().isPresent());
        if (!invokesReflectively) return false;
        return executable.findAll(CatchClause.class).stream().noneMatch(catchClause ->
                catchClause.getParameter().getType().asString().contains("InvocationTargetException"));
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current.isEnclosedExpr() || current.isCastExpr()) {
            current = current.isEnclosedExpr()
                    ? current.asEnclosedExpr().getInner() : current.asCastExpr().getExpression();
        }
        return current;
    }

    public static final class Result {
        private final String code;
        private final int changes;

        private Result(String code, int changes) {
            this.code = code == null ? "" : code;
            this.changes = Math.max(0, changes);
        }

        public String getCode() { return code; }
        public int getChanges() { return changes; }
        public boolean isChanged() { return changes > 0; }
    }
}
