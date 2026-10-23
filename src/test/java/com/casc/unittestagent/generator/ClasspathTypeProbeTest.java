package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import junit.framework.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class ClasspathTypeProbeTest extends TestCase {
    public void testClosingProbeReleasesTheActualJarAndCanBeRepeated() throws Exception {
        Path root = Files.createTempDirectory("probe 中文 ");
        try {
            Path jar = fixtureJar(root);
            try (var probe = ClasspathTypeProbe.from(List.of(jar.toFile()))) {
                assertTrue(probe.test("probe.Fixture"));
                probe.close(); probe.close();
                var loader = ClasspathTypeProbe.class.getDeclaredField("loader"); loader.setAccessible(true);
                assertNull(loader.get(probe));
                assertTrue(probe.test("Fixture"));
            }
            Files.delete(jar);
            assertFalse(Files.exists(jar));
        } finally { delete(root); }
    }

    public void testGeneratorClosesOwnedProbeBeforeReturningCode() throws Exception {
        Path root = Files.createTempDirectory("probe-generator-");
        try {
            Path jar = fixtureJar(root);
            var generator = new JUnitMockitoTestGenerator();
            var probe = ClasspathTypeProbe.from(List.of(jar.toFile()));
            generator.setTypeInstantiabilityProbe(probe);
            generator.setMockitoAvailable(false);
            assertTrue(probe.test("Fixture"));
            var method = new JavaSourceAnalyzer().analyze(
                    "package sample; import probe.Fixture; public class Subject { public static void accept(Fixture value) {} }", "accept");
            var scenario = new TestScenario(); scenario.setType("NORMAL"); scenario.setMethodNameEn("acceptsFixture");
            generator.generate(method, List.of(scenario), new MockStrategy());
            var loader = ClasspathTypeProbe.class.getDeclaredField("loader"); loader.setAccessible(true);
            assertNull("the generator must close even a loader opened before generation", loader.get(probe));
            Files.delete(jar);
        } finally { delete(root); }
    }

    public void testCancelledProbeAndGeneratorNeverReturnAConservativeFallback() {
        try (var probe = ClasspathTypeProbe.from(List.of(), () -> true)) {
            try { probe.test("Anything"); fail("cancelled probes must stop"); }
            catch (com.intellij.openapi.progress.ProcessCanceledException expected) { }
        }
        var generator = new JUnitMockitoTestGenerator();
        generator.setMockitoAvailable(false);
        generator.setTypeInstantiabilityProbe(name -> { throw new com.intellij.openapi.progress.ProcessCanceledException(); });
        var method = new JavaSourceAnalyzer().analyze(
                "package sample; public class Subject { public static void accept(Unknown value) {} }", "accept");
        var scenario = new TestScenario(); scenario.setType("NORMAL"); scenario.setMethodNameEn("acceptsFixture");
        try { generator.generate(method, List.of(scenario), new MockStrategy()); fail("generation must preserve cancellation"); }
        catch (com.intellij.openapi.progress.ProcessCanceledException expected) { }
    }

    private Path fixtureJar(Path root) throws Exception {
        Path source = root.resolve("Fixture.java");
        Files.writeString(source, "package probe; public class Fixture { public Fixture() {} }");
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path javac = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac");
        assertTrue("fixture requires a real JDK compiler", Files.isRegularFile(javac));
        Process compiler = com.casc.unittestagent.coverage.LongCommandLineLauncher.start(
                new ProcessBuilder(javac.toString(), "-encoding", "UTF-8", "-d", classes.toString(), source.toString())
                        .redirectErrorStream(true), root);
        try {
            assertTrue("fixture compilation timed out", compiler.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(compiler.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 0, compiler.exitValue());
        } finally { if (compiler.isAlive()) compiler.destroyForcibly(); }
        Path jar = root.resolve("fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("probe/Fixture.class"));
            output.write(Files.readAllBytes(classes.resolve("probe/Fixture.class"))); output.closeEntry();
        }
        return jar;
    }

    private void delete(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
