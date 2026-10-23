package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.ClassJUnitRepairScope;
import junit.framework.TestCase;

import java.util.Set;

public class ClassRuntimeRepairStabilizerTest extends TestCase {
    private final ClassRuntimeRepairStabilizer stabilizer = new ClassRuntimeRepairStabilizer();

    public void testInitializationRepairSharesNormalizationButCannotChangeTestArrange() {
        String source = "import static org.mockito.Mockito.*; class T { int field=1; @Test void value(){verify(client).send(anyString(), true);} }";
        String fixed = source.replace("field=1", "field=2").replace("anyString(), true", "anyString(), eq(true)");
        stabilizer.validateInitializationRepair(source, fixed);
        try {
            stabilizer.validateInitializationRepair(source, fixed.replace("void value(){", "void value(){int input=0;"));
            fail("initialization repair must not change test body Arrange");
        } catch (RepairCandidateRejectedException expected) {
            assertEquals(RepairCandidateRejectedException.Stage.REPAIR_SCOPE, expected.getStage());
        }
    }

    public void testMethodOnlyChangesOnlyFailingTestAndProtectsFixture() {
        String proposed = current()
                .replace("int shared = 1;", "int shared = 9;")
                .replace("assertEquals(2, shared);", "assertEquals(1, shared);")
                .replace("assertEquals(1, shared);", "assertEquals(7, shared);");

        ClassRuntimeRepairStabilizer.Result result = stabilizer.stabilize(
                current(), proposed, ClassJUnitRepairScope.METHOD_ONLY,
                Set.of("sample.SubjectTest#passing()"), Set.of("sample.SubjectTest#failing()"));

        assertTrue(result.getCode().contains("int shared = 1;"));
        assertTrue(result.getCode().contains("void passing()"));
        assertTrue(result.getCode().contains("assertEquals(1, shared);"));
        assertTrue(result.getCode().contains("assertEquals(7, shared);"));
        assertFalse(result.isFixtureChanged());
        assertEquals(Set.of("failing"), Set.copyOf(result.getChangedTestIds()));
    }

    public void testSharedFixtureCanChangeButPassingTestBodyIsRestored() {
        String proposed = current()
                .replace("int shared = 1;", "int shared = 2;")
                .replace("assertEquals(1, shared);", "assertEquals(99, shared);")
                .replace("assertEquals(2, shared);", "assertEquals(2, shared + 0);");

        ClassRuntimeRepairStabilizer.Result result = stabilizer.stabilize(
                current(), proposed, ClassJUnitRepairScope.SHARED_FIXTURE,
                Set.of("passing"), Set.of("failing"));

        assertTrue(result.getCode().contains("int shared = 2;"));
        assertTrue(result.getCode().contains("assertEquals(1, shared);"));
        assertFalse(result.getCode().contains("assertEquals(99, shared);"));
        assertTrue(result.isFixtureChanged());
    }

    public void testRejectsDeletedOrDisabledTest() {
        String deleted = current().replaceAll("(?s)\\s*@Test void failing\\(\\) \\{.*?\\n    }", "");
        try {
            stabilizer.stabilize(current(), deleted, ClassJUnitRepairScope.SHARED_FIXTURE,
                    Set.of("passing"), Set.of("failing"));
            fail("Expected deleted test to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("增删或改名"));
        }

        String disabled = current().replace("@Test void failing", "@Disabled @Test void failing")
                .replace("import org.junit.jupiter.api.Test;",
                        "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.Disabled;");
        try {
            stabilizer.stabilize(current(), disabled, ClassJUnitRepairScope.SHARED_FIXTURE,
                    Set.of("passing"), Set.of("failing"));
            fail("Expected disabled test to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Disabled"));
        }
    }

    public void testRejectsConstantFalseAssumptionInAnyArgumentPosition() {
        // JUnit5 assumeTrue(boolean,String) 与 JUnit4 assumeTrue(String,boolean) 参数顺序不同，
        // 恒假字面量在任何实参位置都必须被拒绝。
        String messageFirst = current().replace("assertEquals(2, shared);",
                "org.junit.Assume.assumeTrue(\"skip\", false);");
        try {
            stabilizer.stabilize(current(), messageFirst, ClassJUnitRepairScope.METHOD_ONLY,
                    Set.of("passing"), Set.of("failing"));
            fail("Expected constant-false assumption to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("assumption"));
        }

        String booleanFirst = current().replace("assertEquals(2, shared);",
                "Assumptions.assumeTrue(false, \"skip\");");
        try {
            stabilizer.stabilize(current(), booleanFirst, ClassJUnitRepairScope.METHOD_ONLY,
                    Set.of("passing"), Set.of("failing"));
            fail("Expected constant-false assumption to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("assumption"));
        }
    }

    private String current() {
        return """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SubjectTest {
                    int shared = 1;

                    // UnitMind method: sample.Subject#first()
                    @Test void passing() {
                        assertEquals(1, shared);
                    }

                    // UnitMind method: sample.Subject#second()
                    @Test void failing() {
                        assertEquals(2, shared);
                    }
                }
                """;
    }
}
