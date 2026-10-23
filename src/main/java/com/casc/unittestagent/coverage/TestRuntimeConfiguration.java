package com.casc.unittestagent.coverage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 已求值的测试进程配置。参数只交给 ProcessBuilder，不经过 shell，也不写入诊断或模型上下文。 */
public final class TestRuntimeConfiguration {
    private final List<String> jvmArguments;
    private final Map<String, String> systemProperties;
    private final Map<String, String> environment;
    private final Set<String> excludedEnvironment;
    private final boolean inheritEnvironment;
    private final File workingDirectory;
    private final String javaExecutable;
    private final String origin;

    public TestRuntimeConfiguration(List<String> jvmArguments, Map<String, String> systemProperties,
                                    Map<String, String> environment, Set<String> excludedEnvironment,
                                    boolean inheritEnvironment, File workingDirectory,
                                    String javaExecutable, String origin) {
        this.jvmArguments = List.copyOf(jvmArguments);
        this.systemProperties = Map.copyOf(systemProperties);
        this.environment = Map.copyOf(environment);
        this.excludedEnvironment = Set.copyOf(excludedEnvironment);
        this.inheritEnvironment = inheritEnvironment;
        this.workingDirectory = workingDirectory;
        this.javaExecutable = javaExecutable == null ? "" : javaExecutable;
        this.origin = origin == null ? "" : origin;
        validate();
    }

    public static TestRuntimeConfiguration defaults(File directory) {
        return new TestRuntimeConfiguration(List.of(), Map.of(), Map.of(), Set.of(), true, directory != null && directory.isDirectory() ? directory : null, "", "IDE");
    }

    public TestRuntimeConfiguration withOrigin(String value) {
        return new TestRuntimeConfiguration(jvmArguments, systemProperties, environment, excludedEnvironment,
                inheritEnvironment, workingDirectory, javaExecutable, value);
    }

    public TestRuntimeConfiguration withJvmArguments(List<String> args) {
        return new TestRuntimeConfiguration(args, systemProperties, environment, excludedEnvironment,
                inheritEnvironment, workingDirectory, javaExecutable, origin);
    }

    static TestRuntimeConfiguration fromJson(JSONObject json, File baseDirectory, String origin) {
        List<String> args = strings(json.optJSONArray("jvmArgs"));
        File directory = new File(json.optString("workingDirectory", baseDirectory.getAbsolutePath()));
        if (!directory.isAbsolute()) directory = new File(baseDirectory, directory.getPath());
        String java = json.optString("javaExecutable", "");
        if (!java.isBlank() && !new File(java).isAbsolute()) java = new File(baseDirectory, java).getAbsolutePath();
        return new TestRuntimeConfiguration(args, map(json.optJSONObject("systemProperties")),
                map(json.optJSONObject("environment")), new LinkedHashSet<>(strings(json.optJSONArray("excludedEnvironment"))),
                json.optBoolean("inheritEnvironment", true), directory, java, origin);
    }

    private void validate() {
        if (workingDirectory != null && !workingDirectory.isDirectory()) {
            throw new IllegalArgumentException("测试 workingDirectory 不存在或不是目录。");
        }
        if (!javaExecutable.isBlank() && !new File(javaExecutable).isFile()) {
            throw new IllegalArgumentException("测试 javaExecutable 不存在。");
        }
        boolean optionValue = false;
        for (String arg : jvmArguments) {
            if (optionValue) {
                if (arg.isBlank() || arg.startsWith("-") || arg.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("测试 JVM 选项缺少有效参数。");
                }
                optionValue = false;
                continue;
            }
            // JVM 参数不能替换插件选定的入口、classpath 或注入一个参数文件绕过隔离选择。
            if (!arg.startsWith("-") || arg.startsWith("@") || arg.indexOf('\0') >= 0
                    || Set.of("-cp", "-classpath", "--class-path", "-jar", "-m", "--module").contains(arg)
                    || arg.startsWith("--class-path=") || arg.startsWith("--module=")) {
                throw new IllegalArgumentException("测试 JVM 参数包含进程入口或 classpath 选项，无法安全复用。");
            }
            optionValue = Set.of("--add-opens", "--add-exports", "--add-reads", "--add-modules",
                    "--limit-modules", "--module-path", "-p", "--upgrade-module-path", "--patch-module",
                    "--enable-native-access").contains(arg);
        }
        if (optionValue) throw new IllegalArgumentException("测试 JVM 选项缺少参数。");
        for (String name : systemProperties.keySet()) {
            if (name.isBlank() || name.contains("=") || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("测试系统属性名称无效。");
            }
        }
    }

    static List<String> strings(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }

    static Map<String, String> map(JSONObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        if (object != null) for (String name : object.keySet()) result.put(name, object.get(name).toString());
        return result;
    }

    public List<String> getJvmArguments() { return jvmArguments; }
    public Map<String, String> getSystemProperties() { return systemProperties; }
    public Map<String, String> getEnvironment() { return environment; }
    public Set<String> getExcludedEnvironment() { return excludedEnvironment; }
    public boolean isInheritEnvironment() { return inheritEnvironment; }
    public File getWorkingDirectory() { return workingDirectory; }
    public String getJavaExecutable() { return javaExecutable; }
    public String getOrigin() { return origin; }
}
