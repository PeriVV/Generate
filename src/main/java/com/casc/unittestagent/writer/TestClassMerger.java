package com.casc.unittestagent.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合并已有测试类和本次生成的测试代码。
 * 该类避免追加时重复 package、import、Mock 字段和测试方法，保证多次为不同方法生成测试不会覆盖旧内容。
 */
public class TestClassMerger {
    /** 匹配 Java import 行，普通 import 和 static import 都会被保留。 */
    private static final Pattern IMPORT_PATTERN = Pattern.compile("(?m)^import\\s+(?:static\\s+)?[^;]+;\\s*$");
    /** 匹配 Mockito 字段块，覆盖 @Mock 和 @InjectMocks 两类字段。 */
    private static final Pattern MOCK_FIELD_PATTERN = Pattern.compile("(?s)\\n?\\s+@(Mock|InjectMocks)\\s*\\n\\s+private\\s+([\\w.$<>?,\\s]+)\\s+(\\w+)\\s*;\\s*\\n?");
    /** 匹配测试方法名，自动重名时只处理 @Test 方法。 */
    private static final Pattern TEST_METHOD_PATTERN = Pattern.compile("(?m)(@Test\\s*\\n\\s*(?:[\\w<>?,\\s]+\\s+)?void\\s+)(\\w+)(\\s*\\()");

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
        if (existingTestCode == null || existingTestCode.isBlank()) {
            return generatedTestCode;
        }
        MergeOptions effectiveOptions = options == null ? new MergeOptions() : options;
        String existing = normalizeNewlines(existingTestCode);
        String generated = normalizeNewlines(generatedTestCode);

        if (effectiveOptions.isReplaceExistingRegionForSameMethod()) {
            existing = removeExistingRegion(existing, testedMethodName);
        }

        List<String> generatedFieldBlocks = extractMockFieldBlocks(generated);
        String generatedMembers = extractGeneratedMembers(generated, generatedFieldBlocks);
        generatedMembers = renameConflictingTestMethods(existing, generatedMembers, effectiveOptions);

        List<String> newFields = effectiveOptions.isMergeMockFields()
                ? filterNewMockFields(existing, generatedFieldBlocks)
                : generatedFieldBlocks;
        String region = buildRegion(testedMethodName, newFields, generatedMembers);
        String merged = insertBeforeLastBrace(existing, region);
        return effectiveOptions.isMergeImports() ? mergeImports(merged, existing, generated) : merged;
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
        return existingTestCode.contains("// region UnitMind generated tests for " + testedMethodName);
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

        String withoutImports = IMPORT_PATTERN.matcher(mergedCode).replaceAll("").replaceAll("(?m)\\n{3,}", "\n\n");
        String importBlock = importBlock(normalImports, staticImports);
        int packageEnd = withoutImports.indexOf(";\n");
        if (withoutImports.startsWith("package ") && packageEnd >= 0) {
            return withoutImports.substring(0, packageEnd + 2) + "\n" + importBlock + withoutImports.substring(packageEnd + 2).stripLeading();
        }
        return importBlock + withoutImports.stripLeading();
    }

    /**
     * 收集源码中的 import 行。
     * static import 单独分组，保持 JUnit 和 Mockito 静态导入可读。
     */
    private void collectImports(String code, Set<String> normalImports, Set<String> staticImports) {
        Matcher matcher = IMPORT_PATTERN.matcher(code);
        while (matcher.find()) {
            String line = matcher.group().trim();
            if (line.startsWith("import static ")) {
                staticImports.add(line);
            } else {
                normalImports.add(line);
            }
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
     * 提取新生成测试类中的 Mock 字段块。
     * 字段块保留注解和声明，后续可以整体插入到 UnitMind region 内。
     */
    private List<String> extractMockFieldBlocks(String code) {
        List<String> fields = new ArrayList<>();
        String inner = classInner(code);
        Matcher matcher = MOCK_FIELD_PATTERN.matcher(inner);
        while (matcher.find()) {
            fields.add(matcher.group().stripTrailing());
        }
        return fields;
    }

    /**
     * 提取除 Mock 字段外的新生成成员。
     * 包含 @Test 方法和必要 helper 方法，用于插入本次 UnitMind region。
     */
    private String extractGeneratedMembers(String generatedCode, List<String> generatedFieldBlocks) {
        String inner = classInner(generatedCode);
        for (String fieldBlock : generatedFieldBlocks) {
            inner = inner.replace(fieldBlock, "");
        }
        return inner.stripTrailing();
    }

    /**
     * 过滤已有测试类中已经存在的 Mock/InjectMocks 字段。
     * 重复依据为“字段类型 + 字段名”，避免重复声明导致编译失败。
     */
    private List<String> filterNewMockFields(String existingCode, List<String> generatedFieldBlocks) {
        List<String> result = new ArrayList<>();
        Set<String> existingSignatures = fieldSignatures(existingCode);
        for (String fieldBlock : generatedFieldBlocks) {
            String signature = fieldSignature(fieldBlock);
            if (signature != null && !existingSignatures.contains(signature)) {
                result.add(fieldBlock);
            }
        }
        return result;
    }

    /**
     * 提取已有测试类字段签名。
     * 签名使用“类型 + 字段名”，与需求中的去重规则保持一致。
     */
    private Set<String> fieldSignatures(String code) {
        Set<String> signatures = new LinkedHashSet<>();
        Matcher matcher = MOCK_FIELD_PATTERN.matcher(classInner(code));
        while (matcher.find()) {
            signatures.add(matcher.group(2).trim().replaceAll("\\s+", " ") + " " + matcher.group(3));
        }
        return signatures;
    }

    /**
     * 提取字段块签名。
     * 无法解析时返回 null，调用方会保守地保留该字段块。
     */
    private String fieldSignature(String fieldBlock) {
        Matcher matcher = MOCK_FIELD_PATTERN.matcher("\n" + fieldBlock + "\n");
        if (matcher.find()) {
            return matcher.group(2).trim().replaceAll("\\s+", " ") + " " + matcher.group(3);
        }
        return null;
    }

    /**
     * 对重复测试方法名追加递增后缀。
     * 只处理 @Test 方法，helper 方法优先通过替换旧 region 避免重复。
     */
    private String renameConflictingTestMethods(String existingCode, String generatedMembers, MergeOptions options) {
        if (!options.isRenameDuplicatedTestMethods()) {
            return generatedMembers;
        }
        Set<String> existingNames = methodNames(existingCode);
        Matcher matcher = TEST_METHOD_PATTERN.matcher(generatedMembers);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String originalName = matcher.group(2);
            String uniqueName = uniqueName(originalName, existingNames);
            existingNames.add(uniqueName);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + uniqueName + matcher.group(3)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 提取源码中的方法名。
     * 用于判断测试方法是否与已有类成员重名。
     */
    private Set<String> methodNames(String code) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)\\b(?:void|[A-Z]\\w*(?:<[^>]+>)?)\\s+(\\w+)\\s*\\(").matcher(code);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
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
        String regionStart = Pattern.quote("    // region UnitMind generated tests for " + testedMethodName);
        return code.replaceAll("(?s)\\n?" + regionStart + ".*?\\n\\s{4}// endregion\\n?", "\n");
    }

    /**
     * 插入到测试类最后一个右花括号之前。
     * 追加内容必须位于类体内部，而不是简单拼到文件末尾。
     */
    private String insertBeforeLastBrace(String existingCode, String region) {
        int index = existingCode.lastIndexOf('}');
        if (index < 0) {
            return existingCode + "\n" + region;
        }
        return existingCode.substring(0, index).stripTrailing() + "\n" + region + existingCode.substring(index);
    }

    /**
     * 提取测试类类体内部文本。
     * 生成器输出单个测试类，因此取第一个左花括号和最后一个右花括号之间的内容即可。
     */
    private String classInner(String code) {
        int start = code.indexOf('{');
        int end = code.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return code.substring(start + 1, end);
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
