package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import junit.framework.TestCase;
import java.nio.file.*;
import java.util.*;

/** Real bytecode + fresh JVM + agent. Exercises ownership, not mocked JaCoCo counters. */
public class CoverageMeasurementIntegrityTest extends TestCase {
    private static final String SUBJECT = """
            package sample;
            import java.util.function.IntUnaryOperator;
            public class Subject {
                public IntUnaryOperator target() {
                    return value -> {
                        if (value > 0) return 1;
                        return -1;
                    };
                }
                public int unrelated(boolean flag) { return flag ? 10 : 20; }
            }
            """;

    public void testUninvokedCallbackHasMissedLinesAndBranchesEvenWhenOuterMethodCompletes() throws Exception {
        var run = measure(SUBJECT, "new Subject().target();");
        assertEquals(2, run.getSnapshot().getMeasuredMethods().size());
        assertEquals("SELECTED_METHOD_WITH_LAMBDAS", run.getSnapshot().getMeasurementScope());
        assertTrue(run.getSnapshot().getLine().getMissed() > 0);
        assertEquals(0, run.getSnapshot().getBranch().getCovered());
        assertEquals(2, run.getSnapshot().getBranch().getTotal());
        assertFalse(CoverageLoopPolicy.perfect(run.getSnapshot()));
        assertFalse(CoverageLoopPolicy.reached(run.getSnapshot(), new CoverageConfig()));
    }
    public void testExecutedLambdaCountsBothPathsWithoutIncludingUnrelatedClassMethod() throws Exception {
        var run = measure(SUBJECT, "new Subject().target().applyAsInt(1); new Subject().target().applyAsInt(-1);");
        assertEquals(2, run.getSnapshot().getBranch().getCovered());
        assertEquals(2, run.getSnapshot().getBranch().getTotal());
        assertTrue(CoverageLoopPolicy.perfect(run.getSnapshot()));
        assertTrue(run.getClassSnapshot().getBranch().getMissed() > 0);
    }
    public void testSerializableAndNestedLambdasAreOwnedTransitively() throws Exception {
        String source = """
                package sample;
                import java.util.function.*;
                public class Subject {
                    public Supplier<IntUnaryOperator> target() {
                        return (Supplier<IntUnaryOperator> & java.io.Serializable) () -> value -> {
                            if (value > 0) return 1;
                            return -1;
                        };
                    }
                }
                """;
        var run = measure(source, "new Subject().target().get().applyAsInt(1);");
        assertEquals(3, run.getSnapshot().getMeasuredMethods().size());
        assertEquals(1, run.getSnapshot().getBranch().getCovered());
        assertEquals(2, run.getSnapshot().getBranch().getTotal());
    }
    public void testMethodReferenceKeepsOrdinaryHelperSeparate() throws Exception {
        var run = measure(SUBJECT.replace("return value -> {\n            if (value > 0) return 1;\n            return -1;\n        };", "return this::helper;")
                .replace("public int unrelated", "private int helper(int n) { return n > 0 ? 1 : -1; }\n    public int unrelated"),
                "new Subject().target().applyAsInt(1);");
        // The assertion also checks the replacement, rather than accidentally testing the lambda fixture.
        assertEquals(1, run.getSnapshot().getMeasuredMethods().size());
        assertFalse(run.getSnapshot().getBranch().isApplicable());
    }
    public void testSkippedTestsCannotProduceFullCoverageProof() throws Exception {
        try (var artifact = compile(SUBJECT, "new Subject().target();", "@org.junit.jupiter.api.Disabled")) {
            var run = new JacocoCoverageExecutor().execute(artifact, model(SUBJECT), SUBJECT, new CoverageConfig(), () -> false);
            assertFalse(run.getSnapshot().isAvailable());
            assertEquals(1, run.getSnapshot().getExecution().getSkipped());
        }
    }
    public void testUnknownMethodCannotBorrowOtherClassLines() throws Exception {
        try (var artifact = compile(SUBJECT, "new Subject().target();", "")) {
            var method = model(SUBJECT); method.setMethodName("absent");
            var run = new JacocoCoverageExecutor().execute(artifact, method, SUBJECT, new CoverageConfig(), () -> false);
            assertFalse(run.getSnapshot().isAvailable()); assertTrue(run.isEnvironmentError());
        }
    }
    public void testRepeatedArtifactDoesNotReusePreviousJUnitXmlOrExecutionData() throws Exception {
        try (var artifact = compile(SUBJECT, "new Subject().target().applyAsInt(1);", "")) {
            var executor = new JacocoCoverageExecutor();
            assertTrue(executor.execute(artifact, model(SUBJECT), SUBJECT, new CoverageConfig(), () -> false).getSnapshot().isAvailable());
            // Fail JVM startup after a successful run while deliberately retaining the old artifact root.
            var broken = new CompiledTestArtifact(artifact.getRoot(), artifact.getClasses(), artifact.getTestClassName(),
                    artifact.getTargetClassName(), artifact.getJavaExecutable(), List.of(), List.of(), false, null,
                    TestRuntimeConfiguration.defaults(null).withJvmArguments(List.of("-agentlib:unitmind_missing_agent")));
            var second = executor.execute(broken, model(SUBJECT), SUBJECT, new CoverageConfig(), () -> false);
            assertFalse(second.getSnapshot().isAvailable());
            assertEquals(0, second.getSnapshot().getExecution().getFound());
            executor.executeTestsOnly(artifact, new CoverageConfig(), () -> false);
            assertEquals(0, executor.executeTestsOnly(broken, new CoverageConfig(), () -> false).getSnapshot().getExecution().getFound());
        }
    }
    public void testEditedSourceCannotReuseMethodRangesFromEarlierAnalysis() throws Exception {
        String changed = SUBJECT.replace("if (value > 0)", "if (value > 1)");
        try (var artifact = compile(changed, "new Subject().target();", "")) {
            var run = new JacocoCoverageExecutor().execute(artifact, model(SUBJECT), changed, new CoverageConfig(), () -> false);
            assertFalse(run.getSnapshot().isAvailable());
            assertTrue(run.getSnapshot().getMessage().contains("源码版本不一致"));
        }
    }
    public void testOverloadsOnSameLineDoNotShareCoverageOrLambdaBodies() throws Exception {
        String source = "package sample; public class Subject { public int target() { return 1; } public int target(int v) { return v > 0 ? 1 : 0; } }";
        var run = measure(source, "new Subject().target();");
        assertEquals(1, run.getSnapshot().getMeasuredMethods().size());
        assertFalse(run.getSnapshot().getBranch().isApplicable());
        assertEquals(1, run.getSnapshot().getLine().getTotal());
    }
    public void testAmbiguousLegacyScopeNeverSubstitutesWholeClassCounters() throws Exception {
        String source = "package sample; public class Subject { public int target() { return 1; } public int target(int v) { return v > 0 ? 1 : 0; } }";
        try (var artifact = compile(source, "new Subject().target();", "")) {
            var method = model(source); method.setMethodId(null);
            var run = new JacocoCoverageExecutor().execute(artifact, method, source, new CoverageConfig(), () -> false);
            assertFalse(run.getSnapshot().isAvailable());
            assertTrue(run.getSnapshot().getMessage().contains("无法唯一确定"));
        }
    }
    public void testNoExecutableLinesNeverSatisfiesThresholdOrPerfectCoverage() {
        var snapshot = new CoverageSnapshot(); snapshot.setAvailable(true);
        assertFalse(CoverageLoopPolicy.reached(snapshot, new CoverageConfig()));
        assertFalse(CoverageLoopPolicy.perfect(snapshot));
    }
    public void testChangedBytecodeAfterExecutionCannotProduceCoverageForAnotherVersion() throws Exception {
        String changed = SUBJECT.replace("if (value > 0)", "if (value > 9)");
        try (var replacement = compile(changed, "new Subject().target();", "")) {
            String replacementPath = replacement.getClasses().resolve("sample/Subject.class").toString()
                    .replace("\\", "\\\\").replace("\"", "\\\"");
            String body = "new Subject().target().applyAsInt(1); try { java.nio.file.Files.copy(java.nio.file.Path.of(\""
                    + replacementPath + "\"), java.nio.file.Path.of(Subject.class.getProtectionDomain().getCodeSource().getLocation().toURI())"
                    + ".resolve(\"sample/Subject.class\"), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }"
                    + " catch (Exception ex) { throw new RuntimeException(ex); }";
            try (var artifact = compile(SUBJECT, body, "")) {
                var run = new JacocoCoverageExecutor().execute(artifact, model(SUBJECT), SUBJECT, new CoverageConfig(), () -> false);
                assertTrue(run.getSnapshot().getExecution().isFullySuccessful());
                assertFalse(run.getSnapshot().isAvailable()); assertFalse(run.getClassSnapshot().isAvailable());
                assertTrue(run.getSnapshot().getMessage(), run.getSnapshot().getMessage().contains("字节码版本不一致"));
            }
        }
    }
    private CoverageRunResult measure(String source, String body) throws Exception {
        try (var artifact = compile(source, body, "")) {
            var run = new JacocoCoverageExecutor().execute(artifact, model(source), source, new CoverageConfig(), () -> false);
            assertTrue(run.getSnapshot().getMessage() + run.getSnapshot().getExecution().getOutput(), run.getSnapshot().isAvailable());
            return run;
        }
    }
    private MethodModel model(String source) { return new JavaSourceAnalyzer().analyze(source, "target"); }
    private CompiledTestArtifact compile(String source, String body, String annotation) throws Exception {
        Path root = Files.createTempDirectory("unitmind-measurement-integrity-");
        Path sources = Files.createDirectories(root.resolve("source/sample")), classes = Files.createDirectories(root.resolve("classes"));
        Path sut = sources.resolve("Subject.java"), tests = sources.resolve("SubjectTest.java");
        Files.writeString(sut, source);
        Files.writeString(tests, "package sample; class SubjectTest { " + annotation
                + " @org.junit.jupiter.api.Test void exercise() { " + body + " } }");
        var compiler = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/javac").toString(), "-g",
                "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), sut.toString(), tests.toString()).redirectErrorStream(true).start();
        String output = new String(compiler.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output, 0, compiler.waitFor());
        return new CompiledTestArtifact(root, classes, "sample.SubjectTest", "sample.Subject",
                Path.of(System.getProperty("java.home"), "bin/java").toString(), List.of(), List.of());
    }
}
