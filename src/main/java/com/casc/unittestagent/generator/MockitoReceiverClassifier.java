package com.casc.unittestagent.generator;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;

/** Local AST evidence for a type receiver, shared by preparation and preflight. */
public final class MockitoReceiverClassifier {
    private MockitoReceiverClassifier() { }

    public static boolean isTypeReceiver(Expression expression) {
        Expression scope = expression;
        while (scope.isEnclosedExpr()) scope = scope.asEnclosedExpr().getInner();
        if (!scope.isNameExpr() && !scope.isFieldAccessExpr()) return false;
        String name = scope.toString();
        String root = name.split("\\.")[0];
        var unit = scope.findCompilationUnit().orElse(null);
        if (unit == null) return false;
        // A field/parameter such as holder.Client is an object expression, even with capitals.
        if (unit.findAll(VariableDeclarator.class).stream().anyMatch(v -> root.equals(v.getNameAsString()))
                || unit.findAll(Parameter.class).stream().anyMatch(p -> root.equals(p.getNameAsString()))) return false;
        if (unit.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk()
                && (name.equals(i.getNameAsString()) || root.equals(i.getName().getIdentifier())))) return true;
        // Includes same-package types, qualified package.Type and nested Outer.Inner types.
        // Unknown unconventional lowercase type names remain for the compiler/runtime.
        String terminal = name.substring(name.lastIndexOf('.') + 1);
        return !terminal.isBlank() && Character.isUpperCase(terminal.charAt(0));
    }
}
