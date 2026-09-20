package com.casc.unittestagent.coverage;

import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Windows 命令行超限改写测试：javac 收敛为 @argfile、java 的 -cp 收敛为
 * pathing jar，其余参数与顺序必须原样保留，保证行为等价。
 */
public class LongCommandLineLauncherTest extends TestCase {
    private Path auxiliaryDirectory;

    @Override
    protected void setUp() throws Exception {
        auxiliaryDirectory = Files.createTempDirectory("unitmind-long-command-test-");
    }

    @Override
    protected void tearDown() throws Exception {
        try (var paths = Files.walk(auxiliaryDirectory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    public void testShortCommandStaysWithinLimit() {
        assertFalse(LongCommandLineLauncher.exceedsWindowsLimit(
                List.of("javac", "-encoding", "UTF-8", "Foo.java")));
    }

    public void testLongClasspathExceedsLimit() {
        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-classpath");
        command.add("x".repeat(32000));
        assertTrue(LongCommandLineLauncher.exceedsWindowsLimit(command));
    }

    public void testJavacLongCommandBecomesArgumentFile() throws Exception {
        List<String> command = List.of(
                "C:\\jdk\\bin\\javac.exe",
                "-encoding", "UTF-8",
                "-classpath", "C:\\repo\\a.jar" + File.pathSeparator + "C:\\repo\\b.jar",
                "-d", "C:\\out dir\\classes",
                "C:\\src\\Foo.java");

        List<String> adapted = LongCommandLineLauncher.adaptForWindows(command, auxiliaryDirectory);

        assertEquals(2, adapted.size());
        assertEquals("C:\\jdk\\bin\\javac.exe", adapted.get(0));
        assertTrue(adapted.get(1).startsWith("@"));
        Path argumentFile = Path.of(adapted.get(1).substring(1));
        assertTrue(Files.isRegularFile(argumentFile));
        String[] tokens = Files.readString(argumentFile, StandardCharsets.UTF_8).strip().split("\\R");
        // 参数逐行写入、顺序不变；反斜杠统一为正斜杠；含空格的参数加引号。
        assertEquals("-encoding", tokens[0]);
        assertEquals("UTF-8", tokens[1]);
        assertEquals("-classpath", tokens[2]);
        assertEquals("C:/repo/a.jar" + File.pathSeparator + "C:/repo/b.jar", tokens[3]);
        assertEquals("-d", tokens[4]);
        assertEquals("\"C:/out dir/classes\"", tokens[5]);
        assertEquals("C:/src/Foo.java", tokens[6]);
    }

    public void testJavaLongCommandRewritesClasspathWithPathingJar() throws Exception {
        String classpath = "/repo/spring lib/a.jar" + File.pathSeparator + "/repo/b.jar";
        List<String> command = List.of(
                "/jdk/bin/java",
                "-javaagent:/tools/jacocoagent.jar",
                "-cp",
                classpath,
                "org.junit.platform.console.ConsoleLauncher",
                "--select-class=demo.FooTest");

        List<String> adapted = LongCommandLineLauncher.adaptForWindows(command, auxiliaryDirectory);

        assertEquals(command.size(), adapted.size());
        assertEquals("/jdk/bin/java", adapted.get(0));
        assertEquals("-javaagent:/tools/jacocoagent.jar", adapted.get(1));
        assertEquals("-cp", adapted.get(2));
        assertEquals("org.junit.platform.console.ConsoleLauncher", adapted.get(4));
        assertEquals("--select-class=demo.FooTest", adapted.get(5));
        Path pathingJar = Path.of(adapted.get(3));
        assertTrue(Files.isRegularFile(pathingJar));
        // 读回 manifest 验证折叠的长 Class-Path 能被标准解析器还原，且条目被 URL 编码。
        try (JarFile jar = new JarFile(pathingJar.toFile())) {
            String manifestClassPath = jar.getManifest().getMainAttributes()
                    .getValue(Attributes.Name.CLASS_PATH);
            assertNotNull(manifestClassPath);
            assertTrue(manifestClassPath.contains("file:" + "/repo/spring%20lib/a.jar"));
            assertTrue(manifestClassPath.contains("file:" + "/repo/b.jar"));
        }
    }

    public void testPathingJarDirectoryEntriesKeepTrailingSlash() throws Exception {
        Path classesDirectory = Files.createDirectories(auxiliaryDirectory.resolve("classes"));
        List<String> command = List.of("/jdk/bin/java", "-cp", classesDirectory.toString(), "demo.Main");

        List<String> adapted = LongCommandLineLauncher.adaptForWindows(command, auxiliaryDirectory);

        try (JarFile jar = new JarFile(Path.of(adapted.get(2)).toFile())) {
            String manifestClassPath = jar.getManifest().getMainAttributes()
                    .getValue(Attributes.Name.CLASS_PATH);
            // JDK 按“URL 以 / 结尾”区分目录与 jar；目录条目丢失斜杠后类查找必然失败。
            // File.toURI() 与 Path.toUri() 的斜杠个数不同但语义等价，断言用同一来源。
            assertEquals(classesDirectory.toFile().toURI().toString(), manifestClassPath);
        }
    }

    public void testJavaCommandWithoutClasspathFailsExplicitly() {
        try {
            LongCommandLineLauncher.adaptForWindows(
                    List.of("/jdk/bin/java", "-version"), auxiliaryDirectory);
            fail("缺少 -classpath 的 java 命令应显式失败。");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("-classpath"));
        }
    }
}
