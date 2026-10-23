package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

public class GeneratedTestCodePostProcessorTest extends TestCase {
    public void testRestoresMissingMockitoAnnotationImportsAndPreservesCode() {
        String source = """
                package sample;
                import org.mockito.junit.jupiter.MockitoExtension;
                class SubjectTest {
                    // fixture must remain intact
                    @Mock Object client;
                    @Spy Object value;
                    @Captor Object captor;
                    @InjectMocks Object subject;
                    @org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
                    void test() { org.junit.jupiter.api.Assertions.assertEquals(7, 7); }
                }
                """;
        var processor = new GeneratedTestCodePostProcessor();
        var result = processor.process(source, new MethodModel());
        for (String name : java.util.List.of("Mock", "Spy", "Captor", "InjectMocks"))
            assertTrue(result.getCode(), result.getCode().contains("import org.mockito." + name + ";"));
        assertTrue(result.getCode().contains("// fixture must remain intact"));
        assertTrue(result.getCode().contains("assertEquals(7, 7)"));
        assertEquals(result.getCode(), processor.process(result.getCode(), new MethodModel()).getCode());
        assertEquals(1, result.getWarnings().stream().filter(w -> w.contains("注解导入")).count());
    }

    public void testDoesNotOverrideCustomOrWildcardAnnotationTypes() {
        var processor = new GeneratedTestCodePostProcessor();
        for (String source : java.util.List.of(
                "import custom.Mock; import org.mockito.Mockito; class SubjectTest { @Mock Object field; }",
                "import org.mockito.Mockito; class SubjectTest { @interface Mock {} @Mock Object field; }",
                "import org.mockito.*; class SubjectTest { @Mock Object field; }",
                "class SubjectTest { @Mock Object field; }",
                "import org.mockito.Mockito; class SubjectTest { @org.mockito.Mock Object field; }")) {
            String result = processor.process(source, new MethodModel()).getCode();
            assertFalse(result, result.contains("import org.mockito.Mock;"));
        }
    }

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
