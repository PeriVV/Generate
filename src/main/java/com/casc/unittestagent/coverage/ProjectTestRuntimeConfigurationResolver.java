package com.casc.unittestagent.coverage;

import com.intellij.util.execution.ParametersListUtil;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 只在真实执行前调用；构建工具求值在 IDE 读动作之外进行，不影响静态分析/编译校验。 */
public class ProjectTestRuntimeConfigurationResolver {
    public TestRuntimeConfiguration resolve(Path sourceFile, Path projectDirectory, String javaExecutable,
                                             File defaultDirectory, BooleanSupplier cancelled) throws Exception {
        return resolve(sourceFile, projectDirectory, javaExecutable, defaultDirectory, cancelled, false);
    }

    public TestRuntimeConfiguration resolve(Path sourceFile, Path projectDirectory, String javaExecutable,
                                             File defaultDirectory, BooleanSupplier cancelled,
                                             boolean bundledJunit) throws Exception {
        return resolve(sourceFile, projectDirectory, javaExecutable, defaultDirectory, cancelled, bundledJunit, null);
    }

    public TestRuntimeConfiguration resolve(Path sourceFile, Path projectDirectory, String javaExecutable,
                                             File defaultDirectory, BooleanSupplier cancelled,
                                             boolean bundledJunit, com.intellij.openapi.project.Project project) throws Exception {
        BuildConfigurationProcess.checkCancelled(cancelled);
        Path source = sourceFile.toAbsolutePath().normalize();
        Path boundary = projectDirectory == null ? null : projectDirectory.toAbsolutePath().normalize();
        if (boundary != null && !source.startsWith(boundary)) boundary = null;
        Path optionsFile = nearest(source.getParent(), boundary, ".unitmind/test-runtime.json");
        JSONObject options = optionsFile == null ? new JSONObject()
                : new JSONObject(Files.readString(optionsFile, StandardCharsets.UTF_8));
        Path optionsBase = optionsFile == null ? (boundary == null ? source.getParent() : boundary)
                : optionsFile.getParent().getParent();
        String mode = options.optString("executionMode", "auto");
        if (!List.of("auto", "project", "sandbox").contains(mode)) {
            throw new IOException("executionMode 必须为 auto、project 或 sandbox。");
        }
        boolean projectOptions = List.of("buildTool", "arguments", "executable", "buildJavaHome",
                "mavenExecutionId", "gradleTask").stream().anyMatch(options::has);
        if (options.has("runtime")) {
            return TestRuntimeConfiguration.fromJson(options.getJSONObject("runtime"), optionsBase.toFile(),
                    "本地 runtime 导出" + (bundledJunit ? "；插件内置 JUnit" : ""));
        }
        if (mode.equals("sandbox") && projectOptions) {
            throw new IOException("sandbox 模式不能同时指定构建工具导出选项；请使用 runtime 配置沙箱 JVM 环境。");
        }
        if (mode.equals("sandbox") || mode.equals("auto") && bundledJunit && !projectOptions) {
            return TestRuntimeConfiguration.defaults(defaultDirectory).withOrigin(
                    "内置离线沙箱（使用模块 JDK/业务依赖，未读取 Maven/Gradle 测试配置）");
        }
        Path pom = nearest(source.getParent(), boundary, "pom.xml");
        Path gradle = nearestGradle(source.getParent(), boundary);
        String tool = options.optString("buildTool", "");
        if (tool.isBlank()) {
            if (pom == null && gradle == null) return TestRuntimeConfiguration.defaults(defaultDirectory);
            if (pom != null && gradle != null && pom.getParent().equals(gradle)) {
                throw new IOException("当前项目同时包含 Maven 和 Gradle；请在 .unitmind/test-runtime.json 指定 buildTool。");
            }
            tool = pom != null && (gradle == null || pom.getParent().getNameCount() > gradle.getNameCount()) ? "maven" : "gradle";
        }
        if (!tool.equals("maven") && !tool.equals("gradle")) throw new IOException("buildTool 必须为 maven 或 gradle。");
        if (tool.equals("maven") && pom == null || tool.equals("gradle") && gradle == null) {
            throw new IOException("未找到所选构建工具的项目描述文件。");
        }
        List<String> arguments = TestRuntimeConfiguration.strings(options.optJSONArray("arguments"));
        validateArguments(arguments);
        String executable = options.optString("executable", "");
        if (!executable.isBlank() && !Path.of(executable).isAbsolute()) executable = optionsBase.resolve(executable).normalize().toString();
        // Wrapper 通常遵从父进程 JAVA_HOME；只有未设置时才用模块 SDK。允许显式选择构建工具 JDK。
        String inheritedJavaHome = System.getenv("JAVA_HOME");
        String buildJava = inheritedJavaHome == null || inheritedJavaHome.isBlank() ? javaExecutable : "";
        String buildJavaHome = options.optString("buildJavaHome", "");
        if (!buildJavaHome.isBlank()) {
            Path home = optionsBase.resolve(buildJavaHome).normalize();
            Path binary = home.resolve(isWindows() ? "bin/java.exe" : "bin/java");
            if (!Files.isRegularFile(binary)) throw new IOException("buildJavaHome 中未找到 Java 可执行文件。");
            buildJava = binary.toString();
        }
        Path scratch = Files.createTempDirectory("unitmind-test-runtime-");
        try {
            int timeout = Math.max(5, Math.min(300, options.optInt("timeoutSeconds", 60)));
            if (tool.equals("maven")) {
                Path module = pom.getParent();
                Path wrapper = nearest(module, boundary, isWindows() ? "mvnw.cmd" : "mvnw");
                Path root = wrapper == null ? module : wrapper.getParent();
                IdeMavenBuildConfiguration.Configuration ide = IdeMavenBuildConfiguration.resolve(project, module, executable.isBlank());
                com.intellij.openapi.diagnostic.Logger.getInstance(ProjectTestRuntimeConfigurationResolver.class).info(
                        "UnitMind runtime configuration: Maven launcher source=" + (!executable.isBlank() ? "explicit override"
                                : ide != null ? "IDEA project Maven settings" : "project Wrapper or PATH"));
                if (executable.isBlank() && ide != null) executable = ide.executable();
                List<String> command = BuildConfigurationProcess.executable(root, tool, executable);
                if (ide != null) command.addAll(mavenIdeArguments(ide.arguments(), arguments));
                command.addAll(arguments);
                command.addAll(List.of("-B", "-q", "-N", "-f", pom.toString()));
                String inheritedMavenArgs = System.getenv("MAVEN_ARGS");
                List<String> userArguments = new ArrayList<>(ParametersListUtil.parse(
                        inheritedMavenArgs == null ? "" : inheritedMavenArgs));
                Path config = nearest(module, boundary, ".mvn/maven.config");
                if (config != null) userArguments.addAll(ParametersListUtil.parse(Files.readString(config, StandardCharsets.UTF_8)));
                userArguments.addAll(arguments);
                Map<String, String> requested = MavenTestRuntimeConfiguration.userProperties(userArguments);
                if (requested.containsKey("output") || requested.containsKey("expression")) {
                    throw new IOException("Maven 用户属性 output/expression 与配置导出参数冲突，请使用本地 runtime 导出。");
                }
                List<String> effective = new ArrayList<>(command);
                effective.addAll(List.of("org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom",
                        "-Doutput=" + scratch.resolve("effective-pom.xml")));
                BuildConfigurationProcess.run(effective, module.toFile(), buildJava, timeout, cancelled);
                List<String> users = new ArrayList<>(command);
                users.addAll(List.of("org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate",
                        "-Dexpression=session.userProperties", "-Doutput=" + scratch.resolve("user-properties.xml")));
                BuildConfigurationProcess.run(users, module.toFile(), buildJava, timeout, cancelled);
                Map<String, String> userProperties = MavenTestRuntimeConfiguration.exportedUserProperties(scratch.resolve("user-properties.xml"));
                TestRuntimeConfiguration configuration = MavenTestRuntimeConfiguration.parse(scratch.resolve("effective-pom.xml"), module,
                        options.optString("mavenExecutionId", ""), userProperties);
                if (ide != null) configuration = configuration.withOrigin(configuration.getOrigin() + "；IDEA Maven 设置");
                return bundledJunit ? configuration.withOrigin(configuration.getOrigin() + "；插件内置 JUnit") : configuration;
            }
            Path settings = nearest(gradle, boundary, "settings.gradle");
            Path kotlinSettings = nearest(gradle, boundary, "settings.gradle.kts");
            if (settings == null || kotlinSettings != null && kotlinSettings.getNameCount() > settings.getNameCount()) {
                settings = kotlinSettings;
            }
            Path root = settings == null ? gradle : settings.getParent();
            Path input = scratch.resolve("input.json");
            Path output = scratch.resolve("runtime.json");
            String exportTask = "unitmindExportTestRuntime" + UUID.randomUUID().toString().replace("-", "");
            Files.writeString(input, new JSONObject().put("sourceFile", source.toString()).put("output", output.toString())
                    .put("exportTask", exportTask).put("gradleTask", options.optString("gradleTask", "")).toString(), StandardCharsets.UTF_8);
            Path script = scratch.resolve("export-test-runtime.gradle");
            try (InputStream resource = getClass().getResourceAsStream("/runtime/export-test-runtime.gradle")) {
                if (resource == null) throw new IOException("插件缺少 Gradle 测试配置导出脚本。");
                String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(script, template.replace("__UNITMIND_INPUT__", groovyLiteral(input.toString())), StandardCharsets.UTF_8);
            }
            List<String> command = BuildConfigurationProcess.executable(root, tool, executable);
            command.addAll(arguments);
            command.addAll(List.of("--console=plain", "--no-configuration-cache", "--init-script", script.toString(), ":" + exportTask));
            BuildConfigurationProcess.run(command, root.toFile(), buildJava, timeout, cancelled);
            JSONObject exported = new JSONObject(Files.readString(output, StandardCharsets.UTF_8));
            if (exported.has("error")) throw new IOException("Gradle 测试配置无法复用：" + exported.getString("error"));
            return TestRuntimeConfiguration.fromJson(exported, root.toFile(), "Gradle " + exported.optString("task")
                    + (bundledJunit ? "；插件内置 JUnit" : ""));
        } finally {
            CompiledTestArtifact.deleteRecursively(scratch);
        }
    }

    static List<String> mavenIdeArguments(List<String> ide, List<String> explicit) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < ide.size(); i++) {
            String value = ide.get(i);
            if (value.equals("--settings")) {
                String path = ide.get(++i);
                if (explicit.stream().noneMatch(arg -> arg.startsWith("-s") && !arg.startsWith("--")
                        || arg.equals("--settings") || arg.startsWith("--settings="))) result.addAll(List.of(value, path));
            } else if (value.startsWith("-Dmaven.repo.local=")) {
                if (explicit.stream().noneMatch(arg -> arg.startsWith("-Dmaven.repo.local=")
                        || arg.contains("maven.repo.local="))) result.add(value);
            } else if (value.equals("--offline")) {
                if (explicit.stream().noneMatch(arg -> arg.equals("-o") || arg.equals("--offline"))) result.add(value);
            }
        }
        return result;
    }

    private static void validateArguments(List<String> arguments) throws IOException {
        java.util.Set<String> withValue = java.util.Set.of("-D", "--define", "-P", "-s", "--settings", "-gs", "--global-settings",
                "-t", "--toolchains", "-gt", "--global-toolchains", "--activate-profiles", "-pl", "--projects",
                "-g", "--gradle-user-home", "-I", "--init-script", "-c", "--settings-file", "--include-build",
                "--console", "--warning-mode", "-x", "--exclude-task", "--max-workers", "--priority");
        boolean value = false;
        for (String argument : arguments) {
            if (value) { value = false; continue; }
            if (!argument.startsWith("-") || argument.equals("--")) {
                throw new IOException("arguments 只接受构建工具选项，不得包含 Maven 生命周期或 Gradle 任务。");
            }
            value = withValue.contains(argument);
        }
        if (value) throw new IOException("arguments 中的构建工具选项缺少参数。");
    }

    private static String groovyLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static Path nearestGradle(Path start, Path boundary) {
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            for (String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
                if (Files.isRegularFile(directory.resolve(name))) return directory;
            }
            if (directory.equals(boundary)) break;
        }
        return null;
    }

    private static Path nearest(Path start, Path boundary, String relative) {
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
            if (directory.equals(boundary)) break;
        }
        return null;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
}
