package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.coverage.CoverageProgressListener;
import com.casc.unittestagent.evaluation.EvalAttemptResult;
import com.casc.unittestagent.evaluation.EvalCaseLoader;
import com.casc.unittestagent.evaluation.EvalCaseManifest;
import com.casc.unittestagent.evaluation.EvalDatasetContract;
import com.casc.unittestagent.evaluation.EvalLlmTelemetry;
import com.casc.unittestagent.evaluation.EvalPreflight;
import com.casc.unittestagent.evaluation.EvalReportWriter;
import com.casc.unittestagent.evaluation.EvalResultStore;
import com.casc.unittestagent.evaluation.EvalRunMetadata;
import com.casc.unittestagent.evaluation.EvalSecretRedactor;
import com.casc.unittestagent.evaluation.EvalSummary;
import com.casc.unittestagent.generator.ClassTestAssembler;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.llm.DefaultLlmClientFactory;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.PathUtil;
import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Explicit, paid real-API evaluation entry point. The ordinary Gradle test task excludes this class.
 */
public class UnitMindRealApiEvaluationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor REAL_JDK = new LightProjectDescriptor() {
        @Override
        public Sdk getSdk() {
            return JavaSdk.getInstance().createJdk("UnitMindEvalJdk", System.getProperty("java.home"), false);
        }
    };

    @Override
    protected LightProjectDescriptor getProjectDescriptor() { return REAL_JDK; }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        attachEvaluationRuntime();
    }

    public void testRunRealApiEvaluation() throws Exception {
        assertTrue("This paid test must only be launched by the unitMindEval task",
                Boolean.parseBoolean(System.getProperty("unitmind.eval.enabled", "false")));
        if (Runtime.version().feature() != 17) {
            throw new IllegalStateException("unitMindEval requires a Java 17 runtime, found "
                    + Runtime.version());
        }

        Path projectRoot = Path.of(requiredProperty("unitmind.eval.root")).toAbsolutePath().normalize();
        Path casesRoot = projectRoot.resolve("evaluation/cases");
        Path reportsRoot = Path.of(requiredProperty("unitmind.eval.reports")).toAbsolutePath().normalize();
        String profile = System.getProperty("unitmind.eval.profile", "regular").trim().toLowerCase(Locale.ROOT);
        String caseFilter = System.getProperty("unitmind.eval.case", "").trim();
        String resumeRunId = System.getProperty("unitmind.eval.resume", "").trim();
        boolean promoteBaseline = Boolean.parseBoolean(
                System.getProperty("unitmind.eval.promoteBaseline", "false"));

        String apiKey = requiredEnvironment("UNITMIND_API_KEY");
        String apiUrl = requiredApiUrl(requiredEnvironment("UNITMIND_API_URL"));
        String model = requiredEnvironment("UNITMIND_MODEL");
        configureEvaluation(apiUrl, model);

        List<EvalCaseManifest> allCases = EvalCaseLoader.load(casesRoot);
        EvalDatasetContract.verify(allCases);
        // Validate every checked-in case before any paid request, even for a filtered smoke run.
        EvalPreflight.verifyAll(allCases);
        List<EvalCaseManifest> selected = EvalCaseLoader.select(allCases, profile, caseFilter);
        if (selected.isEmpty()) throw new IllegalArgumentException("No evaluation cases selected");
        if (promoteBaseline && !caseFilter.isBlank()) {
            throw new IllegalArgumentException("Baseline promotion requires an unfiltered profile run");
        }

        String manifestHash = EvalCaseLoader.fingerprint(allCases);
        String selectionHash = CodeFingerprint.sha256(selected.stream()
                .map(EvalCaseManifest::getId).sorted().reduce("", (left, right) -> left + "|" + right));
        String configHash = evaluationConfigHash(apiUrl, model);
        int attemptsPerCase = profile.equals("release") ? 3 : 1;
        String runId = resumeRunId.isBlank() ? newRunId(profile, model) : resumeRunId;
        EvalSecretRedactor redactor = new EvalSecretRedactor(apiKey);
        EvalResultStore store = new EvalResultStore(reportsRoot, runId, redactor);
        EvalRunMetadata metadata = new EvalRunMetadata(runId, profile, attemptsPerCase, model, apiUrl,
                manifestHash, selectionHash, configHash, resolveGitRevision(projectRoot));
        verifyResumeCompatibility(store, metadata);
        store.write("run.json", metadata.toJson().toString(2));

        Map<String, LoadedCase> loaded = loadSelectedCases(selected);
        Set<String> completed = store.completedKeys();
        for (EvalCaseManifest item : selected) {
            for (int attempt = 1; attempt <= attemptsPerCase; attempt++) {
                if (completed.contains(EvalResultStore.key(item.getId(), attempt))) continue;
                AttemptArtifacts artifacts = executeAttempt(loaded.get(item.getId()), attempt);
                store.checkpoint(artifacts.result, artifacts.initialCode, artifacts.finalCode);
            }
        }

        metadata.finish();
        store.write("run.json", metadata.toJson().toString(2));
        List<JSONObject> attempts = store.loadAttempts();
        JSONObject summary = EvalSummary.aggregate(attempts, metadata, selected.size());
        Path baseline = baselinePath(projectRoot, profile, model);
        EvalReportWriter.attachTrend(summary, baseline);
        EvalReportWriter.write(store, summary);
        long infrastructureFailures = attempts.stream().filter(item -> item.optBoolean("environmentError")
                || !item.optString("fatalError").isBlank()).count();
        if (promoteBaseline && infrastructureFailures == 0L) {
            EvalReportWriter.promote(summary, baseline);
        }
        assertEquals("Evaluation reports were written to " + store.getRunDirectory()
                + ", but infrastructure failures were recorded", 0L, infrastructureFailures);
    }

    private AttemptArtifacts executeAttempt(LoadedCase loaded, int attempt) throws Exception {
        focusTarget(loaded);
        Future<AttemptArtifacts> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> {
            long started = System.currentTimeMillis();
            EvalAttemptResult attemptResult = new EvalAttemptResult(loaded.manifest, attempt);
            EvalLlmTelemetry telemetry = new EvalLlmTelemetry(new DefaultLlmClientFactory());
            String initialCode = "";
            String finalCode = "";
            try {
                if (loaded.manifest.getScope() == EvalCaseManifest.Scope.METHOD) {
                    MethodRun methodRun = runMethod(loaded, telemetry);
                    GenerationResult generated = methodRun.result;
                    initialCode = methodRun.initialCode;
                    finalCode = generated.getGeneratedTestFile() == null ? ""
                            : generated.getGeneratedTestFile().getContent();
                    attemptResult.captureGeneration(generated);
                    attemptResult.setInitialValidation(methodRun.initialValidation);
                    attemptResult.setFinalValidation(methodRun.finalValidation);
                    attemptResult.captureExecution(generated.getCoverageReport());
                } else {
                    ClassRun classRun = runClass(loaded, telemetry);
                    ClassGenerationResult generated = classRun.result;
                    initialCode = classRun.initialCode;
                    attemptResult.captureClassGeneration(generated);
                    attemptResult.setInitialValidation(classRun.initialValidation);
                    attemptResult.setFinalValidation(generated.getValidationResult());
                    attemptResult.captureClassExecution(generated.getClassJUnitRepairReport());
                    if (generated.getGeneratedTestFile() != null) {
                        finalCode = generated.getGeneratedTestFile().getContent();
                    }
                }
            } catch (Exception ex) {
                attemptResult.setFatalError(safeMessage(ex));
            } finally {
                attemptResult.setTelemetry(telemetry);
                attemptResult.setDurationMillis(System.currentTimeMillis() - started);
            }
            return new AttemptArtifacts(attemptResult, initialCode, finalCode);
        }, new EmptyProgressIndicator()));
        return future.get(30, TimeUnit.MINUTES);
    }

    private MethodRun runMethod(LoadedCase loaded, EvalLlmTelemetry telemetry) {
        UnitTestGenerationService service = methodService(telemetry);
        GenerationResult result = service.analyzeMethod(getProject(), loaded.psiFile, myFixture.getEditor());
        result = service.planScenarios(result);
        result = service.planMockStrategy(result);
        result = service.generateTestCodeCandidate(getProject(), loaded.psiFile, result);
        GeneratedTestFile initialFile = result.getGeneratedTestFile();
        String initialCode = initialFile == null || initialFile.getContent() == null
                ? "" : initialFile.getContent();
        ValidationResult initialValidation = initialFile == null
                ? ValidationResult.status(ValidationStatus.NOT_RUN)
                : service.validateCode(getProject(), loaded.psiFile, initialFile, initialCode);
        if (initialFile != null) {
            GeneratedTestFile repaired = service.validateAndRepair(getProject(), loaded.psiFile, result,
                    initialFile, LlmConfigManager.load());
            result.setGeneratedTestFile(repaired);
        } else {
            result.setValidationResult(initialValidation);
        }
        // Coverage validates more candidates and mutates result.validation; retain the verdict of
        // the dedicated generation compile-repair loop for Compile@Budget and repair telemetry.
        ValidationResult finalValidation = result.getValidationResult();
        if (result.getValidationResult().getStatus() == ValidationStatus.PASSED
                && result.getGeneratedTestFile() != null) {
            service.runCoverageLoop(getProject(), loaded.psiFile, result,
                    result.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE);
        }
        return new MethodRun(result, initialValidation, finalValidation, initialCode);
    }

    private ClassRun runClass(LoadedCase loaded, EvalLlmTelemetry telemetry) {
        UnitTestGenerationService methodService = methodService(telemetry);
        ClassUnitTestGenerationService classService = new ClassUnitTestGenerationService(
                new JavaSourceAnalyzer(), new TestDataGenerator(), methodService, new ClassTestAssembler());
        ClassGenerationResult result = classService.analyzeClass(getProject(), loaded.psiFile, myFixture.getEditor());
        result = classService.generateClassTest(getProject(), loaded.psiFile, result, null);
        ValidationResult aggregateValidation = result.getValidationResult();
        String initialCode = result.getGeneratedTestFile() == null ? ""
                : result.getGeneratedTestFile().getContent();
        if (aggregateValidation != null && aggregateValidation.getStatus() == ValidationStatus.PASSED
                && result.getGeneratedTestFile() != null) {
            result = classService.runFinalJUnitRepair(getProject(), loaded.psiFile, result,
                    initialCode, () -> false, CoverageProgressListener.NONE);
        }
        return new ClassRun(result, aggregateValidation, initialCode);
    }

    private UnitTestGenerationService methodService(EvalLlmTelemetry telemetry) {
        return new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(),
                new ProjectCompileVerifier(), telemetry);
    }

    private Map<String, LoadedCase> loadSelectedCases(List<EvalCaseManifest> selected) throws Exception {
        Map<String, LoadedCase> result = new HashMap<>();
        Path materializedRoot = Files.createTempDirectory("unitmind-eval-cases-");
        materializedRoot.toFile().deleteOnExit();
        for (EvalCaseManifest item : selected) {
            // Copy each case's full source tree onto real disk and register the
            // src/main/java directory as a module source root. The compile sandbox
            // resolves same-package collaborator classes from the javac sourcepath,
            // which requires disk-backed, package-aligned roots; the in-memory
            // temp:// fixture layout cannot serve that.
            Path caseRoot = materializedRoot.resolve(item.getId());
            try (Stream<Path> paths = Files.walk(item.getCaseDirectory().resolve("src"))) {
                for (Path file : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                    String relative = item.getCaseDirectory().relativize(file).toString().replace('\\', '/');
                    Path diskFile = caseRoot.resolve(relative);
                    Files.createDirectories(diskFile.getParent());
                    Files.copy(file, diskFile);
                }
            }
            Path productionRoot = caseRoot.resolve("src/main/java");
            VirtualFile sourceRoot = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(productionRoot.toRealPath());
            if (sourceRoot == null) {
                throw new IllegalStateException(item.getId() + ": materialized source root not found");
            }
            PsiTestUtil.addSourceContentToRoots(getModule(), sourceRoot);
            Path materializedTarget = caseRoot.resolve(item.getCaseDirectory()
                    .relativize(item.getTargetSourcePath()).toString().replace('\\', '/'));
            PsiFile target = null;
            try (Stream<Path> paths = Files.walk(productionRoot)) {
                for (Path file : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                    String relative = productionRoot.relativize(file).toString().replace('\\', '/');
                    VirtualFile virtualFile = sourceRoot.findFileByRelativePath(relative);
                    if (virtualFile == null) {
                        throw new IllegalStateException(item.getId() + ": VFS file missing for " + relative);
                    }
                    PsiFile added = PsiManager.getInstance(getProject()).findFile(virtualFile);
                    if (added == null) {
                        throw new IllegalStateException(item.getId() + ": PSI file missing for " + relative);
                    }
                    if (file.normalize().equals(materializedTarget.normalize())) {
                        target = added;
                    }
                }
            }
            if (target == null) throw new IllegalStateException(item.getId() + ": target PSI file was not loaded");
            result.put(item.getId(), new LoadedCase(item, target));
        }
        return result;
    }

    private void focusTarget(LoadedCase loaded) {
        myFixture.openFileInEditor(loaded.psiFile.getVirtualFile());
        if (!(loaded.psiFile instanceof PsiJavaFile javaFile)) {
            throw new IllegalArgumentException(loaded.manifest.getId() + ": target is not a Java PSI file");
        }
        PsiClass targetClass = null;
        for (PsiClass candidate : javaFile.getClasses()) {
            if (loaded.manifest.simpleClassName().equals(candidate.getName())) {
                targetClass = candidate;
                break;
            }
        }
        if (targetClass == null) throw new IllegalArgumentException("PSI target class not found");
        int offset = targetClass.getTextOffset() + 1;
        if (loaded.manifest.getScope() == EvalCaseManifest.Scope.METHOD) {
            List<String> expectedTypes = loaded.manifest.methodParameterTypes().stream()
                    .map(this::normalizeType).toList();
            List<PsiMethod> matches = new ArrayList<>();
            for (PsiMethod method : targetClass.findMethodsByName(loaded.manifest.methodName(), false)) {
                List<String> actualTypes = List.of(method.getParameterList().getParameters()).stream()
                        .map(parameter -> normalizeType(parameter.getType().getPresentableText())).toList();
                if (actualTypes.equals(expectedTypes)) matches.add(method);
            }
            if (matches.size() != 1) {
                throw new IllegalArgumentException("PSI method target did not resolve exactly once: "
                        + loaded.manifest.getTargetMethod());
            }
            offset = matches.get(0).getTextOffset() + 1;
        }
        Editor editor = myFixture.getEditor();
        editor.getCaretModel().moveToOffset(offset);
    }

    private void attachEvaluationRuntime() throws Exception {
        Set<String> paths = new HashSet<>();
        for (String name : List.of(
                "org.junit.jupiter.api.Test",
                "org.junit.jupiter.api.Assertions",
                "org.junit.jupiter.api.extension.ExtendWith",
                "org.mockito.Mockito",
                "org.mockito.junit.jupiter.MockitoExtension",
                "org.junit.platform.console.ConsoleLauncher",
                "org.opentest4j.AssertionFailedError",
                "net.bytebuddy.ByteBuddy",
                "net.bytebuddy.agent.ByteBuddyAgent",
                "org.objenesis.Objenesis")) {
            Class<?> type = Class.forName(name);
            String path = PathUtil.getJarPathForClass(type);
            if (path != null && paths.add(path)) {
                File root = new File(path);
                PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(),
                        "unitmind-eval-" + paths.size(), root.getParent(), root.getName());
            }
        }
    }

    private void configureEvaluation(String apiUrl, String model) {
        LlmConfig config = new LlmConfig();
        config.setEnabled(true);
        config.setApiUrl(apiUrl);
        config.setModel(model);
        config.setTimeoutSeconds(300);
        config.setMaxTokens(LlmConfig.DEFAULT_MAX_TOKENS);
        config.setMaxCompileRepairRounds(5);
        config.setTemperature(0.2d);
        config.setFallbackToRuleBased(true);
        LlmConfigManager.save(config, null);
        CoverageConfigManager.save(fixedCoverageConfig());
    }

    private CoverageConfig fixedCoverageConfig() {
        CoverageConfig config = new CoverageConfig();
        config.setLineThreshold(0.90d);
        config.setBranchThreshold(0.80d);
        config.setMaxRounds(5);
        config.setMaxRepairRounds(5);
        config.setExecutionTimeoutSeconds(60);
        config.setExistingBaselineEnabled(true);
        return config;
    }

    private String evaluationConfigHash(String apiUrl, String model) {
        return CodeFingerprint.sha256("java=17|api=" + apiUrl + "|model=" + model
                + "|temperature=0.2|maxTokens=" + LlmConfig.DEFAULT_MAX_TOKENS + "|compileRepair=5|coverageExplore=5"
                + "|coverageRepair=5|classRuntimeRepair=5|classCompileRepair=3"
                + "|classRepairProtocol=full-class-monotonic-v1"
                + "|executionTimeout=60|line=0.9|branch=0.8|fallback=true");
    }

    private void verifyResumeCompatibility(EvalResultStore store, EvalRunMetadata metadata) throws Exception {
        Path runFile = store.getRunDirectory().resolve("run.json");
        if (!Files.isRegularFile(runFile)) return;
        JSONObject previous = new JSONObject(Files.readString(runFile, StandardCharsets.UTF_8));
        JSONObject current = metadata.toJson();
        for (String key : List.of("profile", "model", "manifestHash", "selectionHash", "configHash")) {
            if (!previous.optString(key).equals(current.optString(key))) {
                throw new IllegalArgumentException("Cannot resume run: " + key + " changed");
            }
        }
    }

    private Path baselinePath(Path projectRoot, String profile, String model) {
        String safeModel = model.replaceAll("[^A-Za-z0-9._-]", "_");
        return projectRoot.resolve("evaluation/baselines/" + profile + "-" + safeModel + ".json");
    }

    private String newRunId(String profile, String model) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        return timestamp + "-" + profile + "-" + model.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String resolveGitRevision(Path projectRoot) {
        String configured = System.getProperty("unitmind.eval.gitRevision", "").trim();
        if (!configured.isBlank() && !configured.equals("unknown")) return configured;
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(projectRoot.toFile()).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return "unknown";
            String revision = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return revision.isBlank() ? "unknown" : revision;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private String normalizeType(String value) {
        return value.replace("java.lang.", "").replace(" ", "").replace("...", "[]");
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("Missing system property: " + name);
        return value;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value.trim();
    }

    private String requiredApiUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("UNITMIND_API_URL must be an absolute HTTP(S) URL");
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid UNITMIND_API_URL: " + error.getMessage());
        }
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record LoadedCase(EvalCaseManifest manifest, PsiFile psiFile) {}
    private record MethodRun(GenerationResult result,
                             ValidationResult initialValidation,
                             ValidationResult finalValidation,
                             String initialCode) {}
    private record ClassRun(ClassGenerationResult result,
                            ValidationResult initialValidation,
                            String initialCode) {}
    private record AttemptArtifacts(EvalAttemptResult result, String initialCode, String finalCode) {}
}
