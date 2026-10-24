package com.casc.unittestagent.coverage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Windows CreateProcess 的命令行上限约 32K 字符，超限时 ProcessBuilder.start()
 * 抛出“文件名或扩展名太长”（错误 206），并被上层归类为环境错误。沙箱 javac 会把
 * -classpath/-processorpath/--module-path/-sourcepath 全部拼进命令行（开启
 * obtainProcessorsFromClasspath 后 processorpath 还会再并入一遍完整 classpath），
 * 覆盖率执行也会把项目运行时 classpath 整体传给 java -cp，大型项目极易超限。
 *
 * 超限时把命令改写为不受长度限制的形式再启动：
 * - javac：JDK 8 起即支持 @argfile，编译器参数写入 UTF-8 文件，-J 参数留在启动命令上；
 * - java：@argfile 需 JDK 9+，改用对版本无要求的 pathing jar——manifest 的
 *   Class-Path 引用原始 classpath 条目，命令行上只保留单个 jar 路径。
 *
 * 辅助文件优先写在沙箱内；无法以 ASCII 引用的启动文件会临时转存，进程退出后清理。
 */
public final class LongCommandLineLauncher {
    /** CreateProcessW 的 lpCommandLine 上限是 32767 个 UTF-16 字符，留出余量。 */
    private static final int SAFE_COMMAND_LENGTH = 30000;

    private LongCommandLineLauncher() {
    }

    /**
     * 启动子进程。在 Windows 上处理超长命令，以及会被旧 JDK 的 ANSI 启动器
     * 损坏的 Unicode 编译参数和 classpath；其他命令保持直接启动。
     *
     * @param auxiliaryDirectory 存放参数文件/pathing jar 的目录（沙箱临时目录），必须已存在。
     */
    public static Process start(ProcessBuilder builder, Path auxiliaryDirectory) throws IOException {
        List<String> command = builder.command();
        Path staged = null;
        try {
            if (isWindows() && (exceedsWindowsLimit(command) || hasUnicodeCompilerOrClasspathArguments(command))) {
                List<String> adapted = adapt(command, auxiliaryDirectory, builder.directory());
                staged = stageBootstrapIfNeeded(adapted, builder.directory());
                builder.command(adapted);
            }
            Process process = builder.start();
            if (staged != null) {
                Path owned = staged;
                process.onExit().thenRun(() -> removeBootstrap(owned));
            }
            return process;
        } catch (IOException | RuntimeException | Error failure) {
            removeBootstrap(staged);
            throw failure;
        }
    }

    /** Never change the process directory: relative compiler options and application paths depend on it. */
    private static Path stageBootstrapIfNeeded(List<String> command, File workingDirectory) throws IOException {
        int index = classpathValueIndex(command);
        if (index < 0 && command.get(command.size() - 1).startsWith("@")) index = command.size() - 1;
        if (index < 0) return null;
        String value = command.get(index);
        String prefix = value.startsWith("@") ? "@" : "";
        String reference = prefix.isEmpty() ? value : value.substring(1);
        if (!hasUnicode(reference)) return null;
        Path base = (workingDirectory == null ? Path.of("") : workingDirectory.toPath()).toAbsolutePath().normalize();
        Path original = Path.of(reference);
        if (!original.isAbsolute()) original = base.resolve(original);
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        List<Path> locations = new ArrayList<>();
        if (!hasUnicode(temporary.toString())) locations.add(temporary);
        if (!locations.contains(base)) locations.add(base);
        IOException last = null;
        for (Path location : locations) {
            Path candidate = null;
            try {
                candidate = Files.createTempFile(location, ".unitmind-bootstrap-", prefix.isEmpty() ? ".jar" : ".args");
                Files.copy(original, candidate, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                String argument = location.equals(base) ? candidate.getFileName().toString() : candidate.toString();
                command.set(index, prefix + argument);
                return candidate;
            } catch (IOException failure) {
                removeBootstrap(candidate);
                last = failure;
            }
        }
        throw new IOException("无法为 Windows 启动器创建 ASCII 参数引用，请检查临时目录或进程工作目录的写入权限。", last);
    }

    private static void removeBootstrap(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException failure) { file.toFile().deleteOnExit(); }
    }

    private static boolean hasUnicodeCompilerOrClasspathArguments(List<String> command) {
        String executable = command.get(0).replace('\\', '/');
        executable = executable.substring(executable.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (executable.equals("javac") || executable.equals("javac.exe")) {
            return command.stream().skip(1).anyMatch(LongCommandLineLauncher::hasUnicode);
        }
        int index = classpathValueIndex(command);
        return index >= 0 && hasUnicode(command.get(index));
    }

    private static boolean hasUnicode(String text) {
        return text.chars().anyMatch(value -> value > 127);
    }

    /** 预估 ProcessBuilder 生成的 Windows 命令行长度是否可能触碰 CreateProcess 上限。 */
    static boolean exceedsWindowsLimit(List<String> command) {
        int length = 1;
        for (String argument : command) {
            // 留出 Windows 引号及尾部反斜杠转义膨胀的余量。
            length += argument.length() * 2 + 3;
        }
        return length > SAFE_COMMAND_LENGTH;
    }

    static List<String> adaptForWindows(List<String> command, Path auxiliaryDirectory) throws IOException {
        return adapt(command, auxiliaryDirectory, null);
    }

    static List<String> adapt(List<String> command, Path auxiliaryDirectory, File workingDirectory) throws IOException {
        String rawExecutable = command.get(0);
        int lastSeparator = Math.max(rawExecutable.lastIndexOf('/'), rawExecutable.lastIndexOf('\\'));
        String executable = (lastSeparator >= 0 ? rawExecutable.substring(lastSeparator + 1) : rawExecutable)
                .toLowerCase(Locale.ROOT);
        if (executable.startsWith("javac")) {
            List<String> adapted = new ArrayList<>();
            adapted.add(command.get(0));
            // -J options are consumed by the launcher, never by javac's @file parser.
            command.stream().skip(1).filter(arg -> arg.startsWith("-J")).forEach(adapted::add);
            adapted.add("-J-Dfile.encoding=UTF-8");
            adapted.add("@" + commandPath(writeJavacArgumentFile(command, auxiliaryDirectory), workingDirectory));
            return adapted;
        }
        return rewriteJavaClasspathWithPathingJar(command, auxiliaryDirectory, workingDirectory);
    }

    private static Path writeJavacArgumentFile(List<String> command, Path directory) throws IOException {
        Path argumentFile = Files.createTempFile(directory, "javac-args-", ".txt");
        StringBuilder content = new StringBuilder();
        for (int index = 1; index < command.size(); index++) {
            String token = command.get(index);
            if (token.startsWith("-J")) continue;
            // Both JDK 8's StreamTokenizer and later javac parsers accept these quoted escapes.
            // Backslashes may be annotation processor values, rather than path separators.
            content.append('"').append(token.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f"))
                    .append('"').append('\n');
        }
        Files.writeString(argumentFile, content.toString(), StandardCharsets.UTF_8);
        return argumentFile;
    }

    /** 找到 -cp/-classpath/--class-path 及其取值的位置；找不到返回 -1。 */
    private static int classpathValueIndex(List<String> command) {
        for (int index = 1; index < command.size() - 1; index++) {
            String flag = command.get(index);
            if ("-cp".equals(flag) || "-classpath".equals(flag) || "--class-path".equals(flag)) {
                return index + 1;
            }
        }
        return -1;
    }

    private static List<String> rewriteJavaClasspathWithPathingJar(List<String> command,
                                                                   Path directory, File workingDirectory) throws IOException {
        int classpathValueIndex = classpathValueIndex(command);
        if (classpathValueIndex < 0) {
            throw new IOException("java 命令中未找到可改写的 -classpath 参数，无法规避 Windows 命令行长度限制。");
        }
        StringBuilder manifestClassPath = new StringBuilder();
        File base = workingDirectory == null ? new File("").getAbsoluteFile() : workingDirectory.getAbsoluteFile();
        for (String entry : command.get(classpathValueIndex).split(Pattern.quote(File.pathSeparator), -1)) {
            boolean wildcard = entry.equals("*") || entry.endsWith("/*") || entry.endsWith("\\*");
            String fileEntry = wildcard ? entry.substring(0, entry.length() - 1) : entry;
            Path resolved = fileEntry.isEmpty() ? base.toPath() : Path.of(fileEntry);
            // Path.resolve also handles Windows drive-root-relative entries such as \repo.
            if (!resolved.isAbsolute()) resolved = base.toPath().resolve(resolved);
            File path = resolved.toAbsolutePath().normalize().toFile();
            if (wildcard) {
                File[] jars = path.listFiles(file -> file.isFile()
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (jars != null) for (File jar : java.util.Arrays.stream(jars).sorted().toList()) {
                    appendClasspath(manifestClassPath, jar);
                }
            } else {
                appendClasspath(manifestClassPath, path);
            }
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, manifestClassPath.toString());
        Path pathingJar = Files.createTempFile(directory, "classpath-", ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pathingJar))) {
            zip.putNextEntry(new ZipEntry(JarFile.MANIFEST_NAME));
            manifest.write(zip);
            zip.closeEntry();
        }
        List<String> adapted = new ArrayList<>(command);
        adapted.set(classpathValueIndex, commandPath(pathingJar, workingDirectory));
        return adapted;
    }

    static String commandPath(Path file, File workingDirectory) {
        Path absolute = file.toAbsolutePath().normalize();
        if (hasUnicode(absolute.toString())) {
            Path base = (workingDirectory == null ? Path.of("") : workingDirectory.toPath()).toAbsolutePath().normalize();
            try {
                Path relative = base.relativize(absolute);
                if (!hasUnicode(relative.toString())) return relative.toString();
            } catch (IllegalArgumentException differentRoots) {
                // A different drive cannot be expressed relative to the process directory.
            }
        }
        return absolute.toString();
    }

    private static void appendClasspath(StringBuilder value, File entry) {
        if (value.length() > 0) value.append(' ');
        value.append(entry.toURI().toASCIIString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
