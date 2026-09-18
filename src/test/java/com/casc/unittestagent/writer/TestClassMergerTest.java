package com.casc.unittestagent.writer;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TestClassMergerTest {
    @Test
    public void overloadedMethodsUseDistinctStableRegions() {
        String existing = "package sample; class SubjectTest { }";
        String generated = "package sample; class SubjectTest { @org.junit.jupiter.api.Test void generated() {} }";
        TestClassMerger merger = new TestClassMerger();

        String first = merger.merge(existing, generated,
                GenerationTarget.method("sample.Subject#work(int)"), new MergeOptions());
        String second = merger.merge(first, generated.replace("generated", "generatedString"),
                GenerationTarget.method("sample.Subject#work(java.lang.String)"), new MergeOptions());

        assertTrue(second.contains("sample.Subject#work(int)"));
        assertTrue(second.contains("sample.Subject#work(java.lang.String)"));
    }

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

    @Test
    public void classRegionIsAtomicallyReplacedWithoutRemovingManualTests() {
        String existing = """
                package sample;
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void manualTest() { }
                }
                """;
        String first = """
                package sample;
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void generatedOne() { }
                }
                """;
        String second = first.replace("generatedOne", "generatedTwo");
        TestClassMerger merger = new TestClassMerger();
        GenerationTarget target = GenerationTarget.classTarget("sample.Subject");

        String once = merger.merge(existing, first, target, new MergeOptions());
        String twice = merger.merge(once, second, target, new MergeOptions());

        assertTrue(twice.contains("void manualTest()"));
        assertTrue(twice.contains("void generatedTwo()"));
        assertTrue(!twice.contains("void generatedOne()"));
        assertTrue(merger.hasRegion(twice, target));
        assertTrue(twice.contains("region UnitMind generated tests for class sample.Subject"));
    }

    @Test
    public void classRegionMergeRejectsManualFieldTypeConflict() {
        String existing = """
                package sample;
                class SubjectTest {
                    private String subject;
                }
                """;
        String generated = """
                package sample;
                import org.mockito.InjectMocks;
                class SubjectTest {
                    @InjectMocks
                    private Subject subject;
                    @org.junit.jupiter.api.Test void generatedTest() { }
                }
                """;
        try {
            new TestClassMerger().merge(existing, generated,
                    GenerationTarget.classTarget("sample.Subject"), new MergeOptions());
            org.junit.Assert.fail("field conflict should block class region merge");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("字段冲突"));
        }
    }

    @Test
    public void classRegionMergeAddsRequiredClassFixtureAnnotation() {
        String existing = """
                package sample;
                class SubjectTest {
                    void manualHelper() { }
                }
                """;
        String generated = """
                package sample;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class SubjectTest {
                    @org.junit.jupiter.api.Test void generatedTest() { }
                }
                """;

        String merged = new TestClassMerger().merge(existing, generated,
                GenerationTarget.classTarget("sample.Subject"), new MergeOptions());

        assertTrue(merged.contains("@ExtendWith(MockitoExtension.class)\nclass SubjectTest"));
        assertTrue(merged.contains("void manualHelper()"));
    }

    @Test
    public void classRegionMergeRejectsManualMethodSignatureConflict() {
        String existing = """
                package sample;
                class SubjectTest {
                    @org.junit.jupiter.api.Test void shouldLoad() { }
                }
                """;
        String generated = """
                package sample;
                class SubjectTest {
                    @org.junit.jupiter.api.Test void shouldLoad() { }
                }
                """;
        try {
            new TestClassMerger().merge(existing, generated,
                    GenerationTarget.classTarget("sample.Subject"), new MergeOptions());
            org.junit.Assert.fail("method signature conflict should block class region merge");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("方法签名冲突"));
        }
    }
}
