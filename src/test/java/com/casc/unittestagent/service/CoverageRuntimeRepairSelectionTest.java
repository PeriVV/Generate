package com.casc.unittestagent.service;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.OracleDecision;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import com.casc.unittestagent.llm.LlmGenerationRequest;
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

    public void testOracleAuthorityReferenceMustResolveToRealContextEvidence() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        ProjectSymbolSnapshot snapshot = new ProjectSymbolSnapshot();
        snapshot.setContractFacts(List.of("CONTRACT: R-17 says value must be 2"));
        request.setSymbolSnapshot(snapshot);
        OracleAssessment assessment = new OracleAssessment();
        assessment.setDisposition(RuntimeRepairDisposition.REPAIR_TEST);
        assessment.setDecision(OracleDecision.CORRECT);
        assessment.setAuthority(OracleAuthority.CONTRACT_FACT);
        assessment.setAuthorityRef("CONTRACT_FACT:0");

        assertTrue(UnitTestGenerationService.oracleAuthorityReferenceSupported(request, assessment));

        assessment.setAuthorityRef("CONTRACT_FACT:9");
        assertFalse(UnitTestGenerationService.oracleAuthorityReferenceSupported(request, assessment));
        assessment.setAuthority(OracleAuthority.PRODUCTION_SOURCE);
        assessment.setAuthorityRef("PRODUCTION_SOURCE:0");
        assertFalse(UnitTestGenerationService.oracleAuthorityReferenceSupported(request, assessment));

        assessment.setAuthority(OracleAuthority.CONTRACT_FACT);
        assessment.setAuthorityRef("CONTRACT_FACT:0");
        snapshot.setContractFacts(List.of("TARGET_SIGNATURE: value() -> int"));
        assertFalse(UnitTestGenerationService.oracleAuthorityReferenceSupported(request, assessment));
    }

    public void testAssertionMismatchIsOracleSensitiveButReflectionWrapperIsMechanical() {
        RuntimeDiagnostic mismatch = diagnostic("SubjectTest#value()", "expected 2 but was 1");
        mismatch.setCategory("ASSERTION_VALUE_MISMATCH");
        assertTrue(UnitTestGenerationService.hasOracleSensitiveFailure(List.of(mismatch)));

        RuntimeDiagnostic wrapper = diagnostic("SubjectTest#throwsExpected()", "wrapped");
        wrapper.setCategory("ASSERT_THROWS_WRAPPED_BY_REFLECTION");
        assertFalse(UnitTestGenerationService.hasOracleSensitiveFailure(List.of(wrapper)));

        RuntimeDiagnostic fixture = diagnostic("SubjectTest#works()", "npe");
        fixture.setCategory("NULL_FIXTURE_FAILURE");
        assertFalse(UnitTestGenerationService.hasOracleSensitiveFailure(List.of(fixture)));

        RuntimeDiagnostic wrongLogger = diagnostic("SubjectTest#logs()", "wrong logger instance");
        wrongLogger.setCategory("MOCKITO_VERIFY_WRONG_INSTANCE");
        assertFalse(UnitTestGenerationService.hasOracleSensitiveFailure(List.of(wrongLogger)));
    }

    public void testOnlyDeterministicallyProvenFixtureRepairCanBypassOracleSensitiveHistory() {
        OracleAssessment assessment = new OracleAssessment();
        assessment.setRootCauseCategory("FIXTURE_DEPENDENCY_MISSING");
        assessment.setDecision(OracleDecision.KEEP);
        assessment.setAuthority(OracleAuthority.NONE);

        RuntimeDiagnostic fixture = diagnostic("SubjectTest#works()", "dependency is null");
        fixture.setCategory("FIXTURE_DEPENDENCY_MISSING");
        assertTrue(UnitTestGenerationService.isProvenFixtureRepair(
                assessment, List.of(fixture)));

        RuntimeDiagnostic assertion = diagnostic("SubjectTest#contract()", "expected 2 but was 1");
        assertion.setCategory("ASSERTION_VALUE_MISMATCH");
        assertFalse(UnitTestGenerationService.isProvenFixtureRepair(
                assessment, List.of(fixture, assertion)));

        assessment.setRootCauseCategory("FIXTURE_DEPENDENCY_MISSING_BY_GUESS");
        assertFalse(UnitTestGenerationService.isProvenFixtureRepair(
                assessment, List.of(fixture)));
    }

    public void testUnsupportedFixtureClaimRestoresObservedExpectedExceptionCategory() {
        OracleAssessment assessment = new OracleAssessment();
        assessment.setRootCauseCategory("FIXTURE_DEPENDENCY_MISSING");
        assessment.setDisposition(RuntimeRepairDisposition.REPAIR_TEST);
        RuntimeDiagnostic diagnostic = diagnostic("SubjectTest#rejectsAtLimit()",
                "Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.");
        diagnostic.setCategory("ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN");

        assertTrue(UnitTestGenerationService.normalizeUnsupportedFixtureClassification(
                assessment, List.of(diagnostic)));
        assertEquals("ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN", assessment.getRootCauseCategory());
        assertEquals(RuntimeRepairDisposition.INCONCLUSIVE, assessment.getDisposition());
        assertTrue(assessment.getEvidence().stream().anyMatch(item -> item.contains("已拒绝模型")));
        assertTrue(UnitTestGenerationService.shouldAnalyzePossibleSourceFailure(assessment));
    }

    public void testFocusesMechanicalFailureBeforeIndependentOracleConflict() {
        RuntimeDiagnostic ambiguous = diagnostic("SubjectTest#contractFailure()", "expected 2 but was 1");
        ambiguous.setCategory("ASSERTION_VALUE_MISMATCH");
        RuntimeDiagnostic mechanical = diagnostic("SubjectTest#returnsEarly()", "No interactions wanted here");
        mechanical.setCategory("MOCKITO_NO_INTERACTIONS_CONTRADICTION");

        List<RuntimeDiagnostic> focused = UnitTestGenerationService.focusRuntimeDiagnostics(
                List.of(ambiguous, mechanical));

        assertEquals(1, focused.size());
        assertTrue(focused.get(0).getTestId().contains("returnsEarly"));
    }

    public void testNoInteractionsGateExceptionAllowsOnlyDeterministicRewrite() {
        String current = """
                import org.junit.jupiter.api.Test;
                import static org.mockito.Mockito.*;
                class SubjectTest {
                    @Test void returnsEarly() {
                        verify(dao).findById("a");
                        verifyNoInteractions(dao);
                    }
                }
                """;
        String proposed = current.replace("verifyNoInteractions(dao)",
                "verifyNoMoreInteractions(dao)");
        RuntimeDiagnostic diagnostic = diagnostic("SubjectTest#returnsEarly()", "No interactions wanted");
        diagnostic.setCategory("MOCKITO_NO_INTERACTIONS_CONTRADICTION");

        assertTrue(UnitTestGenerationService.isProvenNoInteractionsContradictionRepair(
                current, proposed, List.of(diagnostic)));
        assertFalse(UnitTestGenerationService.isProvenNoInteractionsContradictionRepair(
                current, proposed.replace("findById(\"a\")", "findById(\"b\")"),
                List.of(diagnostic)));
    }

    private RuntimeDiagnostic diagnostic(String testId, String message) {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId(testId);
        diagnostic.setExceptionType("java.lang.AssertionError");
        diagnostic.setMessage(message);
        return diagnostic;
    }
}
