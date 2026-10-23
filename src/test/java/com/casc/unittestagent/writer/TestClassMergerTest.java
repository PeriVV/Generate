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

    @Test
    public void methodMergeReusesPackageVisibleMocksHelpersAndNestedTestsByStructure() {
        String fixture = """
                package sample;
                class SubjectTest {
                    @org.mockito.Mock Object repository;
                    Object subject;
                    @org.junit.jupiter.api.BeforeEach void setup() { subject = repository; }
                    Object helper() { return subject; }
                    @org.junit.jupiter.api.Nested class Cases {
                        @org.junit.jupiter.api.Test void works() { helper(); }
                    }
                }
                """;
        var merged = new TestClassMerger().merge(fixture, fixture, "target", new MergeOptions());
        var type = com.github.javaparser.StaticJavaParser.parse(merged).getClassByName("SubjectTest").orElseThrow();
        org.junit.Assert.assertEquals(2, type.getFields().size());
        org.junit.Assert.assertEquals(1, type.getMethodsByName("setup").size());
        org.junit.Assert.assertEquals(1, type.getMethodsByName("helper").size());
        org.junit.Assert.assertEquals(1, type.getMembers().stream().filter(m -> m.isClassOrInterfaceDeclaration()).count());
    }
    @Test
    public void methodMergeBlocksChangedSharedLifecycleAndFieldFixtures() {
        String original = "package sample; class SubjectTest { Object subject; @org.junit.jupiter.api.BeforeEach void setup() { subject = null; } }";
        for (String changed : new String[]{original.replace("Object subject", "String subject"),
                original.replace("subject = null", "subject = new Object()"), original.replace("void setup", "void otherSetup")}) {
            try {
                new TestClassMerger().merge(original, changed, "target", new MergeOptions());
                org.junit.Assert.fail("Shared fixture conflicts must be explicit");
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("冲突")); }
        }
    }
    @Test
    public void methodMergeBlocksImportRebindingAndAddsRequiredMockitoExtension() {
        String existing = "package sample;\nimport one.Subject;\nclass SubjectTest {}";
        String generated = "package sample;\nimport two.Subject;\nclass SubjectTest {}";
        try { new TestClassMerger().merge(existing, generated, "target", new MergeOptions()); org.junit.Assert.fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("import")); }
        String code = new TestClassMerger().merge("package sample; class SubjectTest {}",
                "package sample; @org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) class SubjectTest {}",
                "target", new MergeOptions());
        assertTrue(code.contains("@org.junit.jupiter.api.extension.ExtendWith"));
    }

    @Test
    public void membersGoIntoNamedTestTypeEvenWithTrailingBracesAndOtherTopLevelTypes() {
        String existing = "package sample; class Helper {}\nclass SubjectTest { @org.junit.jupiter.api.Test void human() {} }\nclass Trailer {} // trailing }";
        String generated = "package sample; class SubjectTest { @org.junit.jupiter.api.Test void added() {} }";
        var code = new TestClassMerger().merge(existing, generated, "target", new MergeOptions());
        var unit = com.github.javaparser.StaticJavaParser.parse(code);
        org.junit.Assert.assertEquals(1, unit.getClassByName("SubjectTest").orElseThrow().getMethodsByName("added").size());
        org.junit.Assert.assertEquals(0, unit.getClassByName("Trailer").orElseThrow().getMethods().size());
        org.junit.Assert.assertEquals(0, unit.getClassByName("Helper").orElseThrow().getMethods().size());
    }
}
