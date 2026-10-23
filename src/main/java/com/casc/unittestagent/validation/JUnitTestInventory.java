package com.casc.unittestagent.validation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.*;

/** Shared source inventory and binding of real JUnit IDs to declared method groups. */
public final class JUnitTestInventory {
    private JUnitTestInventory() { }
    public record Method(String id, String owner, String name, int start, int end, boolean removable) { }
    private static final Set<String> ANNOTATIONS = Set.of("org.junit.jupiter.api.Test", "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory", "org.junit.jupiter.api.TestTemplate", "org.junit.jupiter.params.ParameterizedTest", "org.junit.Test");

    public static List<Method> methods(String code) {
        CompilationUnit unit = StaticJavaParser.parse(code); List<Method> methods = new ArrayList<>();
        boolean generatedRegions = unit.getAllComments().stream().anyMatch(c -> c.isLineComment()
                && c.getContent().strip().startsWith("region UnitMind generated tests for "));
        if (generatedRegions && inGeneratedRegion(unit, Integer.MAX_VALUE)) {
            throw new IllegalStateException("UnitMind 生成区域缺少结束标记，无法确认人工测试边界。");
        }
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        for (MethodDeclaration declaration : unit.findAll(MethodDeclaration.class)) {
            if (!isTest(unit, declaration)) continue;
            String owner = pkg + owner(declaration); int start = declaration.getBegin().orElseThrow().line;
            String id = owner + "#" + declaration.getSignature().asString();
            methods.add(new Method(id, owner, declaration.getNameAsString(), start,
                    declaration.getEnd().orElseThrow().line, !generatedRegions || inGeneratedRegion(unit, start)));
        }
        return methods;
    }

    public static boolean isTest(CompilationUnit unit, MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString(); if (ANNOTATIONS.contains(name)) return true;
            if (name.contains(".") || unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> name.equals(t.getNameAsString()))) return false;
            return unit.getImports().stream().anyMatch(i -> !i.isStatic() && (i.isAsterisk()
                    ? ANNOTATIONS.contains(i.getNameAsString() + "." + name)
                    : ANNOTATIONS.contains(i.getNameAsString()) && name.equals(i.getName().getIdentifier())));
        });
    }

    public static Method resolve(List<Method> methods, Set<String> retained, String testId) {
        int hash = testId.indexOf('#'); if (hash < 0) return null;
        String owner = testId.substring(0, hash).replace('$', '.');
        String name = testId.substring(hash + 1).split("[\\[(]", 2)[0];
        var candidates = methods.stream().filter(m -> retained.contains(m.id) && owner.equals(m.owner) && name.equals(m.name)).toList();
        if (candidates.size() == 1) return candidates.get(0);
        String invocation = testId.substring(hash + 1);
        int open = invocation.indexOf('('), close = invocation.indexOf(')', open + 1);
        if (open < 0 || close < 0) return null;
        String parameters = simpleParameters(invocation.substring(open + 1, close));
        var exact = candidates.stream().filter(m -> {
            int begin = m.id.lastIndexOf('(');
            return simpleParameters(m.id.substring(begin + 1, m.id.length() - 1)).equals(parameters);
        }).toList();
        return exact.size() == 1 ? exact.get(0) : null;
    }

    private static String simpleParameters(String parameters) {
        String erased = parameters;
        while (erased.contains("<")) {
            String next = erased.replaceAll("<[^<>]*>", "");
            if (next.equals(erased)) break;
            erased = next;
        }
        return java.util.Arrays.stream(erased.split(",", -1)).map(String::strip)
                .map(p -> p.replace('$', '.').replace("...", "[]"))
                .map(p -> p.substring(p.lastIndexOf('.') + 1)).collect(java.util.stream.Collectors.joining(","));
    }

    public static String owner(Node node) {
        List<String> owners = new ArrayList<>(); Node parent = node.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof TypeDeclaration<?> type) owners.add(0, type.getNameAsString());
            parent = parent.getParentNode().orElse(null);
        }
        return String.join(".", owners);
    }

    public static boolean isRemovableMember(CompilationUnit unit, Node member) {
        boolean regions = unit.getAllComments().stream().anyMatch(c -> c.isLineComment()
                && c.getContent().strip().startsWith("region UnitMind generated tests for "));
        return !regions || inGeneratedRegion(unit, member.getBegin().orElseThrow().line);
    }

    private static boolean inGeneratedRegion(CompilationUnit unit, int line) {
        Deque<Boolean> regions = new ArrayDeque<>();
        var comments = unit.getAllComments().stream().filter(c -> c.isLineComment())
                .sorted(java.util.Comparator.comparingInt(c -> c.getBegin().orElseThrow().line)).toList();
        for (var comment : comments) {
            if (comment.getBegin().orElseThrow().line > line) break;
            String text = comment.getContent().strip();
            if (text.startsWith("region ")) regions.push(text.startsWith("region UnitMind generated tests for "));
            else if (text.equals("endregion") && !regions.isEmpty()) regions.pop();
        }
        return regions.contains(true);
    }

}
