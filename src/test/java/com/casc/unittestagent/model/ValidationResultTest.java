package com.casc.unittestagent.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ValidationResultTest {
    @Test
    public void passedResultMustMatchExactCandidateHash() {
        ValidationResult result = ValidationResult.status(ValidationStatus.PASSED);
        result.setCodeHash("abc");

        assertTrue(result.isPassedFor("abc"));
        assertFalse(result.isPassedFor("def"));
    }

    @Test
    public void environmentDiagnosticsAreNotRepairable() {
        ValidationResult result = ValidationResult.status(ValidationStatus.ENVIRONMENT_ERROR);
        result.setDiagnostics(List.of(new CompileDiagnostic(
                "ERROR", "SDK_NOT_CONFIGURED", "missing sdk", -1, -1, "", false)));

        assertFalse(result.hasRepairableErrors());
    }
}
