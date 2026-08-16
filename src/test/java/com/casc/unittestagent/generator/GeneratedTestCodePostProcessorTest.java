package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

public class GeneratedTestCodePostProcessorTest extends TestCase {
    public void testDoesNotRewriteBusinessAssertionValue() {
        String source = """
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    void testValue() { assertEquals(1, subject.value()); }
                }
                """;

        String processed = new GeneratedTestCodePostProcessor().process(source, new MethodModel()).getCode();

        assertTrue(processed.contains("assertEquals(1, subject.value())"));
        assertFalse(processed.contains("assertEquals(0, subject.value())"));
    }

    public void testNormalizesSingleGeneratedTopLevelTestClassName() {
        String source = """
                package org.jsoup.nodes;
                class DocumentTypeOuterHtmlHeadTest {
                    DocumentTypeOuterHtmlHeadTest() {}
                    void shouldWork() {}
                }
                """;
        MethodModel method = new MethodModel();
        method.setClassName("DocumentType");

        GeneratedTestCodePostProcessor.ProcessedCode processed =
                new GeneratedTestCodePostProcessor().process(source, method);

        assertTrue(processed.getCode().contains("class DocumentTypeTest"));
        assertTrue(processed.getCode().contains("DocumentTypeTest()"));
        assertFalse(processed.getCode().contains("DocumentTypeOuterHtmlHeadTest"));
        assertTrue(processed.getWarnings().stream().anyMatch(item -> item.contains("规范化")));
    }
}
