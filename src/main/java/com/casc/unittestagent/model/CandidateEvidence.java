package com.casc.unittestagent.model;

import java.util.Map;
import java.util.Objects;

/** Immutable input snapshot, shared by compile/runtime/coverage reports for the same candidate. */
public record CandidateEvidence(CandidateIdentity identity, long projectRootsRevision, Map<String, String> inputs) {
    public CandidateEvidence {
        Objects.requireNonNull(identity);
        inputs = Map.copyOf(inputs);
    }

    public boolean matches(String codeHash, String sourceHash) {
        return identity.codeHash().equals(codeHash) && identity.sourceHash().equals(sourceHash);
    }
}
