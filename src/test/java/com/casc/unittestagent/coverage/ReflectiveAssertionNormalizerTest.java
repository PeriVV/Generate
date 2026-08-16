package com.casc.unittestagent.coverage;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class ReflectiveAssertionNormalizerTest extends TestCase {
    public void testUnwrapsDirectReflectiveInvocationWithoutChangingContract() {
        String code = """
                import java.lang.reflect.Method;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class AttributeTest {
                    void shouldReturnFalseForNullKey(Method method, Object attribute) {
                        assertThrows(NullPointerException.class,
                                () -> method.invoke(attribute, (Object) null));
                    }
                }
                """;

        ReflectiveAssertionNormalizer.Result result = ReflectiveAssertionNormalizer.normalize(code);

        assertTrue(result.isChanged());
        assertEquals(1, result.getChanges());
        assertTrue(result.getCode().contains("assertThrows(NullPointerException.class"));
        assertTrue(result.getCode().contains("catch (InvocationTargetException reflectionFailure)"));
        assertTrue(result.getCode().contains("throw reflectionFailure.getCause()"));
        assertTrue(result.getCode().contains("import java.lang.reflect.InvocationTargetException;"));
        assertFalse(CoverageCandidatePreflight.inspect(result.getCode()).stream().anyMatch(item ->
                "REFLECTION_ASSERT_THROWS_WRAPS_CAUSE".equals(item.getCode())));
    }

    public void testNormalizationIsIdempotent() {
        String code = """
                import java.lang.reflect.Method;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class AttributeTest {
                    void test(Method method, Object attribute) {
                        assertThrows(NullPointerException.class, () -> method.invoke(attribute));
                    }
                }
                """;

        String once = ReflectiveAssertionNormalizer.normalize(code).getCode();
        ReflectiveAssertionNormalizer.Result twice = ReflectiveAssertionNormalizer.normalize(once);

        assertFalse(twice.isChanged());
        assertEquals(once, twice.getCode());
    }

    public void testKeepsExplicitInvocationTargetExceptionContract() {
        String code = """
                class AttributeTest {
                    void test(java.lang.reflect.Method method, Object attribute) {
                        assertThrows(java.lang.reflect.InvocationTargetException.class,
                                () -> method.invoke(attribute));
                    }
                }
                """;

        ReflectiveAssertionNormalizer.Result result = ReflectiveAssertionNormalizer.normalize(code);

        assertFalse(result.isChanged());
        assertEquals(code, result.getCode());
    }

    public void testNormalizedReflectionAssertionCompilesAndPassesJUnit() throws Exception {
        Path root = Files.createTempDirectory("unitmind-reflective-normalizer-");
        Path source = root.resolve("source/sample");
        Path classes = root.resolve("classes");
        Files.createDirectories(source);
        Files.createDirectories(classes);
        String subject = """
                package sample;
                public class Subject {
                    private void rejectNull(String value) {
                        if (value == null) throw new NullPointerException("value");
                    }
                }
                """;
        String test = """
                package sample;
                import java.lang.reflect.Method;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class SubjectTest {
                    @Test void rejectsNullThroughReflection() throws Exception {
                        Method method = Subject.class.getDeclaredMethod("rejectNull", String.class);
                        method.setAccessible(true);
                        assertThrows(NullPointerException.class,
                                () -> method.invoke(new Subject(), (Object) null));
                    }
                }
                """;
        String normalized = ReflectiveAssertionNormalizer.normalize(test).getCode();
        Path subjectFile = source.resolve("Subject.java");
        Path testFile = source.resolve("SubjectTest.java");
        Files.writeString(subjectFile, subject, StandardCharsets.UTF_8);
        Files.writeString(testFile, normalized, StandardCharsets.UTF_8);
        String standaloneLauncher = Arrays.stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(path -> Path.of(path).getFileName().toString()
                        .startsWith("junit-platform-console-standalone"))
                .findFirst().orElseThrow(() -> new AssertionError("JUnit standalone launcher not found"));

        Process javac = new ProcessBuilder("javac", "--release", "17", "-g", "-cp",
                standaloneLauncher, "-d", classes.toString(),
                subjectFile.toString(), testFile.toString()).redirectErrorStream(true).start();
        String compilerOutput = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(compilerOutput, 0, javac.waitFor());

        String runtimeClasspath = classes + java.io.File.pathSeparator + standaloneLauncher;
        Process junit = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", runtimeClasspath, "org.junit.platform.console.ConsoleLauncher", "execute",
                "--disable-banner", "--disable-ansi-colors", "--details=none",
                "--include-engine=junit-jupiter", "--select-class=sample.SubjectTest")
                .redirectErrorStream(true).start();
        String junitOutput = new String(junit.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(junitOutput, 0, junit.waitFor());
    }
}
