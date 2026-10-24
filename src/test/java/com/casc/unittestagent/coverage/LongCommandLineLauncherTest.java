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

        assertEquals(3, adapted.size());
        assertEquals("C:\\jdk\\bin\\javac.exe", adapted.get(0));
        assertEquals("-J-Dfile.encoding=UTF-8", adapted.get(1));
        assertTrue(adapted.get(2).startsWith("@"));
        Path argumentFile = Path.of(adapted.get(2).substring(1));
        assertTrue(Files.isRegularFile(argumentFile));
        String[] tokens = Files.readString(argumentFile, StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals("\"-encoding\"", tokens[0]);
        assertEquals("\"UTF-8\"", tokens[1]);
        assertEquals("\"-classpath\"", tokens[2]);
        assertEquals("\"C:\\\\repo\\\\a.jar" + File.pathSeparator + "C:\\\\repo\\\\b.jar\"", tokens[3]);
        assertEquals("\"-d\"", tokens[4]);
        assertEquals("\"C:\\\\out dir\\\\classes\"", tokens[5]);
        assertEquals("\"C:\\\\src\\\\Foo.java\"", tokens[6]);
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
            assertTrue(manifestClassPath.contains(new File("/repo/spring lib/a.jar").toURI().toASCIIString()));
            assertTrue(manifestClassPath.contains(new File("/repo/b.jar").toURI().toASCIIString()));
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

    public void testRealJavacArgumentFilePreservesUnicodeQuotesBackslashesAndJvmOptions() throws Exception {
        Path folder = Files.createDirectories(auxiliaryDirectory.resolve("中文 space # apostrophe's"));
        Path processors = Files.createDirectories(folder.resolve("processors"));
        Path processor = folder.resolve("Recorder.java");
        Files.writeString(processor, """
                import javax.annotation.processing.*;
                import javax.lang.model.element.TypeElement;
                import java.util.Set;
                @SupportedAnnotationTypes("*") @SupportedOptions({"out", "value"})
                public class Recorder extends AbstractProcessor {
                    public javax.lang.model.SourceVersion getSupportedSourceVersion() { return javax.lang.model.SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> types, RoundEnvironment round) {
                        try { java.nio.file.Files.writeString(java.nio.file.Path.of(processingEnv.getOptions().get("out")), processingEnv.getOptions().get("value")); }
                        catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """);
        LocalCoverageClosureSupport.compile(processor, processors, List.of());
        Path source = folder.resolve("Input.java"); Files.writeString(source, "class Input { String 中文; }");
        Path output = folder.resolve("record.txt");
        String value = "中文 C:\\project\\new\\tab\\ trailing\\ \"quoted\" # ' tab\t newline\n";
        String javac = Path.of(System.getProperty("java.home"), "bin", BuildConfigurationProcess.isWindows() ? "javac.exe" : "javac").toString();
        var command = LongCommandLineLauncher.adapt(List.of(javac, "-J-Dfile.encoding=windows-1252", "-encoding", "UTF-8",
                "-processorpath", processors.toString(), "-processor", "Recorder", "-proc:only",
                "-Aout=" + output, "-Avalue=" + value, source.toString()), folder, folder.toFile());
        assertTrue(command.toString(), command.contains("-J-Dfile.encoding=windows-1252"));
        var process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, process.exitValue());
        assertEquals(value, Files.readString(output));
    }

    public void testRealJavaPathingJarPreservesRelativeWildcardAndEmptyClasspathEntries() throws Exception {
        Path root = Files.createDirectories(auxiliaryDirectory.resolve("中文 runtime space"));
        Path lib = Files.createDirectories(root.resolve("lib"));
        Path dep = Files.createDirectories(root.resolve("dep"));
        Path helper = root.resolve("Helper.java"); Files.writeString(helper, "public class Helper { public static String value() { return \"中文\"; } }");
        LocalCoverageClosureSupport.compile(helper, dep, List.of());
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(lib.resolve("中文 dependency.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("Helper.class")); jar.write(Files.readAllBytes(dep.resolve("Helper.class"))); jar.closeEntry();
        }
        Path main = root.resolve("Main.java"); Files.writeString(main, "class Main { public static void main(String[] args) { System.out.print(Helper.value()); } }");
        LocalCoverageClosureSupport.compile(main, root, List.of(dep.toFile()));
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", BuildConfigurationProcess.isWindows() ? "java.exe" : "java").toString();
        var command = LongCommandLineLauncher.adapt(List.of(javaCommand, "-Dfile.encoding=UTF-8", "-cp", "lib/*" + File.pathSeparator, "Main"), root, root.toFile());
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue()); assertEquals("中文", output);
        StringBuilder longClasspath = new StringBuilder("lib/*");
        while (longClasspath.length() < 40000) longClasspath.append(File.pathSeparator).append("missing/012345678901234567890123456789");
        longClasspath.append(File.pathSeparator);
        var builder = new ProcessBuilder(javaCommand, "-Dfile.encoding=UTF-8", "-cp", longClasspath.toString(), "Main")
                .directory(root.toFile()).redirectErrorStream(true);
        process = LongCommandLineLauncher.start(builder, root);
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue()); assertEquals("中文", output);
    }

    public void testOptionalJava8CompilerReadsUtf8ArgumentFileInUnicodeDirectory() throws Exception {
        String home = System.getProperty("unitmind.test.java8.home", "");
        if (home.isBlank()) return;
        Path folder = Files.createDirectories(auxiliaryDirectory.resolve("中文 Java8 path"));
        Path source = folder.resolve("Input.java");
        Files.writeString(source, "public class Input { public static void main(String[] args) { System.out.print(\"中文\"); } }");
        String suffix = BuildConfigurationProcess.isWindows() ? ".exe" : "";
        var command = LongCommandLineLauncher.adapt(List.of(Path.of(home, "bin", "javac" + suffix).toString(),
                "-encoding", "UTF-8", "-d", folder.toString(), source.toString()), folder, folder.toFile());
        var compile = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
        assertTrue(compile.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, compile.exitValue());
        var runtime = LongCommandLineLauncher.adapt(List.of(Path.of(home, "bin", "java" + suffix).toString(), "-Dfile.encoding=UTF-8", "-cp", folder.toString(), "Input"), folder, folder.toFile());
        var run = new ProcessBuilder(runtime).directory(folder.toFile()).redirectErrorStream(true).start();
        assertTrue(run.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("中文", new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); assertEquals(0, run.exitValue());
    }

    public void testShortUnicodeSourceAndClasspathUseSafeWindowsLaunch() throws Exception {
        Path folder = Files.createDirectories(auxiliaryDirectory.resolve("中文 short launch"));
        Path source = folder.resolve("Input.java");
        Files.writeString(source, "public class Input { public static void main(String[] args) { System.out.print(\"中文\"); } }");
        String suffix = BuildConfigurationProcess.isWindows() ? ".exe" : "";
        String home = System.getProperty("java.home");
        Process compiler = LongCommandLineLauncher.start(new ProcessBuilder(
                Path.of(home, "bin", "javac" + suffix).toString(), "-encoding", "UTF-8",
                "-d", folder.toString(), source.toString()).redirectErrorStream(true), auxiliaryDirectory);
        assertTrue(compiler.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, compiler.exitValue());
        Process runtime = LongCommandLineLauncher.start(new ProcessBuilder(
                Path.of(home, "bin", "java" + suffix).toString(), "-Dfile.encoding=UTF-8",
                "-cp", folder.toString(), "Input").redirectErrorStream(true), auxiliaryDirectory);
        assertTrue(runtime.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(runtime.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, runtime.exitValue());
        assertEquals("中文", output);
    }

    public void testUnicodeAuxiliaryWithoutExplicitDirectoryPreservesWorkingDirectoryAndArguments() throws Exception {
        verifyUnicodeAuxiliaryWithoutDirectory(false);
    }

    public void testUnicodeTemporaryRootFallsBackWithoutChangingWorkingDirectory() throws Exception {
        verifyUnicodeAuxiliaryWithoutDirectory(true);
    }

    private void verifyUnicodeAuxiliaryWithoutDirectory(boolean unicodeTemporaryRoot) throws Exception {
        Path folder = Files.createDirectories(auxiliaryDirectory.resolve("中文 bootstrap space"));
        Path source = folder.resolve("WorkingDirectory.java");
        Files.writeString(source, "public class WorkingDirectory { public static void main(String[] args) { "
                + "System.out.print(System.getProperty(\"user.dir\") + \"\\n\" + args[0]); } }");
        String suffix = BuildConfigurationProcess.isWindows() ? ".exe" : "";
        String previousTemporary = System.getProperty("java.io.tmpdir");
        try {
            if (unicodeTemporaryRoot) System.setProperty("java.io.tmpdir", folder.toString());
            ProcessBuilder compiler = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javac" + suffix).toString(),
                    "-encoding", "UTF-8", "-d", folder.toString(), source.toString()).redirectErrorStream(true);
            Process compile = LongCommandLineLauncher.start(compiler, folder);
            assertTrue(compile.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, compile.exitValue());
            assertNull(compiler.directory());
            assertBootstrapRemoved(compiler, true);
            ProcessBuilder runtime = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java" + suffix).toString(),
                    "-Dfile.encoding=UTF-8", "-cp", folder.toString(), "WorkingDirectory", "@kept-as-application-argument")
                    .redirectErrorStream(true);
            Process run = LongCommandLineLauncher.start(runtime, folder);
            assertTrue(run.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, run.exitValue());
            String[] lines = output.split("\\R", 2);
            assertEquals(Path.of("").toRealPath(), Path.of(lines[0]).toRealPath());
            assertEquals("@kept-as-application-argument", lines[1]);
            assertNull(runtime.directory());
            assertBootstrapRemoved(runtime, false);
        } finally {
            System.setProperty("java.io.tmpdir", previousTemporary);
        }
    }

    public void testFailedLaunchCleansStagedBootstrap() throws Exception {
        Path folder = Files.createDirectories(auxiliaryDirectory.resolve("中文 failed launch"));
        ProcessBuilder builder = new ProcessBuilder(folder.resolve("missing").resolve("javac.exe").toString(),
                "-d", folder.toString(), folder.resolve("Missing.java").toString());
        try {
            LongCommandLineLauncher.start(builder, folder);
            fail("Missing executable must fail to launch");
        } catch (java.io.IOException expected) {
            assertBootstrapRemoved(builder, true);
        }
    }

    private void assertBootstrapRemoved(ProcessBuilder builder, boolean compiler) throws Exception {
        if (!BuildConfigurationProcess.isWindows()) return;
        String reference = compiler ? builder.command().get(builder.command().size() - 1).substring(1)
                : builder.command().get(builder.command().indexOf("-cp") + 1);
        assertTrue("Bootstrap reference must survive ANSI launchers: " + reference,
                reference.chars().allMatch(value -> value < 128));
        Path bootstrap = Path.of(reference).toAbsolutePath();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (Files.exists(bootstrap) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse("Process-owned bootstrap must be deleted after exit or launch failure: " + bootstrap, Files.exists(bootstrap));
    }
}
