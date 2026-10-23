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
import com.intellij.openapi.roots.ModuleRootModificationUtil;
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

/** Actual javac, MockitoExtension, JUnit and service gates. The model is an in-process stub. */
public class MockWiringIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("MockWiringJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample;
            public class Subject {
              Repository repository = key -> "real";
              public String lookup(String key) { return repository.find(key); }
            }
            """;
    private static final String TEST = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.junit.jupiter.MockitoExtension;
            import org.mockito.Mock;
            import org.mockito.InjectMocks;
            import static org.mockito.Mockito.*;
            import static org.junit.jupiter.api.Assertions.*;
            @ExtendWith(MockitoExtension.class)
            class SubjectTest {
              @Mock Repository repository;
              @InjectMocks Subject subject;
              @BeforeEach void setup() { subject = new Subject(); }
              @Test void delegates() {
                subject.lookup("key");
                verify(repository).find("key");
              }
              @Test void subjectExists() { assertNotNull(subject); }
            }
            """;
    private static final String FIXED = TEST.replace("subject = new Subject();", "");
    private static final String INTERNAL_SOURCE = """
            package sample;
            public class Subject {
              private final java.util.Map<String, String> entries = new java.util.HashMap<>();
              public void put(String key) { entries.put(key, "value"); }
              public String lookup(String key) { return entries.get(key); }
            }
            """;
    private static final String INTERNAL_TEST = TEST.replace("@Mock Repository repository;", "@Mock java.util.Map<String, String> entries;")
            .replace("verify(repository).find(\"key\")", "verify(entries).get(\"key\")");
    private static final String BEHAVIOR_TEST = INTERNAL_TEST.replace("@Mock java.util.Map<String, String> entries;", "")
            .replace("@InjectMocks Subject subject;", "Subject subject;")
            .replace("subject.lookup(\"key\");", "subject.put(\"key\");")
            .replace("verify(entries).get(\"key\");", "assertEquals(\"value\", subject.lookup(\"key\"));");
    private Path root;
    private PsiFile source;
    private final AtomicInteger compileRepairs = new AtomicInteger();
    private final AtomicInteger classRepairs = new AtomicInteger();
    private final AtomicInteger validations = new AtomicInteger();
    private String production = SOURCE;
    private String draft = TEST;
    private String fixed = FIXED;
    private boolean malicious;
    private UnitTestGenerationService service;
    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        if (getName().contains("Internal")) { production=INTERNAL_SOURCE; draft=INTERNAL_TEST; fixed=BEHAVIOR_TEST; }
        if (getName().contains("RealBehavior")) production=SOURCE.replace("return repository.find(key);", "return \"bug\";");
        root = Files.createTempDirectory("unitmind-mock-wiring-");
        Path sources = root.resolve("src/main/java"); Files.createDirectories(sources.resolve("sample"));
        Files.writeString(sources.resolve("sample/Subject.java"), production);
        Files.writeString(sources.resolve("sample/Repository.java"), "package sample; public interface Repository { String find(String key); }");
        var vfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources); PsiTestUtil.addSourceRoot(getModule(), vfs);
        source = PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/Subject.java"));
        var llm = new LlmConfig(); llm.setEnabled(true); llm.setMaxCompileRepairRounds(2); LlmConfigManager.save(llm, null);
        var config = new CoverageConfig(); config.setMaxRounds(1); config.setMaxRepairRounds(1); config.setLineThreshold(1); config.setBranchThreshold(1); CoverageConfigManager.save(config);
        var validator = new ProjectCompileVerifier();
        service = new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(), (p, psi, file, code) -> {
            validations.incrementAndGet(); return validator.validate(p, psi, file, code);
        }, ignored -> new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) { return code(draft); }
            @Override public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                compileRepairs.incrementAndGet();
                assertTrue(request.getCompilerDiagnostics().stream().anyMatch(d -> MockWiringAnalyzer.WIRING.equals(d.getCode()) || MockWiringAnalyzer.INTERNAL.equals(d.getCode())));
                return code(fixed);
            }
            @Override public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) { throw new AssertionError("wiring repair must use shared class engine"); }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) {
                classRepairs.incrementAndGet();
                assertFalse(request.getRuntimeDiagnostics().get(0).getMockWiringEvidence().isEmpty());
                var response = code(malicious ? fixed.replace("subject.lookup(\"key\")", "subject.lookup(\"other\")") : fixed);
                response.setRepairScope("SHARED_FIXTURE"); response.setRepairDisposition("REPAIR_TEST"); response.setOracleDecision("KEEP");
                response.setOracleAuthority("NONE"); response.setOracleAuthorityRef("NONE");
                // Intentionally wrong model story must not become the diagnosis.
                response.setDiagnosisRootCauseCategory("MOCKITO_WANTED_BUT_NOT_INVOKED"); response.setDiagnosisEvidence(List.of("model says some other unstubbed object was used"));
                return response;
            }
        });
    }
    public void testGenerationChecksWiringBeforeCompilerAndRepairsWithoutRuntimeFailure() throws Exception {
        var result = background(() -> service.generateTestCode(getProject(), source, candidate()));
        assertEquals(result.getValidationResult().getDiagnostics().toString(), ValidationStatus.PASSED, result.getValidationResult().getStatus());
        assertEquals(1,compileRepairs.get()); assertEquals(1,validations.get()); assertEquals(0,classRepairs.get());
        var finalResult=background(() -> service.runFinalTestRegression(getProject(),source,result,result.getGeneratedTestFile().getContent(),()->false,CoverageProgressListener.NONE));
        assertEquals(finalResult.getRuntimeValidationReport().getMessage(),ClassJUnitStatus.PASSED,finalResult.getRuntimeValidationReport().getStatus());
        assertEquals(2,finalResult.getRuntimeValidationReport().getFinalExecution().getSucceeded());
    }
    public void testRuntimeRestoresInjectionAndPreservesAllTestBodies() throws Exception {
        var result=background(() -> service.runRuntimeValidation(getProject(),source,candidate(),draft,()->false,CoverageProgressListener.NONE));
        var report=result.getRuntimeValidationReport();
        assertEquals(report.getMessage(),ClassJUnitStatus.PASSED,report.getStatus());
        assertEquals(1,report.getInitialExecution().getFailed()); assertEquals(2,report.getFinalExecution().getSucceeded());
        assertEquals(1,classRepairs.get()); assertEquals(1,report.getFixtureRepairRounds());
        assertEquals(MockWiringAnalyzer.WIRING,report.getOracleAssessment().getRootCauseCategory());
    }
    public void testCoverageRepairsWiringAndMeasuresThePassingArtifact() throws Exception {
        var result=background(() -> service.runCoverageLoop(getProject(),source,candidate(),draft,()->false,CoverageProgressListener.NONE));
        var report=result.getCoverageReport();
        assertEquals(report.getMessage(),CoverageStatus.PASSED,report.getStatus());
        assertEquals(1,classRepairs.get()); assertEquals(1,report.getRepairRounds());
        assertTrue(report.getGenerated().getExecution().isFullySuccessful());
        assertEquals(1d,report.getGenerated().getLine().getRate());
    }
    public void testWiringProofDoesNotAuthorizeChangingInputs() throws Exception {
        malicious=true;
        var result=background(() -> service.runRuntimeValidation(getProject(),source,candidate(),draft,()->false,CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED,result.getRuntimeValidationReport().getStatus());
        assertEquals(draft,result.getGeneratedTestFile().getContent());
        assertEquals("REPAIR_SCOPE",result.getRuntimeValidationReport().getCandidateRejections().get(0).stage());
    }
    public void testFinalRegressionDoesNotSilentlyRepairExistingFailedFile() throws Exception {
        var result=background(() -> service.runFinalTestRegression(getProject(),source,candidate(),draft,()->false,CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED,result.getRuntimeValidationReport().getStatus());
        assertEquals(draft,result.getGeneratedTestFile().getContent()); assertEquals(0,classRepairs.get());
    }
    public void testInternalStateOracleIsRejectedBeforeFirstCompilationAndReplacedByBehaviorTest() throws Exception {
        var result=background(() -> service.generateTestCode(getProject(),source,candidate()));
        assertEquals(result.getValidationResult().getDiagnostics().toString(),ValidationStatus.PASSED,result.getValidationResult().getStatus());
        assertEquals(1,compileRepairs.get()); assertEquals(1,validations.get());
        assertFalse(result.getGeneratedTestFile().getContent().contains("verify(entries)"));
        var regression=background(() -> service.runFinalTestRegression(getProject(),source,result,result.getGeneratedTestFile().getContent(),()->false,CoverageProgressListener.NONE));
        assertEquals(regression.getRuntimeValidationReport().getMessage(),ClassJUnitStatus.PASSED,regression.getRuntimeValidationReport().getStatus());
        assertEquals(2,regression.getRuntimeValidationReport().getFinalExecution().getSucceeded());
    }
    public void testInternalStateOracleCannotBeSilentlyReplacedDuringRuntimeRepair() throws Exception {
        var result=background(() -> service.runRuntimeValidation(getProject(),source,candidate(),draft,()->false,CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.NEEDS_REVIEW,result.getRuntimeValidationReport().getStatus());
        var assessment=result.getRuntimeValidationReport().getOracleAssessment();
        assertEquals(MockWiringAnalyzer.INTERNAL,assessment.getRootCauseCategory());
        assertTrue(assessment.getEvidence().stream().anyMatch(e -> e.contains("独立契约授权")));
        assertFalse(assessment.getEvidence().stream().anyMatch(e -> e.contains("some other unstubbed")));
        assertEquals(draft,result.getGeneratedTestFile().getContent());
    }
    public void testRealBehaviorFailureRemainsFailedAfterWiringRepair() throws Exception {
        var result=background(() -> service.runRuntimeValidation(getProject(),source,candidate(),draft,()->false,CoverageProgressListener.NONE));
        assertEquals(ClassJUnitStatus.TEST_FAILED,result.getRuntimeValidationReport().getStatus());
        assertEquals(1,result.getRuntimeValidationReport().getFinalExecution().getFailed());
        assertTrue(result.getGeneratedTestFile().getContent().contains("verify(repository).find(\"key\")"));
        assertEquals(production,ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<String>)source::getText));
    }
    private GenerationResult candidate() {
        var result=new GenerationResult(); result.setMethodModel(new JavaSourceAnalyzer().analyze(production,"lookup"));
        var strategy=new MockStrategy(); strategy.setStrategyItems(List.of(new MockStrategyItem(true,"FIELD","repository","find","repository","mock","selected"))); result.setMockStrategy(strategy);
        var file=new GeneratedTestFile(); file.setClassName("SubjectTest"); file.setFileName("SubjectTest.java"); file.setPackageName("sample"); file.setContent(draft); result.setGeneratedTestFile(file); return result;
    }
    private static LlmGenerationResponse code(String code) { var r=new LlmGenerationResponse(); r.setTestCode(code); return r; }
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
            try { return action.call(); } catch(Exception e) { throw new RuntimeException(e); }
        },new EmptyProgressIndicator())).get(120,TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() -> ModuleRootModificationUtil.updateModel(getModule(),model -> {
                if(root!=null) for(var entry:model.getContentEntries()) if(entry.getUrl().contains(root.getFileName().toString())) model.removeContentEntry(entry);
            }));
            super.tearDown();
        } finally { if(root!=null) try(var files=Files.walk(root)) { for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
}
