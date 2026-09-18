package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import junit.framework.TestCase;

import java.util.List;

public class ClassRepairProtocolTest extends TestCase {
    public void testClassRepairPromptContainsFullClassAndRegressionSets() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setPackageName("sample");
        request.setClassName("Subject");
        request.setClassSourceCode("class Subject { int value() { return 1; } }");
        request.setExistingTestCodeSummary("class SubjectTest { }");
        request.setFailingTestIds(List.of("fails"));
        request.setPassedTestIds(List.of("passes"));
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#fails()");
        diagnostic.setExceptionType("org.opentest4j.AssertionFailedError");
        diagnostic.setExpectedValue("2");
        diagnostic.setActualValue("1");
        request.setRuntimeDiagnostics(List.of(diagnostic));

        String prompt = new LlmPromptBuilder().classRepairPrompt(request);

        assertTrue(prompt.contains("SHARED_FIXTURE"));
        assertTrue(prompt.contains("class Subject { int value() { return 1; } }"));
        assertTrue(prompt.contains("class SubjectTest { }"));
        assertTrue(prompt.contains("passes"));
        assertTrue(prompt.contains("fails"));
    }

    public void testParserPreservesClassRepairScope() {
        String response = """
                {"repairScope":"SHARED_FIXTURE",
                 "diagnosis":{"rootCauseCategory":"FIXTURE","evidence":["field is null"],"oracleDecision":"KEEP",
                 "oracleAuthority":"NONE","oracleAuthorityRef":"NONE","repairDisposition":"REPAIR_TEST"},
                 "testCode":"package sample; class SubjectTest {}","warnings":[]}
                """;

        LlmGenerationResponse parsed = new LlmResponseParser().parseClassRepair(response);

        assertEquals("SHARED_FIXTURE", parsed.getRepairScope());
        assertTrue(parsed.getTestCode().contains("SubjectTest"));
        assertEquals("FIXTURE", parsed.getDiagnosisRootCauseCategory());
    }

    public void testClassRepairParserAllowsTerminalDiagnosisWithoutTestCode() {
        String response = """
                {"repairScope":"METHOD_ONLY",
                 "diagnosis":{"rootCauseCategory":"POTENTIAL_SUT_BUG","evidence":["contract fact"],
                 "oracleDecision":"KEEP","oracleAuthority":"CONTRACT_FACT",
                 "oracleAuthorityRef":"CONTRACT_FACT:0",
                 "repairDisposition":"SUSPECTED_SUT_BUG"},"edits":[],"warnings":[]}
                """;

        LlmGenerationResponse parsed = new LlmResponseParser().parseClassRepair(response);

        assertEquals("SUSPECTED_SUT_BUG", parsed.getRepairDisposition());
        assertTrue(parsed.getTestCode().isBlank());
    }
}
