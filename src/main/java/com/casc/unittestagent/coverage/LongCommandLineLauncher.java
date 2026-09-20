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
 * - javac：JDK 8 起即支持 @argfile，把除可执行文件外的全部参数写入参数文件；
 * - java：@argfile 需 JDK 9+，改用对版本无要求的 pathing jar——manifest 的
 *   Class-Path 引用原始 classpath 条目，命令行上只保留单个 jar 路径。
 *
 * 辅助文件写在沙箱临时目录内，随既有 sandbox 清理流程一起删除。
 */
public final class LongCommandLineLauncher {
    /** CreateProcessW 的 lpCommandLine 上限是 32767 个 UTF-16 字符，留出余量。 */
    private static final int SAFE_COMMAND_LENGTH = 30000;

    private LongCommandLineLauncher() {
    }

    /**
     * 启动子进程。仅在 Windows 且预估命令行超限时改写命令，其余平台与短命令
     * 保持原样直启，避免改变已验证路径的行为。
     *
     * @param auxiliaryDirectory 存放参数文件/pathing jar 的目录（沙箱临时目录），必须已存在。
     */
    public static Process start(ProcessBuilder builder, Path auxiliaryDirectory) throws IOException {
        List<String> command = builder.command();
        if (isWindows() && exceedsWindowsLimit(command)) {
            builder.command(adaptForWindows(command, auxiliaryDirectory));
        }
        return builder.start();
    }

    /** 预估 ProcessBuilder 生成的 Windows 命令行长度是否可能触碰 CreateProcess 上限。 */
    static boolean exceedsWindowsLimit(List<String> command) {
        int length = 1;
        for (String argument : command) {
            // 分隔符 + 可能的引号对，宁多勿少，仅用作门槛判断。
            length += argument.length() + 3;
        }
        return length > SAFE_COMMAND_LENGTH;
    }

    static List<String> adaptForWindows(List<String> command, Path auxiliaryDirectory) throws IOException {
        String rawExecutable = command.get(0);
        int lastSeparator = Math.max(rawExecutable.lastIndexOf('/'), rawExecutable.lastIndexOf('\\'));
        String executable = (lastSeparator >= 0 ? rawExecutable.substring(lastSeparator + 1) : rawExecutable)
                .toLowerCase(Locale.ROOT);
        if (executable.startsWith("javac")) {
            return List.of(command.get(0), "@" + writeJavacArgumentFile(command, auxiliaryDirectory));
        }
        return rewriteJavaClasspathWithPathingJar(command, auxiliaryDirectory);
    }

    private static Path writeJavacArgumentFile(List<String> command, Path directory) throws IOException {
        Path argumentFile = Files.createTempFile(directory, "javac-args-", ".txt");
        StringBuilder content = new StringBuilder();
        for (int index = 1; index < command.size(); index++) {
            // 参数文件里反斜杠的转义规则在不同 JDK、是否处于引号内并不一致；而 javac 的
            // 参数全部是路径/标志/类名，统一换成 Windows 同样接受的正斜杠最稳妥。
            String token = command.get(index).replace('\\', '/');
            if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
                content.append('"').append(token).append('"');
            } else {
                content.append(token);
            }
            content.append(System.lineSeparator());
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
                                                                   Path directory) throws IOException {
        int classpathValueIndex = classpathValueIndex(command);
        if (classpathValueIndex < 0) {
            throw new IOException("java 命令中未找到可改写的 -classpath 参数，无法规避 Windows 命令行长度限制。");
        }
        StringBuilder manifestClassPath = new StringBuilder();
        for (String entry : command.get(classpathValueIndex).split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            if (manifestClassPath.length() > 0) manifestClassPath.append(' ');
            // manifest Class-Path 只接受 URL；绝对 file: URL 不做相对解析，toURI 顺带完成编码。
            manifestClassPath.append(new File(entry).toURI());
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
        adapted.set(classpathValueIndex, pathingJar.toAbsolutePath().toString());
        return adapted;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
