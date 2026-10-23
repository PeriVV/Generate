package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.llm.*;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real JVM reproduction of SUT -> real collaborator -> constructor-null repository. No API calls. */
public class NestedFixtureRepairIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("NestedFixtureJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample;
            public class Subject {
                private final AuditService audit;
                public Subject(AuditService audit) { this.audit = audit; }
                public int run() { audit.record(); return 7; }
                public int other() { return 3; }
            }
            """;
    private static final String BROKEN_METHOD = "@Test void records() { assertEquals(7, subject.run()); }";
    private static final String FIXED_METHOD = """
            @Test void records() {
                audit = new AuditService(org.mockito.Mockito.mock(Repository.class));
                subject = new Subject(audit);
                assertEquals(7, subject.run());
            }
            """;
    private static final String TEST = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.BeforeEach;
            import static org.junit.jupiter.api.Assertions.*;
            class SubjectTest {
                AuditService audit;
                Subject subject;
                @BeforeEach void setUp() { audit = new AuditService(null); subject = new Subject(audit); }
                %s
                @Test void alreadyPassing() { assertEquals(3, subject.other()); }
            }
            """.formatted(BROKEN_METHOD);
    private Path root;
    private PsiFile source;

    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-nested-fixture-");
        Path sources = root.resolve("src/main/java");
        Files.createDirectories(sources.resolve("sample"));
        Files.writeString(sources.resolve("sample/Subject.java"), SOURCE);
        Files.writeString(sources.resolve("sample/Repository.java"), "package sample; public interface Repository { void save(String value); }");
        Files.writeString(sources.resolve("sample/AuditService.java"), """
                package sample;
                public class AuditService {
                    private final Repository repository;
                    public AuditService(Repository repository) { this.repository = repository; }
                    public void record() { repository.save("event"); }
                }
                """);
        var vfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources);
        PsiTestUtil.addSourceRoot(getModule(), vfs);
        source = PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/Subject.java"));
        LlmConfig llm = new LlmConfig(); llm.setEnabled(true); LlmConfigManager.save(llm, null);
        CoverageConfig config = new CoverageConfig(); config.setMaxRounds(3); config.setMaxRepairRounds(3);
        config.setLineThreshold(1); config.setBranchThreshold(1); CoverageConfigManager.save(config);
    }

    public void testCoverageRepairsNestedFixtureWithoutChangingOracleOrPassingTest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        LlmClient client = client(requests);
        var service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> client);
        GenerationResult result = background(() -> service.runCoverageLoop(getProject(), source,
                candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        assertEquals(result.getCoverageReport().getMessage(), CoverageStatus.PASSED, result.getCoverageReport().getStatus());
        assertEquals(1, requests.get());
        assertEquals(1, result.getCoverageReport().getRepairRounds());
        assertTrue(result.getCoverageReport().getGenerated().getExecution().isSuccessful());
        assertEquals(2, result.getCoverageReport().getGenerated().getExecution().getSucceeded());
        assertTrue(result.getGeneratedTestFile().getContent().contains("alreadyPassing"));
        var regression = background(() -> service.runFinalTestRegression(getProject(), source, result,
                result.getGeneratedTestFile().getContent(), () -> false, CoverageProgressListener.NONE));
        assertEquals(regression.getRuntimeValidationReport().getMessage(), ClassJUnitStatus.PASSED,
                regression.getRuntimeValidationReport().getStatus());
    }

    public void testIndependentRuntimeValidationAlsoRecognizesNestedFixture() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> client(requests));
        var result = background(() -> service.runRuntimeValidation(getProject(), source, candidate(), TEST,
                () -> false, CoverageProgressListener.NONE));
        var report = result.getRuntimeValidationReport();
        assertEquals(report.getMessage() + report.getAttempts().stream().map(ClassJUnitRepairAttempt::getRejectionReason).toList(),
                ClassJUnitStatus.PASSED, report.getStatus());
        assertEquals(1, report.getRuntimeRepairRounds());
        assertEquals(1, requests.get());
        assertEquals(1, report.getInitialExecution().getFailed());
        assertEquals(2, report.getFinalExecution().getSucceeded());
    }

    public void testCoverageRejectsUnsafeCandidateThenRetriesOriginalFailure() throws Exception {
        var requests = new AtomicInteger();
        var service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> rejectingClient(requests, false));
        var result = background(() -> service.runCoverageLoop(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        var report = result.getCoverageReport();
        assertEquals(report.getMessage(), CoverageStatus.PASSED, report.getStatus());
        assertEquals(2, requests.get()); assertEquals(2, report.getRepairRounds());
        assertEquals(1, report.getCandidateRejections().size());
        assertEquals(List.of("FIXTURE_DEPENDENCY_MISSING"), report.getCandidateRejections().get(0).observedFailureCategories());
        assertEquals("ORACLE_CONTRACT", report.getCandidateRejections().get(0).stage());
        assertTrue(result.getGeneratedTestFile().getContent().contains("assertEquals(7, subject.run())"));
        assertTrue(report.getGenerated().getExecution().isSuccessful());
    }

    public void testExhaustedRejectedCandidatesPreserveActualFailureAndNeverExecutePatch() throws Exception {
        var config = CoverageConfigManager.load(); config.setMaxRepairRounds(2); CoverageConfigManager.save(config);
        var requests = new AtomicInteger();
        var service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), ignored -> rejectingClient(requests, true));
        var result = background(() -> service.runCoverageLoop(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        var report = result.getCoverageReport();
        assertEquals(2, requests.get()); assertEquals(2, report.getRepairRounds());
        assertEquals(2, report.getCandidateRejections().size());
        assertEquals("FIXTURE_DEPENDENCY_MISSING", report.getOracleAssessment().getRootCauseCategory());
        assertEquals("NONE", report.getOracleAssessment().getAuthorityRef());
        assertEquals(TEST, result.getGeneratedTestFile().getContent());
        assertTrue(report.getMessage(), report.getMessage().contains("候选补丁"));
        assertTrue(report.getMessage(), report.getMessage().contains("预算已用完"));
        assertFalse(report.getMessage(), report.getMessage().contains("分类=补丁违反 Oracle 门禁"));
    }

    public void testIndependentRuntimeUsesSameRejectionFeedbackAndPreservesFailureEvidence() throws Exception {
        var requests = new AtomicInteger();
        var service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> rejectingClient(requests, false));
        var result = background(() -> service.runRuntimeValidation(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
        var report = result.getRuntimeValidationReport();
        assertEquals(report.getMessage(), ClassJUnitStatus.PASSED, report.getStatus());
        assertEquals(2, requests.get()); assertEquals(1, report.getCandidateRejections().size());
        assertEquals(1, report.getRegressionRejections());
        assertFalse(report.getAttempts().get(0).isAccepted());
        assertTrue(report.getAttempts().get(1).isAccepted());
        assertEquals(1, report.getInitialExecution().getFailed());
        assertEquals(2, report.getFinalExecution().getSucceeded());
    }

    public void testPatchShapeAndScopeViolationsUseSameBoundedFeedbackLoop() throws Exception {
        for (String stage : List.of("PATCH_SHAPE", "REPAIR_SCOPE")) {
            var requests = new AtomicInteger();
            var service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(), config -> rejectingClient(requests, false, stage));
            var result = background(() -> service.runCoverageLoop(getProject(), source, candidate(), TEST, () -> false, CoverageProgressListener.NONE));
            var report = result.getCoverageReport();
            assertEquals(report.getMessage(), CoverageStatus.PASSED, report.getStatus());
            assertEquals(2, requests.get()); assertEquals(2, report.getRepairRounds());
            assertEquals(1, report.getCandidateRejections().size());
            assertEquals(stage, report.getCandidateRejections().get(0).stage());
            assertFalse(report.getCandidateRejections().get(0).candidateHash().isBlank());
            assertEquals(List.of("FIXTURE_DEPENDENCY_MISSING"), report.getCandidateRejections().get(0).observedFailureCategories());
        }
    }

    private LlmClient rejectingClient(AtomicInteger requests, boolean alwaysReject) {
        return rejectingClient(requests, alwaysReject, "ORACLE_CONTRACT");
    }

    private LlmClient rejectingClient(AtomicInteger requests, boolean alwaysReject, String rejectionStage) {
        return new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) { throw new AssertionError("no real API"); }
            private LlmGenerationResponse response(LlmGenerationRequest request, boolean classMode) {
                int call = requests.incrementAndGet();
                assertEquals("FIXTURE_DEPENDENCY_MISSING", request.getRuntimeDiagnostics().get(0).getCategory());
                if (call > 1) {
                    String feedback = request.getPreviousAttemptSummary() + request.getUserInstruction();
                    assertTrue(feedback, feedback.contains("安全门禁"));
                    assertTrue(feedback, feedback.contains("FIXTURE_DEPENDENCY_MISSING"));
                    assertEquals(TEST, request.getExistingTestCodeSummary());
                }
                var response = new LlmGenerationResponse();
                response.setRepairDisposition("REPAIR_TEST"); response.setOracleDecision("KEEP");
                response.setOracleAuthority("NONE"); response.setOracleAuthorityRef("NONE");
                response.setDiagnosisRootCauseCategory("FIXTURE_DEPENDENCY_MISSING");
                response.setDiagnosisEvidence(List.of("Real collaborator constructor received null repository"));
                String method = alwaysReject || call == 1 ? FIXED_METHOD.replace("assertEquals(7", "assertEquals(0") : FIXED_METHOD;
                if (classMode) {
                    response.setRepairScope("METHOD_ONLY"); response.setTestCode(TEST.replace(BROKEN_METHOD, method));
                } else {
                    var patch = new LlmMethodPatch(); patch.setMethodName("records"); patch.setMethodCode(method); response.setMethodPatches(List.of(patch));
                    if (call == 1 && "PATCH_SHAPE".equals(rejectionStage)) patch.setMethodCode("@Test void records( }");
                    if (call == 1 && "REPAIR_SCOPE".equals(rejectionStage)) patch.setMethodName("alreadyPassing");
                }
                return response;
            }
            @Override public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) { return response(request, false); }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) { return response(request, true); }
        };
    }

    private LlmClient client(AtomicInteger requests) {
        return new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) { throw new AssertionError("no real API"); }
            private LlmGenerationResponse response(LlmGenerationRequest request) {
                requests.incrementAndGet();
                assertEquals("FIXTURE_DEPENDENCY_MISSING", request.getRuntimeDiagnostics().get(0).getCategory());
                assertTrue(request.getRuntimeDiagnostics().get(0).getSuggestion().contains("sample.AuditService"));
                var response = new LlmGenerationResponse();
                response.setRepairDisposition("REPAIR_TEST"); response.setOracleDecision("KEEP");
                response.setOracleAuthority("NONE"); response.setOracleAuthorityRef("NONE");
                response.setDiagnosisRootCauseCategory("FIXTURE_DEPENDENCY_MISSING");
                response.setDiagnosisEvidence(List.of("Real collaborator constructor received null repository"));
                return response;
            }
            @Override public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) {
                var response = response(request);
                var patch = new LlmMethodPatch(); patch.setMethodName("records"); patch.setMethodCode(FIXED_METHOD);
                response.setMethodPatches(List.of(patch));
                return response;
            }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) {
                var response = response(request);
                response.setRepairScope("METHOD_ONLY");
                response.setTestCode(TEST.replace(BROKEN_METHOD, FIXED_METHOD));
                return response;
            }
        };
    }
    private GenerationResult candidate() {
        var result = new GenerationResult(); result.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE, "run"));
        var file = new GeneratedTestFile(); file.setPackageName("sample"); file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java"); file.setContent(TEST); result.setGeneratedTestFile(file); return result;
    }
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
            try { return action.call(); } catch (Exception error) { throw new RuntimeException(error); }
        }, new EmptyProgressIndicator())).get(120, TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule(), model -> {
                        if (root != null) for (var entry : model.getContentEntries())
                            if (entry.getUrl().contains(root.getFileName().toString())) model.removeContentEntry(entry);
                    }));
            super.tearDown();
        }
        finally { if (root != null) try (var files = Files.walk(root)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } }
    }
}
