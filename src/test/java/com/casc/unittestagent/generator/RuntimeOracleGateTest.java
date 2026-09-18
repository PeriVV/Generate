package com.casc.unittestagent.generator;

import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.OracleAssessment;
import junit.framework.TestCase;

import java.util.List;
import java.util.Set;

public class RuntimeOracleGateTest extends TestCase {
    private final RuntimeOracleGate gate = new RuntimeOracleGate();

    public void testKeepAllowsArrangeRepairWithoutChangingAssertion() {
        String current = """
                class SubjectTest {
                    @Test void value() { Subject subject = null; assertEquals(2, subject.value()); }
                }
                """;
        String proposed = current.replace("Subject subject = null", "Subject subject = new Subject()");

        gate.validateRepair(assessment("KEEP", "NONE"), current, proposed, Set.of("value"));
    }

    public void testKeepRejectsExpectedValueChange() {
        String current = "class SubjectTest { @Test void value(){ assertEquals(2, new Subject().value()); } }";
        String proposed = current.replace("assertEquals(2", "assertEquals(1");

        try {
            gate.validateRepair(assessment("KEEP", "USER_REQUIREMENT"), current, proposed, Set.of("value"));
            fail("expected unsafe oracle change rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException ex) {
            assertTrue(ex.getMessage().contains("oracleDecision=KEEP"));
        }
    }

    public void testKeepRejectsReplacingActualCallWithConstant() {
        String current = "class SubjectTest { @Test void value(){ assertEquals(2, new Subject().value()); } }";
        String proposed = current.replace("new Subject().value()", "2");

        try {
            gate.validateRepair(assessment("KEEP", "USER_REQUIREMENT"), current, proposed, Set.of("value"));
            fail("expected tautological assertion rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("value"));
        }
    }

    public void testKeepRejectsReplacingIndirectActualSourceWithConstant() {
        String current = "class SubjectTest { @Test void value(){ int actual = new Subject().value(); assertEquals(2, actual); } }";
        String proposed = current.replace("int actual = new Subject().value()", "int actual = 2");

        try {
            gate.validateRepair(assessment("KEEP", "NONE"), current, proposed, Set.of("value"));
            fail("expected indirect oracle source rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("oracle-source:actual"));
        }
    }

    public void testKeepRejectsChangingExpectedLocalValue() {
        String current = "class SubjectTest { @Test void value(){ int expected = 2; assertEquals(expected, run()); } }";
        String proposed = current.replace("int expected = 2", "int expected = 1");

        try {
            gate.validateRepair(assessment("KEEP", "NONE"), current, proposed, Set.of("value"));
            fail("expected local expected-value rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("oracle-source:expected"));
        }
    }

    public void testCorrectRequiresIndependentContractAuthority() {
        String current = "class SubjectTest { @Test void value(){ assertEquals(2, run()); } }";
        String proposed = current.replace("assertEquals(2", "assertEquals(1");

        try {
            gate.validateRepair(assessment("CORRECT", "PRODUCTION_SOURCE"), current, proposed, Set.of("value"));
            fail("production source must not authorize oracle correction");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("PRODUCTION_SOURCE"));
        }
        gate.validateRepair(assessment("CORRECT", "CONTRACT_FACT"), current, proposed, Set.of("value"));
    }

    public void testKeepRejectsAssertJFluentExpectedValueChange() {
        String current = "class SubjectTest { @Test void value(){ assertThat(run()).isEqualTo(2); } }";
        String proposed = current.replace("isEqualTo(2)", "isEqualTo(1)");

        try {
            gate.validateRepair(assessment("KEEP", "NONE"), current, proposed, Set.of("value"));
            fail("expected fluent assertion rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("fluent:"));
        }
    }

    public void testKeepRejectsMovingAssertionBehindDeadBranch() {
        String current = "class SubjectTest { @Test void value(){ assertEquals(2, run()); } }";
        String proposed = "class SubjectTest { @Test void value(){ if (false) { assertEquals(2, run()); } } }";

        try {
            gate.validateRepair(assessment("KEEP", "NONE"), current, proposed, Set.of("value"));
            fail("expected dead branch rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("control-if")
                    || expected.getMessage().contains("IfStmt"));
        }
    }

    public void testKeepRejectsSkippingTestWithAssumption() {
        String current = "class SubjectTest { @Test void value(){ assertEquals(2, run()); } }";
        String proposed = "class SubjectTest { @Test void value(){ assumeTrue(false); assertEquals(2, run()); } }";

        try {
            gate.validateRepair(assessment("KEEP", "NONE"), current, proposed, Set.of("value"));
            fail("expected assumption rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("assumption:"));
        }
    }

    private OracleAssessment assessment(String decision, String authority) {
        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRepairDisposition("REPAIR_TEST");
        response.setOracleDecision(decision);
        response.setOracleAuthority(authority);
        response.setOracleAuthorityRef("NONE".equals(authority) || "PRODUCTION_SOURCE".equals(authority)
                ? "NONE" : authority + ":0");
        response.setDiagnosisRootCauseCategory("TEST_FIXTURE");
        response.setDiagnosisEvidence(List.of("evidence"));
        return gate.assessment(response);
    }
}
