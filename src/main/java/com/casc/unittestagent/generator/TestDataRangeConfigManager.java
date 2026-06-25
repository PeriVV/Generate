package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.TestDataRangeConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 维护测试数据范围的进程内配置。
 * 该类通过复制对象隔离 UI 编辑态和运行态，避免对话框中的临时修改提前影响生成流程。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestDataRangeConfigManager {
    /** 当前进程内生效的测试数据范围配置列表。 */
    private static List<TestDataRangeConfig> configs = defaultConfigs();

    /**
     * 禁止创建配置管理器实例。
     * 配置以静态进程内状态维护，私有构造用于表达该类只提供集中管理入口。
     */
    private TestDataRangeConfigManager() {
    }

    /**
     * 读取当前测试数据范围配置。
     * 返回副本而不是内部列表，避免调用方绕过管理器直接修改运行态配置。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 当前配置列表的副本
     */
    public static synchronized List<TestDataRangeConfig> getConfigs() {
        List<TestDataRangeConfig> copies = new ArrayList<>();
        for (TestDataRangeConfig config : configs) {
            copies.add(config.copy());
        }
        return copies;
    }

    /**
     * 用用户确认后的配置替换当前运行态配置。
     * 方法会复制入参，避免对话框后续对象变化影响已提交配置。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param newConfigs 用户确认后的新配置列表
     */
    public static synchronized void updateConfigs(List<TestDataRangeConfig> newConfigs) {
        List<TestDataRangeConfig> copies = new ArrayList<>();
        for (TestDataRangeConfig config : newConfigs) {
            copies.add(config.copy());
        }
        configs = copies;
    }

    /**
     * 恢复内置默认测试数据范围。
     * 用于配置对话框的重置操作，保证用户可以回到可用的基础生成规则。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     */
    public static synchronized void resetToDefault() {
        configs = defaultConfigs();
    }

    /**
     * 按 Java 类型查找测试数据范围配置。
     * 查找时会去除包名和泛型信息，使常见类型写法都能匹配同一配置。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param javaType 需要生成测试值的 Java 类型名称
     * @return 匹配到的类型配置副本
     */
    public static synchronized Optional<TestDataRangeConfig> findByType(String javaType) {
        String normalized = normalize(javaType);
        for (TestDataRangeConfig config : configs) {
            if (matchesType(normalize(config.getTypeName()), normalized)) {
                return Optional.of(config.copy());
            }
        }
        return Optional.empty();
    }

    /**
     * 构造内置默认配置列表。
     * 默认值覆盖常用基础类型，确保用户未配置时也能生成可用测试数据。
     */
    private static List<TestDataRangeConfig> defaultConfigs() {
        List<TestDataRangeConfig> defaults = new ArrayList<>();
        defaults.add(new TestDataRangeConfig("byte / Byte", "-128", "127", null, null));
        defaults.add(new TestDataRangeConfig("short / Short", "-32768", "32767", null, null));
        defaults.add(new TestDataRangeConfig("int / Integer", "-100", "100", null, null));
        defaults.add(new TestDataRangeConfig("long / Long", "-1000", "1000", null, null));
        defaults.add(new TestDataRangeConfig("float / Float", "-100.0", "100.0", 2, null));
        defaults.add(new TestDataRangeConfig("double / Double", "-100.0", "100.0", 2, null));
        defaults.add(new TestDataRangeConfig("boolean / Boolean", null, null, null, null, "true,false"));
        defaults.add(new TestDataRangeConfig("char / Character", null, null, null, null, "a,Z,0"));
        defaults.add(new TestDataRangeConfig("String", null, null, null, "[A-Za-z0-9_]{6,12}"));
        return defaults;
    }

    /**
     * 标准化类型名用于配置查找。
     * 去除泛型和包名前缀，避免 java.lang.Integer 与 Integer 匹配不到同一配置。
     */
    private static String normalize(String type) {
        if (type == null) {
            return "";
        }
        int genericStart = type.indexOf('<');
        String normalized = genericStart >= 0 ? type.substring(0, genericStart) : type;
        int lastDot = normalized.lastIndexOf('.');
        normalized = lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized.trim();
        if (normalized.contains("/")) {
            String[] parts = normalized.split("/");
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                if (builder.length() > 0) {
                    builder.append("/");
                }
                builder.append(part.trim());
            }
            return builder.toString();
        }
        return normalized;
    }

    /**
     * 判断配置类型是否覆盖当前 Java 类型。
     * 合并展示的类型会以斜杠分隔，查找时任意一侧命中都视为可用配置。
     */
    private static boolean matchesType(String configType, String javaType) {
        if (configType.equals(javaType)) {
            return true;
        }
        if (!configType.contains("/")) {
            return false;
        }
        for (String part : configType.split("/")) {
            if (part.trim().equals(javaType)) {
                return true;
            }
        }
        return false;
    }
}
