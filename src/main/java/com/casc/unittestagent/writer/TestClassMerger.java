package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.GenerationScope;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合并已有测试类和本次生成的测试代码。
 * 该类避免追加时重复 package、import、Mock 字段和测试方法，保证多次为不同方法生成测试不会覆盖旧内容。
 */
public class TestClassMerger {
    /**
     * 合并已有测试类和新生成测试类。
     *
     * @param existingTestCode 已有测试类源码
     * @param generatedTestCode 本次生成的完整测试类源码
     * @param testedMethodName 本次被测方法名，用于 UnitMind region 标记
     * @param options 合并选项，控制是否替换旧区域、去重 import 和字段
     * @return 合并后的测试类源码
     */
    public String merge(String existingTestCode,
                        String generatedTestCode,
                        String testedMethodName,
                        MergeOptions options) {
        return merge(existingTestCode, generatedTestCode, GenerationTarget.method(testedMethodName), options);
    }

    public String merge(String existingTestCode,
                        String generatedTestCode,
                        GenerationTarget generationTarget,
                        MergeOptions options) {
        if (existingTestCode == null || existingTestCode.isBlank()) {
            return generatedTestCode;
        }
        MergeOptions effectiveOptions = options == null ? new MergeOptions() : options;
        String existing = normalizeNewlines(existingTestCode);
        String generated = normalizeNewlines(generatedTestCode);

        String regionLabel = generationTarget == null ? "未知方法" : generationTarget.regionLabel();
        if (effectiveOptions.isReplaceExistingRegionForSameMethod()) {
            existing = removeExistingRegion(existing, regionLabel);
        }
        if (generationTarget != null && generationTarget.getScope() == GenerationScope.CLASS) {
            validateClassMergeConflicts(existing, generated);
            existing = mergeClassAnnotations(existing, generated);
        }

        validateImportConflicts(StaticJavaParser.parse(existing), StaticJavaParser.parse(generated));
        String members = mergeMethodMembers(existing, generated, effectiveOptions);
        existing = mergeClassAnnotations(existing, generated);
        String merged = insertIntoTestClass(existing, generated, buildRegion(regionLabel, List.of(), members));
        return effectiveOptions.isMergeImports() ? mergeImports(merged, existing, generated) : merged;
    }

    /** Shared fixtures are AST members, regardless of visibility, annotation layout or nested tests. */
    private String mergeMethodMembers(String existing, String generated, MergeOptions options) {
        var oldUnit = StaticJavaParser.parse(existing); var newUnit = StaticJavaParser.parse(generated);
        var newType = testType(newUnit, null);
        var oldType = testType(oldUnit, newType.getNameAsString());
        if (!oldUnit.getPackageDeclaration().map(p -> p.getNameAsString()).equals(
                newUnit.getPackageDeclaration().map(p -> p.getNameAsString()))) {
            throw new IllegalStateException("追加测试的包名与已有测试类不一致。");
        }
        var fields = fieldsByName(oldType);
        var methods = new LinkedHashMap<String, MethodDeclaration>();
        oldType.getMethods().forEach(m -> methods.put(methodSignature(m), m));
        Set<String> names = new LinkedHashSet<>(); oldType.getMethods().forEach(m -> names.add(m.getNameAsString()));
        StringBuilder members = new StringBuilder();
        for (var original : newType.getMembers()) {
            var member = original.clone();
            if (member.isFieldDeclaration()) {
                var field = member.asFieldDeclaration();
                var conflicts = field.getVariables().stream().map(v -> fields.get(v.getNameAsString()))
                        .filter(java.util.Objects::nonNull).toList();
                if (!conflicts.isEmpty()) {
                    if (conflicts.size() == field.getVariables().size()
                            && conflicts.stream().allMatch(f -> equivalent(f, field))) continue;
                    throw new IllegalStateException("共享 fixture 字段冲突，不能安全追加：" + field.getVariables());
                }
            } else if (member.isMethodDeclaration()) {
                var method = member.asMethodDeclaration(); var prior = methods.get(methodSignature(method));
                if (prior != null) {
                    if (equivalent(prior, method)) continue;
                    if (!testMethod(prior) || !testMethod(method) || !options.isRenameDuplicatedTestMethods()) {
                        throw new IllegalStateException("共享 fixture/helper 方法冲突，不能安全追加：" + methodSignature(method));
                    }
                    String name = uniqueName(method.getNameAsString(), names); method.setName(name); names.add(name);
                }
                for (var priorMethod : oldType.getMethods()) {
                    if (!java.util.Collections.disjoint(lifecycleRoles(priorMethod), lifecycleRoles(method))) {
                        throw new IllegalStateException("JUnit 生命周期 fixture 冲突，不能安全叠加：" + method.getNameAsString());
                    }
                }
            } else if (member.isTypeDeclaration()) {
                var type = member.asTypeDeclaration();
                var prior = oldType.getMembers().stream().filter(m -> m.isTypeDeclaration()
                        && m.asTypeDeclaration().getNameAsString().equals(type.getNameAsString())).findFirst();
                if (prior.isPresent()) {
                    if (equivalent(prior.get(), member)) continue;
                    throw new IllegalStateException("嵌套测试类冲突，不能安全追加：" + type.getNameAsString());
                }
            } else {
                throw new IllegalStateException("追加包含构造函数或初始化块，请人工合并共享 fixture。");
            }
            members.append("\n").append(member).append("\n");
        }
        return members.toString();
    }

    private boolean equivalent(com.github.javaparser.ast.Node left, com.github.javaparser.ast.Node right) {
        var a = left.clone(); var b = right.clone();
        a.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove); a.removeComment();
        b.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove); b.removeComment();
        return a.equals(b);
    }
    private boolean testMethod(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> Set.of("Test", "ParameterizedTest", "RepeatedTest",
                "TestFactory", "TestTemplate").contains(a.getName().getIdentifier()));
    }
    private Set<String> lifecycleRoles(MethodDeclaration method) {
        Set<String> result = new LinkedHashSet<>();
        method.getAnnotations().forEach(a -> {
            String name = a.getName().getIdentifier();
            if (Set.of("BeforeEach", "AfterEach", "BeforeAll", "AfterAll").contains(name)) result.add(name);
        });
        return result;
    }

    /**
     * 判断已有测试类中是否已经存在某个方法的 UnitMind 生成区域。
     *
     * @param existingTestCode 已有测试类源码
     * @param testedMethodName 被测方法名
     * @return 已存在对应区域时返回 true
     */
    public boolean hasRegionForMethod(String existingTestCode, String testedMethodName) {
        if (existingTestCode == null || testedMethodName == null || testedMethodName.isBlank()) {
            return false;
        }
        String marker = "region UnitMind generated tests for " + testedMethodName;
        return StaticJavaParser.parse(existingTestCode).getAllComments().stream()
                .anyMatch(c -> c.isLineComment() && marker.equals(c.getContent().strip()));
    }

    public boolean hasRegion(String existingTestCode, GenerationTarget target) {
        return target != null && hasRegionForMethod(existingTestCode, target.regionLabel());
    }

    /** 类级 region 必须保护人工成员；对无法安全自动化解决的冲突显式阻止默认合并。 */
    private void validateClassMergeConflicts(String existingCode, String generatedCode) {
        try {
            CompilationUnit existingUnit = StaticJavaParser.parse(existingCode);
            CompilationUnit generatedUnit = StaticJavaParser.parse(generatedCode);
            validateImportConflicts(existingUnit, generatedUnit);
            ClassOrInterfaceDeclaration generatedClass = testType(generatedUnit, null);
            ClassOrInterfaceDeclaration existingClass = testType(existingUnit, generatedClass.getNameAsString());
            Map<String, FieldDeclaration> existingFields = fieldsByName(existingClass);
            for (FieldDeclaration generatedField : generatedClass.getFields()) {
                generatedField.getVariables().forEach(variable -> {
                    FieldDeclaration existingField = existingFields.get(variable.getNameAsString());
                    if (existingField == null) return;
                    boolean compatibleMock = mockField(existingField) && mockField(generatedField)
                            && existingField.getVariable(0).getType().asString().equals(variable.getType().asString());
                    if (!compatibleMock) {
                        throw new IllegalStateException("整类生成成员与人工字段冲突：" + variable.getNameAsString()
                                + "。请选择覆盖整个测试类，或手工处理后再追加。");
                    }
                });
            }
            Set<String> existingMethods = new LinkedHashSet<>();
            existingClass.getMethods().forEach(method -> existingMethods.add(methodSignature(method)));
            for (MethodDeclaration method : generatedClass.getMethods()) {
                if (!existingMethods.contains(methodSignature(method))) continue;
                throw new IllegalStateException("整类生成方法与人工方法签名冲突：" + methodSignature(method)
                        + "。请选择覆盖整个测试类，或手工处理后再追加。");
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("无法安全分析整类合并冲突：" + ex.getMessage(), ex);
        }
    }

    /** 类模式追加时，把 MockitoExtension 等类级 fixture 注解合并到人工测试类。 */
    private String mergeClassAnnotations(String existingCode, String generatedCode) {
        CompilationUnit existingUnit = StaticJavaParser.parse(existingCode);
        CompilationUnit generatedUnit = StaticJavaParser.parse(generatedCode);
        ClassOrInterfaceDeclaration generatedClass = testType(generatedUnit, null);
        ClassOrInterfaceDeclaration existingClass = testType(existingUnit, generatedClass.getNameAsString());
        Set<String> existingAnnotations = new LinkedHashSet<>();
        existingClass.getAnnotations().forEach(annotation -> existingAnnotations.add(normalizeAnnotation(annotation.toString())));
        List<String> missing = new ArrayList<>();
        generatedClass.getAnnotations().forEach(annotation -> {
            if (!existingAnnotations.contains(normalizeAnnotation(annotation.toString()))) {
                String name = annotation.getName().getIdentifier();
                boolean conflict = existingClass.getAnnotations().stream()
                        .anyMatch(a -> a.getName().getIdentifier().equals(name));
                if (conflict && name.equals("DisplayName")) return; // Keep the human class title.
                if (conflict && !Set.of("Tag", "ExtendWith").contains(name)) {
                    throw new IllegalStateException("测试类 fixture 注解冲突，不能安全追加：" + name);
                }
                missing.add(annotation.toString());
            }
        });
        if (missing.isEmpty()) return existingCode;

        var begin = existingClass.getBegin().orElseThrow();
        int offset = 0;
        for (int line = 1; line < begin.line; line++) offset = existingCode.indexOf('\n', offset) + 1;
        offset += begin.column - 1;
        return existingCode.substring(0, offset) + String.join("\n", missing) + "\n" + existingCode.substring(offset);
    }

    private String normalizeAnnotation(String annotation) {
        return annotation == null ? "" : StaticJavaParser.parseAnnotation(annotation).toString();
    }

    private void validateImportConflicts(CompilationUnit existing, CompilationUnit generated) {
        Map<String, String> imports = new LinkedHashMap<>();
        for (ImportDeclaration declaration : existing.getImports()) addImport(imports, declaration);
        for (ImportDeclaration declaration : generated.getImports()) {
            if (declaration.isStatic() || declaration.isAsterisk()) continue;
            String name = declaration.getNameAsString();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            String previous = imports.get(simple);
            if (previous != null && !previous.equals(name)) {
                throw new IllegalStateException("整类生成 import 简名冲突：" + previous + " / " + name);
            }
            imports.put(simple, name);
        }
    }

    private void addImport(Map<String, String> imports, ImportDeclaration declaration) {
        if (declaration.isStatic() || declaration.isAsterisk()) return;
        String name = declaration.getNameAsString();
        imports.put(name.substring(name.lastIndexOf('.') + 1), name);
    }

    private Map<String, FieldDeclaration> fieldsByName(ClassOrInterfaceDeclaration type) {
        Map<String, FieldDeclaration> fields = new LinkedHashMap<>();
        for (FieldDeclaration field : type.getFields()) {
            field.getVariables().forEach(variable -> fields.put(variable.getNameAsString(), field));
        }
        return fields;
    }

    private boolean mockField(FieldDeclaration field) {
        return field.getAnnotations().stream().anyMatch(annotation ->
                "Mock".equals(annotation.getNameAsString()) || "InjectMocks".equals(annotation.getNameAsString()));
    }

    private String methodSignature(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        method.getParameters().forEach(parameter -> types.add(parameter.getType().asString()));
        return method.getNameAsString() + "(" + String.join(",", types) + ")";
    }

    /**
     * 合并 import 并按普通 import、static import 分组排序。
     * 合并后重新写入 package 声明之后，避免追加时产生重复 import。
     */
    private String mergeImports(String mergedCode, String existingCode, String generatedCode) {
        Set<String> normalImports = new LinkedHashSet<>();
        Set<String> staticImports = new LinkedHashSet<>();
        collectImports(existingCode, normalImports, staticImports);
        collectImports(generatedCode, normalImports, staticImports);

        var unit = StaticJavaParser.parse(mergedCode);
        var text = new StringBuilder(mergedCode);
        var declarations = new ArrayList<>(unit.getImports());
        java.util.Collections.reverse(declarations);
        for (var declaration : declarations) {
            text.delete(offset(mergedCode, declaration.getBegin().orElseThrow()), offset(mergedCode, declaration.getEnd().orElseThrow()) + 1);
        }
        String withoutImports = text.toString();
        int insertion = unit.getPackageDeclaration().map(p -> offset(mergedCode, p.getEnd().orElseThrow()) + 1).orElse(0);
        return withoutImports.substring(0, insertion) + "\n\n" + importBlock(normalImports, staticImports)
                + withoutImports.substring(insertion).stripLeading();
    }

    /**
     * 收集源码中的 import 行。
     * static import 单独分组，保持 JUnit 和 Mockito 静态导入可读。
     */
    private void collectImports(String code, Set<String> normalImports, Set<String> staticImports) {
        for (var imported : StaticJavaParser.parse(code).getImports()) {
            String line = "import " + (imported.isStatic() ? "static " : "") + imported.getNameAsString()
                    + (imported.isAsterisk() ? ".*" : "") + ";";
            if (imported.isStatic()) staticImports.add(line); else normalImports.add(line);
        }
    }

    /**
     * 构造排序后的 import 块。
     * 普通 import 与 static import 之间保留一个空行，符合常见 Java 风格。
     */
    private String importBlock(Set<String> normalImports, Set<String> staticImports) {
        List<String> normal = new ArrayList<>(normalImports);
        List<String> statics = new ArrayList<>(staticImports);
        Collections.sort(normal);
        Collections.sort(statics);
        StringBuilder builder = new StringBuilder();
        for (String importLine : normal) {
            builder.append(importLine).append("\n");
        }
        if (!normal.isEmpty() && !statics.isEmpty()) {
            builder.append("\n");
        }
        for (String importLine : statics) {
            builder.append(importLine).append("\n");
        }
        if (!normal.isEmpty() || !statics.isEmpty()) {
            builder.append("\n");
        }
        return builder.toString();
    }

    /**
     * 生成不与已有方法冲突的方法名。
     * 后缀从 _2 开始递增，符合需求中的重名处理规则。
     */
    private String uniqueName(String originalName, Set<String> existingNames) {
        if (!existingNames.contains(originalName)) {
            return originalName;
        }
        int index = 2;
        while (existingNames.contains(originalName + "_" + index)) {
            index++;
        }
        return originalName + "_" + index;
    }

    /**
     * 构造本次生成区域。
     * 区域注释让同一方法重复生成时可以精准替换旧内容。
     */
    private String buildRegion(String testedMethodName, List<String> newFields, String generatedMembers) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n    // region UnitMind generated tests for ").append(testedMethodName).append("\n\n");
        for (String field : newFields) {
            builder.append(indentFlatBlock(field)).append("\n\n");
        }
        if (generatedMembers != null && !generatedMembers.isBlank()) {
            builder.append(indentBlock(generatedMembers)).append("\n\n");
        }
        builder.append("    // endregion\n");
        return builder.toString();
    }

    /**
     * 删除同一被测方法的旧 UnitMind region。
     * 用户选择替换时使用该逻辑，避免同一方法多次生成造成测试类膨胀。
     */
    private String removeExistingRegion(String code, String testedMethodName) {
        var comments = StaticJavaParser.parse(code).getAllComments().stream().filter(c -> c.isLineComment())
                .sorted(java.util.Comparator.comparingInt(c -> c.getBegin().orElseThrow().line)).toList();
        String marker = "region UnitMind generated tests for " + testedMethodName;
        String[] lines = code.split("\n", -1); int[] starts = new int[lines.length];
        for (int i = 1; i < lines.length; i++) starts[i] = starts[i - 1] + lines[i - 1].length() + 1;
        var ranges = new ArrayList<int[]>();
        for (int i = 0; i < comments.size(); i++) {
            var begin = comments.get(i); if (!marker.equals(begin.getContent().strip())) continue;
            int depth = 1, end = i + 1;
            for (; end < comments.size(); end++) {
                String content = comments.get(end).getContent().strip();
                if (content.startsWith("region ")) depth++;
                if (content.equals("endregion") && --depth == 0) break;
            }
            if (end == comments.size()) throw new IllegalStateException("生成区域缺少结束标记，已阻止自动替换：" + testedMethodName);
            int first = begin.getBegin().orElseThrow().line - 1;
            int last = comments.get(end).getEnd().orElseThrow().line - 1;
            if (!lines[first].substring(0, begin.getBegin().orElseThrow().column - 1).isBlank()
                    || !lines[last].substring(0, comments.get(end).getBegin().orElseThrow().column - 1).isBlank()) {
                throw new IllegalStateException("生成区域标记与其他代码位于同一行，已阻止自动替换。");
            }
            ranges.add(new int[]{starts[first], last + 1 < starts.length ? starts[last + 1] : code.length()});
            i = end;
        }
        var result = new StringBuilder(code);
        for (int i = ranges.size() - 1; i >= 0; i--) result.delete(ranges.get(i)[0], ranges.get(i)[1]);
        return result.toString();
    }

    /**
     * 插入到测试类最后一个右花括号之前。
     * 追加内容必须位于类体内部，而不是简单拼到文件末尾。
     */
    private String insertIntoTestClass(String existingCode, String generatedCode, String region) {
        String name = testType(StaticJavaParser.parse(generatedCode), null).getNameAsString();
        var type = testType(StaticJavaParser.parse(existingCode), name);
        int end = offset(existingCode, type.getEnd().orElseThrow());
        return existingCode.substring(0, end).stripTrailing() + "\n" + region + existingCode.substring(end);
    }

    private int offset(String code, com.github.javaparser.Position position) {
        int offset = 0;
        for (int line = 1; line < position.line; line++) offset = code.indexOf('\n', offset) + 1;
        return offset + position.column - 1;
    }

    private ClassOrInterfaceDeclaration testType(CompilationUnit unit, String preferredName) {
        var types = unit.getTypes().stream().filter(t -> t.isClassOrInterfaceDeclaration())
                .map(t -> t.asClassOrInterfaceDeclaration()).toList();
        if (preferredName != null) return types.stream().filter(t -> t.getNameAsString().equals(preferredName))
                .findFirst().orElseThrow(() -> new IllegalStateException("追加测试的类名与已有测试类不一致。"));
        if (types.size() == 1) return types.get(0);
        var candidates = types.stream().filter(t -> t.isPublic() || t.findAll(MethodDeclaration.class).stream().anyMatch(this::testMethod)).toList();
        if (candidates.size() != 1) throw new IllegalStateException("无法唯一确定顶层测试类，已阻止自动合并。");
        return candidates.get(0);
    }

    /**
     * 统一换行符。
     * 合并逻辑按 \n 处理，避免 Windows 换行导致正则匹配失败。
     */
    private String normalizeNewlines(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 确保成员块使用四空格缩进。
     * 生成器通常已经带缩进，重复调用时保持幂等。
     */
    private String indentBlock(String block) {
        String normalized = stripCommonIndent(block);
        String[] lines = normalized.split("\\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line.isBlank()) {
                builder.append("\n");
            } else {
                builder.append("    ").append(line).append("\n");
            }
        }
        return builder.toString().stripTrailing();
    }

    /**
     * 对字段块做逐行缩进归一化。
     * 字段声明只有注解和一行 private 声明，逐行去左空白更稳定。
     */
    private String indentFlatBlock(String block) {
        String[] lines = block.split("\\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            builder.append("    ").append(line.stripLeading()).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    /**
     * 去除成员块公共缩进。
     * 已有测试类可能使用 4 或 8 空格缩进，统一后再插入可以避免缩进逐次累积。
     */
    private String stripCommonIndent(String block) {
        String[] lines = block.split("\\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
            return block.strip();
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line.length() >= minIndent) {
                builder.append(line.substring(minIndent));
            } else {
                builder.append(line.stripLeading());
            }
            builder.append("\n");
        }
        return builder.toString().strip();
    }
}
