package com.casc.unittestagent.evaluation;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Loads, validates, filters, and fingerprints checked-in evaluation cases. */
public final class EvalCaseLoader {
    private EvalCaseLoader() {}

    public static List<EvalCaseManifest> load(Path casesRoot) throws Exception {
        if (!Files.isDirectory(casesRoot)) {
            throw new IllegalArgumentException("Evaluation cases directory does not exist: " + casesRoot);
        }
        List<Path> manifests;
        try (Stream<Path> paths = Files.walk(casesRoot)) {
            manifests = paths.filter(path -> path.getFileName().toString().equals("case.json"))
                    .sorted().toList();
        }
        List<EvalCaseManifest> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (Path manifestPath : manifests) {
            JSONObject json = new JSONObject(Files.readString(manifestPath, StandardCharsets.UTF_8));
            EvalCaseManifest manifest = EvalCaseManifest.fromJson(manifestPath.getParent(), json);
            if (!ids.add(manifest.getId())) {
                throw new IllegalArgumentException("Duplicate evaluation case id: " + manifest.getId());
            }
            String target = manifest.getScope() + "|" + manifest.getTargetClass() + "|" + manifest.getTargetMethod();
            if (!targets.add(target)) {
                throw new IllegalArgumentException("Duplicate evaluation target: " + target);
            }
            Path caseDirectory = manifest.getCaseDirectory().toAbsolutePath().normalize();
            Path targetSource = manifest.getTargetSourcePath().toAbsolutePath().normalize();
            if (!targetSource.startsWith(caseDirectory)) {
                throw new IllegalArgumentException(manifest.getId() + ": targetSource escapes case directory");
            }
            if (!Files.isRegularFile(targetSource)) {
                throw new IllegalArgumentException(manifest.getId() + ": missing target source "
                        + manifest.getTargetSourcePath());
            }
            if (manifest.getSourceKind() == EvalCaseManifest.SourceKind.OPEN_SOURCE
                    && manifest.getUpstream().isBlank()) {
                throw new IllegalArgumentException(manifest.getId() + ": open-source case requires upstream");
            }
            boolean testSources;
            Path testRoot = manifest.getCaseDirectory().resolve("src/test/java");
            if (!Files.isDirectory(testRoot)) {
                testSources = false;
            } else {
                try (Stream<Path> paths = Files.walk(testRoot)) {
                    testSources = paths.anyMatch(path -> path.toString().endsWith(".java"));
                }
            }
            if (testSources != manifest.hasExistingTests()) {
                throw new IllegalArgumentException(manifest.getId()
                        + ": existingTests does not match checked-in test sources");
            }
            cases.add(manifest);
        }
        cases.sort(Comparator.comparing(EvalCaseManifest::getId));
        return cases;
    }

    public static List<EvalCaseManifest> select(List<EvalCaseManifest> cases,
                                                String profile,
                                                String caseFilter) {
        String normalizedProfile = profile == null ? "regular" : profile.trim().toLowerCase();
        if (!List.of("smoke", "regular", "release", "real").contains(normalizedProfile)) {
            throw new IllegalArgumentException("Unknown evaluation profile: " + profile);
        }
        String filter = caseFilter == null ? "" : caseFilter.trim();
        return cases.stream()
                .filter(item -> switch (normalizedProfile) {
                    case "smoke" -> item.isSmoke();
                    // The real-project track: vendored production sources only.
                    case "real" -> item.getSourceKind() == EvalCaseManifest.SourceKind.OPEN_SOURCE
                            && (item.getId().startsWith("real-") || item.getId().startsWith("class-real-"));
                    default -> true;
                })
                .filter(item -> filter.isBlank() || item.getId().equals(filter))
                .toList();
    }

    public static String fingerprint(List<EvalCaseManifest> cases) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (EvalCaseManifest item : cases.stream().sorted(Comparator.comparing(EvalCaseManifest::getId)).toList()) {
            List<Path> files;
            try (Stream<Path> paths = Files.walk(item.getCaseDirectory())) {
                files = paths.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                digest.update(item.getId().getBytes(StandardCharsets.UTF_8));
                digest.update(item.getCaseDirectory().relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
