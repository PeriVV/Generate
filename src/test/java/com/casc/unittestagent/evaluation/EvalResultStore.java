package com.casc.unittestagent.evaluation;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checkpointed result and generated-source storage for long-running real API evaluations. */
public final class EvalResultStore {
    private final Path runDirectory;
    private final Path attemptsFile;
    private final EvalSecretRedactor redactor;

    public EvalResultStore(Path reportsRoot, String runId, EvalSecretRedactor redactor) throws Exception {
        this.runDirectory = reportsRoot.resolve(runId).normalize();
        this.attemptsFile = runDirectory.resolve("attempts.jsonl");
        this.redactor = redactor;
        Files.createDirectories(runDirectory);
        Files.createDirectories(runDirectory.resolve("cases"));
    }

    public Set<String> completedKeys() throws Exception {
        Set<String> keys = new HashSet<>();
        if (!Files.isRegularFile(attemptsFile)) return keys;
        for (String line : Files.readAllLines(attemptsFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JSONObject json = new JSONObject(line);
            keys.add(key(json.getString("caseId"), json.getInt("attempt")));
        }
        return keys;
    }

    public void checkpoint(EvalAttemptResult result,
                           String initialCode,
                           String finalCode) throws Exception {
        Path attemptDirectory = runDirectory.resolve("cases")
                .resolve(result.getManifest().getId())
                .resolve(Integer.toString(result.getAttempt()));
        Files.createDirectories(attemptDirectory);
        Files.writeString(attemptDirectory.resolve("initial-test.java"),
                redactor.safe(initialCode), StandardCharsets.UTF_8);
        Files.writeString(attemptDirectory.resolve("final-test.java"),
                redactor.safe(finalCode), StandardCharsets.UTF_8);
        JSONObject json = result.toJson(redactor);
        Files.writeString(attemptDirectory.resolve("diagnostics.json"),
                json.toString(2), StandardCharsets.UTF_8);
        Files.writeString(attemptsFile, json + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public List<JSONObject> loadAttempts() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        if (!Files.isRegularFile(attemptsFile)) return result;
        for (String line : Files.readAllLines(attemptsFile, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) result.add(new JSONObject(line));
        }
        return result;
    }

    public void write(String name, String content) throws Exception {
        Files.writeString(runDirectory.resolve(name), content == null ? "" : content, StandardCharsets.UTF_8);
    }

    public Path getRunDirectory() { return runDirectory; }
    public static String key(String caseId, int attempt) { return caseId + "#" + attempt; }
}
