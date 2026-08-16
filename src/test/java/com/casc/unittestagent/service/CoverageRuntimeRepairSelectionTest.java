package com.casc.unittestagent.service;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestCaseExecution;
import com.casc.unittestagent.model.TestExecutionSummary;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CoverageRuntimeRepairSelectionTest extends TestCase {
    public void testKeepsDistinctRuntimeFailuresEditableInSameExecution() {
        RuntimeDiagnostic first = diagnostic("sample.SubjectTest#firstFailure()", "first");
        RuntimeDiagnostic second = diagnostic("sample.SubjectTest#secondFailure()", "second");
        List<RuntimeDiagnostic> focused = new ArrayList<>();

        Set<String> editableTests = UnitTestGenerationService.rememberRuntimeDiagnostics(
                focused, List.of(first, second));

        assertEquals(2, focused.size());
        assertSame(first, focused.get(0));
        assertSame(second, focused.get(1));
        assertEquals(Set.of("firstFailure", "secondFailure"), editableTests);
    }

    public void testNormalizesParameterizedTestIdsForRepairScope() {
        RuntimeDiagnostic diagnostic = diagnostic(
                "sample.SubjectTest#parameterizedFailure(java.lang.String)[2]", "failure");

        assertEquals(Set.of("parameterizedFailure"),
                UnitTestGenerationService.failingTestNames(List.of(diagnostic)));
    }

    public void testGroupsSeveralFailuresWhenTheyShareOneRootCause() {
        RuntimeDiagnostic first = diagnostic("sample.SubjectTest#firstFailure()", "shared root");
        RuntimeDiagnostic second = diagnostic("sample.SubjectTest#secondFailure()", "shared root");
        first.setCategory("NULL_FIXTURE_FAILURE");
        second.setCategory("NULL_FIXTURE_FAILURE");
        first.setRootCauseType("java.lang.NullPointerException");
        second.setRootCauseType("java.lang.NullPointerException");
        List<RuntimeDiagnostic> focused = new ArrayList<>();

        Set<String> editableTests = UnitTestGenerationService.rememberRuntimeDiagnostics(
                focused, List.of(first, second));

        assertEquals(2, focused.size());
        assertEquals(Set.of("firstFailure", "secondFailure"), editableTests);
    }

    public void testDerivesEditableAndProtectedScopesFromRealExecutionResults() {
        TestExecutionSummary summary = new TestExecutionSummary();
        summary.setTestCases(List.of(
                new TestCaseExecution("sample.SubjectTest#passing()", true, false),
                new TestCaseExecution("sample.SubjectTest#firstFailure()", false, false),
                new TestCaseExecution("sample.SubjectTest#secondFailure(java.lang.String)[2]", false, false),
                new TestCaseExecution("sample.SubjectTest#skipped()", false, true)));

        assertEquals(Set.of("firstFailure", "secondFailure"),
                UnitTestGenerationService.failingTestNames(summary));
        assertEquals(Set.of("passing"), UnitTestGenerationService.passedTestNames(summary));
    }

    public void testSemanticFingerprintIgnoresFormattingButDetectsBehaviorChange() {
        String compact = "class SubjectTest { @Test void works(){ run(1); } }";
        String formatted = """
                class SubjectTest {
                    @Test
                    void works() {
                        run(1);
                    }
                }
                """;
        String changed = "class SubjectTest { @Test void works(){ run(2); } }";

        assertEquals(UnitTestGenerationService.testCodeFingerprint(compact),
                UnitTestGenerationService.testCodeFingerprint(formatted));
        assertFalse(UnitTestGenerationService.testCodeFingerprint(compact)
                .equals(UnitTestGenerationService.testCodeFingerprint(changed)));
    }

    public void testRejectedRepairFeedbackNamesTheMethodsActuallyChanged() {
        String current = "class SubjectTest { void passing(){ run(1); } void failing(){ run(-1); } }";
        String proposed = "class SubjectTest { void passing(){ run(999); } void failing(){ run(-1); } }";

        String summary = UnitTestGenerationService.semanticEditSummary(current, proposed);

        assertTrue(summary.contains("CHANGED passing()"));
        assertFalse(summary.contains("failing()"));
    }

    public void testRuntimeFailureSignatureIgnoresMessagesAndLineNumbers() {
        RuntimeDiagnostic first = diagnostic("sample.SubjectTest#failure()", "message one");
        first.setCategory("ASSERTION_VALUE_MISMATCH");
        first.setExpectedValue("true");
        first.setActualValue("false");
        first.setProductionFrame("at sample.Subject.work(Subject.java:10)");
        RuntimeDiagnostic second = diagnostic("sample.SubjectTest#failure()", "different custom message");
        second.setCategory("ASSERTION_VALUE_MISMATCH");
        second.setExpectedValue("true");
        second.setActualValue("false");
        second.setProductionFrame("at sample.Subject.work(Subject.java:99)");

        assertEquals(UnitTestGenerationService.runtimeFailureSignature(List.of(first)),
                UnitTestGenerationService.runtimeFailureSignature(List.of(second)));
        second.setActualValue("true");
        assertFalse(UnitTestGenerationService.runtimeFailureSignature(List.of(first))
                .equals(UnitTestGenerationService.runtimeFailureSignature(List.of(second))));
    }

    private RuntimeDiagnostic diagnostic(String testId, String message) {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId(testId);
        diagnostic.setExceptionType("java.lang.AssertionError");
        diagnostic.setMessage(message);
        return diagnostic;
    }
}
