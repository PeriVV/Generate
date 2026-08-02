package com.casc.unittestagent.writer;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TestClassMergerTest {
    @Test
    public void mergedContentIsTheFinalCompilableUnitToValidate() {
        String existing = "package sample;\n\nimport org.junit.jupiter.api.Test;\n\nclass SubjectTest {\n}\n";
        String generated = """
                package sample;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;

                class SubjectTest {
                    @Test
                    void shouldLoad() {
                        assertNotNull("ok");
                    }
                }
                """;

        String merged = new TestClassMerger().merge(existing, generated, "load", new MergeOptions());

        assertTrue(merged.contains("region UnitMind generated tests for load"));
        assertTrue(merged.contains("import static org.junit.jupiter.api.Assertions.assertNotNull;"));
        assertTrue(merged.contains("void shouldLoad()"));
    }
}
