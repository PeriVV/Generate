package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.MethodModel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地真实仓库覆盖率闭环测试的共用工具。
 * JsoupLocalCoverageClosureTest 与 OpenSourceCoverageClosureMatrixTest 共享：
 * 定位方法行范围、准备沙箱（拷贝 classes + 编译候选测试）、执行 JaCoCo 闭环。
 */
final class LocalCoverageClosureSupport {
    private LocalCoverageClosureSupport() {
    }

    /** 按声明文本在源码中定位方法并计算行范围，供覆盖率按方法范围统计。 */
    static MethodModel method(String source, String declaration) {
        String[] lines = source.split("\\R", -1);
        int start = -1;
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(declaration)) {
                start = index + 1;
                break;
            }
        }
        assertTrue("Method declaration not found: " + declaration, start > 0);
        int depth = 0;
        boolean opened = false;
        int end = start;
        for (int index = start - 1; index < lines.length; index++) {
            for (char character : lines[index].toCharArray()) {
                if (character == '{') {
                    depth++;
                    opened = true;
                } else if (character == '}') {
                    depth--;
                }
            }
            end = index + 1;
            if (opened && depth == 0) break;
        }
        MethodModel method = new MethodModel();
        method.setSourceStartLine(start);
        method.setSourceEndLine(end);
        method.setMethodBodyText(String.join("\n", java.util.Arrays.copyOfRange(lines, start - 1, end)));
        method.setMethodName(com.github.javaparser.StaticJavaParser.parseMethodDeclaration(method.getMethodBodyText()).getNameAsString());
        return method;
    }

    /** 读取 classpath 文件为存在的依赖 jar 列表；空文件返回空列表。 */
    static List<File> dependencies(Path classpathFile) throws Exception {
        List<File> files = new ArrayList<>();
        String value = Files.readString(classpathFile, StandardCharsets.UTF_8).strip();
        if (value.isBlank()) return files;
        for (String entry : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            File file = new File(entry);
            if (file.exists()) files.add(file);
        }
        return files;
    }

    /** 把真实仓库的编译产物拷贝进沙箱，保持包结构。 */
    static void copyTree(Path source, Path target) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                // 沙箱按类路径模式编译执行，模块化项目的 module-info.class 会让
                // javac/JVM 进入模块解析并因缺依赖模块而报错，直接不拷贝。
                if (Files.isRegularFile(path) && "module-info.class".equals(path.getFileName().toString())) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 用当前 JDK 把候选测试编译进沙箱 classes，classpath 含插件自带 JUnit5/Mockito。 */
    static void compile(Path source, Path classes, List<File> dependencies) throws Exception {
        String javac = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac").toString();
        List<String> command = new ArrayList<>();
        command.add(javac);
        command.add("-encoding");
        command.add("UTF-8");
        command.add("--release");
        command.add("17");
        command.add("-g");
        command.add("-cp");
        List<String> classpath = new ArrayList<>();
        classpath.add(classes.toString());
        classpath.add(System.getProperty("java.class.path"));
        for (File dependency : dependencies) classpath.add(dependency.getAbsolutePath());
        command.add(String.join(File.pathSeparator, classpath));
        command.add("-d");
        command.add(classes.toString());
        command.add(source.toString());
        Path auxiliary = Files.createTempDirectory("unitmind-test-javac-");
        try {
            Process process = LongCommandLineLauncher.start(new ProcessBuilder(command).redirectErrorStream(true), auxiliary);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, process.waitFor());
        } finally { CompiledTestArtifact.deleteRecursively(auxiliary); }
    }

    /** 在沙箱上执行一次覆盖率闭环，返回带 JaCoCo 行/分支数据的执行结果。 */
    static CoverageRunResult execute(Path root,
                                     Path classes,
                                     List<File> dependencies,
                                     String sourceText,
                                     MethodModel method,
                                     String testClass,
                                     String targetClass) {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        CoverageConfig config = new CoverageConfig();
        config.setExecutionTimeoutSeconds(60);
        try (CompiledTestArtifact artifact = new CompiledTestArtifact(root, classes,
                testClass, targetClass, java, dependencies, new ArrayList<>())) {
            return new JacocoCoverageExecutor().execute(artifact, method, sourceText, config, () -> false);
        }
    }

    static Path environmentPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    static String details(CoverageRunResult result) {
        return result.getSnapshot().getMessage() + "\n" + result.getSnapshot().getExecution().getOutput();
    }

    static String metric(CoverageSnapshot snapshot) {
        String branch = snapshot.getBranch().isApplicable()
                ? percent(snapshot.getBranch().getRate()) : "N/A";
        return "行 " + percent(snapshot.getLine().getRate()) + " (" + snapshot.getLine().getCovered() + "/"
                + snapshot.getLine().getTotal() + ")，分支 " + branch + " ("
                + snapshot.getBranch().getCovered() + "/" + snapshot.getBranch().getTotal() + ")";
    }

    static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100.0d);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(String output, int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected exit " + expected + " but " + actual + "\n" + output);
    }
}
