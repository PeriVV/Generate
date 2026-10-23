package com.casc.unittestagent.coverage;

import com.intellij.openapi.progress.ProcessCanceledException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 有界执行构建工具的配置导出；输出丢弃，防止项目配置中的口令进入日志/模型修复提示词。 */
final class BuildConfigurationProcess {
    private BuildConfigurationProcess() { }

    static void run(List<String> command, File directory, String javaExecutable,
                    int timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        // IDEA's launcher escapes Windows batch arguments, including spaces and cmd metacharacters.
        var builder = new com.intellij.execution.configurations.GeneralCommandLine(command)
                .withWorkDirectory(directory).withRedirectErrorStream(true)
                .withParentEnvironmentType(com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType.SYSTEM);
        if (javaExecutable != null && !javaExecutable.isBlank()) {
            File javaHome = new File(javaExecutable).getParentFile().getParentFile();
            builder.withEnvironment("JAVA_HOME", javaHome.getAbsolutePath());
        }
        Process process;
        try { process = builder.createProcess(); }
        catch (com.intellij.execution.ExecutionException ex) {
            throw new IOException("无法启动构建工具，请检查 executable、JAVA_HOME 和目录权限。");
        }
        process.getOutputStream().close();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (IOException ignored) { }
        }, "unitmind-build-config-output");
        drain.setDaemon(true);
        drain.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
                if (System.nanoTime() >= deadline) throw new IOException("构建工具导出测试配置超时。");
            }
            checkCancelled(cancelled);
            if (process.exitValue() != 0) {
                com.intellij.openapi.diagnostic.Logger.getInstance(BuildConfigurationProcess.class).warn(
                        "UnitMind runtime configuration: build export failed, exitCode=" + process.exitValue());
                throw new IOException("构建工具导出测试配置失败（退出码 " + process.exitValue()
                        + "）。请在项目中检查构建工具、仓库访问和配置导出；构建输出未收集到模型上下文。");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProcessCanceledException();
        } finally {
            ProcessCleanup.terminate(process);
        }
    }

    static List<String> executable(Path root, String tool, String explicit) throws IOException {
        boolean windows = isWindows();
        String wrapper = tool.equals("maven") ? (windows ? "mvnw.cmd" : "mvnw")
                : (windows ? "gradlew.bat" : "gradlew");
        Path binary = explicit.isBlank() ? root.resolve(wrapper) : Path.of(explicit);
        if (!binary.isAbsolute()) binary = root.resolve(binary);
        if (!Files.isRegularFile(binary) && explicit.isBlank()) {
            String name = tool.equals("maven") ? (windows ? "mvn.cmd" : "mvn") : (windows ? "gradle.bat" : "gradle");
            binary = findOnPath(name);
        }
        if (binary == null || !Files.isRegularFile(binary)) {
            com.intellij.openapi.diagnostic.Logger.getInstance(BuildConfigurationProcess.class).warn(
                    "UnitMind runtime configuration: build executable unavailable, tool=" + tool);
            throw new IOException("未找到项目 Wrapper 或构建工具；可在 .unitmind/test-runtime.json 中配置 executable。");
        }
        if (windows) return windowsExecutable(binary);
        if (!Files.isExecutable(binary)) return new ArrayList<>(List.of("/bin/sh", binary.toString()));
        return new ArrayList<>(List.of(binary.toString()));
    }

    static List<String> windowsExecutable(Path binary) {
        String name = binary.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".cmd") || name.endsWith(".bat")) {
            return new ArrayList<>(List.of("cmd.exe", "/d", "/v:off", "/c", binary.toString()));
        }
        return new ArrayList<>(List.of(binary.toString()));
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path findOnPath(String name) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            String value = entry.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            try {
                Path candidate = Path.of(value).resolve(name);
                if (Files.isRegularFile(candidate)) return candidate;
            } catch (java.nio.file.InvalidPathException ignored) { }
        }
        return null;
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new ProcessCanceledException();
        }
    }
}
