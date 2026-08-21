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
                 "diagnosis":{"rootCauseCategory":"FIXTURE","evidence":["field is null"],"oracleDecision":"KEEP"},
                 "testCode":"package sample; class SubjectTest {}","warnings":[]}
                """;

        LlmGenerationResponse parsed = new LlmResponseParser().parse(response);

        assertEquals("SHARED_FIXTURE", parsed.getRepairScope());
        assertTrue(parsed.getTestCode().contains("SubjectTest"));
        assertEquals("FIXTURE", parsed.getDiagnosisRootCauseCategory());
    }
}
