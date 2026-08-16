package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

public class ConstraintExtractorBranchTest extends TestCase {
    public void testCapturesOrdinaryIfAndNestedPathConstraint() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                class Subject {
                    int run(Input input) {
                        if (input.ready()) {
                            if (input.value() > 0) return 1;
                        }
                        return 0;
                    }
                }
                """, "run");

        assertEquals(2, method.getBranches().size());
        assertEquals("conditional", method.getBranches().get(0).getAction());
        assertTrue(method.getBranches().get(0).getSourceStartLine() > 0);
        assertFalse(method.getBranches().get(0).getBranchId().isBlank());
        assertEquals("return", method.getBranches().get(1).getAction());
        assertTrue(method.getBranches().get(1).getPreconditions().contains("input.ready()"));
    }

    public void testNestedThrowBelongsOnlyToInnerBranch() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                class Subject {
                    void run(Input input) {
                        if (input.ready()) {
                            if (input.invalid()) throw new IllegalArgumentException();
                        }
                    }
                }
                """, "run");

        assertEquals(2, method.getBranches().size());
        assertEquals("conditional", method.getBranches().get(0).getAction());
        assertEquals("throw", method.getBranches().get(1).getAction());
        assertEquals(java.util.List.of("input.ready()"), method.getBranches().get(1).getPreconditions());
    }
}
