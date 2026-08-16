package com.casc.unittestagent.generator;

import com.casc.unittestagent.llm.LlmMethodPatch;
import junit.framework.TestCase;

public class CoverageTestClassStabilizerTest extends TestCase {
    public void testPreservesExistingFixtureAndAppendsOnlyNewTests() {
        String current = """
                package sample;
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    private final Subject subject = new Subject("stable");
                    @Test void existingTest() { subject.run(1); }
                }
                """;
        String proposed = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    private final Subject subject = new Subject("rewritten");
                    @Test void existingTest() { subject.run(999); }
                    @Test void coversNegative() { assertEquals(-1, subject.run(-1)); }
                }
                """;

        String merged = new CoverageTestClassStabilizer().mergeAdditions(current, proposed);

        assertTrue(merged.contains("new Subject(\"stable\")"));
        assertFalse(merged.contains("new Subject(\"rewritten\")"));
        assertTrue(merged.contains("subject.run(1)"));
        assertFalse(merged.contains("subject.run(999)"));
        assertTrue(merged.contains("coversNegative"));
        assertTrue(merged.contains("assertEquals"));
    }

    public void testReturnsExactCurrentCodeWhenNoTestWasAdded() {
        String current = "class SubjectTest { @Test void existing() { run(1); } }";
        String proposed = "class SubjectTest { @Test void existing() { run(2); } }";

        assertEquals(current, new CoverageTestClassStabilizer().mergeAdditions(current, proposed));
    }

    public void testClassRepairCannotDeleteExistingGeneratedTest() {
        String current = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    private Subject subject;
                    // UnitMind method: sample.Subject#first()
                    @Test void shouldFirst() { subject.first(); }
                    // UnitMind method: sample.Subject#second()
                    @Test void shouldSecond() { subject.second(); }
                }
                """;
        String repaired = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    private Subject subject;
                    // UnitMind method: sample.Subject#first()
                    @Test void shouldFirst() { subject.firstFixed(); }
                }
                """;

        String stabilized = new CoverageTestClassStabilizer()
                .preserveTestsDuringRepair(current, repaired);

        assertTrue(stabilized.contains("firstFixed"));
        assertFalse(stabilized.contains("subject.first();"));
        assertTrue(stabilized.contains("shouldSecond"));
        assertTrue(stabilized.contains("sample.Subject#second()"));
    }

    public void testCompileRepairDoesNotRestoreRemovedInvalidScaffolding() {
        String current = """
                import missing.InvalidType;
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    InvalidType invalidField;
                    void invalidHelper() { InvalidType.call(); }
                    @Test void existingTest() { subject.run(); }
                }
                """;
        String repaired = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void existingTest() { subject.run(); }
                }
                """;

        String stabilized = new CoverageTestClassStabilizer()
                .preserveTestsDuringRepair(current, repaired);

        assertEquals(repaired, stabilized);
        assertFalse(stabilized.contains("InvalidType"));
        assertFalse(stabilized.contains("invalidHelper"));
    }

    public void testRuntimeRepairCanChangeOnlyFailingTest() {
        String current = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void passingTest() { subject.run(1); }
                    @Test void failingTest() { subject.run(-1); }
                }
                """;
        String repaired = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void passingTest() { subject.run(999); }
                    @Test void failingTest() { subject.run(0); }
                }
                """;

        String stabilized = new CoverageTestClassStabilizer()
                .preservePassingTestsDuringRepair(current, repaired, java.util.Set.of("failingTest"));

        assertTrue(stabilized.contains("passingTest()"));
        assertTrue(stabilized.contains("subject.run(1)"));
        assertFalse(stabilized.contains("subject.run(999)"));
        assertTrue(stabilized.contains("subject.run(0)"));
    }

    public void testRuntimeRepairLeavesAllCurrentlyFailingTestsEditable() {
        String current = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void passingTest() { subject.run(1); }
                    @Test void firstFailure() { subject.run(-1); }
                    @Test void secondFailure() { subject.run(-2); }
                }
                """;
        String repaired = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void passingTest() { subject.run(999); }
                    @Test void firstFailure() { subject.run(0); }
                    @Test void secondFailure() { subject.run(2); }
                }
                """;

        String stabilized = new CoverageTestClassStabilizer()
                .preservePassingTestsDuringRepair(current, repaired,
                        java.util.Set.of("firstFailure", "secondFailure"));

        assertTrue(stabilized.contains("subject.run(1)"));
        assertFalse(stabilized.contains("subject.run(999)"));
        assertTrue(stabilized.contains("subject.run(0)"));
        assertTrue(stabilized.contains("subject.run(2)"));
    }

    public void testMethodPatchReplacesOnlyNamedFailingTest() {
        String current = """
                import org.junit.jupiter.api.Test;
                class SubjectTest {
                    @Test void passingTest() { subject.run(1); }
                    @Test void failingTest() { subject.run(-1); }
                }
                """;
        LlmMethodPatch patch = new LlmMethodPatch();
        patch.setMethodName("failingTest");
        patch.setMethodCode("@Test void failingTest() { subject.run(0); }");

        String result = new CoverageTestClassStabilizer().applyMethodPatches(
                current, java.util.List.of(patch), java.util.Set.of("failingTest"));

        assertTrue(result.contains("subject.run(1)"));
        assertTrue(result.contains("subject.run(0)"));
        assertFalse(result.contains("subject.run(-1)"));
    }

    public void testMethodPatchRejectsPassingTestTarget() {
        String current = "import org.junit.jupiter.api.Test; class SubjectTest { @Test void passingTest() {} }";
        LlmMethodPatch patch = new LlmMethodPatch();
        patch.setMethodName("passingTest");
        patch.setMethodCode("@Test void passingTest() { fail(); }");

        try {
            new CoverageTestClassStabilizer().applyMethodPatches(
                    current, java.util.List.of(patch), java.util.Set.of("failingTest"));
            fail("expected scope rejection");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("越界"));
        }
    }

    public void testRuntimeMethodPatchIsNotDiscardedByExplorationMerge() {
        String current = """
                import org.junit.jupiter.api.Test;
                class AttributesTest {
                    @Test void shouldHandleNullValueAsBooleanAttribute() {
                        assertNull(attributes.get("key"));
                    }
                }
                """;
        LlmMethodPatch patch = new LlmMethodPatch();
        patch.setMethodName("shouldHandleNullValueAsBooleanAttribute");
        patch.setMethodCode("""
                @Test void shouldHandleNullValueAsBooleanAttribute() {
                    assertEquals("", attributes.get("key"));
                    assertTrue(attributes.hasKey("key"));
                }
                """);
        CoverageTestClassStabilizer stabilizer = new CoverageTestClassStabilizer();

        String patched = stabilizer.applyMethodPatches(current, java.util.List.of(patch),
                java.util.Set.of("shouldHandleNullValueAsBooleanAttribute"));
        String stabilized = stabilizer.stabilizeCandidate(current, patched,
                CoverageTestClassStabilizer.CandidateMode.RUNTIME_METHOD_PATCH);

        assertEquals(patched, stabilized);
        assertTrue(stabilized.contains("assertEquals(\"\", attributes.get(\"key\"))"));
        assertTrue(stabilized.contains("attributes.hasKey(\"key\")"));
        assertFalse(stabilized.contains("assertNull"));
    }

    public void testExplorationStillRejectsRewriteWithoutNewTest() {
        String current = "class SubjectTest { @Test void existing() { run(1); } }";
        String rewrite = "class SubjectTest { @Test void existing() { run(2); } }";

        String stabilized = new CoverageTestClassStabilizer().stabilizeCandidate(
                current, rewrite, CoverageTestClassStabilizer.CandidateMode.COVERAGE_EXPLORATION);

        assertEquals(current, stabilized);
    }
}
