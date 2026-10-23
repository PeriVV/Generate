package com.casc.unittestagent.validation;

import com.casc.unittestagent.model.*;
import junit.framework.TestCase;
import java.util.*;

public class JUnitExecutionEvidenceTest extends TestCase {
    private static final String CODE = "import org.junit.jupiter.api.Test; class T { @Test void exercise() {} }";

    public void testAggregateSuccessWithoutTestRecordsCannotAuthorizeWrite() {
        var run = passing("T#exercise()"); run.getTestCases().clear();
        JUnitExecutionEvidence.requireDeclaredTests(CODE, run);
        assertTrue(run.isSuccessful()); assertEquals(0, run.getExitCode());
        assertFalse(run.isFullySuccessful()); assertFalse(run.getEvidenceFailures().isEmpty());
        var report = ClassJUnitRepairReport.status(ClassJUnitStatus.PASSED);
        report.setCodeHash("test"); report.setSourceHash("source"); report.setFinalExecution(run);
        assertFalse(report.isPassedFor("test", "source"));
    }
    public void testUndiscoveredMethodRejectsEvidenceWithoutInventingProcessFailure() {
        var run = passing("T#exercise()");
        JUnitExecutionEvidence.requireDeclaredTests(CODE.replace("} }", "} @Test private void ignored() {} }"), run);
        assertEquals(0, run.getExitCode()); assertTrue(run.isSuccessful()); assertFalse(run.isFullySuccessful());
        assertEquals("JUnitUndiscoveredTests", run.getDiagnostics().get(0).getExceptionType());
    }
    public void testRecordFailureCannotHideBehindGreenCounters() {
        var run = passing("T#exercise()"); run.getTestCases().get(0).setSuccessful(false);
        assertTrue(run.isSuccessful()); assertFalse(run.isFullySuccessful());
    }
    public void testEqualTotalsDoNotProveSameInvocationInventory() {
        var baseline = passing("T#exercise()[1]", "T#exercise()[2]");
        var measured = passing("T#exercise()[1]", "T#exercise()[3]");
        assertFalse(JUnitExecutionEvidence.sameInventory(baseline, measured));
        JUnitExecutionEvidence.requireSameInventory(baseline, measured);
        assertEquals(0, measured.getExitCode()); assertFalse(measured.isFullySuccessful());
        assertEquals("JUnitInventoryChanged", measured.getDiagnostics().get(0).getExceptionType());
    }
    public void testInvocationMultiplicityMattersButOrderingDoesNot() {
        var baseline = passing("T#exercise()[1]", "T#exercise()[1]", "T#exercise()[2]");
        assertTrue(JUnitExecutionEvidence.sameInventory(baseline,
                passing("T#exercise()[2]", "T#exercise()[1]", "T#exercise()[1]")));
        assertFalse(JUnitExecutionEvidence.sameInventory(baseline,
                passing("T#exercise()[2]", "T#exercise()[2]", "T#exercise()[1]")));
    }
    public void testInventoryBindingHandlesNestedTypesAndOverloads() {
        String code = "import org.junit.jupiter.api.Test; class T { class Nested { @Test void exercise(String s) {} @Test void exercise(int s) {} } }";
        var missing = JUnitExecutionEvidence.unexecutedMethods(code, passing("T$Nested#exercise(java.lang.String)"));
        assertEquals(Set.of("T.Nested#exercise(int)"), missing.keySet());
    }
    private static TestExecutionSummary passing(String... ids) {
        var run = new TestExecutionSummary(); run.setFound(ids.length); run.setSucceeded(ids.length); run.setExitCode(0);
        run.setTestCases(new ArrayList<>(Arrays.stream(ids).map(id -> new TestCaseExecution(id, true, false)).toList()));
        return run;
    }
}
