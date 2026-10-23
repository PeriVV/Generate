package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.MethodModel;
import com.intellij.util.PathUtil;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BundledMockitoRuntimeTest extends TestCase {
    public void testBothOfflineBundlesAreCompleteAndOutsidePluginLibraryClasspath() throws Exception {
        Path installed = Path.of(System.getProperty("unitmind.test.pluginLibDirectory"));
        try (var files = Files.list(installed)) {
            assertFalse("Mockito and its bytecode libraries must stay out of the IDE classloader",
                    files.anyMatch(path -> path.getFileName().toString().matches("(mockito|byte-buddy|objenesis).*\\.jar")));
        }
        assertEquals(5, BundledMockitoRuntime.libraries(8).size());
        assertEquals(5, BundledMockitoRuntime.libraries(17).size());
        assertTrue(BundledMockitoRuntime.libraries(8).stream().anyMatch(file -> file.getName().equals("mockito-core-4.11.0.jar")));
        assertTrue(BundledMockitoRuntime.libraries(17).stream().anyMatch(file -> file.getName().equals("mockito-core-5.18.0.jar")));
    }

    public void testLegacyMockitoActuallyExecutesWithJUnitAndCoverage() throws Exception {
        executeWithProjectJdk(8);
    }

    public void testModernMockitoExecutesOnJava11WithJUnitAndCoverage() throws Exception { executeWithProjectJdk(11); }
    public void testModernMockitoExecutesOnJava21WithJUnitAndCoverage() throws Exception { executeWithProjectJdk(21); }
    public void testModernMockitoExecutesOnJava25WithJUnitAndCoverage() throws Exception { executeWithProjectJdk(25); }

    private void executeWithProjectJdk(int feature) throws Exception {
        String configured = System.getProperty("unitmind.test.java" + feature + ".home", "");
        if (configured.isBlank() && feature != 8) return;
        String home = configured.isBlank() ? System.getProperty("java.home") : configured;
        String executable = Path.of(home, "bin", LocalCoverageClosureSupport.isWindows() ? "java.exe" : "java").toString();
        if (!configured.isBlank()) assertEquals(feature, BundledMockitoRuntime.javaFeature(executable));
        List<File> libraries = new ArrayList<>(BundledMockitoRuntime.libraries(feature));
        libraries.add(new File(PathUtil.getJarPathForClass(org.junit.platform.console.ConsoleLauncher.class)));
        Path root = Files.createTempDirectory("unitmind-mockito-java8-");
        try {
            Path classes = root.resolve("classes");
            Files.createDirectories(classes);
            String source = """
                    public class LegacySubject {
                        public interface Gateway { int value(); }
                        public int fetch(Gateway gateway) { return gateway.value() + 1; }
                    }
                    """;
            Path production = root.resolve("LegacySubject.java");
            Files.writeString(production, source);
            Path tests = root.resolve("LegacySubjectTest.java");
            Files.writeString(tests, """
                    import org.junit.jupiter.api.Test;
                    import org.junit.jupiter.api.extension.ExtendWith;
                    import org.mockito.Mock;
                    import org.mockito.junit.jupiter.MockitoExtension;
                    import static org.mockito.Mockito.*;
                    import static org.junit.jupiter.api.Assertions.*;
                    @ExtendWith(MockitoExtension.class)
                    class LegacySubjectTest {
                        @Mock LegacySubject.Gateway gateway;
                        @Test void mocksRealBusinessDependency() {
                            when(gateway.value()).thenReturn(4);
                            assertEquals(5, new LegacySubject().fetch(gateway));
                            verify(gateway).value();
                        }
                    }
                    """);
            String classpath = libraries.stream().map(File::getAbsolutePath)
                    .collect(java.util.stream.Collectors.joining(File.pathSeparator));
            String javac = Path.of(home, "bin", LocalCoverageClosureSupport.isWindows() ? "javac.exe" : "javac").toString();
            String target = Integer.toString(feature);
            ProcessBuilder compilerBuilder = new ProcessBuilder(javac, "-encoding", "UTF-8", "-source", target, "-target", target,
                    "-g", "-cp", classpath, "-d", classes.toString(), production.toString(), tests.toString())
                    .redirectErrorStream(true);
            Process compiler = LongCommandLineLauncher.start(compilerBuilder, root);
            String output = new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, compiler.waitFor());
            try (var input = new java.io.DataInputStream(Files.newInputStream(classes.resolve("LegacySubject.class")))) {
                assertEquals(0xCAFEBABE, input.readInt());
                input.readUnsignedShort();
                assertEquals(feature + 44, input.readUnsignedShort());
            }
            List<File> runtime = new ArrayList<>();
            runtime.add(classes.toFile());
            runtime.addAll(libraries);
            try (var artifact = new CompiledTestArtifact(root, classes, "LegacySubjectTest", "LegacySubject",
                    executable, runtime, List.of())) {
                var executor = new JacocoCoverageExecutor();
                var only = executor.executeTestsOnly(artifact, new CoverageConfig(), () -> false);
                assertFalse(LocalCoverageClosureSupport.details(only), only.isEnvironmentError());
                assertTrue(LocalCoverageClosureSupport.details(only), only.getSnapshot().getExecution().isSuccessful());
                assertEquals(1, only.getSnapshot().getExecution().getSucceeded());
                MethodModel method = new MethodModel();
                method.setMethodName("fetch");
                method.setSourceStartLine(3);
                method.setSourceEndLine(3);
                var coverage = executor.execute(artifact, method, source, new CoverageConfig(), () -> false);
                assertTrue(LocalCoverageClosureSupport.details(coverage), coverage.getSnapshot().getExecution().isSuccessful());
                assertTrue(LocalCoverageClosureSupport.details(coverage), coverage.getSnapshot().isAvailable());
                assertTrue(coverage.getSnapshot().getLine().getCovered() > 0);
            }
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }
}
