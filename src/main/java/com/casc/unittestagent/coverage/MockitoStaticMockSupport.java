package com.casc.unittestagent.coverage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Detects whether the project's Mockito runtime can create static mocks. */
public final class MockitoStaticMockSupport {
    private static final String EXTENSION = "mockito-extensions/org.mockito.plugins.MockMaker";
    private static final Pattern CORE_VERSION = Pattern.compile("mockito-core-(\\d+)(?:[.-].*)?\\.jar",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATIC_USAGE = Pattern.compile(
            "(?s).*\\b(?:Mockito\\s*\\.\\s*)?mockStatic\\s*\\(.*|.*\\bMockedStatic\\s*<.*");

    private final boolean supported;
    private final String description;

    private MockitoStaticMockSupport(boolean supported, String description) {
        this.supported = supported;
        this.description = description;
    }

    public static MockitoStaticMockSupport detect(Iterable<File> classpath) {
        String core = "";
        if (classpath != null) {
            for (File entry : classpath) {
                if (entry == null || !entry.exists()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("mockito-inline-") && name.endsWith(".jar")) {
                    return new MockitoStaticMockSupport(true, "已检测到 " + entry.getName());
                }
                Matcher matcher = CORE_VERSION.matcher(entry.getName());
                if (matcher.matches()) {
                    core = entry.getName();
                    try {
                        if (Integer.parseInt(matcher.group(1)) >= 5) {
                            return new MockitoStaticMockSupport(true,
                                    entry.getName() + " 默认使用 inline MockMaker");
                        }
                    } catch (NumberFormatException ignored) { }
                }
                if ((entry.isDirectory() || name.contains("mockito")) && hasInlineExtension(entry)) {
                    return new MockitoStaticMockSupport(true,
                            "已检测到 mock-maker-inline 扩展：" + entry.getName());
                }
            }
        }
        String detail = core.isBlank() ? "未检测到支持静态 Mock 的 inline MockMaker"
                : core + " 未启用 inline MockMaker";
        return new MockitoStaticMockSupport(false, detail);
    }

    public static boolean usesStaticMocking(String source) {
        return source != null && STATIC_USAGE.matcher(source).matches();
    }

    private static boolean hasInlineExtension(File entry) {
        try {
            if (entry.isDirectory()) {
                File extension = new File(entry, EXTENSION);
                return extension.isFile()
                        && Files.readString(extension.toPath(), StandardCharsets.UTF_8).contains("mock-maker-inline");
            }
            if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) return false;
            try (ZipFile zip = new ZipFile(entry)) {
                ZipEntry extension = zip.getEntry(EXTENSION);
                if (extension == null) return false;
                return new String(zip.getInputStream(extension).readAllBytes(), StandardCharsets.UTF_8)
                        .contains("mock-maker-inline");
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isSupported() { return supported; }
    public String getDescription() { return description; }
}
