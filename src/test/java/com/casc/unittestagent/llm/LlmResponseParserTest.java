package com.casc.unittestagent.llm;

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
                {"diagnosis":{"rootCauseCategory":"LOW_CONFIDENCE_ORACLE","evidence":["existing test"],"oracleDecision":"CORRECT"},
                 "edits":[{"targetMethod":"failingTest","reason":"oracle conflicts with source","expectedEffect":"expected matches contract"}],
                 "methodPatches":[{"methodName":"failingTest","methodCode":"@Test void failingTest() { assertTrue(value); }"}],
                 "warnings":[]}
                """;

        LlmGenerationResponse response = new LlmResponseParser().parseCoverage(raw);

        assertEquals("LOW_CONFIDENCE_ORACLE", response.getDiagnosisRootCauseCategory());
        assertEquals("CORRECT", response.getOracleDecision());
        assertEquals(1, response.getMethodPatches().size());
        assertEquals("failingTest", response.getEdits().get(0).getTargetMethod());
    }

    public void testRejectsPatchWithoutMatchingStructuredEdit() {
        String raw = """
                {"diagnosis":{"rootCauseCategory":"ORACLE","evidence":["source"],"oracleDecision":"CORRECT"},
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
}
