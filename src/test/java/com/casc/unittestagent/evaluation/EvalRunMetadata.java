package com.casc.unittestagent.evaluation;

import org.json.JSONObject;

import java.net.URI;
import java.time.Instant;

/** Reproducibility metadata for one evaluation run. */
public final class EvalRunMetadata {
    private final String runId;
    private final String profile;
    private final int attemptsPerCase;
    private final String model;
    private final String apiHost;
    private final String manifestHash;
    private final String selectionHash;
    private final String configHash;
    private final String gitRevision;
    private final String startedAt;
    private String finishedAt = "";

    public EvalRunMetadata(String runId,
                           String profile,
                           int attemptsPerCase,
                           String model,
                           String apiUrl,
                           String manifestHash,
                           String configHash,
                           String gitRevision) {
        this(runId, profile, attemptsPerCase, model, apiUrl, manifestHash, manifestHash,
                configHash, gitRevision);
    }

    public EvalRunMetadata(String runId,
                           String profile,
                           int attemptsPerCase,
                           String model,
                           String apiUrl,
                           String manifestHash,
                           String selectionHash,
                           String configHash,
                           String gitRevision) {
        this.runId = runId;
        this.profile = profile;
        this.attemptsPerCase = attemptsPerCase;
        this.model = model;
        this.apiHost = safeHost(apiUrl);
        this.manifestHash = manifestHash;
        this.selectionHash = selectionHash;
        this.configHash = configHash;
        this.gitRevision = gitRevision;
        this.startedAt = Instant.now().toString();
    }

    public void finish() { finishedAt = Instant.now().toString(); }

    public JSONObject toJson() {
        return new JSONObject()
                .put("runId", runId)
                .put("profile", profile)
                .put("attemptsPerCase", attemptsPerCase)
                .put("model", model)
                .put("apiHost", apiHost)
                .put("manifestHash", manifestHash)
                .put("selectionHash", selectionHash)
                .put("configHash", configHash)
                .put("gitRevision", gitRevision)
                .put("javaVersion", System.getProperty("java.version", ""))
                .put("os", System.getProperty("os.name", ""))
                .put("startedAt", startedAt)
                .put("finishedAt", finishedAt);
    }

    private String safeHost(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl == null ? "" : apiUrl);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignored) {
            return "invalid";
        }
    }
}
