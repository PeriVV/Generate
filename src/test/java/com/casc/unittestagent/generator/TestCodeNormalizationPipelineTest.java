package com.casc.unittestagent.generator;

import junit.framework.TestCase;

public class TestCodeNormalizationPipelineTest extends TestCase {
    public void testDoesNotAddMatchersToPlainStaticStubbing() {
        for (String owner : java.util.List.of("RemoteClient", "example.transport.RemoteClient")) {
            String code = "import static org.mockito.Mockito.*; class T { void test() { "
                    + "when(" + owner + ".exec(session, anyString(), any())).thenReturn(\"ok\"); } }";
            assertEquals(code, TestCodeNormalizationPipeline.normalize(code).code());
        }
    }

    private static final String IMPORTS = "import static org.mockito.Mockito.*; import static org.junit.jupiter.api.Assertions.*;\n";

    public void testNormalizesVerificationStubbingBddAndDoStyleUsingSameRules() {
        for (String statement : java.util.List.of(
                "verify(client).upload(anyString(), content.getBytes(), true);",
                "when(client.upload(anyString(), content.getBytes(), true)).thenReturn(path);",
                "doReturn(path).when(client).upload(anyString(), content.getBytes(), true);",
                "org.mockito.BDDMockito.given(client.upload(anyString(), content.getBytes(), true)).willReturn(path);")) {
            String source = IMPORTS + "class T { @Test void works() { " + statement + " } }";
            var result = TestCodeNormalizationPipeline.normalize(source);
            assertTrue(result.code(), result.changed());
            assertTrue(result.code(), result.code().contains("org.mockito.ArgumentMatchers.eq(content.getBytes())"));
            assertTrue(result.code(), result.code().contains("org.mockito.ArgumentMatchers.eq(true)"));
            assertEquals(result.code(), TestCodeNormalizationPipeline.normalize(result.code()).code());
            assertFalse(TestCodeNormalizationPipeline.normalize(result.code()).changed());
        }
    }

    public void testDoesNotIntroduceMatcherIntoPlainCallsOrBroadenExistingArguments() {
        String source = IMPORTS + "class T { @Test void works() { client.upload(path, bytes, true); verify(client).delete(path); } }";
        assertEquals(source, TestCodeNormalizationPipeline.normalize(source).code());
        String mixed = IMPORTS + "class T { @Test void works() { verify(client, times(2)).upload(anyString(), bytes, false); } }";
        String code = TestCodeNormalizationPipeline.normalize(mixed).code();
        assertTrue(code, code.contains("verify(client, times(2))"));
        assertTrue(code, code.contains("eq(bytes)"));
        assertTrue(code, code.contains("eq(false)"));
        assertFalse(code.contains("any(byte[].class)"));
    }

    public void testAmbiguousAndShadowedFrameworkBindingsRemainUnchanged() {
        for (String source : java.util.List.of(
                "class T { void works(){ verify(client).upload(anyString(), bytes, true); } }",
                IMPORTS + "class T { Object verify(Object c){return c;} void works(){ verify(client).upload(anyString(), bytes, true); } }",
                IMPORTS + "class T extends Base { void works(){ verify(client).upload(anyString(), bytes, true); } }",
                "import custom.Mockito; class T { void works(){ Mockito.verify(client).upload(Mockito.anyString(), bytes, true); } }",
                IMPORTS + "import static custom.Helpers.*; class T { void works(){ verify(client).upload(anyString(), bytes, true); } }")) {
            assertEquals(source, TestCodeNormalizationPipeline.normalize(source).code());
        }
    }

    public void testNestedMatchersAndCaptureAreNotTreatedAsLiteralValues() {
        for (String value : java.util.List.of("eq(anyString())", "captor.capture()", "custom.eq(bytes)")) {
            String source = IMPORTS + "class T { void works(){ verify(client).upload(anyString(), " + value + ", true); } }";
            var normalized = TestCodeNormalizationPipeline.normalize(source);
            assertEquals(source, normalized.code());
            assertFalse(normalized.code(), normalized.code().contains("eq(captor.capture())"));
            assertFalse(normalized.code(), normalized.code().contains("eq(custom.eq(bytes))"));
            assertTrue(normalized.code(), normalized.code().contains(value));
        }
    }

    public void testReturnsAuditableRulesAndLeavesMalformedJavaToCompiler() {
        var result = TestCodeNormalizationPipeline.normalize("import static org.mockito.Mockito.*; class T { @Mock Object client; void run(){verify(client).send(anyString(), true);} }");
        assertTrue(result.edits().stream().anyMatch(e -> e.kind() == TestCodeNormalizationPipeline.Kind.IMPORT));
        assertTrue(result.edits().stream().anyMatch(e -> e.kind() == TestCodeNormalizationPipeline.Kind.FRAMEWORK_SYNTAX && e.line() > 0));
        String invalid = "class T { void test( }";
        assertEquals(invalid, TestCodeNormalizationPipeline.normalize(invalid).code());
        assertFalse(TestCodeNormalizationPipeline.normalize(invalid).changed());
    }
}
