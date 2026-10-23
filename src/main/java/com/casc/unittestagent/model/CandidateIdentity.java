package com.casc.unittestagent.model;

import java.util.Objects;

/** The three independent inputs to a verification result. Raw runtime settings never enter a report. */
public record CandidateIdentity(String sourceHash, String codeHash, String environmentHash) {
    public CandidateIdentity {
        Objects.requireNonNull(sourceHash);
        Objects.requireNonNull(codeHash);
        Objects.requireNonNull(environmentHash);
        if (sourceHash.isBlank() || codeHash.isBlank() || environmentHash.isBlank())
            throw new IllegalArgumentException("验证身份缺少源码、测试或环境指纹。");
    }
}
