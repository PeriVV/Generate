package com.casc.unittestagent.coverage;

import junit.framework.TestCase;

public class MockitoNoInteractionsContradictionGuardTest extends TestCase {
    public void testRepairsOnlyContradictoryNoInteractionsAssertion() {
        String code = """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.mockito.Mockito.verify;
                import static org.mockito.Mockito.verifyNoInteractions;
                class SubjectTest {
                    Dao dao;
                    @Test void returnsEarly() {
                        verify(dao).findById("a");
                        verifyNoInteractions(dao);
                    }
                }
                """;

        MockitoNoInteractionsContradictionGuard.Result result =
                MockitoNoInteractionsContradictionGuard.inspectAndRepair(code, true);

        assertTrue(result.isChanged());
        assertTrue(result.getCode().contains("verifyNoMoreInteractions(dao)"));
        assertTrue(result.getCode().contains("Mockito.verifyNoMoreInteractions"));
        assertFalse(result.getCode().contains("verifyNoInteractions(dao)"));
    }

    public void testDoesNotRewriteUserOwnedOrIndependentMocks() {
        String code = """
                import org.junit.jupiter.api.Test;
                import static org.mockito.Mockito.*;
                class SubjectTest {
                    Dao readDao; Dao writeDao;
                    @Test void returnsEarly() {
                        verify(readDao).findById("a");
                        verifyNoInteractions(writeDao);
                    }
                }
                """;

        assertFalse(MockitoNoInteractionsContradictionGuard.inspectAndRepair(code, true).isChanged());
        assertFalse(MockitoNoInteractionsContradictionGuard.inspectAndRepair(
                code.replace("writeDao", "readDao"), false).isChanged());
    }
}
