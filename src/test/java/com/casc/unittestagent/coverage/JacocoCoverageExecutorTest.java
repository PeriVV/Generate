package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageGap;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.TestExecutionSummary;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class JacocoCoverageExecutorTest extends TestCase {
    public void testReportsOnlySelectedMethodLinesAndMissedBranch() throws Exception {
        Path root = Files.createTempDirectory("unitmind-jacoco-test-");
        Path source = root.resolve("source/sample");
        Path classes = root.resolve("classes");
        Files.createDirectories(source);
        Files.createDirectories(classes);
        String subject = """
                package sample;
                public class Subject {
                    public int classify(int value) {
                        if (value > 0) {
                            return 1;
                        }
                        return -1;
                    }
                    public int unrelated() { return 99; }
                }
                """;
        String test = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    @Test void coversPositive() {
                        assertEquals(1, new Subject().classify(1));
                    }
                }
                """;
        Path subjectFile = source.resolve("Subject.java");
        Path testFile = source.resolve("SubjectTest.java");
        Files.writeString(subjectFile, subject, StandardCharsets.UTF_8);
        Files.writeString(testFile, test, StandardCharsets.UTF_8);
        Process javac = new ProcessBuilder("javac", "--release", "17", "-g", "-cp", System.getProperty("java.class.path"),
                "-d", classes.toString(), subjectFile.toString(), testFile.toString())
                .redirectErrorStream(true).start();
        String javacOutput = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(javacOutput, 0, javac.waitFor());

        List<File> runtime = new ArrayList<>();
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        MethodModel method = new MethodModel();
        method.setMethodName("classify");
        method.setSourceStartLine(3);
        method.setSourceEndLine(8);
        CoverageConfig config = new CoverageConfig();
        config.setExecutionTimeoutSeconds(30);

        try (CompiledTestArtifact artifact = new CompiledTestArtifact(root, classes,
                "sample.SubjectTest", "sample.Subject", java, runtime, new ArrayList<>())) {
            CoverageRunResult result = new JacocoCoverageExecutor().execute(
                    artifact, method, subject, config, () -> false);

            assertFalse(result.getSnapshot().getMessage(), result.isEnvironmentError());
            assertTrue(result.getSnapshot().getMessage() + "\n" + result.getSnapshot().getExecution().getOutput(),
                    result.getSnapshot().isAvailable());
            assertEquals(1, result.getSnapshot().getExecution().getFound());
            assertEquals(2, result.getSnapshot().getBranch().getTotal());
            assertEquals(1, result.getSnapshot().getBranch().getCovered());
            assertTrue(result.getGaps().stream().anyMatch(gap ->
                    gap.getKind() == CoverageGap.Kind.BRANCH && gap.getLineNumber() == 4));
            assertTrue(result.getGaps().stream().noneMatch(gap -> gap.getLineNumber() == 9));
        }
    }

    public void testNoBranchMetricIsNotApplicableAndCountsAsComplete() {
        com.casc.unittestagent.model.CoverageMetric metric =
                new com.casc.unittestagent.model.CoverageMetric(0, 0);
        assertFalse(metric.isApplicable());
        assertEquals(1.0d, metric.getRate());
    }

    public void testAnalyzesConcreteMethodInNestedClassByBinaryName() throws Exception {
        Path root = Files.createTempDirectory("unitmind-jacoco-nested-test-");
        Path source = root.resolve("source/sample");
        Path classes = root.resolve("classes");
        Files.createDirectories(source);
        Files.createDirectories(classes);
        String subject = """
                package sample;
                public abstract class Evaluator {
                    public abstract boolean matches(int value);
                    public static final class Positive extends Evaluator {
                        @Override public boolean matches(int value) {
                            if (value > 0) return true;
                            return false;
                        }
                    }
                }
                """;
        String test = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class EvaluatorTest {
                    @Test void positiveMatches() {
                        assertTrue(new Evaluator.Positive().matches(1));
                    }
                }
                """;
        Path subjectFile = source.resolve("Evaluator.java");
        Path testFile = source.resolve("EvaluatorTest.java");
        Files.writeString(subjectFile, subject, StandardCharsets.UTF_8);
        Files.writeString(testFile, test, StandardCharsets.UTF_8);
        Process javac = new ProcessBuilder("javac", "--release", "17", "-g", "-cp",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                subjectFile.toString(), testFile.toString()).redirectErrorStream(true).start();
        String javacOutput = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(javacOutput, 0, javac.waitFor());

        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        MethodModel method = new MethodModel();
        method.setMethodName("matches");
        method.setMethodBodyText("{ if (value > 0) return true; return false; }");
        method.setSourceStartLine(5);
        method.setSourceEndLine(8);
        CoverageConfig config = new CoverageConfig();
        config.setExecutionTimeoutSeconds(30);

        try (CompiledTestArtifact artifact = new CompiledTestArtifact(root, classes,
                "sample.EvaluatorTest", "sample.Evaluator$Positive", java,
                new ArrayList<>(), new ArrayList<>())) {
            CoverageRunResult result = new JacocoCoverageExecutor().execute(
                    artifact, method, subject, config, () -> false);

            assertFalse(result.getSnapshot().getMessage(), result.isEnvironmentError());
            assertTrue(result.getSnapshot().getMessage(), result.getSnapshot().isAvailable());
            assertEquals(2, result.getSnapshot().getBranch().getTotal());
            assertEquals(1, result.getSnapshot().getBranch().getCovered());
            assertTrue(result.getGaps().stream().anyMatch(gap ->
                    gap.getKind() == CoverageGap.Kind.BRANCH && gap.getLineNumber() == 6));
        }
    }

    public void testRecoversNestedMethodWhenSnapshotPointsAtOuterSameSignature() throws Exception {
        Path root = Files.createTempDirectory("unitmind-jacoco-nested-recovery-test-");
        Path source = root.resolve("source/sample");
        Path classes = root.resolve("classes");
        Files.createDirectories(source);
        Files.createDirectories(classes);
        String subject = """
                package sample;
                public abstract class Wrapper {
                    public abstract Wrapper append(char value);
                    public static final class Nested extends Wrapper {
                        @Override
                        public Nested append(char value) {
                            return this;
                        }
                    }
                }
                """;
        String test = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                class WrapperTest {
                    @Test void appendsThroughNestedImplementation() {
                        assertNotNull(new Wrapper.Nested().append('x'));
                    }
                }
                """;
        Path subjectFile = source.resolve("Wrapper.java");
        Path testFile = source.resolve("WrapperTest.java");
        Files.writeString(subjectFile, subject, StandardCharsets.UTF_8);
        Files.writeString(testFile, test, StandardCharsets.UTF_8);
        Process javac = new ProcessBuilder("javac", "--release", "17", "-g", "-cp",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                subjectFile.toString(), testFile.toString()).redirectErrorStream(true).start();
        String javacOutput = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(javacOutput, 0, javac.waitFor());

        MethodModel method = new MethodModel();
        method.setClassName("Nested");
        method.setMethodName("append");
        method.setMethodBodyText("{ return this; }");
        method.setSourceStartLine(5);
        method.setSourceEndLine(8);
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();

        // Simulate the historical snapshot bug: target owner is the outer class, while the selected
        // source range belongs to its nested concrete implementation.
        try (CompiledTestArtifact artifact = new CompiledTestArtifact(root, classes,
                "sample.WrapperTest", "sample.Wrapper", java,
                new ArrayList<>(), new ArrayList<>())) {
            CoverageRunResult result = new JacocoCoverageExecutor().execute(
                    artifact, method, subject, new CoverageConfig(), () -> false);

            assertFalse(result.getSnapshot().getMessage(), result.isEnvironmentError());
            assertTrue(result.getSnapshot().getMessage(), result.getSnapshot().isAvailable());
            assertEquals(1, result.getSnapshot().getLine().getCovered());
            assertEquals(0, result.getSnapshot().getLine().getMissed());
        }
    }

    public void testDoesNotMixLocalClassBranchesIntoSelectedMethod() throws Exception {
        Path root = Files.createTempDirectory("unitmind-jacoco-local-class-test-");
        Path source = root.resolve("source/sample");
        Path classes = root.resolve("classes");
        Files.createDirectories(source);
        Files.createDirectories(classes);
        String subject = """
                package sample;
                public class LocalSubject {
                    public int target(boolean flag) {
                        class Local { int value(boolean inner) { return inner ? 1 : 2; } }
                        return flag ? new Local().value(true) : 0;
                    }
                }
                """;
        String test = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class LocalSubjectTest {
                    @Test void coversPositive() { assertEquals(1, new LocalSubject().target(true)); }
                }
                """;
        Path subjectFile = source.resolve("LocalSubject.java");
        Path testFile = source.resolve("LocalSubjectTest.java");
        Files.writeString(subjectFile, subject, StandardCharsets.UTF_8);
        Files.writeString(testFile, test, StandardCharsets.UTF_8);
        Process javac = new ProcessBuilder("javac", "--release", "17", "-g", "-cp",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                subjectFile.toString(), testFile.toString()).redirectErrorStream(true).start();
        String javacOutput = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(javacOutput, 0, javac.waitFor());

        MethodModel method = new MethodModel();
        method.setMethodName("target");
        method.setMethodBodyText("{ class Local { ... } return flag ? new Local().value(true) : 0; }");
        method.setSourceStartLine(3);
        method.setSourceEndLine(6);
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();

        try (CompiledTestArtifact artifact = new CompiledTestArtifact(root, classes,
                "sample.LocalSubjectTest", "sample.LocalSubject", java,
                new ArrayList<>(), new ArrayList<>())) {
            CoverageRunResult result = new JacocoCoverageExecutor().execute(
                    artifact, method, subject, new CoverageConfig(), () -> false);

            assertTrue(result.getSnapshot().getMessage(), result.getSnapshot().isAvailable());
            assertEquals("Only target(boolean) branches should be counted", 2,
                    result.getSnapshot().getBranch().getTotal());
            assertEquals(1, result.getSnapshot().getBranch().getCovered());
        }
    }

    public void testCreatesFallbackDiagnosticWhenDiscoveryFailsBeforeXmlReport() {
        TestExecutionSummary summary = new TestExecutionSummary();
        summary.setExitCode(1);
        summary.setOutput("TestEngine with ID 'junit-jupiter' failed to discover tests\n"
                + "Caused by: java.lang.NoClassDefFoundError: sample/MissingType\n"
                + "    at sample.SubjectTest.<clinit>(SubjectTest.java:12)\n");

        JacocoCoverageExecutor.ensureFailureDiagnostic(summary, "sample.SubjectTest");

        assertEquals(1, summary.getDiagnostics().size());
        assertEquals("sample.SubjectTest", summary.getDiagnostics().get(0).getTestId());
        assertTrue(summary.getDiagnostics().get(0).getMessage().contains("failed to discover"));
        assertTrue(summary.getDiagnostics().get(0).getStackTrace().get(0).contains("Caused by"));
    }

    public void testDoesNotReplaceXmlRuntimeDiagnostic() {
        TestExecutionSummary summary = new TestExecutionSummary();
        summary.setExitCode(1);
        com.casc.unittestagent.model.RuntimeDiagnostic existing =
                new com.casc.unittestagent.model.RuntimeDiagnostic();
        existing.setMessage("assertion failed");
        summary.getDiagnostics().add(existing);

        JacocoCoverageExecutor.ensureFailureDiagnostic(summary, "sample.SubjectTest");

        assertEquals(1, summary.getDiagnostics().size());
        assertEquals("assertion failed", summary.getDiagnostics().get(0).getMessage());
    }

    public void testPreservesExpectedActualAndRootCauseForReflectiveAssertionFailure() {
        com.casc.unittestagent.model.RuntimeDiagnostic diagnostic =
                JacocoCoverageExecutor.runtimeDiagnostic(
                        "org.jsoup.nodes.AttributeTest#shouldHandleBoundaryValues()",
                        "org.opentest4j.AssertionFailedError",
                        "Unexpected exception type thrown, expected: <java.lang.NullPointerException> "
                                + "but was: <java.lang.reflect.InvocationTargetException>",
                        "org.opentest4j.AssertionFailedError\n"
                                + "at org.junit.jupiter.api.AssertThrows.assertThrows(AssertThrows.java:67)\n"
                                + "at org.jsoup.nodes.AttributeTest.shouldHandleBoundaryValues(AttributeTest.java:42)\n"
                                + "Caused by: java.lang.reflect.InvocationTargetException\n"
                                + "at java.base/java.lang.reflect.Method.invoke(Method.java:580)\n"
                                + "Caused by: java.lang.NullPointerException\n"
                                + "at org.jsoup.nodes.Attribute.setKey(Attribute.java:70)\n");

        assertEquals("java.lang.NullPointerException", diagnostic.getExpectedExceptionType());
        assertEquals("java.lang.reflect.InvocationTargetException", diagnostic.getActualExceptionType());
        assertEquals("java.lang.NullPointerException", diagnostic.getRootCauseType());
        assertEquals(List.of("java.lang.reflect.InvocationTargetException", "java.lang.NullPointerException"),
                diagnostic.getCauseChain());
        assertTrue(diagnostic.getStackTrace().stream().anyMatch(line ->
                line.equals("Caused by: java.lang.NullPointerException")));
        int invocationCause = diagnostic.getStackTrace().indexOf(
                "Caused by: java.lang.reflect.InvocationTargetException");
        int reflectionFrame = diagnostic.getStackTrace().indexOf(
                "at java.base/java.lang.reflect.Method.invoke(Method.java:580)");
        int rootCause = diagnostic.getStackTrace().indexOf("Caused by: java.lang.NullPointerException");
        int productionFrame = diagnostic.getStackTrace().indexOf(
                "at org.jsoup.nodes.Attribute.setKey(Attribute.java:70)");
        assertTrue(invocationCause < reflectionFrame);
        assertTrue(reflectionFrame < rootCause);
        assertTrue(rootCause < productionFrame);
    }

    public void testParsesExpectedExceptionWhenNothingWasThrown() {
        com.casc.unittestagent.model.RuntimeDiagnostic diagnostic =
                JacocoCoverageExecutor.runtimeDiagnostic(
                        "sample.ProcessServiceTest#rejectsAtConcurrentLimit()",
                        "org.opentest4j.AssertionFailedError",
                        "Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.",
                        "at sample.ProcessServiceTest.rejectsAtConcurrentLimit(ProcessServiceTest.java:31)");

        assertEquals("java.lang.IllegalStateException", diagnostic.getExpectedExceptionType());
        assertEquals("", diagnostic.getActualExceptionType());
    }

    public void testParsesNestedAngleBracketAssertionValuesWithoutCallingThemExceptionTypes() {
        String expected = "<!DOCTYPE html \"-//W3C//DTD HTML 4.01//EN\" "
                + "\"http://www.w3.org/TR/html4/strict.dtd\">";
        String actual = "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.01//EN\" "
                + "\"http://www.w3.org/TR/html4/strict.dtd\">";

        com.casc.unittestagent.model.RuntimeDiagnostic diagnostic =
                JacocoCoverageExecutor.runtimeDiagnostic(
                        "org.jsoup.nodes.DocumentTypeTest#shouldHandleBoundaryValues()",
                        "org.opentest4j.AssertionFailedError",
                        "expected: <" + expected + "> but was: <" + actual + ">",
                        "at org.jsoup.nodes.DocumentTypeTest.shouldHandleBoundaryValues(DocumentTypeTest.java:27)");

        assertEquals(expected, diagnostic.getExpectedValue());
        assertEquals(actual, diagnostic.getActualValue());
        assertEquals("", diagnostic.getExpectedExceptionType());
        assertEquals("", diagnostic.getActualExceptionType());
    }

    public void testPreservesMockitoFailureDetailsAndArrowSourceFrames() {
        String details = """
                quietAppendable.append(" ");
                Wanted 1 time:
                -> at org.jsoup.nodes.DocumentTypeTest.shouldHandleBoundaryValues(DocumentTypeTest.java:57)
                But was 2 times:
                -> at org.jsoup.nodes.DocumentType.outerHtmlHead(DocumentType.java:98)
                -> at org.jsoup.nodes.DocumentType.outerHtmlHead(DocumentType.java:100)
                """;

        com.casc.unittestagent.model.RuntimeDiagnostic diagnostic =
                JacocoCoverageExecutor.runtimeDiagnostic(
                        "org.jsoup.nodes.DocumentTypeTest#shouldHandleBoundaryValues()",
                        "org.mockito.exceptions.verification.TooManyActualInvocations",
                        "quietAppendable.append(\" \" );", details);

        assertTrue(diagnostic.getFailureDetails().contains("Wanted 1 time"));
        assertTrue(diagnostic.getFailureDetails().contains("But was 2 times"));
        assertTrue(diagnostic.getStackTrace().stream().anyMatch(frame ->
                frame.contains("DocumentTypeTest.java:57")));
    }

    public void testClassifiesPlatformEngineDiscoveryFailureAsEnvironmentError() {
        TestExecutionSummary summary = new TestExecutionSummary();
        summary.setExitCode(255);
        summary.setOutput("org.junit.platform.commons.JUnitException: "
                + "TestEngine with ID 'junit-platform-suite' failed to discover tests\n"
                + "Caused by: java.lang.NoSuchMethodError: org.junit.platform.engine.EngineDiscoveryRequest.foo()\n");
        JacocoCoverageExecutor.ensureFailureDiagnostic(summary, "sample.SubjectTest");

        assertTrue(JacocoCoverageExecutor.isExecutionEnvironmentFailure(summary));
        assertTrue(summary.getDiagnostics().get(0).getMessage().contains("Caused by"));
    }

    public void testZeroDiscoveredTestsWithoutEngineFailureIsNotEnvironmentError() {
        TestExecutionSummary summary = new TestExecutionSummary();
        summary.setExitCode(2);
        summary.setOutput("No tests were found for selector sample.SubjectTest");
        JacocoCoverageExecutor.ensureFailureDiagnostic(summary, "sample.SubjectTest");

        assertFalse(JacocoCoverageExecutor.isExecutionEnvironmentFailure(summary));
    }
}
