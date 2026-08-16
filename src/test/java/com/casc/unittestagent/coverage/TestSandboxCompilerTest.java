package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class TestSandboxCompilerTest extends TestCase {
    public void testModuleJdkFallbackEmitsTargetClassIntoSandbox() throws Exception {
        Path root = Files.createTempDirectory("unitmind-target-compiler-test-");
        try {
            Path sourceRoot = root.resolve("source");
            Path sourcePackage = sourceRoot.resolve("sample");
            Path classes = root.resolve("classes");
            Files.createDirectories(sourcePackage);
            Files.createDirectories(classes);
            Path source = sourcePackage.resolve("Subject.java");
            Files.writeString(source, """
                    package sample;
                    public class Subject {
                        public int value() { return 42; }
                    }
                    """, StandardCharsets.UTF_8);

            ProjectTestEnvironment environment = new ProjectTestEnvironment();
            environment.javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "java.exe" : "java").toString();
            environment.languageFeature = 17;
            environment.sourcePath.add(sourceRoot.toFile());

            CompileDiagnostic diagnostic = new TestSandboxCompiler().compileTargetWithModuleJdk(
                    environment, sourceRoot, classes, source, "sample.Subject");

            assertNull(diagnostic == null ? "" : diagnostic.getMessage(), diagnostic);
            assertTrue(Files.isRegularFile(classes.resolve("sample/Subject.class")));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    public void testModuleJdkFallbackEmitsCandidateTestClassToo() throws Exception {
        Path root = Files.createTempDirectory("unitmind-sandbox-compiler-test-");
        try {
            Path sourceRoot = root.resolve("source");
            Path sourcePackage = sourceRoot.resolve("sample");
            Path classes = root.resolve("classes");
            Files.createDirectories(sourcePackage);
            Files.createDirectories(classes);
            Path subject = sourcePackage.resolve("Subject.java");
            Path test = sourcePackage.resolve("SubjectTest.java");
            Files.writeString(subject, """
                    package sample;
                    public class Subject { public int value() { return 42; } }
                    """, StandardCharsets.UTF_8);
            Files.writeString(test, """
                    package sample;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class SubjectTest {
                        @Test void returnsValue() { assertEquals(42, new Subject().value()); }
                    }
                    """, StandardCharsets.UTF_8);

            ProjectTestEnvironment environment = environment(sourceRoot);
            environment.classpath.addAll(classpathFiles());
            CompileDiagnostic diagnostic = new TestSandboxCompiler().compileSandboxWithModuleJdk(
                    environment, sourceRoot, classes, subject, test, "sample.Subject", "sample.SubjectTest");

            assertNull(diagnostic == null ? "" : diagnostic.getMessage(), diagnostic);
            assertTrue(Files.isRegularFile(classes.resolve("sample/Subject.class")));
            assertTrue(Files.isRegularFile(classes.resolve("sample/SubjectTest.class")));
        } finally {
            deleteTree(root);
        }
    }

    public void testInspectorDetectsExpectedTopLevelClassAndPackage() {
        JavaTestSourceInspector.Inspection inspection = JavaTestSourceInspector.inspect("""
                package org.jsoup.select;
                class Helper {}
                class AndTest {}
                """);

        assertTrue(inspection.isParsed());
        assertEquals("org.jsoup.select", inspection.getPackageName());
        assertTrue(inspection.declares("AndTest"));
        assertFalse(inspection.declares("OtherTest"));
    }

    public void testModuleJdkFallbackMarksTargetSourceFailureAsNonRepairable() throws Exception {
        Path root = Files.createTempDirectory("unitmind-target-diagnostic-test-");
        try {
            Path sourceRoot = root.resolve("source");
            Path sourcePackage = sourceRoot.resolve("sample");
            Path classes = root.resolve("classes");
            Files.createDirectories(sourcePackage);
            Files.createDirectories(classes);
            Path subject = sourcePackage.resolve("Subject.java");
            Path test = sourcePackage.resolve("SubjectTest.java");
            Files.writeString(subject,
                    "package sample; public class Subject { MissingProjectType value; }",
                    StandardCharsets.UTF_8);
            Files.writeString(test,
                    "package sample; class SubjectTest { Subject value = new Subject(); }",
                    StandardCharsets.UTF_8);

            CompileDiagnostic diagnostic = new TestSandboxCompiler().compileSandboxWithModuleJdk(
                    environment(sourceRoot), sourceRoot, classes, subject, test,
                    "sample.Subject", "sample.SubjectTest");

            assertNotNull(diagnostic);
            assertEquals("TARGET_SOURCE", diagnostic.getSourceOrigin());
            assertFalse(diagnostic.isRepairable());
        } finally {
            deleteTree(root);
        }
    }

    public void testModuleJdkFallbackKeepsCandidateTestFailureRepairable() throws Exception {
        Path root = Files.createTempDirectory("unitmind-test-diagnostic-test-");
        try {
            Path sourceRoot = root.resolve("source");
            Path sourcePackage = sourceRoot.resolve("sample");
            Path classes = root.resolve("classes");
            Files.createDirectories(sourcePackage);
            Files.createDirectories(classes);
            Path subject = sourcePackage.resolve("Subject.java");
            Path test = sourcePackage.resolve("SubjectTest.java");
            Files.writeString(subject,
                    "package sample; public class Subject { public int value() { return 1; } }",
                    StandardCharsets.UTF_8);
            Files.writeString(test,
                    "package sample; class SubjectTest { int value = new Subject().missing(); }",
                    StandardCharsets.UTF_8);

            CompileDiagnostic diagnostic = new TestSandboxCompiler().compileSandboxWithModuleJdk(
                    environment(sourceRoot), sourceRoot, classes, subject, test,
                    "sample.Subject", "sample.SubjectTest");

            assertNotNull(diagnostic);
            assertEquals("TEST_SOURCE", diagnostic.getSourceOrigin());
            assertTrue(diagnostic.isRepairable());
        } finally {
            deleteTree(root);
        }
    }

    private ProjectTestEnvironment environment(Path sourceRoot) {
        ProjectTestEnvironment environment = new ProjectTestEnvironment();
        environment.javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java").toString();
        environment.languageFeature = 17;
        environment.sourcePath.add(sourceRoot.toFile());
        return environment;
    }

    private java.util.List<java.io.File> classpathFiles() {
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) files.add(new java.io.File(entry));
        }
        return files;
    }

    private void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
