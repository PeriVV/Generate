package com.casc.unittestagent.coverage;

import com.intellij.openapi.application.PathManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** Extracts prepackaged dependencies for javac/child JVM only; never loads Mockito into the IDE. */
public final class BundledMockitoRuntime {
    private static final Pattern VERSION = Pattern.compile("^(?:1\\.)?(\\d+)");

    public static int javaFeature(String executable) throws IOException {
        Path release = Path.of(executable).toAbsolutePath().getParent().getParent().resolve("release");
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(release)) { values.load(input); }
        var matcher = VERSION.matcher(values.getProperty("JAVA_VERSION", "").replace("\"", ""));
        if (!matcher.find()) throw new IOException("无法确定模块/测试 JVM 的 Java 版本，请检查 JDK release 文件。");
        return Integer.parseInt(matcher.group(1));
    }

    public static List<File> libraries(int javaFeature) throws IOException {
        if (javaFeature < 8) throw new IOException("内置测试环境要求项目 JDK 8 或更高版本。");
        String variant = javaFeature < 11 ? "java8" : "java11";
        String base = "/runtime/mockito/" + variant + "/";
        String index;
        try (InputStream input = BundledMockitoRuntime.class.getResourceAsStream(base + "index.txt")) {
            if (input == null) throw new IOException("插件缺少内置 Mockito 离线依赖清单，请重新安装。");
            index = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<File> result = new ArrayList<>();
        for (String line : index.lines().toList()) {
            if (line.isBlank()) continue;
            String[] parts = line.split(" ", 2);
            if (parts.length != 2 || !parts[0].matches("[a-zA-Z0-9._-]+\\.jar") || !parts[1].matches("[0-9a-f]{64}")) {
                throw new IOException("插件内置 Mockito 离线依赖清单无效。");
            }
            Path folder = Path.of(PathManager.getSystemPath(), "unitmind", "test-runtimes", parts[1]);
            Path target = folder.resolve(parts[0]);
            if (!Files.isRegularFile(target) || !digest(target).equals(parts[1])) {
                Files.createDirectories(folder);
                Path temporary = Files.createTempFile(folder, "extract-", ".jar");
                try {
                    try (InputStream input = BundledMockitoRuntime.class.getResourceAsStream(base + parts[0])) {
                        if (input == null) throw new IOException("插件内置 Mockito 依赖不完整，请重新安装。");
                        Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (!digest(temporary).equals(parts[1])) throw new IOException("插件内置 Mockito 依赖校验失败，请重新安装。");
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(temporary); }
            }
            result.add(target.toFile());
        }
        if (result.size() != 5) throw new IOException("插件内置 Mockito 依赖集合不完整。");
        return result;
    }

    private static String digest(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
