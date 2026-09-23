package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.ClassJUnitRepairScope;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对整类运行修复执行 AST 范围门禁，保护已通过测试和测试集合。 */
public class ClassRuntimeRepairStabilizer {
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    public Result stabilize(String currentCode,
                            String proposedCode,
                            ClassJUnitRepairScope scope,
                            Set<String> passedTestIds,
                            Set<String> failingTestIds) {
        if (currentCode == null || currentCode.isBlank()) {
            throw new IllegalArgumentException("当前完整测试类为空。");
        }
        if (proposedCode == null || proposedCode.isBlank()) {
            throw new IllegalArgumentException("整类运行修复返回了空代码。");
        }
        CompilationUnit current = StaticJavaParser.parse(currentCode);
        CompilationUnit proposed = StaticJavaParser.parse(proposedCode);
        ClassOrInterfaceDeclaration currentType = primaryType(current);
        ClassOrInterfaceDeclaration proposedType = proposed.getClassByName(currentType.getNameAsString())
                .orElseThrow(() -> new IllegalArgumentException(
                        "修复候选未声明测试类 " + currentType.getNameAsString()));
        if (!packageName(current).equals(packageName(proposed))) {
            throw new IllegalArgumentException("修复候选修改了测试类 package。");
        }
        if (hasDisabled(proposedType)) {
            throw new IllegalArgumentException("修复候选不得添加 @Disabled。");
        }

        Map<String, MethodDeclaration> currentTests = tests(currentType);
        Map<String, MethodDeclaration> proposedTests = tests(proposedType);
        if (!currentTests.keySet().equals(proposedTests.keySet())) {
            throw new IllegalArgumentException("修复候选不得增删或改名测试方法：原="
                    + currentTests.keySet() + "，新=" + proposedTests.keySet());
        }
        for (MethodDeclaration test : proposedTests.values()) validateTestBody(test);

        Set<String> passed = methodNames(passedTestIds);
        Set<String> failing = methodNames(failingTestIds);
        ClassJUnitRepairScope effectiveScope = scope == null
                ? ClassJUnitRepairScope.METHOD_ONLY : scope;
        CompilationUnit stabilized;
        boolean fixtureChanged;
        if (effectiveScope == ClassJUnitRepairScope.METHOD_ONLY) {
            stabilized = current.clone();
            ClassOrInterfaceDeclaration target = primaryType(stabilized);
            Map<String, MethodDeclaration> targetTests = tests(target);
            for (String name : failing) {
                MethodDeclaration replacement = proposedTests.get(name);
                MethodDeclaration original = targetTests.get(name);
                if (replacement != null && original != null) {
                    MethodDeclaration copy = replacement.clone();
                    preserveOwnerMarker(currentTests.get(name), copy);
                    original.replace(copy);
                }
            }
            mergeImports(stabilized, proposed);
            fixtureChanged = false;
        } else {
            stabilized = proposed;
            ClassOrInterfaceDeclaration target = primaryType(stabilized);
            Map<String, MethodDeclaration> targetTests = tests(target);
            for (String name : passed) {
                MethodDeclaration original = currentTests.get(name);
                MethodDeclaration replacement = targetTests.get(name);
                if (original != null && replacement != null) replacement.replace(original.clone());
            }
            targetTests = tests(target);
            for (String name : currentTests.keySet()) {
                preserveOwnerMarker(currentTests.get(name), targetTests.get(name));
            }
            mergeImports(stabilized, current);
            fixtureChanged = !nonTestMembers(currentType).equals(nonTestMembers(target));
        }

        String code = stabilized.toString();
        Map<String, MethodDeclaration> stabilizedTests = tests(primaryType(stabilized));
        List<String> changed = new ArrayList<>();
        for (String name : currentTests.keySet()) {
            if (!normalize(currentTests.get(name)).equals(normalize(stabilizedTests.get(name)))) {
                changed.add(name);
            }
        }
        if (changed.isEmpty() && !fixtureChanged) {
            throw new IllegalArgumentException("整类运行修复未产生语义修改。");
        }
        return new Result(code, changed, fixtureChanged);
    }

    private void validateTestBody(MethodDeclaration test) {
        if (hasDisabled(test)) throw new IllegalArgumentException("测试方法不得添加 @Disabled：" + test.getName());
        if (test.getBody().isEmpty() || test.getBody().get().getStatements().isEmpty()) {
            throw new IllegalArgumentException("测试方法不得为空：" + test.getName());
        }
        for (MethodCallExpr call : test.findAll(MethodCallExpr.class)) {
            if (!("assumeTrue".equals(call.getNameAsString()) || "assumeFalse".equals(call.getNameAsString()))) continue;
            // 检查所有实参位置：JUnit5 assumeTrue(boolean,String) 与 JUnit4 assumeTrue(String,boolean)
            // 参数顺序不同，只查固定位置会被双参重载绕过。静态字面量检查只是纵深防御，
            // 运行时 skipped 不变量（接受门禁）才是兜底。
            for (Expression argument : call.getArguments()) {
                if (!argument.isBooleanLiteralExpr()) continue;
                boolean literal = argument.asBooleanLiteralExpr().getValue();
                if (("assumeTrue".equals(call.getNameAsString()) && !literal)
                        || ("assumeFalse".equals(call.getNameAsString()) && literal)) {
                    throw new IllegalArgumentException("测试方法不得用恒假 assumption 跳过执行：" + test.getName());
                }
            }
        }
    }

    private Map<String, MethodDeclaration> tests(ClassOrInterfaceDeclaration type) {
        Map<String, MethodDeclaration> result = new LinkedHashMap<>();
        type.getMethods().stream().filter(this::isTest).forEach(method -> {
            String name = method.getNameAsString();
            if (result.put(name, method) != null) {
                throw new IllegalArgumentException("整类运行修复暂不支持同名重载测试：" + name);
            }
        });
        return result;
    }

    private Set<String> methodNames(Set<String> ids) {
        Set<String> result = new LinkedHashSet<>();
        if (ids == null) return result;
        for (String id : ids) {
            String value = id == null ? "" : id;
            int hash = value.lastIndexOf('#');
            if (hash >= 0) value = value.substring(hash + 1);
            int paren = value.indexOf('(');
            if (paren >= 0) value = value.substring(0, paren);
            int bracket = value.indexOf('[');
            if (bracket >= 0) value = value.substring(0, bracket);
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    private void preserveOwnerMarker(MethodDeclaration original, MethodDeclaration replacement) {
        if (original == null || replacement == null || replacement.getComment().isPresent()) return;
        original.getComment().ifPresent(comment -> replacement.setComment(comment.clone()));
    }

    private List<String> nonTestMembers(ClassOrInterfaceDeclaration type) {
        List<String> result = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member.isMethodDeclaration() && isTest(member.asMethodDeclaration())) continue;
            result.add(member.toString().replaceAll("\\s+", " ").trim());
        }
        return result;
    }

    private void mergeImports(CompilationUnit target, CompilationUnit source) {
        Set<String> present = new LinkedHashSet<>();
        target.getImports().forEach(item -> present.add(item.toString().trim()));
        for (ImportDeclaration item : source.getImports()) {
            if (present.add(item.toString().trim())) target.addImport(item.clone());
        }
    }

    private ClassOrInterfaceDeclaration primaryType(CompilationUnit unit) {
        return unit.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException("测试源码中未找到测试类"));
    }

    private String packageName(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
    }

    private boolean isTest(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> TEST_ANNOTATIONS.contains(annotation.getName().getIdentifier()));
    }

    private boolean hasDisabled(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .anyMatch(annotation -> "Disabled".equals(annotation.getName().getIdentifier()));
    }

    private String normalize(MethodDeclaration method) {
        return method == null ? "" : method.toString().replaceAll("\\s+", " ").trim();
    }

    public static final class Result {
        private final String code;
        private final List<String> changedTestIds;
        private final boolean fixtureChanged;

        private Result(String code, List<String> changedTestIds, boolean fixtureChanged) {
            this.code = code;
            this.changedTestIds = new ArrayList<>(changedTestIds);
            this.fixtureChanged = fixtureChanged;
        }

        public String getCode() { return code; }
        public List<String> getChangedTestIds() { return new ArrayList<>(changedTestIds); }
        public boolean isFixtureChanged() { return fixtureChanged; }
    }
}
