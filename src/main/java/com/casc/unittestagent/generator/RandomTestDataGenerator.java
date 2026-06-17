package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.TestDataRangeConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 根据 Java 类型和用户配置生成可写入测试源码的字面量表达式。
 * 该类把随机取值、边界值和非法值的生成规则集中在一起，避免生成器到处散落类型判断。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class RandomTestDataGenerator {
    /** 字符串测试数据允许使用的字符集，避免生成需要转义的复杂字符。 */
    private static final String ASCII = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_";

    /**
     * 为指定 Java 类型生成普通测试值表达式。
     * 返回值是可直接写入 Java 测试源码的字面量或构造表达式。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param javaType 需要生成测试值的 Java 类型名称
     * @return 可直接写入 Java 测试源码的普通值表达式
     */
    public String generateValueExpression(String javaType) {
        String type = simpleType(javaType);
        switch (type) {
            case "byte":
            case "Byte":
                return String.valueOf(randomLong(type));
            case "short":
            case "Short":
                return String.valueOf(randomLong(type));
            case "int":
            case "Integer":
                return String.valueOf(randomLong(type));
            case "long":
            case "Long":
                return randomLong(type) + "L";
            case "float":
            case "Float":
                return randomDecimal(type) + "f";
            case "double":
            case "Double":
                return randomDecimal(type);
            case "boolean":
            case "Boolean":
                return String.valueOf(ThreadLocalRandom.current().nextBoolean());
            case "String":
                return "\"" + randomAsciiString() + "\"";
            default:
                if ("List".equals(type) || "Collection".equals(type)) {
                    return "List.of(\"test_" + ThreadLocalRandom.current().nextInt(1000, 9999) + "\")";
                }
                return canUseConstructor(type) ? "new " + type + "()" : "null";
        }
    }

    /**
     * 生成指定类型的最小边界值表达式。
     * 优先使用用户配置，缺失配置时退回普通值以保证生成流程不中断。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param javaType 需要生成测试值的 Java 类型名称
     * @return 可直接写入 Java 测试源码的最小边界值表达式
     */
    public String minValueExpression(String javaType) {
        Optional<TestDataRangeConfig> config = TestDataRangeConfigManager.findByType(javaType);
        if (config.isEmpty() || config.get().getMinValue() == null) {
            return generateValueExpression(javaType);
        }
        return literalFor(javaType, config.get().getMinValue());
    }

    /**
     * 生成指定类型的最大边界值表达式。
     * 优先使用用户配置，缺失配置时退回普通值以保证生成流程不中断。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param javaType 需要生成测试值的 Java 类型名称
     * @return 可直接写入 Java 测试源码的最大边界值表达式
     */
    public String maxValueExpression(String javaType) {
        Optional<TestDataRangeConfig> config = TestDataRangeConfigManager.findByType(javaType);
        if (config.isEmpty() || config.get().getMaxValue() == null) {
            return generateValueExpression(javaType);
        }
        return literalFor(javaType, config.get().getMaxValue());
    }

    /**
     * 生成低于最小边界的非法值表达式。
     * 该值用于非空基本类型的异常输入场景，以覆盖边界保护逻辑。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param javaType 需要生成测试值的 Java 类型名称
     * @return 可直接写入 Java 测试源码的低于最小值表达式
     */
    public String belowMinValueExpression(String javaType) {
        Optional<TestDataRangeConfig> config = TestDataRangeConfigManager.findByType(javaType);
        if (config.isEmpty() || config.get().getMinValue() == null) {
            return generateValueExpression(javaType);
        }
        String type = simpleType(javaType);
        if (isFloating(type)) {
            double value = Double.parseDouble(config.get().getMinValue()) - 1.0d;
            return literalFor(javaType, String.valueOf(value));
        }
        long value = Long.parseLong(config.get().getMinValue()) - 1L;
        return literalFor(javaType, String.valueOf(value));
    }

    /**
     * 生成参数关系比较中的低值。
     * 该方法封装关系用例的低边界选择，便于后续替换更复杂的策略。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param javaType 需要生成测试值的 Java 类型名称
     * @return 参数关系场景中代表低值一侧的表达式
     */
    public String relationLowValue(String javaType) {
        return minValueExpression(javaType);
    }

    /**
     * 生成参数关系比较中的高值。
     * 该方法封装关系用例的高边界选择，便于后续替换更复杂的策略。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param javaType 需要生成测试值的 Java 类型名称
     * @return 参数关系场景中代表高值一侧的表达式
     */
    public String relationHighValue(String javaType) {
        return maxValueExpression(javaType);
    }

    /**
     * 在配置范围内生成整数值。
     * 最大值不是 Long.MAX_VALUE 时使用闭区间，符合用户对边界配置的直觉。
     */
    private long randomLong(String javaType) {
        TestDataRangeConfig config = TestDataRangeConfigManager.findByType(javaType)
                .orElse(new TestDataRangeConfig(javaType, "0", "100", null, null));
        long min = Long.parseLong(config.getMinValue());
        long max = Long.parseLong(config.getMaxValue());
        if (max == Long.MAX_VALUE) {
            return ThreadLocalRandom.current().nextLong(min, max);
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1L);
    }

    /**
     * 在配置范围内生成小数值。
     * 生成后按配置小数位四舍五入，保证写入测试源码的字面量稳定可读。
     */
    private String randomDecimal(String javaType) {
        TestDataRangeConfig config = TestDataRangeConfigManager.findByType(javaType)
                .orElse(new TestDataRangeConfig(javaType, "-100.0", "100.0", 2, null));
        double min = Double.parseDouble(config.getMinValue());
        double max = Double.parseDouble(config.getMaxValue());
        double value = ThreadLocalRandom.current().nextDouble(min, max);
        int scale = config.getDecimalScale() == null ? 2 : config.getDecimalScale();
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 把原始配置值转换为 Java 字面量。
     * long 和 float 需要追加后缀，double 需要按配置小数位格式化。
     */
    private String literalFor(String javaType, String rawValue) {
        String type = simpleType(javaType);
        if ("long".equals(type) || "Long".equals(type)) {
            return rawValue + "L";
        }
        if ("float".equals(type) || "Float".equals(type)) {
            return BigDecimal.valueOf(Double.parseDouble(rawValue)).setScale(decimalScale(javaType), RoundingMode.HALF_UP).toPlainString() + "f";
        }
        if ("double".equals(type) || "Double".equals(type)) {
            return BigDecimal.valueOf(Double.parseDouble(rawValue)).setScale(decimalScale(javaType), RoundingMode.HALF_UP).toPlainString();
        }
        return rawValue;
    }

    /**
     * 读取浮点类型的小数位配置。
     * 缺失配置时使用 2 位小数，保证默认生成值不会过长。
     */
    private int decimalScale(String javaType) {
        return TestDataRangeConfigManager.findByType(javaType)
                .map(TestDataRangeConfig::getDecimalScale)
                .orElse(2);
    }

    /**
     * 生成简单 ASCII 字符串。
     * 限制字符集可以避免生成测试源码时出现额外转义问题。
     */
    private String randomAsciiString() {
        int length = ThreadLocalRandom.current().nextInt(6, 13);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            builder.append(ASCII.charAt(ThreadLocalRandom.current().nextInt(ASCII.length())));
        }
        return builder.toString();
    }

    /**
     * 提取 Java 类型的简单名称。
     * 去除包名和泛型后再判断类型，提升配置匹配和生成规则复用率。
     */
    private String simpleType(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return "Object";
        }
        String type = javaType.trim();
        int genericStart = type.indexOf('<');
        if (genericStart >= 0) {
            type = type.substring(0, genericStart);
        }
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    /**
     * 判断类型是否为浮点类型。
     * 浮点类型需要独立处理小数位和字面量后缀。
     */
    private boolean isFloating(String type) {
        return "float".equals(type) || "Float".equals(type) || "double".equals(type) || "Double".equals(type);
    }

    /**
     * 判断未知类型是否可以尝试无参构造。
     * 以大写开头的类型更可能是类名，生成 new 表达式比直接 null 更利于普通路径测试。
     */
    private boolean canUseConstructor(String type) {
        return type != null
                && !type.isBlank()
                && Character.isUpperCase(type.charAt(0))
                && !looksLikeExternalInterface(type);
    }

    /**
     * 判断类型是否像外部接口或框架协作对象。
     * 这类类型通常应由 Mockito 提供 Mock，不能假设存在可调用的无参构造方法。
     */
    private boolean looksLikeExternalInterface(String type) {
        return "HttpServletRequest".equals(type)
                || "HttpServletResponse".equals(type)
                || type.endsWith("FilterChain")
                || type.endsWith("RestTemplate")
                || type.endsWith("WebClient")
                || type.endsWith("RedisTemplate")
                || type.endsWith("JdbcTemplate")
                || "ObjectMapper".equals(type)
                || "RuntimeDataSourceRegistry".equals(type)
                || type.endsWith("Mapper")
                || type.endsWith("Repository")
                || type.endsWith("Dao")
                || type.endsWith("Client")
                || type.endsWith("Service");
    }
}
