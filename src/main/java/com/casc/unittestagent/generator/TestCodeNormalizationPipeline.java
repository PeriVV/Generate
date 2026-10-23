package com.casc.unittestagent.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.*;

/**
 * Shared, deterministic preparation for generation, repairs and Oracle comparison.
 * Rules must preserve argument expressions and never change assertion values, verification
 * modes, receivers or execution paths. Unknown bindings are left for compilation/runtime.
 * Oracle snapshots run the SAME rules on both sides instead of stripping arbitrary AST nodes.
 */
public final class TestCodeNormalizationPipeline {
    public enum Kind { IMPORT, FRAMEWORK_SYNTAX }
    public record Edit(String rule, Kind kind, int line) {}
    public record Result(String code, List<Edit> edits) {
        public boolean changed() { return !edits.isEmpty(); }
    }
    private interface Rule { void apply(CompilationUnit unit, List<Edit> edits); }
    private static final Set<String> MOCKITO = Set.of("org.mockito.Mockito");
    private static final Set<String> MATCHER_OWNERS = Set.of("org.mockito.Mockito", "org.mockito.ArgumentMatchers", "org.mockito.AdditionalMatchers");
    private static final Set<String> MATCHERS = Set.of("eq", "same", "refEq", "argThat", "any", "anyBoolean", "anyByte", "anyChar",
            "anyShort", "anyInt", "anyLong", "anyFloat", "anyDouble", "anyString", "anyList", "anySet", "anyMap", "anyCollection",
            "anyIterable", "isA", "isNull", "isNotNull", "notNull", "nullable", "contains", "startsWith", "endsWith", "matches",
            "booleanThat", "byteThat", "charThat", "shortThat", "intThat", "longThat", "floatThat", "doubleThat");
    private static final List<Rule> RULES = List.of(TestCodeNormalizationPipeline::annotationImports, TestCodeNormalizationPipeline::matcherArguments);

    private TestCodeNormalizationPipeline() {}

    public static Result normalize(String source) {
        String code = source == null ? "" : source;
        try {
            CompilationUnit unit = StaticJavaParser.parse(code);
            LexicalPreservingPrinter.setup(unit);
            List<Edit> edits = new ArrayList<>();
            for (Rule rule : RULES) rule.apply(unit, edits);
            return new Result(edits.isEmpty() ? code : LexicalPreservingPrinter.print(unit), List.copyOf(edits));
        } catch (RuntimeException ignored) {
            // No partial output on a parse/printing failure. The normal compiler gate owns it.
            return new Result(code, List.of());
        }
    }

    private static void annotationImports(CompilationUnit unit, List<Edit> edits) {
        if (unit.getImports().stream().noneMatch(i -> i.getNameAsString().startsWith("org.mockito.") || "org.mockito".equals(i.getNameAsString()))) return;
        for (String name : List.of("Mock", "Spy", "Captor", "InjectMocks")) {
            boolean used = unit.findAll(AnnotationExpr.class).stream().anyMatch(a -> name.equals(a.getNameAsString()));
            boolean declared = unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> name.equals(t.getNameAsString()))
                    || unit.findAll(TypeParameter.class).stream().anyMatch(t -> name.equals(t.getNameAsString()));
            boolean imported = unit.getImports().stream().anyMatch(i -> (!i.isAsterisk() && name.equals(i.getName().getIdentifier()))
                    || (!i.isStatic() && i.isAsterisk() && "org.mockito".equals(i.getNameAsString())));
            if (used && !declared && !imported) {
                unit.addImport("org.mockito." + name);
                edits.add(new Edit("MOCKITO_ANNOTATION_IMPORT", Kind.IMPORT, -1));
            }
        }
    }

    private static void matcherArguments(CompilationUnit unit, List<Edit> edits) {
        for (MethodCallExpr invocation : new ArrayList<>(unit.findAll(MethodCallExpr.class))) {
            if (!isMockitoInvocation(invocation)) continue;
            boolean hasMatcher = invocation.getArguments().stream().anyMatch(e -> matcher(e));
            if (!hasMatcher) continue;
            // Nested matchers/captors require separate semantics; never wrap them in eq(...).
            if (invocation.getArguments().stream().anyMatch(e -> {
                var calls = matcher(e) ? e.asMethodCallExpr().getArguments().stream()
                        .flatMap(a -> a.findAll(MethodCallExpr.class).stream()) : e.findAll(MethodCallExpr.class).stream();
                return calls.anyMatch(c -> MATCHERS.contains(c.getNameAsString()) || "capture".equals(c.getNameAsString()));
            })) continue;
            for (int index = 0; index < invocation.getArguments().size(); index++) {
                Expression argument = invocation.getArgument(index);
                Expression value = null;
                if (!matcher(argument)) value = argument;
                else if (argument.isMethodCallExpr() && "eq".equals(argument.asMethodCallExpr().getNameAsString())
                        && argument.asMethodCallExpr().getArguments().size() == 1
                        && argument.asMethodCallExpr().getTypeArguments().isEmpty()) {
                    var eq = argument.asMethodCallExpr();
                    if (eq.getArgument(0).findAll(MethodCallExpr.class).stream().anyMatch(c -> MATCHERS.contains(c.getNameAsString()) || "capture".equals(c.getNameAsString()))) continue;
                    if (!"org.mockito.ArgumentMatchers".equals(eq.getScope().map(Node::toString).orElse(""))) value = eq.getArgument(0);
                }
                if (value == null) continue;
                var eq = new MethodCallExpr(StaticJavaParser.parseExpression("org.mockito.ArgumentMatchers"), "eq");
                eq.addArgument(value.clone());
                invocation.setArgument(index, eq);
                edits.add(new Edit("MOCKITO_MATCHER_ARGUMENTS", Kind.FRAMEWORK_SYNTAX, invocation.getBegin().map(p -> p.line).orElse(-1)));
            }
        }
    }

    private static boolean matcher(Expression expression) {
        return expression.isMethodCallExpr() && MATCHERS.contains(expression.asMethodCallExpr().getNameAsString())
                && boundTo(expression.asMethodCallExpr(), MATCHER_OWNERS);
    }

    private static boolean isMockitoInvocation(MethodCallExpr invocation) {
        // verify(mock, mode).method(...)
        if (invocation.getScope().orElse(null) instanceof MethodCallExpr verify
                && "verify".equals(verify.getNameAsString()) && boundTo(verify, MOCKITO)) return true;
        // when(mock.method(...)) / given(mock.method(...))
        Node parent = invocation.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr stub && stub.getArguments().contains(invocation)
                && ("when".equals(stub.getNameAsString()) && boundTo(stub, MOCKITO)
                || "given".equals(stub.getNameAsString()) && boundTo(stub, Set.of("org.mockito.BDDMockito")))) {
            // Mockito.when(Type.staticCall(...)) still executes a real static call unless
            // an explicit static mock is active. Never add matcher registrations here.
            return invocation.getScope().isPresent()
                    && !MockitoReceiverClassifier.isTypeReceiver(invocation.getScope().get());
        }
        // doReturn/doThrow/doNothing/doAnswer(...).when(mock).method(...)
        if (invocation.getScope().orElse(null) instanceof MethodCallExpr when && "when".equals(when.getNameAsString())) {
            Expression root = when.getScope().orElse(null);
            while (root instanceof MethodCallExpr call) {
                if (Set.of("doReturn", "doThrow", "doNothing", "doAnswer").contains(call.getNameAsString()) && boundTo(call, MOCKITO)) return true;
                root = call.getScope().orElse(null);
            }
        }
        return false;
    }

    /** Conservative local binding proof; no simple-name suffix heuristics. */
    public static boolean boundTo(MethodCallExpr call, Set<String> owners) {
        CompilationUnit unit = call.findCompilationUnit().orElse(null);
        if (unit == null) return false;
        String name = call.getNameAsString();
        if (call.getScope().isEmpty()) {
            if (unit.findAll(MethodDeclaration.class).stream().anyMatch(m -> name.equals(m.getNameAsString()))) return false;
            if (call.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> !c.getExtendedTypes().isEmpty()).orElse(false)) return false;
            var exact = unit.getImports().stream().filter(i -> i.isStatic() && !i.isAsterisk() && name.equals(i.getName().getIdentifier())).toList();
            if (!exact.isEmpty()) return exact.stream().allMatch(i -> owners.contains(i.getNameAsString().substring(0, i.getNameAsString().lastIndexOf('.'))));
            var stars = unit.getImports().stream().filter(i -> i.isStatic() && i.isAsterisk()).toList();
            return stars.stream().anyMatch(i -> owners.contains(i.getNameAsString())) && stars.stream().allMatch(i ->
                    owners.contains(i.getNameAsString()) || MATCHER_OWNERS.contains(i.getNameAsString())
                            || Set.of("org.junit.jupiter.api.Assertions", "org.junit.Assert", "org.junit.jupiter.api.Assumptions").contains(i.getNameAsString()));
        }
        String scope = call.getScope().get().toString();
        String root = scope.split("\\.")[0];
        if (unit.findAll(VariableDeclarator.class).stream().anyMatch(v -> root.equals(v.getNameAsString()))
                || unit.findAll(Parameter.class).stream().anyMatch(p -> root.equals(p.getNameAsString()))
                || unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> root.equals(t.getNameAsString()))
                || unit.findAll(TypeParameter.class).stream().anyMatch(t -> root.equals(t.getNameAsString()))) return false;
        if (owners.contains(scope)) return true;
        if (scope.contains(".")) return false;
        if (unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> scope.equals(t.getNameAsString()))
                || unit.findAll(TypeParameter.class).stream().anyMatch(t -> scope.equals(t.getNameAsString()))) return false;
        var exact = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && scope.equals(i.getName().getIdentifier())).toList();
        if (!exact.isEmpty()) return exact.stream().allMatch(i -> owners.contains(i.getNameAsString()));
        return owners.stream().anyMatch(owner -> owner.endsWith("." + scope)) && unit.getImports().stream()
                .anyMatch(i -> !i.isStatic() && i.isAsterisk() && "org.mockito".equals(i.getNameAsString()))
                && unit.getImports().stream().noneMatch(i -> !i.isStatic() && i.isAsterisk() && !"org.mockito".equals(i.getNameAsString()));
    }
}
