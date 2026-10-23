package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.RunnableTestSelectionReport;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.*;

/** Immutable exclusion intent carried from selection through every merge and the final commit. */
final class SelectionExclusionGuard {
    private record Rule(String id, String owner, String name, String signature, String body, boolean helper) { }
    private final List<Rule> rules;
    private SelectionExclusionGuard(List<Rule> rules) { this.rules = List.copyOf(rules); }

    static SelectionExclusionGuard snapshot(RunnableTestSelectionReport report, String sourceHash) {
        if (report == null || !report.hasActiveExclusions()) return new SelectionExclusionGuard(List.of());
        if (!sourceHash.equals(report.getSourceHash())) {
            throw new IllegalStateException("筛选后的被测源码已变更，请重新筛选后再合并。");
        }
        var rules = new ArrayList<Rule>();
        for (var origin : report.getActiveExclusionOrigins()) {
            var original = StaticJavaParser.parse(origin.originalCode());
            var indexed = new LinkedHashMap<String, MethodDeclaration>();
            original.findAll(MethodDeclaration.class).forEach(m -> indexed.put(id(original, m), m));
            for (var excluded : origin.tests()) {
                var method = indexed.get(excluded.testId());
                if (method == null) throw new IllegalStateException("排除清单无法绑定原测试方法：" + excluded.testId());
                rules.add(new Rule(excluded.testId(), owner(original, method), method.getNameAsString(),
                        method.getSignature().asString(), body(method, method.getNameAsString()), false));
            }
            Set<String> retained = new LinkedHashSet<>();
            com.casc.unittestagent.validation.JUnitTestInventory.methods(origin.originalCode()).forEach(m -> retained.add(m.id()));
            origin.tests().forEach(t -> retained.remove(t.testId()));
            var projection = com.casc.unittestagent.validation.TestSubsetProjection.project(origin.originalCode(), retained);
            for (String helperId : projection.removedHelpers()) {
                var helper = indexed.get(helperId);
                if (helper == null) throw new IllegalStateException("辅助方法清理清单无法绑定原代码：" + helperId);
                rules.add(new Rule(helperId, owner(original, helper), helper.getNameAsString(),
                        helper.getSignature().asString(), body(helper, helper.getNameAsString()), true));
            }
        }
        return new SelectionExclusionGuard(rules);
    }

    String removeExcludedFromExisting(String code) {
        if (rules.isEmpty() || code == null || code.isBlank()) return code;
        var unit = StaticJavaParser.parse(code); LexicalPreservingPrinter.setup(unit);
        var generatedLines = new HashSet<Integer>();
        unit.findAll(MethodDeclaration.class).forEach(m -> {
            int line = m.getBegin().orElseThrow().line;
            if (generatedRegion(unit, line)) generatedLines.add(line);
        });
        boolean changed = false;
        for (var method : new ArrayList<>(unit.findAll(MethodDeclaration.class))) {
            var matching = rules.stream().filter(r -> !r.helper && matches(unit, method, r)).toList();
            if (!matching.isEmpty()) {
                var proven = matching.stream().filter(r -> body(method, r.name).equals(r.body)).findFirst();
                var rule = proven.orElse(matching.get(0));
                boolean exact = id(unit, method).equals(rule.id);
                if (!body(method, rule.name).equals(rule.body)
                        || !exact && !generatedLines.contains(method.getBegin().orElseThrow().line)) {
                    throw new IllegalStateException("已有测试与排除清单冲突，不能安全自动删除：" + id(unit, method)
                            + "。该方法已被改动或无法确认生成来源，请人工处理后重新筛选。");
                }
                method.remove(); changed = true;
            }
        }
        var orphanHelpers = new ArrayList<MethodDeclaration>();
        for (var method : unit.findAll(MethodDeclaration.class)) {
            var matching = rules.stream().filter(r -> r.helper && matches(unit, method, r)).toList();
            if (matching.isEmpty()) continue;
            var proven = matching.stream().filter(r -> body(method, r.name).equals(r.body)).findFirst();
            if (proven.isEmpty() || !id(unit, method).equals(proven.get().id)
                    && !generatedLines.contains(method.getBegin().orElseThrow().line))
                throw new IllegalStateException("已清理的辅助方法在已有文件中发生冲突，不能安全自动删除：" + id(unit, method));
            orphanHelpers.add(method);
        }
        if (!orphanHelpers.isEmpty()) {
            if (!com.casc.unittestagent.validation.TestSubsetProjection.unreferenced(unit, orphanHelpers))
                throw new IllegalStateException("已清理的辅助方法仍被已有测试或共享初始化引用，请人工处理后重新筛选。");
            orphanHelpers.forEach(Node::remove); changed = true;
        }
        if (!changed) return code;
        // Remove only explicit imports whose identifiers disappeared with the excluded methods.
        var used = new HashSet<String>();
        unit.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream()
                .filter(n -> n.findAncestor(com.github.javaparser.ast.ImportDeclaration.class).isEmpty())
                .forEach(n -> used.add(n.getIdentifier()));
        unit.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).forEach(a -> used.add(a.getName().getIdentifier()));
        for (var imported : new ArrayList<>(unit.getImports())) {
            if (!imported.isAsterisk() && !used.contains(imported.getName().getIdentifier())) imported.remove();
        }
        return LexicalPreservingPrinter.print(unit);
    }

    void verifyAbsent(String code) {
        if (rules.isEmpty()) return;
        var unit = StaticJavaParser.parse(code);
        for (var method : unit.findAll(MethodDeclaration.class)) for (var rule : rules) {
            if (matches(unit, method, rule)) throw new IllegalStateException(
                    (rule.helper ? "最终测试文件重新包含已清理的辅助方法，已阻止写入：" : "最终测试文件重新包含已排除用例，已阻止写入：") + id(unit, method));
        }
    }

    private static boolean matches(CompilationUnit unit, MethodDeclaration method, Rule rule) {
        if (!owner(unit, method).equals(rule.owner)) return false;
        if (method.getNameAsString().equals(rule.name)) {
            return method.getSignature().asString().equals(rule.signature);
        }
        return method.getNameAsString().matches(java.util.regex.Pattern.quote(rule.name) + "_[0-9]+")
                && method.clone().setName(rule.name).getSignature().asString().equals(rule.signature);
    }
    private static String body(MethodDeclaration method, String name) {
        var copy = method.clone(); copy.setName(name);
        copy.getAllContainedComments().forEach(Node::remove); copy.removeComment();
        return copy.toString();
    }
    private static String id(CompilationUnit unit, MethodDeclaration method) {
        return owner(unit, method) + "#" + method.getSignature().asString();
    }
    private static String owner(CompilationUnit unit, MethodDeclaration method) {
        var names = new ArrayList<String>(); Node node = method.getParentNode().orElse(null);
        while (node != null) {
            if (node instanceof TypeDeclaration<?> type) names.add(0, type.getNameAsString());
            node = node.getParentNode().orElse(null);
        }
        return unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + String.join(".", names);
    }
    private static boolean generatedRegion(CompilationUnit unit, int line) {
        boolean generated = false;
        var comments = unit.getAllComments().stream().filter(c -> c.isLineComment()
                && c.getBegin().orElseThrow().line < line)
                .sorted(Comparator.comparingInt(c -> c.getBegin().orElseThrow().line)).toList();
        for (var comment : comments) {
            String text = comment.getContent().strip();
            if (text.startsWith("region UnitMind generated tests for ")) generated = true;
            if (text.equals("endregion")) generated = false;
        }
        return generated;
    }
}
