package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

public class JavaSourceAnalyzerMethodSelectionTest extends TestCase {
    public void testCaretLineSelectsConcreteNestedOverrideInsteadOfOuterAbstractMethod() {
        String source = """
                package sample;
                public abstract class Evaluator {
                    public abstract boolean matches(int value);

                    public static final class Positive extends Evaluator {
                        @Override
                        public boolean matches(int value) {
                            return value > 0;
                        }
                    }
                }
                """;

        MethodModel model = new JavaSourceAnalyzer().analyze(source, "matches", 8);

        assertEquals("Positive", model.getClassName());
        assertEquals("matches", model.getMethodName());
        assertEquals(6, model.getSourceStartLine());
        assertEquals(9, model.getSourceEndLine());
        assertTrue(model.getMethodBodyText().contains("value > 0"));
    }

    public void testCaretLineSelectsCorrectOverload() {
        String source = """
                package sample;
                class Subject {
                    int parse(String value) { return value.length(); }
                    int parse(int value) {
                        return value + 1;
                    }
                }
                """;

        MethodModel model = new JavaSourceAnalyzer().analyze(source, "parse", 5);

        assertEquals("int", model.getParameters().get(0).getType());
        assertEquals(4, model.getSourceStartLine());
        assertEquals(6, model.getSourceEndLine());
    }
}
