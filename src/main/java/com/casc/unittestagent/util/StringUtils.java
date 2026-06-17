package com.casc.unittestagent.util;

import java.util.Locale;

/**
 * 提供生成测试代码时需要的字符串处理工具。
 * 该类主要处理 Java 标识符格式，避免场景名和变量名生成时出现非法字符。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class StringUtils {
    /**
     * 禁止创建字符串工具类实例。
     * 所有方法都是无状态静态工具方法，私有构造可以避免额外对象创建和误用。
     */
    private StringUtils() {
    }

    /**
     * 将类名转换为首字母小写的实例名。
     * 生成测试代码时用它创建符合 Java 命名习惯的被测对象变量。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param text 待处理的文本内容
     * @return 首字母小写后的文本
     */
    public static String lowerCamel(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * 把条件表达式转换为可拼入 Java 方法名的片段。
     * 该方法移除运算符和非法字符，避免异常场景名生成后无法编译。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param text 待处理的文本内容
     * @return 适合拼入 Java 标识符的文本片段
     */
    public static String sanitizeJavaIdentifierPart(String text) {
        if (text == null || text.isBlank()) {
            return "Condition";
        }

        String value = text
                .replace(">=", " Greater Than Or Equal ")
                .replace("<=", " Less Than Or Equal ")
                .replace("!=", " Not Equal ")
                .replace("==", " Equal ")
                .replace(">", " Greater Than ")
                .replace("<", " Less Than ")
                .replace("&&", " And ")
                .replace("||", " Or ")
                .replace("(", " ")
                .replace(")", " ")
                .replace(".", " ")
                .replace("\"", " ")
                .replace("'", " ");

        StringBuilder result = new StringBuilder();
        for (String part : value.split("[^A-Za-z0-9]+")) {
            if (part.isBlank()) {
                continue;
            }
            String normalized = part.toLowerCase(Locale.ROOT);
            if ("null".equals(normalized)) {
                part = "Null";
            }
            result.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                result.append(part.substring(1));
            }
        }

        return result.length() == 0 ? "Condition" : result.toString();
    }
}
