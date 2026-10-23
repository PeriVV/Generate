package com.casc.unittestagent.model;

import java.util.List;

/** Candidate safety is a separate outcome from the failure of the last actually executed test. */
public record RepairCandidateRejection(String stage, String candidateHash, String reason,
                                       List<String> observedFailureCategories) {
    public RepairCandidateRejection {
        stage = stage == null ? "" : stage;
        candidateHash = candidateHash == null ? "" : candidateHash;
        reason = reason == null ? "" : reason.substring(0, Math.min(reason.length(), 6000));
        observedFailureCategories = observedFailureCategories == null ? List.of() : List.copyOf(observedFailureCategories);
    }
}
