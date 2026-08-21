package com.casc.unittestagent.evaluation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable description of one checked-in UnitMind evaluation target. */
public final class EvalCaseManifest {
    public enum Scope { METHOD, CLASS }
    public enum SourceKind { SELF, OPEN_SOURCE }
    public enum Difficulty { EASY, MEDIUM, HARD }
    public enum BranchBand { NONE, LOW, HIGH }

    private final Path caseDirectory;
    private final String id;
    private final Scope scope;
    private final SourceKind sourceKind;
    private final Difficulty difficulty;
    private final BranchBand branchBand;
    private final String targetSource;
    private final String targetClass;
    private final String targetMethod;
    private final String sourceVersion;
    private final String jdkVersion;
    private final String license;
    private final String upstream;
    private final String upstreamPath;
    private final boolean existingTests;
    private final boolean smoke;
    private final List<String> tags;

    private EvalCaseManifest(Path caseDirectory,
                             String id,
                             Scope scope,
                             SourceKind sourceKind,
                             Difficulty difficulty,
                             BranchBand branchBand,
                             String targetSource,
                             String targetClass,
                             String targetMethod,
                             String sourceVersion,
                             String jdkVersion,
                             String license,
                             String upstream,
                             String upstreamPath,
                             boolean existingTests,
                             boolean smoke,
                             List<String> tags) {
        this.caseDirectory = caseDirectory;
        this.id = id;
        this.scope = scope;
        this.sourceKind = sourceKind;
        this.difficulty = difficulty;
        this.branchBand = branchBand;
        this.targetSource = targetSource;
        this.targetClass = targetClass;
        this.targetMethod = targetMethod;
        this.sourceVersion = sourceVersion;
        this.jdkVersion = jdkVersion;
        this.license = license;
        this.upstream = upstream;
        this.upstreamPath = upstreamPath;
        this.existingTests = existingTests;
        this.smoke = smoke;
        this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
    }

    public static EvalCaseManifest fromJson(Path caseDirectory, JSONObject json) {
        String id = required(json, "id");
        Scope scope = enumValue(Scope.class, required(json, "scope"), "scope", id);
        SourceKind kind = enumValue(SourceKind.class, required(json, "sourceKind"), "sourceKind", id);
        Difficulty difficulty = enumValue(Difficulty.class, required(json, "difficulty"), "difficulty", id);
        BranchBand branchBand = enumValue(BranchBand.class,
                json.optString("branchBand", scope == Scope.METHOD ? "LOW" : "NONE"),
                "branchBand", id);
        String targetSource = required(json, "targetSource");
        String targetClass = required(json, "targetClass");
        String targetMethod = json.optString("targetMethod", "").trim();
        if (scope == Scope.METHOD && targetMethod.isBlank()) {
            throw new IllegalArgumentException(id + ": METHOD case requires targetMethod");
        }
        if (scope == Scope.CLASS && !targetMethod.isBlank()) {
            throw new IllegalArgumentException(id + ": CLASS case must not declare targetMethod");
        }
        JSONArray tagArray = json.optJSONArray("tags");
        if (tagArray == null || tagArray.isEmpty()) {
            throw new IllegalArgumentException(id + ": tags must not be empty");
        }
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < tagArray.length(); i++) {
            String tag = tagArray.optString(i, "").trim();
            if (!tag.isBlank() && !tags.contains(tag)) tags.add(tag);
        }
        String upstream = json.optString("upstream", "").trim();
        String upstreamPath = json.optString("upstreamPath", "").trim();
        if (kind == SourceKind.OPEN_SOURCE && (upstream.isBlank() || upstreamPath.isBlank())) {
            throw new IllegalArgumentException(id + ": open-source case requires upstream and upstreamPath");
        }
        return new EvalCaseManifest(caseDirectory, id, scope, kind, difficulty, branchBand,
                targetSource, targetClass, targetMethod, required(json, "sourceVersion"),
                required(json, "jdkVersion"), required(json, "license"), upstream, upstreamPath,
                json.optBoolean("existingTests", false), json.optBoolean("smoke", false), tags);
    }

    private static String required(JSONObject json, String name) {
        String value = json.optString(name, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("Missing required field: " + name);
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field, String id) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException(id + ": invalid " + field + "=" + value);
        }
    }

    public Path getCaseDirectory() { return caseDirectory; }
    public String getId() { return id; }
    public Scope getScope() { return scope; }
    public SourceKind getSourceKind() { return sourceKind; }
    public Difficulty getDifficulty() { return difficulty; }
    public BranchBand getBranchBand() { return branchBand; }
    public String getTargetSource() { return targetSource; }
    public Path getTargetSourcePath() { return caseDirectory.resolve(targetSource).normalize(); }
    public String getTargetClass() { return targetClass; }
    public String getTargetMethod() { return targetMethod; }
    public String getSourceVersion() { return sourceVersion; }
    public String getJdkVersion() { return jdkVersion; }
    public String getLicense() { return license; }
    public String getUpstream() { return upstream; }
    public String getUpstreamPath() { return upstreamPath; }
    public boolean hasExistingTests() { return existingTests; }
    public boolean isSmoke() { return smoke; }
    public List<String> getTags() { return tags; }

    public String simpleClassName() {
        int dot = targetClass.lastIndexOf('.');
        return dot < 0 ? targetClass : targetClass.substring(dot + 1);
    }

    public String methodName() {
        int open = targetMethod.indexOf('(');
        return open < 0 ? targetMethod : targetMethod.substring(0, open).trim();
    }

    public List<String> methodParameterTypes() {
        int open = targetMethod.indexOf('(');
        int close = targetMethod.lastIndexOf(')');
        if (open < 0 || close < open) return List.of();
        String body = targetMethod.substring(open + 1, close).trim();
        if (body.isBlank()) return List.of();
        List<String> types = new ArrayList<>();
        int genericDepth = 0;
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '<') genericDepth++;
            else if (current == '>') genericDepth = Math.max(0, genericDepth - 1);
            else if (current == ',' && genericDepth == 0) {
                types.add(body.substring(start, index).trim());
                start = index + 1;
            }
        }
        types.add(body.substring(start).trim());
        return types;
    }
}
