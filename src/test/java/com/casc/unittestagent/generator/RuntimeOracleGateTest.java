package com.casc.unittestagent.generator;

import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.OracleDecision;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import junit.framework.TestCase;

import java.util.List;
import java.util.Set;

public class RuntimeOracleGateTest extends TestCase {
    private final RuntimeOracleGate gate = new RuntimeOracleGate();

    public void testModelCannotReportCandidateRejectionAsObservedRuntimeFailure() {
        var diagnostic = new RuntimeDiagnostic(); diagnostic.setCategory("MOCKITO_INVALID_MATCHER_USAGE");
        var response = arbitrationResponse("UNSAFE_ORACLE_PATCH");
        var result = gate.assessment(response, List.of(diagnostic));
        assertEquals("MOCKITO_INVALID_MATCHER_USAGE", result.getRootCauseCategory());
        assertTrue(result.getEvidence().toString(), result.getEvidence().stream().anyMatch(e -> e.contains("UNSAFE_ORACLE_PATCH")));
    }

    public void testSharedNormalizationAcceptsSyntaxRepairInsideControlFlow() {
        String current = """
                import static org.mockito.Mockito.*;
                class SubjectTest { @Test void value(){
                    if (enabled) { verify(client, times(2)).upload(anyString(), content.getBytes(), true); }
                } }
                """;
        String fixed = current.replace("content.getBytes(), true", "eq(content.getBytes()), eq(true)");
        gate.validateRepair(assessment("KEEP", "NONE"), current, fixed, Set.of("value"));
        // Protected tests use the same contract representation, including under CORRECT.
        gate.validateRepair(assessment("CORRECT", "CONTRACT_FACT"), current, fixed, Set.of(), Set.of("value"));
    }

    public void testCanonicalContractRejectsParameterModeReceiverAndExecutionChanges() {
        String current = "import static org.mockito.Mockito.*; class T { @Test void value(){verify(client, times(2)).upload(anyString(), bytes, true);} }";
        String fixed = current.replace("bytes, true", "eq(bytes), eq(true)");
        for (String invalid : List.of(fixed.replace("eq(bytes)", "any(byte[].class)"), fixed.replace("eq(true)", "eq(false)"),
                fixed.replace("times(2)", "times(1)"), fixed.replace("verify(client", "verify(other"),
                fixed.replace(".upload(", ".delete("), fixed.replace("verify(client", "if(false) verify(client"))) {
            assertUnsafe(current, invalid);
        }
    }

    public void testCustomEqualityOrVerificationDoesNotGainNormalizationPrivilege() {
        String current = "import static org.mockito.Mockito.*; class T { @Test void value(){verify(client).upload(anyString(), bytes, true);} }";
        assertUnsafe(current, current.replace("bytes, true", "custom.eq(bytes), eq(true)"));
        String shadow = current.replace("class T {", "class T { Object verify(Object c){return c;}");
        assertUnsafe(shadow, shadow.replace("bytes, true", "eq(bytes), eq(true)"));
    }

    public void testLiteralWhitespaceAndTokenBoundariesRemainPartOfContract() {
        assertUnsafe("class T { @Test void value(){assertEquals(\"a b\", actual);} }",
                "class T { @Test void value(){assertEquals(\"ab\", actual);} }");
        assertUnsafe("class T { @Test void value(){assertEquals(a + + b, actual);} }",
                "class T { @Test void value(){assertEquals(a++ + b, actual);} }");
        String source = "class T { @Test void value(){ assertEquals(\"a b\", actual); } }";
        gate.validateRepair(assessment("KEEP", "NONE"), source, source.replace("assertEquals(", "assertEquals( /* comment */ "), Set.of("value"));
    }

    public void testEqualityWrappedLocalExpectedValueStillHasSourceProtection() {
        String current = "import static org.mockito.Mockito.*; class T { @Test void value(){int count=2; verify(client).send(anyString(), count);} }";
        String fixed = current.replace("anyString(), count", "anyString(), eq(count)");
        gate.validateRepair(assessment("KEEP", "NONE"), current, fixed, Set.of("value"));
        assertUnsafe(current, fixed.replace("count=2", "count=1"));
    }

    private void assertUnsafe(String current, String proposed) {
        try {
            gate.validateRepair(assessment("KEEP", "NONE"), current, proposed, Set.of("value"));
            fail("unsafe contract change accepted: " + proposed);
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) { assertTrue(expected.getMessage().contains("Oracle")); }
    }

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

    public void testStrictOracleGateBlocksEvenHighAuthorityCorrection() {
        // 严格准出：即使权威来源可自动修正（EXISTING_TEST/CONTRACT_FACT），CORRECT 一律禁止自动应用。
        OracleAssessment highAuthority = assessment(OracleDecision.CORRECT, OracleAuthority.EXISTING_TEST);
        assertFalse(RuntimeOracleGate.automaticOracleCorrectionBlocked(highAuthority, false));
        assertTrue(RuntimeOracleGate.automaticOracleCorrectionBlocked(highAuthority, true));

        OracleAssessment lowAuthority = assessment(OracleDecision.CORRECT, OracleAuthority.RUNTIME_ACTUAL);
        assertTrue(RuntimeOracleGate.automaticOracleCorrectionBlocked(lowAuthority, false));
        assertTrue(RuntimeOracleGate.automaticOracleCorrectionBlocked(lowAuthority, true));

        // KEEP 决策不受严格开关影响（修 Arrange/Mock 不改 Oracle，仍按 AST 门禁约束）。
        OracleAssessment keep = assessment(OracleDecision.KEEP, OracleAuthority.NONE);
        assertFalse(RuntimeOracleGate.automaticOracleCorrectionBlocked(keep, true));
        assertFalse(RuntimeOracleGate.automaticOracleCorrectionBlocked(null, true));

        String message = RuntimeOracleGate.strictOracleGateMessage(highAuthority);
        assertTrue(message.contains("严格 Oracle 准出"));
        assertTrue(message.contains("EXISTING_TEST"));
        assertTrue(message.contains("人工确认"));
    }

    private static OracleAssessment assessment(OracleDecision decision, OracleAuthority authority) {
        OracleAssessment assessment = new OracleAssessment();
        assessment.setDisposition(RuntimeRepairDisposition.REPAIR_TEST);
        assessment.setDecision(decision);
        assessment.setAuthority(authority);
        assessment.setAuthorityRef(authority.permitsAutomaticOracleCorrection()
                ? authority.name() + ":0" : "NONE");
        return assessment;
    }

    public void testUnknownModelCategoryFallsBackToObservedDeterministicCategory() {
        LlmGenerationResponse response = arbitrationResponse("NEW_FAKE_CATEGORY");
        RuntimeDiagnostic observed = new RuntimeDiagnostic();
        observed.setTestId("sample.SubjectTest#value()");
        observed.setCategory("ASSERTION_VALUE_MISMATCH");

        OracleAssessment assessment = gate.assessment(response, List.of(observed));

        assertEquals("ASSERTION_VALUE_MISMATCH", assessment.getRootCauseCategory());
        assertTrue(assessment.getEvidence().stream()
                .anyMatch(item -> item.contains("不在封闭分类中") && item.contains("NEW_FAKE_CATEGORY")));
    }

    public void testUnknownModelCategoryWithoutDiagnosticsBecomesUnknown() {
        OracleAssessment assessment = gate.assessment(arbitrationResponse("MOCKITO_STATIC_SYSTEM_FORBIDDEN"));

        assertEquals("UNKNOWN", assessment.getRootCauseCategory());
        assertTrue(assessment.getEvidence().stream().anyMatch(item -> item.contains("UNKNOWN")));
    }

    public void testKnownModelCategoryIsCanonicalized() {
        OracleAssessment assessment = gate.assessment(arbitrationResponse(" assertion_value_mismatch "));

        assertEquals("ASSERTION_VALUE_MISMATCH", assessment.getRootCauseCategory());
        assertTrue(assessment.getEvidence().stream().noneMatch(item -> item.contains("不在封闭分类中")));
    }

    public void testLegacySingleArgAssessmentStillParses() {
        OracleAssessment assessment = gate.assessment(arbitrationResponse("FIXTURE_DEPENDENCY_MISSING"));

        assertEquals(RuntimeRepairDisposition.REPAIR_TEST, assessment.getDisposition());
        assertEquals("FIXTURE_DEPENDENCY_MISSING", assessment.getRootCauseCategory());
    }

    public void testFallbackIgnoresUnknownObservedCategory() {
        LlmGenerationResponse response = arbitrationResponse("SOMETHING_NEW");
        RuntimeDiagnostic observed = new RuntimeDiagnostic();
        observed.setTestId("sample.SubjectTest#value()");
        observed.setCategory("ALSO_UNKNOWN_CODE");

        OracleAssessment assessment = gate.assessment(response, List.of(observed));

        assertEquals("UNKNOWN", assessment.getRootCauseCategory());
    }

    private static LlmGenerationResponse arbitrationResponse(String rootCauseCategory) {
        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setRepairDisposition("REPAIR_TEST");
        response.setOracleDecision("KEEP");
        response.setOracleAuthority("NONE");
        response.setOracleAuthorityRef("NONE");
        response.setDiagnosisRootCauseCategory(rootCauseCategory);
        response.setDiagnosisEvidence(List.of("evidence"));
        return response;
    }

    public void testProtectedTestNamesRejectOracleChangeUnderKeep() {
        String current = """
                class SubjectTest {
                    @Test void focused(){ assertEquals(2, new Subject().value()); }
                    @Test void bystander(){ assertEquals(5, new Subject().other()); }
                }
                """;
        String proposed = current.replace("assertEquals(5", "assertTrue(true");

        try {
            gate.validateRepair(assessment("KEEP", "NONE"), current, proposed,
                    Set.of("focused"), Set.of("bystander"));
            fail("expected non-focused oracle hollowing rejection");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("bystander"));
            assertTrue(expected.getMessage().contains("非聚焦"));
        }
    }

    public void testProtectedTestNamesRejectOracleChangeEvenUnderCorrect() {
        String current = """
                class SubjectTest {
                    @Test void focused(){ assertEquals(2, new Subject().value()); }
                    @Test void bystander(){ assertEquals(5, new Subject().other()); }
                }
                """;
        String proposed = current.replace("assertEquals(5", "assertTrue(true");

        // CORRECT + 合法权威只授权修改聚焦测试的期望值，非聚焦测试仍受指纹保护。
        try {
            gate.validateRepair(assessment("CORRECT", "CONTRACT_FACT"), current, proposed,
                    Set.of("focused"), Set.of("bystander"));
            fail("expected non-focused oracle hollowing rejection under CORRECT");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("bystander"));
        }
    }

    public void testCorrectDoesNotAuthorizeExecutionRemoval() {
        String current = """
                class SubjectTest {
                    @Test void focused(){ assertEquals(2, new Subject().value()); }
                }
                """;
        String assumed = current.replace("assertEquals(2",
                "Assumptions.assumeTrue(false); org.junit.jupiter.api.Assertions.assertEquals(2");

        try {
            gate.validateRepair(assessment("CORRECT", "CONTRACT_FACT"), current, assumed,
                    Set.of("focused"), Set.of());
            fail("expected assumption-injection rejection under CORRECT");
        } catch (RuntimeOracleGate.UnsafeOracleCorrectionException expected) {
            assertTrue(expected.getMessage().contains("不授权删除执行"));
        }
    }

    public void testCorrectStillAllowsAuthorizedExpectationChange() {
        String current = """
                class SubjectTest {
                    @Test void focused(){ assertEquals(2, new Subject().value()); }
                }
                """;
        String proposed = current.replace("assertEquals(2", "assertEquals(3");

        gate.validateRepair(assessment("CORRECT", "CONTRACT_FACT"), current, proposed,
                Set.of("focused"), Set.of());
    }

    public void testInvalidArbitrationEnumThrowsResponseFormatException() {
        // 枚举非法属于响应格式违约：抛 LlmResponseFormatException 才能进入格式重试，
        // 而不是以普通 IllegalArgumentException 绕过重试终止闭环。
        LlmGenerationResponse response = arbitrationResponse("FIXTURE_DEPENDENCY_MISSING");
        response.setRepairDisposition("");
        try {
            gate.assessment(response);
            fail("expected response format exception");
        } catch (com.casc.unittestagent.llm.LlmResponseFormatException expected) {
            assertTrue(expected.getMessage().contains("仲裁枚举无效"));
        }
    }
}
