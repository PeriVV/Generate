package com.casc.unittestagent.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.casc.unittestagent.llm.LlmMethodPatch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 纯覆盖率改进阶段稳定已有测试脚手架，只从模型候选中接纳新增测试及其辅助成员。
 * 编译/运行修复仍由完整候选负责，避免增量合并阻止必要的错误修复。
 */
public class CoverageTestClassStabilizer {
    /** 覆盖率候选的三种互斥变换模式。 */
    public enum CandidateMode {
        RUNTIME_METHOD_PATCH,
        COMPILE_REPAIR,
        COVERAGE_EXPLORATION
    }
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");
    private static final Set<String> LIFECYCLE_ANNOTATIONS = Set.of(
            "BeforeEach", "AfterEach", "BeforeAll", "AfterAll");

    /**
     * 按当前唯一模式稳定候选。运行方法补丁已在 applyMethodPatches 中完成
     * 目标范围校验，必须原样保留，不得再进入“只接纳新测试”的探索合并。
     */
    public String stabilizeCandidate(String currentCode,
                                     String proposedCode,
                                     CandidateMode mode) {
        CandidateMode effectiveMode = mode == null ? CandidateMode.COVERAGE_EXPLORATION : mode;
        return switch (effectiveMode) {
            case RUNTIME_METHOD_PATCH -> proposedCode;
            case COMPILE_REPAIR -> preserveTestsDuringRepair(currentCode, proposedCode);
            case COVERAGE_EXPLORATION -> mergeAdditions(currentCode, proposedCode);
        };
    }

    public String mergeAdditions(String currentCode, String proposedCode) {
        if (currentCode == null || currentCode.isBlank()) return proposedCode;
        if (proposedCode == null || proposedCode.isBlank()) return currentCode;
        try {
            CompilationUnit current = StaticJavaParser.parse(currentCode);
            CompilationUnit proposed = StaticJavaParser.parse(proposedCode);
            ClassOrInterfaceDeclaration currentType = primaryType(current);
            ClassOrInterfaceDeclaration proposedType = proposed.getClassByName(currentType.getNameAsString())
                    .orElseGet(() -> primaryType(proposed));

            Set<String> existingMethods = new LinkedHashSet<>();
            currentType.getMethods().forEach(method -> existingMethods.add(methodKey(method)));
            List<MethodDeclaration> newTests = proposedType.getMethods().stream()
                    .filter(this::isTestMethod)
                    .filter(method -> !existingMethods.contains(methodKey(method)))
                    .toList();
            if (newTests.isEmpty()) return currentCode;

            mergeImports(current, proposed);
            mergeFields(currentType, proposedType);
            for (MethodDeclaration method : proposedType.getMethods()) {
                String key = methodKey(method);
                if (existingMethods.contains(key) || isLifecycleMethod(method)) continue;
                currentType.addMember(method.clone());
                existingMethods.add(key);
            }
            mergeNestedTypes(currentType, proposedType);
            return current.toString();
        } catch (Exception ignored) {
            // The normal syntax/compiler validation will provide a precise repair diagnostic.
            return proposedCode;
        }
    }

    /**
     * 编译修复允许改写已有方法，但不允许靠删除其他已生成测试获得“编译成功”。
     * 只有检测到测试方法被删除时才启动反向合并。否则必须原样接受修复候选，
     * 避免把模型为修复编译错误而删除的非法 import、字段或 helper 重新补回。
     */
    public String preserveTestsDuringRepair(String currentCode, String repairedCode) {
        if (currentCode == null || currentCode.isBlank()) return repairedCode;
        if (repairedCode == null || repairedCode.isBlank()) return currentCode;
        try {
            CompilationUnit current = StaticJavaParser.parse(currentCode);
            CompilationUnit repaired = StaticJavaParser.parse(repairedCode);
            ClassOrInterfaceDeclaration currentType = primaryType(current);
            ClassOrInterfaceDeclaration repairedType = repaired.getClassByName(currentType.getNameAsString())
                    .orElseGet(() -> primaryType(repaired));
            Set<String> repairedTests = new LinkedHashSet<>();
            repairedType.getMethods().stream().filter(this::isTestMethod)
                    .forEach(method -> repairedTests.add(methodKey(method)));
            boolean deletedTest = currentType.getMethods().stream().filter(this::isTestMethod)
                    .map(this::methodKey).anyMatch(key -> !repairedTests.contains(key));
            return deletedTest ? mergeAdditions(repairedCode, currentCode) : repairedCode;
        } catch (Exception ignored) {
            return repairedCode;
        }
    }

    /**
     * 运行修复只允许改动本轮失败的测试。模型即使重写了完整类，已经通过的测试方法
     * 也会从当前候选恢复；共享 fixture 仍允许修复，最终由完整回归执行确认其安全性。
     */
    public String preservePassingTestsDuringRepair(String currentCode,
                                                   String repairedCode,
                                                   Set<String> failingTestNames) {
        if (currentCode == null || currentCode.isBlank()) return repairedCode;
        if (repairedCode == null || repairedCode.isBlank()) return currentCode;
        Set<String> failing = failingTestNames == null ? Set.of() : failingTestNames;
        try {
            CompilationUnit current = StaticJavaParser.parse(currentCode);
            CompilationUnit repaired = StaticJavaParser.parse(repairedCode);
            ClassOrInterfaceDeclaration currentType = primaryType(current);
            ClassOrInterfaceDeclaration repairedType = repaired.getClassByName(currentType.getNameAsString())
                    .orElseGet(() -> primaryType(repaired));

            Map<String, MethodDeclaration> repairedMethods = new LinkedHashMap<>();
            repairedType.getMethods().forEach(method -> repairedMethods.put(methodKey(method), method));
            for (MethodDeclaration original : currentType.getMethods()) {
                if (!isTestMethod(original) || failing.contains(original.getNameAsString())) continue;
                String key = methodKey(original);
                MethodDeclaration changed = repairedMethods.get(key);
                if (changed == null) {
                    repairedType.addMember(original.clone());
                } else if (!changed.toString().equals(original.toString())) {
                    changed.replace(original.clone());
                }
            }
            mergeImports(repaired, current);
            return repaired.toString();
        } catch (Exception ignored) {
            return repairedCode;
        }
    }

    /**
     * 将运行修复作为方法级事务应用。补丁只能替换已存在且本轮失败的测试方法，
     * 无法增删 import、fixture、helper 或其他已通过测试。
     */
    public String applyMethodPatches(String currentCode,
                                     List<LlmMethodPatch> patches,
                                     Set<String> editableTestNames) {
        if (currentCode == null || currentCode.isBlank()) {
            throw new IllegalArgumentException("当前测试类为空，无法应用方法补丁。");
        }
        if (patches == null || patches.isEmpty()) return currentCode;
        Set<String> editable = editableTestNames == null ? Set.of() : editableTestNames;
        CompilationUnit unit = StaticJavaParser.parse(currentCode);
        ClassOrInterfaceDeclaration type = primaryType(unit);
        Map<String, MethodDeclaration> currentTests = new LinkedHashMap<>();
        type.getMethods().stream().filter(this::isTestMethod)
                .forEach(method -> currentTests.put(method.getNameAsString(), method));
        Set<String> applied = new LinkedHashSet<>();
        for (LlmMethodPatch patch : patches) {
            String name = patch == null ? "" : patch.getMethodName().strip();
            if (name.isBlank() || !editable.contains(name)) {
                throw new IllegalArgumentException("方法补丁越界，非本轮失败测试：" + name);
            }
            if (!applied.add(name)) throw new IllegalArgumentException("重复方法补丁：" + name);
            MethodDeclaration original = currentTests.get(name);
            if (original == null) throw new IllegalArgumentException("当前测试类中不存在目标方法：" + name);
            BodyDeclaration<?> declaration = StaticJavaParser.parseBodyDeclaration(patch.getMethodCode());
            if (!declaration.isMethodDeclaration()) {
                throw new IllegalArgumentException("方法补丁不是完整方法声明：" + name);
            }
            MethodDeclaration replacement = declaration.asMethodDeclaration();
            if (!name.equals(replacement.getNameAsString()) || !isTestMethod(replacement)) {
                throw new IllegalArgumentException("方法补丁名称或测试注解不匹配：" + name);
            }
            original.replace(replacement);
        }
        return unit.toString();
    }

    private ClassOrInterfaceDeclaration primaryType(CompilationUnit unit) {
        return unit.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException("测试源码中未找到测试类"));
    }

    private void mergeImports(CompilationUnit current, CompilationUnit proposed) {
        Set<String> imports = new LinkedHashSet<>();
        current.getImports().forEach(item -> imports.add(item.toString().trim()));
        for (ImportDeclaration declaration : proposed.getImports()) {
            if (imports.add(declaration.toString().trim())) current.addImport(declaration.clone());
        }
    }

    private void mergeFields(ClassOrInterfaceDeclaration current, ClassOrInterfaceDeclaration proposed) {
        Set<String> names = new LinkedHashSet<>();
        current.getFields().forEach(field -> field.getVariables()
                .forEach(variable -> names.add(variable.getNameAsString())));
        for (FieldDeclaration field : proposed.getFields()) {
            boolean missing = field.getVariables().stream()
                    .allMatch(variable -> !names.contains(variable.getNameAsString()));
            if (!missing) continue;
            current.addMember(field.clone());
            field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
        }
    }

    private void mergeNestedTypes(ClassOrInterfaceDeclaration current, ClassOrInterfaceDeclaration proposed) {
        Set<String> names = new LinkedHashSet<>();
        for (BodyDeclaration<?> member : current.getMembers()) {
            if (member.isTypeDeclaration()) names.add(((TypeDeclaration<?>) member).getNameAsString());
        }
        for (BodyDeclaration<?> member : proposed.getMembers()) {
            if (!member.isTypeDeclaration()) continue;
            TypeDeclaration<?> type = (TypeDeclaration<?>) member;
            if (names.add(type.getNameAsString())) current.addMember((BodyDeclaration<?>) type.clone());
        }
    }

    private boolean isTestMethod(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> TEST_ANNOTATIONS.contains(annotation.getName().getIdentifier()));
    }

    private boolean isLifecycleMethod(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> LIFECYCLE_ANNOTATIONS.contains(annotation.getName().getIdentifier()));
    }

    private String methodKey(MethodDeclaration method) {
        return method.getNameAsString() + "(" + method.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .reduce((left, right) -> left + "," + right).orElse("") + ")";
    }
}
