package com.casc.unittestagent.coverage;

import com.intellij.util.execution.ParametersListUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取 Maven 自身计算的 effective POM，不自行模拟父 POM、Profile 和 pluginManagement 继承。 */
final class MavenTestRuntimeConfiguration {
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private MavenTestRuntimeConfiguration() { }

    static TestRuntimeConfiguration parse(Path effectivePom, Path module, String executionId,
                                          Map<String, String> userProperties) throws Exception {
        Document document = readXml(effectivePom);
        Element project = document.getDocumentElement();
        if (!project.getTagName().equals("project")) {
            throw new IOException("Maven 导出包含多个项目，无法唯一确定当前模块的测试配置。");
        }
        Map<String, String> properties = new LinkedHashMap<>(entries(child(project, "properties")));
        if (properties.containsKey("output") || properties.containsKey("expression")) {
            throw new IOException("Maven 项目属性 output/expression 与配置导出参数冲突，请使用本地 runtime 导出。");
        }
        flatten(project, "project", properties);
        properties.put("basedir", module.toString());
        properties.put("project.basedir", module.toString());
        properties.put("pom.basedir", module.toString());
        properties.put("surefire.forkNumber", "1");
        System.getenv().forEach((key, value) -> properties.put("env." + key, value));
        properties.putAll(userProperties);
        Element plugin = null;
        boolean lifecycleToolchain = children(child(child(project, "build"), "plugins")).stream()
                .anyMatch(item -> text(item, "artifactId").equals("maven-toolchains-plugin")
                        && !children(child(item, "executions")).isEmpty());
        for (Element item : children(child(child(project, "build"), "plugins"))) {
            if (text(item, "artifactId").equals("maven-surefire-plugin")
                    && (text(item, "groupId").isBlank() || text(item, "groupId").equals("org.apache.maven.plugins"))) {
                plugin = item;
                break;
            }
        }
        // Surefire 有默认生命周期绑定；仅在 pluginManagement 中定义时也需要其配置。
        if (plugin == null) {
            for (Element item : children(child(child(child(project, "build"), "pluginManagement"), "plugins"))) {
                if (text(item, "artifactId").equals("maven-surefire-plugin")) { plugin = item; break; }
            }
        }
        List<Element> executions = new ArrayList<>();
        if (plugin != null) for (Element execution : children(child(plugin, "executions"))) {
            boolean testGoal = children(child(execution, "goals")).stream().anyMatch(goal -> goal.getTextContent().trim().equals("test"));
            if (!text(execution, "phase").equals("none") && (testGoal || text(execution, "id").equals("default-test"))) {
                executions.add(execution);
            }
        }
        Element execution = null;
        if (!executionId.isBlank()) {
            execution = executions.stream().filter(item -> text(item, "id").equals(executionId)).findFirst()
                    .orElseThrow(() -> new IOException("找不到指定的 Maven Surefire executionId。"));
        } else if (executions.size() > 1) {
            throw new IOException("Maven 存在多个 Surefire test execution；请在 .unitmind/test-runtime.json 指定 mavenExecutionId。");
        } else if (executions.size() == 1) execution = executions.get(0);
        Element config = merge(child(plugin, "configuration"), child(execution, "configuration"));
        if (config != null && config.getTextContent().contains(effectivePom.getParent().toString())) {
            throw new IOException("Maven 配置引用了导出任务的临时参数，请使用本地 runtime 导出。");
        }
        if (userProperties.getOrDefault("forkCount", text(config, "forkCount")).equals("0") || text(config, "forkMode").equals("never")) {
            throw new IOException("Surefire 配置为 Maven 进程内执行，无法直接复用隔离 JVM 配置；请提供本地 runtime 导出。");
        }
        List<String> args = new ArrayList<>();
        String argLine = userProperties.getOrDefault("argLine", text(config, "argLine"));
        if (!argLine.isBlank()) args.addAll(ParametersListUtil.parse(expand(argLine, properties, "argLine")));
        if (!userProperties.getOrDefault("enableAssertions", text(config, "enableAssertions")).equalsIgnoreCase("false")) args.add("-ea");
        Map<String, String> system = new LinkedHashMap<>();
        system.put("basedir", module.toString());
        for (Element property : children(child(config, "systemProperties"))) {
            system.put(text(property, "name"), expand(text(property, "value"), properties, "systemProperties"));
        }
        String propertyFile = userProperties.getOrDefault("surefire.systemPropertiesFile", text(config, "systemPropertiesFile"));
        if (!propertyFile.isBlank()) {
            Path file = module.resolve(expand(propertyFile, properties, "systemPropertiesFile")).normalize();
            Properties external = new Properties();
            try (InputStream input = Files.newInputStream(file)) { external.load(input); }
            for (String key : external.stringPropertyNames()) system.put(key, external.getProperty(key));
        }
        expandMap(entries(child(config, "systemPropertyVariables")), properties, "systemPropertyVariables").forEach(system::put);
        if (!text(config, "promoteUserPropertiesToSystemProperties").equalsIgnoreCase("false")) system.putAll(userProperties);
        Map<String, String> environment = expandMap(entries(child(config, "environmentVariables")), properties, "environmentVariables");
        Set<String> excluded = new LinkedHashSet<>();
        Element exclusions = child(config, "excludedEnvironmentVariables");
        if (exclusions != null) {
            if (children(exclusions).isEmpty()) addExclusions(excluded, exclusions.getTextContent());
            else for (Element item : children(exclusions)) addExclusions(excluded, item.getTextContent());
        }
        if (userProperties.containsKey("surefire.excludedEnvironmentVariables")) {
            excluded.clear();
            addExclusions(excluded, userProperties.get("surefire.excludedEnvironmentVariables"));
        }
        String cwd = expand(text(config, "workingDirectory"), properties, "workingDirectory");
        File directory = cwd.isBlank() ? module.toFile() : module.resolve(cwd).normalize().toFile();
        String jvm = expand(userProperties.getOrDefault("jvm", text(config, "jvm")), properties, "jvm");
        if (!jvm.isBlank()) jvm = module.resolve(jvm).normalize().toString();
        if ((child(config, "jdkToolchain") != null || lifecycleToolchain) && jvm.isBlank()) {
            throw new IOException("Surefire 使用 jdkToolchain；请通过本地 runtime 配置指定已选定的 javaExecutable。");
        }
        return new TestRuntimeConfiguration(args, system, environment, excluded, true,
                directory, jvm, "Maven Surefire" + (execution == null ? "" : ":" + text(execution, "id")));
    }

    static Map<String, String> exportedUserProperties(Path file) throws Exception {
        Element root = readXml(file).getDocumentElement();
        if (!root.getTagName().equals("properties")) throw new IOException("Maven 用户属性导出格式无效。");
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element item : children(root)) {
            String name = decodePropertyName(item.getTagName());
            if (!Set.of("output", "expression").contains(name)) properties.put(name, item.getTextContent());
        }
        return properties;
    }

    private static String decodePropertyName(String name) throws IOException {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (current != '_' || i + 1 == name.length()) { value.append(current); continue; }
            char next = name.charAt(++i);
            if (next == '_') value.append('_');
            else if (next == '-') value.append('$');
            else if (next == 'x') {
                int end = name.indexOf('_', i + 1);
                if (end < 0) throw new IOException("Maven 用户属性名称编码无效。");
                try { value.appendCodePoint(Integer.parseInt(name.substring(i + 1, end), 16)); }
                catch (IllegalArgumentException ex) { throw new IOException("Maven 用户属性名称编码无效。"); }
                i = end;
            } else throw new IOException("Maven 用户属性名称编码无效。");
        }
        return value.toString();
    }

    private static Document readXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException ex) throws org.xml.sax.SAXException { throw ex; }
            @Override public void fatalError(org.xml.sax.SAXParseException ex) throws org.xml.sax.SAXException { throw ex; }
        });
        return builder.parse(file.toFile());
    }

    static Map<String, String> userProperties(List<String> arguments) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int index = 0; index < arguments.size(); index++) {
            String arg = arguments.get(index);
            if (arg.startsWith("--define=")) arg = "-D" + arg.substring("--define=".length());
            if (arg.equals("--define")) arg = "-D";
            if (arg.equals("-D") && index + 1 < arguments.size()) arg += arguments.get(++index);
            if (!arg.startsWith("-D")) continue;
            String value = arg.substring(2);
            int equals = value.indexOf('=');
            if (!value.isBlank()) properties.put(equals < 0 ? value : value.substring(0, equals),
                    equals < 0 ? "true" : value.substring(equals + 1));
        }
        return properties;
    }

    private static void addExclusions(Set<String> result, String text) {
        for (String item : text.trim().split("[,\\s]+")) if (!item.isBlank()) result.add(item);
    }

    private static Map<String, String> expandMap(Map<String, String> values, Map<String, String> properties, String field) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> value : values.entrySet()) result.put(value.getKey(), expand(value.getValue(), properties, field));
        return result;
    }

    private static String expand(String value, Map<String, String> properties, String field) throws IOException {
        if (field.equals("argLine") && value.contains("@{")) {
            throw new IOException("Maven argLine 含未求值的生命周期晚绑定表达式；请提供本地 runtime 导出。");
        }
        for (int round = 0; round < 12; round++) {
            Matcher matcher = EXPRESSION.matcher(value);
            if (!matcher.find()) return value;
            StringBuffer replaced = new StringBuffer();
            do {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) throw new IOException("Maven 测试配置 " + field
                        + " 含未求值的属性或生命周期晚绑定表达式；请使用本地 runtime 配置提供已求值结果。");
                matcher.appendReplacement(replaced, Matcher.quoteReplacement(replacement));
            } while (matcher.find());
            matcher.appendTail(replaced);
            value = replaced.toString();
        }
        throw new IOException("Maven 测试配置 " + field + " 存在循环属性引用。");
    }

    private static void flatten(Element element, String prefix, Map<String, String> target) {
        if (element == null) return;
        for (Element child : children(element)) {
            if (children(child).isEmpty()) target.put(prefix + "." + child.getTagName(), child.getTextContent().trim());
            else if (Set.of("build", "parent").contains(child.getTagName())) flatten(child, prefix + "." + child.getTagName(), target);
        }
    }

    private static Map<String, String> entries(Element element) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element child : children(element)) result.put(child.getTagName(), child.getTextContent().trim());
        return result;
    }

    private static String text(Element element, String name) {
        Element result = child(element, name);
        return result == null ? "" : result.getTextContent().trim();
    }

    private static Element child(Element element, String name) {
        for (Element child : children(element)) if (child.getTagName().equals(name)) return child;
        return null;
    }

    private static List<Element> children(Element element) {
        List<Element> result = new ArrayList<>();
        if (element != null) for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) result.add((Element) node);
        }
        return result;
    }

    private static Element merge(Element base, Element overriding) {
        if (base == null) return overriding;
        if (overriding == null) return base;
        if (overriding.getAttribute("combine.self").equals("override")) return overriding;
        Element merged = (Element) base.cloneNode(true);
        for (Element item : children(overriding)) {
            Element old = child(merged, item.getTagName());
            Node replacement = item.getOwnerDocument().importNode(
                    old == null || children(item).isEmpty() ? item : merge(old, item), true);
            if (old == null || overriding.getAttribute("combine.children").equals("append")) merged.appendChild(replacement);
            else merged.replaceChild(replacement, old);
        }
        return merged;
    }
}
