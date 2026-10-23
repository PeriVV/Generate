package com.casc.unittestagent.evaluation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Performs all deterministic checks before the first paid model request. */
public final class EvalPreflight {
    private EvalPreflight() {}

    public static void verifyAll(List<EvalCaseManifest> cases) throws Exception {
        List<String> errors = new ArrayList<>();
        for (EvalCaseManifest item : cases) {
            try {
                verifyTarget(item);
                compileProductionSources(item);
            } catch (Exception ex) {
                errors.add(item.getId() + ": " + safeMessage(ex));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Evaluation preflight failed:\n- " + String.join("\n- ", errors));
        }
    }

    static void verifyTarget(EvalCaseManifest item) throws Exception {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        CompilationUnit unit = parser.parse(
                Files.readString(item.getTargetSourcePath(), StandardCharsets.UTF_8))
                .getResult().orElseThrow(() -> new IllegalArgumentException("Java 17 source parse failed"));
        ClassOrInterfaceDeclaration target = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals(item.simpleClassName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("target class not found: " + item.getTargetClass()));
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString()).orElse("");
        String qualifiedName = packageName.isBlank() ? target.getNameAsString()
                : packageName + "." + target.getNameAsString();
        if (!qualifiedName.equals(item.getTargetClass())) {
            throw new IllegalArgumentException("targetClass does not match source: " + qualifiedName);
        }
        if (item.getScope() == EvalCaseManifest.Scope.CLASS) {
            if (target.isInterface() || target.isAbstract()) {
                throw new IllegalArgumentException("class target must be a concrete class");
            }
            return;
        }
        List<String> expected = item.methodParameterTypes().stream()
                .map(EvalPreflight::normalizeType).toList();
        List<MethodDeclaration> matches = target.getMethodsByName(item.methodName()).stream()
                .filter(method -> method.getParameters().stream()
                        .map(parameter -> normalizeType(parameter.getType().asString()))
                        .toList().equals(expected))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("target method must resolve exactly once: " + item.getTargetMethod()
                    + ", matches=" + matches.size());
        }
    }

    static void compileProductionSources(EvalCaseManifest item) throws Exception {
        Path sourceRoot = item.getCaseDirectory().resolve("src/main/java");
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("no production Java sources");
        Path output = Files.createTempDirectory("unitmind-eval-preflight-");
        try {
            List<String> command = new ArrayList<>();
            command.add(javacExecutable().toString());
            command.addAll(List.of("-proc:none", "--release", "17", "-encoding", "UTF-8",
                    "-d", output.toString()));
            for (Path source : sources) command.add(source.toString());
            Process process = com.casc.unittestagent.coverage.LongCommandLineLauncher.start(
                    new ProcessBuilder(command).redirectErrorStream(true), output);
            ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
            process.getInputStream().transferTo(processOutput);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalArgumentException("production sources do not compile: "
                        + processOutput.toString(StandardCharsets.UTF_8));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static Path javacExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "javac.exe" : "javac";
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of(System.getProperty("java.home"), "bin", executable));
        String configuredHome = System.getenv("JAVA_HOME");
        if (configuredHome != null && !configuredHome.isBlank()) {
            candidates.add(Path.of(configuredHome, "bin", executable));
        }
        candidates.add(Path.of("/usr/bin", executable));
        return candidates.stream().filter(Files::isExecutable).findFirst()
                .orElseThrow(() -> new IllegalStateException("JDK compiler executable is not available"));
    }

    private static String normalizeType(String value) {
        return value.replace("java.lang.", "").replace(" ", "").replace("...", "[]");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Temporary preflight output is best-effort cleanup only.
        }
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
