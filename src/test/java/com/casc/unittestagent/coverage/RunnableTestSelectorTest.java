package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import junit.framework.TestCase;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RunnableTestSelectorTest extends TestCase {
    public void testPolicyFailureInOrphanHelperIsIsolatedWithoutExecutingOrRewritingIt() {
        String source=SOURCE.replace("throw new IllegalArgumentException(\"failure\");","hidden();")
                .replace("class T {","class T { private void hidden() throws Exception { Object.class.getDeclaredField(\"unknown\"); }")
                .replace("void bad()","void bad() throws Exception");
        var result=select(source,(code,coverage)->{
            var diagnostics=CoverageCandidatePreflight.inspect(code);
            if(diagnostics.isEmpty()) return probe(code,Set.of(),coverage);
            var validation=ValidationResult.status(ValidationStatus.FAILED); validation.setDiagnostics(diagnostics);
            return new RunnableTestSelector.Probe(validation,new CoverageRunResult());
        });
        assertEquals(result.report().getMessage(),RunnableTestSelectionReport.Status.PASSED,result.report().getStatus());
        assertEquals(1,result.report().getRetainedTests().size()); assertEquals(1,result.report().getRemovedHelpers().size());
        assertFalse(result.report().getSelectedCode().contains("hidden"));
        assertTrue(result.report().getSelectedCode().contains("assertSame(fixture, fixture)"));
        assertTrue(result.finalProbe().run().getSnapshot().isAvailable());
    }
    public void testSameSizeDifferentInvocationInventoryDoesNotReplaceOriginal() {
        String code = "package sample; import org.junit.jupiter.params.ParameterizedTest; class T { @ParameterizedTest void exercise(int n) {} }";
        var result = select(code, (text, coverage) -> {
            var validation = ValidationResult.status(ValidationStatus.PASSED);
            var run = new CoverageRunResult(); var execution = run.getSnapshot().getExecution();
            execution.setExitCode(0); execution.setFound(2); execution.setSucceeded(2);
            execution.setTestCases(List.of(new TestCaseExecution("sample.T#exercise(int)[1]", true, false),
                    new TestCaseExecution("sample.T#exercise(int)[" + (coverage ? "3" : "2") + "]", true, false)));
            run.getSnapshot().setAvailable(coverage);
            return new RunnableTestSelector.Probe(validation, run);
        });
        assertEquals(RunnableTestSelectionReport.Status.BLOCKED, result.report().getStatus());
        assertEquals(code, result.report().getOriginalCode()); assertEquals("", result.report().getSelectedCode());
        assertTrue(result.report().getMessage().contains("身份或执行次数不一致"));
    }
    private static final String SOURCE = """
            package sample;
            import org.junit.jupiter.api.*;
            class T {
                Object fixture = new Object();
                @BeforeEach void setup() { fixture = new Object(); }
                @Test void good() { assertSame(fixture, fixture); }
                @Test void bad() { throw new IllegalArgumentException("failure"); }
            }
            """;

    public void testRetainsPassingBodyAndSharedFixtureAndMeasuresOnlyFinalSubset() {
        AtomicInteger measured = new AtomicInteger();
        var result = select(SOURCE, (code, coverage) -> {
            if (coverage) { measured.incrementAndGet(); assertFalse(code.contains("void bad()")); }
            return probe(code, code.contains("void bad()") ? Set.of("bad") : Set.of(), coverage);
        });
        assertEquals(RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertEquals(SOURCE, result.report().getOriginalCode());
        assertEquals(1, result.report().getRetainedTests().size());
        assertEquals(1, result.report().getExcludedTests().size()); assertEquals(1, measured.get());
        assertTrue(result.report().getSelectedCode().contains("@Test void good() { assertSame(fixture, fixture); }"));
        assertTrue(result.report().getSelectedCode().contains("@BeforeEach void setup() { fixture = new Object(); }"));
        assertTrue(result.finalProbe().run().getSnapshot().isAvailable());
    }

    public void testMethodLocalCompileFailureDoesNotAbandonOtherTests() {
        var result = select(SOURCE, (code, coverage) -> {
            if (!code.contains("void bad()")) return probe(code, Set.of(), coverage);
            var validation = ValidationResult.status(ValidationStatus.FAILED);
            var bad = StaticJavaParser.parse(code).findAll(MethodDeclaration.class).stream().filter(m -> m.getNameAsString().equals("bad")).findFirst().orElseThrow();
            validation.setDiagnostics(List.of(new CompileDiagnostic("ERROR", "CANNOT_FIND_SYMBOL", "unknown symbol",
                    bad.getBegin().orElseThrow().line, 1, "bad", true)));
            return new RunnableTestSelector.Probe(validation, new CoverageRunResult());
        });
        assertEquals(RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertTrue(result.report().getExcludedTests().get(0).reason().contains("CANNOT_FIND_SYMBOL"));
        assertFalse(result.report().getSelectedCode().contains("void bad()"));
    }

    public void testUnusedUnavailableImportIsRemovedOnlyAfterExcludingItsTest() {
        String source = SOURCE.replace("import org.junit.jupiter.api.*;", "import org.junit.jupiter.api.*;\nimport missing.Unavailable;")
                .replace("throw new IllegalArgumentException(\"failure\");", "new Unavailable();");
        var result = select(source, (code, coverage) -> {
            if (!code.contains("import missing.Unavailable;")) return probe(code, Set.of(), coverage);
            var validation = ValidationResult.status(ValidationStatus.FAILED);
            validation.setDiagnostics(List.of(new CompileDiagnostic("ERROR", "PACKAGE_NOT_FOUND", "missing", 3, 1, "import", true)));
            return new RunnableTestSelector.Probe(validation, new CoverageRunResult());
        });
        assertEquals(result.report().getMessage(), RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertTrue(result.report().getSelectedCode().contains("void good()"));
        assertFalse(result.report().getSelectedCode().contains("import missing.Unavailable;"));
        assertEquals(source, result.report().getOriginalCode());
    }

    public void testParameterizedInvocationFailureRemovesWholeMethodGroup() {
        String source = SOURCE.replace("@Test void bad()", "@org.junit.jupiter.params.ParameterizedTest void bad(String value)");
        var result = select(source, (code, coverage) -> {
            var probe = probe(code, code.contains("void bad(") ? Set.of("bad") : Set.of(), coverage);
            if (code.contains("void bad(")) {
                var execution = probe.run().getSnapshot().getExecution();
                execution.setTestCases(List.of(new TestCaseExecution("sample.T#good()", true, false),
                        new TestCaseExecution("sample.T#bad(String)[1]", true, false),
                        new TestCaseExecution("sample.T#bad(String)[2]", false, false)));
            }
            return probe;
        });
        assertEquals(RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertFalse(result.report().getSelectedCode().contains("void bad("));
        assertEquals("sample.T#bad(String)", result.report().getExcludedTests().get(0).testId());
    }

    public void testExplicitLifecycleAndTestAnnotationImportsRemainOnSurvivingMethods() {
        String source = SOURCE.replace("import org.junit.jupiter.api.*;", "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.BeforeEach;");
        var result = select(source, (code, coverage) -> probe(code,
                code.contains("void bad()") ? Set.of("bad") : Set.of(), coverage));
        assertEquals(RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertTrue(result.report().getSelectedCode().contains("import org.junit.jupiter.api.Test;"));
        assertTrue(result.report().getSelectedCode().contains("import org.junit.jupiter.api.BeforeEach;"));
    }

    public void testUnknownCollectiveFailureDoesNotPublishIndividuallyPassingTestsWithoutRegression() {
        var result = select(SOURCE, (code, coverage) -> {
            var probe = probe(code, Set.of(), false);
            if (code.contains("void good()") && code.contains("void bad()")) probe.run().getSnapshot().getExecution().setExitCode(1);
            return probe;
        });
        assertEquals(RunnableTestSelectionReport.Status.BLOCKED, result.report().getStatus());
        assertTrue(result.report().getSelectedCode().isBlank()); assertNull(result.finalProbe());
        assertEquals(SOURCE, result.report().getOriginalCode());
    }

    public void testFailureUnderCoverageRequiresAnotherFullRegression() {
        var result = select(SOURCE, (code, coverage) -> probe(code,
                coverage && code.contains("void bad()") ? Set.of("bad") : Set.of(), coverage));
        assertEquals(RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertFalse(result.report().getSelectedCode().contains("void bad()"));
        assertEquals(4, result.report().getExecutions());
    }

    public void testEnvironmentAndSharedCompilationFailuresDoNotEraseWholeSuite() {
        var environment = select(SOURCE, (code, coverage) -> {
            var probe = probe(code, Set.of(), false); probe.run().setEnvironmentError(true); return probe;
        });
        assertEquals(RunnableTestSelectionReport.Status.BLOCKED, environment.report().getStatus());
        assertTrue(environment.report().getExcludedTests().isEmpty());
        var shared = select(SOURCE, (code, coverage) -> {
            var validation = ValidationResult.status(ValidationStatus.FAILED);
            validation.setDiagnostics(List.of(new CompileDiagnostic("ERROR", "CANNOT_FIND_SYMBOL", "fixture", 4, 1, "fixture", true)));
            return new RunnableTestSelector.Probe(validation, new CoverageRunResult());
        });
        assertEquals(RunnableTestSelectionReport.Status.NO_RUNNABLE_TESTS, shared.report().getStatus());
        assertTrue(shared.report().getSelectedCode().isBlank());
    }

    public void testVerifiedRuntimeSubsetRemainsUsableWhenCoverageAgentIsUnavailable() {
        var result = select(SOURCE, (code, coverage) -> {
            if (!coverage) return probe(code, code.contains("void bad()") ? Set.of("bad") : Set.of(), false);
            var unavailable = new CoverageRunResult(); unavailable.setEnvironmentError(true);
            unavailable.getSnapshot().setMessage("coverage agent unavailable");
            return new RunnableTestSelector.Probe(ValidationResult.status(ValidationStatus.PASSED), unavailable);
        });
        assertEquals(RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertTrue(result.report().getMessage().contains("覆盖率采集不可用"));
        assertFalse(result.finalProbe().run().getSnapshot().isAvailable());
        assertTrue(RunnableTestSelector.passed(result.finalProbe().run().getSnapshot().getExecution()));
        assertFalse(result.report().getSelectedCode().contains("void bad()"));
    }

    public void testNoPassingTestsAndCancellationKeepOriginalWithoutFakeCoverage() {
        var empty = select(SOURCE, (code, coverage) -> probe(code, Set.of("good", "bad"), false));
        assertEquals(RunnableTestSelectionReport.Status.NO_RUNNABLE_TESTS, empty.report().getStatus());
        assertEquals(SOURCE, empty.report().getOriginalCode()); assertNull(empty.finalProbe());
        var cancelled = select(SOURCE, (code, coverage) -> {
            var probe = probe(code, Set.of(), false); probe.run().setCancelled(true); return probe;
        });
        assertEquals(RunnableTestSelectionReport.Status.CANCELLED, cancelled.report().getStatus());
        assertTrue(cancelled.report().getSelectedCode().isBlank());
    }

    public void testExistingTestsOutsideGeneratedRegionAreProtected() {
        String source = SOURCE.replace("    @Test void good()", "    // region UnitMind generated tests for subject\n    @Test void good()")
                .replace("    @Test void bad()", "    // endregion\n    @Test void bad()");
        var result = select(source, (code, coverage) -> probe(code, Set.of("bad"), false));
        assertEquals(RunnableTestSelectionReport.Status.BLOCKED, result.report().getStatus());
        assertTrue(result.report().getMessage().contains("既有测试"));
        assertTrue(result.report().getSelectedCode().isBlank());
    }

    public void testMatcherOriginIsRemovedBeforeRetryingAffectedInnocentTest() {
        int originLine = 7;
        var result = select(SOURCE, (code, coverage) -> {
            var probe = probe(code, code.contains("void bad()") ? Set.of("good", "bad") : Set.of(), coverage);
            if (code.contains("void bad()")) {
                var diagnostic = new RuntimeDiagnostic(); diagnostic.setTestId("sample.T#good()");
                diagnostic.setExceptionType("org.mockito.exceptions.misusing.InvalidUseOfMatchersException");
                diagnostic.setMessage("Misplaced or misused argument matcher detected here:\n-> at sample.T.bad(T.java:" + originLine + ")");
                probe.run().getSnapshot().getExecution().setDiagnostics(List.of(diagnostic));
            }
            return probe;
        });
        assertEquals(result.report().getMessage(), RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertEquals(1, result.report().getExcludedTests().size());
        assertTrue(result.report().getSelectedCode().contains("void good()"));
        assertFalse(result.report().getSelectedCode().contains("void bad()"));
    }

    public void testUndiscoveredMethodsCannotBorrowAnotherMethodsPassingExecution() {
        var result = select(SOURCE, (code, coverage) -> {
            var resultProbe = probe(code, Set.of(), coverage);
            var execution = resultProbe.run().getSnapshot().getExecution();
            execution.setTestCases(List.of(new TestCaseExecution("sample.T#good()", true, false)));
            execution.setFound(1); execution.setSucceeded(1);
            return resultProbe;
        });
        assertEquals(result.report().getMessage(), RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertEquals(1, result.report().getRetainedTests().size());
        assertFalse(result.report().getSelectedCode().contains("void bad()"));
        assertTrue(result.report().getExcludedTests().get(0).reason().contains("未发现"));
    }

    public void testOverloadedTestEvidenceBindsToParameterSignature() {
        String source = "package sample; import org.junit.jupiter.api.Test; class T { @Test void good() {} @Test void good(int value) {} }";
        var result = select(source, (code, coverage) -> {
            var run = new CoverageRunResult(); var execution = new TestExecutionSummary();
            var cases = new ArrayList<TestCaseExecution>(); cases.add(new TestCaseExecution("sample.T#good()", true, false));
            boolean broken = code.contains("void good(int");
            if (broken) cases.add(new TestCaseExecution("sample.T#good(int)[1]", false, false));
            execution.setTestCases(cases); execution.setFound(cases.size()); execution.setSucceeded(1);
            execution.setFailed(broken ? 1 : 0); execution.setExitCode(broken ? 1 : 0);
            run.getSnapshot().setExecution(execution); run.getSnapshot().setAvailable(coverage && !broken);
            return new RunnableTestSelector.Probe(ValidationResult.status(ValidationStatus.PASSED), run);
        });
        assertEquals(result.report().getMessage(), RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertEquals("sample.T#good(int)", result.report().getExcludedTests().get(0).testId());
        assertTrue(result.report().getSelectedCode().contains("void good()"));
    }

    public void testRegionTextInsideStringDoesNotTurnInputTestsIntoProtectedExistingMethods() {
        String code = SOURCE.replace("Object fixture = new Object();", "Object fixture = new Object(); String text = \"// region UnitMind generated tests for fake\";");
        var result = select(code, (candidate, coverage) -> probe(candidate,
                candidate.contains("void bad()") ? Set.of("bad") : Set.of(), coverage));
        assertEquals(result.report().getMessage(), RunnableTestSelectionReport.Status.PASSED, result.report().getStatus());
        assertEquals(1, result.report().getExcludedTests().size());
    }

    public void testUnclosedGeneratedRegionNeverAuthorizesRemovingHumanTests() {
        String code = SOURCE.replace("    @Test void good()", "    // region UnitMind generated tests for subject\n    @Test void good()");
        var result = select(code, (candidate, coverage) -> { throw new AssertionError("must reject before execution"); });
        assertEquals(RunnableTestSelectionReport.Status.BLOCKED, result.report().getStatus());
        assertTrue(result.report().getSelectedCode().isBlank());
        assertTrue(result.report().getMessage().contains("人工测试边界"));
    }

    private static RunnableTestSelector.Result select(String code, RunnableTestSelector.Runner runner) {
        return new RunnableTestSelector().select(code, runner, () -> false, CoverageProgressListener.NONE);
    }
    private static RunnableTestSelector.Probe probe(String code, Set<String> failed, boolean coverage) {
        var run = new CoverageRunResult(); var execution = new TestExecutionSummary(); var cases = new ArrayList<TestCaseExecution>();
        for (MethodDeclaration method : StaticJavaParser.parse(code).findAll(MethodDeclaration.class)) {
            if (!Set.of("good", "bad").contains(method.getNameAsString())) continue;
            cases.add(new TestCaseExecution("sample.T#" + method.getNameAsString() + "()", !failed.contains(method.getNameAsString()), false));
        }
        execution.setTestCases(cases); execution.setFound(cases.size());
        execution.setFailed((int) cases.stream().filter(t -> !t.isSuccessful()).count());
        execution.setSucceeded(cases.size() - execution.getFailed()); execution.setExitCode(execution.getFailed() == 0 ? 0 : 1);
        run.getSnapshot().setExecution(execution); run.getSnapshot().setAvailable(coverage && execution.isSuccessful());
        run.getSnapshot().setMessage(execution.isSuccessful() ? "passed" : "failed");
        return new RunnableTestSelector.Probe(ValidationResult.status(ValidationStatus.PASSED), run);
    }
}
