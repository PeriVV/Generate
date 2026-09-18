package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.SourceBugAnalysis;
import junit.framework.TestCase;

public class LlmResponseParserTest extends TestCase {
    public void testParsesJsonFenceWithUppercaseLanguageAndSurroundingText() {
        String raw = """
                已按要求补充测试：
                ```JSON
                {"mockStrategies":[],"testCode":"package sample;\\nclass SubjectTest {}","warnings":[]}
                ```
                """;

        LlmGenerationResponse response = new LlmResponseParser().parse(raw);

        assertTrue(response.getTestCode().contains("class SubjectTest"));
    }

    public void testAcceptsFencedJavaWhenModelIgnoresJsonEnvelope() {
        String raw = """
                ```java
                package sample;
                class SubjectTest {
                    void coversTarget() {}
                }
                ```
                """;

        LlmGenerationResponse response = new LlmResponseParser().parse(raw);

        assertTrue(response.getTestCode().startsWith("package sample;"));
        assertTrue(response.getTestCode().contains("coversTarget"));
    }

    public void testRejectsEmptyResponseWithSpecificException() {
        try {
            new LlmResponseParser().parse("  \n");
            fail("Expected LlmResponseFormatException");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("空内容"));
        }
    }

    public void testParsesAndValidatesRuntimeMethodPatchContract() {
        String raw = """
                {"diagnosis":{"rootCauseCategory":"LOW_CONFIDENCE_ORACLE","evidence":["existing test"],"oracleDecision":"CORRECT",
                 "oracleAuthority":"EXISTING_TEST","oracleAuthorityRef":"EXISTING_TEST:0","repairDisposition":"REPAIR_TEST"},
                 "edits":[{"targetMethod":"failingTest","reason":"oracle conflicts with source","expectedEffect":"expected matches contract"}],
                 "methodPatches":[{"methodName":"failingTest","methodCode":"@Test void failingTest() { assertTrue(value); }"}],
                 "warnings":[]}
                """;

        LlmGenerationResponse response = new LlmResponseParser().parseCoverage(raw);

        assertEquals("LOW_CONFIDENCE_ORACLE", response.getDiagnosisRootCauseCategory());
        assertEquals("CORRECT", response.getOracleDecision());
        assertEquals("EXISTING_TEST", response.getOracleAuthority());
        assertEquals("REPAIR_TEST", response.getRepairDisposition());
        assertEquals(1, response.getMethodPatches().size());
        assertEquals("failingTest", response.getEdits().get(0).getTargetMethod());
    }

    public void testRejectsPatchWithoutMatchingStructuredEdit() {
        String raw = """
                {"diagnosis":{"rootCauseCategory":"ORACLE","evidence":["source"],"oracleDecision":"CORRECT",
                 "oracleAuthority":"EXISTING_TEST","oracleAuthorityRef":"EXISTING_TEST:0","repairDisposition":"REPAIR_TEST"},
                 "edits":[],
                 "methodPatches":[{"methodName":"failingTest","methodCode":"@Test void failingTest() {}"}]}
                """;
        try {
            new LlmResponseParser().parseCoverage(raw);
            fail("expected structured edit rejection");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("edits"));
        }
    }

    public void testAcceptsSuspectedSutBugWithoutCodePatch() {
        String raw = """
                {"diagnosis":{"rootCauseCategory":"POTENTIAL_SUT_BUG","evidence":["requirement R-17"],
                 "oracleDecision":"KEEP","oracleAuthority":"USER_REQUIREMENT",
                 "oracleAuthorityRef":"USER_REQUIREMENT:0",
                 "repairDisposition":"SUSPECTED_SUT_BUG"},"edits":[],"methodPatches":[],"warnings":[]}
                """;

        LlmGenerationResponse response = new LlmResponseParser().parseCoverage(raw);

        assertEquals("SUSPECTED_SUT_BUG", response.getRepairDisposition());
        assertTrue(response.getMethodPatches().isEmpty());
        assertTrue(response.getTestCode().isBlank());
    }

    public void testRejectsTerminalDiagnosisThatCarriesPatch() {
        String raw = """
                {"diagnosis":{"rootCauseCategory":"POTENTIAL_SUT_BUG","evidence":["requirement"],
                 "oracleDecision":"KEEP","oracleAuthority":"USER_REQUIREMENT",
                 "oracleAuthorityRef":"USER_REQUIREMENT:0",
                 "repairDisposition":"SUSPECTED_SUT_BUG"},"edits":[],
                 "methodPatches":[{"methodName":"failingTest","methodCode":"@Test void failingTest() {}"}]}
                """;
        try {
            new LlmResponseParser().parseCoverage(raw);
            fail("expected terminal patch rejection");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("终止诊断"));
        }
    }

    public void testParsesReadOnlySourceBugAnalysis() {
        String raw = """
                {"summary":"value() returns a value that conflicts with requirement R-17", "confidence":"HIGH",
                 "suspectedLocations":[{"file":"Subject.java","line":3,"symbol":"value()","reason":"returns 1"}],
                 "hypotheses":["hard-coded fallback is incorrect"],
                 "evidence":["R-17 expects 2 but runtime actual is 1"],
                 "recommendedChecks":["review the return expression and R-17 history"]}
                """;

        SourceBugAnalysis analysis = new LlmResponseParser().parseSourceBugAnalysis(raw);

        assertTrue(analysis.isAttempted());
        assertTrue(analysis.isAvailable());
        assertEquals("HIGH", analysis.getConfidence());
        assertEquals(3, analysis.getSuspectedLocations().get(0).getLine());
        assertTrue(analysis.getEvidence().get(0).contains("runtime actual"));
    }

    public void testRejectsSourceBugAnalysisWithCodeMutationField() {
        String raw = """
                {"summary":"likely bug","confidence":"MEDIUM","suspectedLocations":[],
                 "hypotheses":[],"evidence":["contract mismatch"],"recommendedChecks":["review source"],
                 "edits":[{"target":"Subject.java"}]}
                """;
        try {
            new LlmResponseParser().parseSourceBugAnalysis(raw);
            fail("expected read-only field rejection");
        } catch (LlmResponseFormatException ex) {
            assertTrue(ex.getMessage().contains("禁止字段"));
        }
    }
}
