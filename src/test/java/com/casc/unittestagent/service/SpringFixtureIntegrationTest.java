package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.llm.*;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.*;
import com.casc.unittestagent.validation.ProjectCompileVerifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.PathUtil;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Spring/JUnit and javac; model responses are local stubs and contain no business sources. */
public class SpringFixtureIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("SpringFixtureJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package sample;
            import org.springframework.beans.factory.annotation.Value;
            public class Subject {
                @Value("${service.name}") private String name;
                @Value("${server.port:8080}") private int port;
                @Value("#{'${feature.exposed:off}' != 'off'}") private Boolean enabled;
                public String value() { return enabled ? name + ":" + port : "off"; }
            }
            """;
    private static final String GOOD = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.springframework.test.context.junit.jupiter.SpringExtension;
            import org.springframework.test.context.ContextConfiguration;
            import org.springframework.test.context.TestPropertySource;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.context.annotation.*;
            import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
            import static org.junit.jupiter.api.Assertions.*;
            @ExtendWith(SpringExtension.class)
            @ContextConfiguration(classes=SubjectTest.Config.class)
            @TestPropertySource(properties={"service.name=demo","server.port=9090","feature.exposed=on"})
            class SubjectTest {
                @Configuration(proxyBeanMethods=false) static class Config {
                    @Bean static PropertySourcesPlaceholderConfigurer placeholders() { return new PropertySourcesPlaceholderConfigurer(); }
                    @Bean Subject subject() { return new Subject(); }
                }
                @Autowired Subject subject;
                @Test void actualPropertyKeysAreBound() { assertEquals("demo:9090", subject.value()); }
                @Test void objectComesFromContainer() { assertNotNull(subject.value()); }
                @Test void typedAndSpelPropertiesAreConverted() { assertTrue(subject.value().endsWith(":9090")); }
            }
            """;
    private static final String MISSING = GOOD.replace("\"service.name=demo\",", "");
    private Path root;
    private PsiFile source;
    private UnitTestGenerationService service;
    private final AtomicInteger compileRepairs = new AtomicInteger();
    private final AtomicInteger runtimeRepairs = new AtomicInteger();
    private boolean changeOracle;
    private String generated = GOOD;

    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        // Only the temporary project gets these dependencies, just like a user's Spring project.
        for (Class<?> type : List.of(org.springframework.context.ApplicationContext.class, org.springframework.beans.factory.BeanFactory.class,
                org.springframework.core.SpringVersion.class, org.springframework.expression.Expression.class,
                org.springframework.aop.SpringProxy.class, org.springframework.test.context.TestContext.class,
                org.apache.commons.logging.Log.class, io.micrometer.observation.ObservationRegistry.class, io.micrometer.common.KeyValue.class))
            ModuleRootModificationUtil.addModuleLibrary(getModule(), com.intellij.openapi.vfs.VfsUtil.getUrlForLibraryRoot(new java.io.File(PathUtil.getJarPathForClass(type))));
        root=Files.createTempDirectory("unitmind-spring-fixture-"); Path sources=Files.createDirectories(root.resolve("src/main/java/sample"));
        Files.writeString(sources.resolve("Subject.java"), SOURCE);
        var vfs=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources.getParent()); PsiTestUtil.addSourceRoot(getModule(),vfs);
        source=PsiManager.getInstance(getProject()).findFile(vfs.findFileByRelativePath("sample/Subject.java"));
        var llm=new LlmConfig();llm.setEnabled(true);llm.setMaxCompileRepairRounds(2);LlmConfigManager.save(llm,null);
        var config=new CoverageConfig();config.setMaxRounds(1);config.setMaxRepairRounds(1);config.setLineThreshold(1);config.setBranchThreshold(0.5);CoverageConfigManager.save(config);
        service=new UnitTestGenerationService(new ProjectSymbolSnapshotBuilder(),new ProjectCompileVerifier(), ignored -> new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) {
                assertEquals(List.of("service.name"),request.getSpringPropertyBindings().get(0).keys());
                assertTrue(new LlmPromptBuilder().userPrompt(request).contains("springPropertyBindings"));
                return code(generated);
            }
            @Override public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                compileRepairs.incrementAndGet();
                assertTrue(request.getCompilerDiagnostics().stream().anyMatch(d -> d.getCode().equals("SPRING_CONTAINER_OWNERSHIP")));
                assertFalse(request.getSpringPropertyBindings().isEmpty()); return code(GOOD);
            }
            @Override public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) { throw new AssertionError("Spring initialization must use shared runtime engine"); }
            @Override public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) {
                runtimeRepairs.incrementAndGet();
                assertTrue(request.getUserInstruction().contains("SHARED_FIXTURE"));
                var d=request.getRuntimeDiagnostics().get(0); assertEquals(SpringFixtureAnalyzer.BINDING_FAILURE,d.getCategory());
                assertTrue(d.getRootCauseMessage().contains("service.name")); assertFalse(d.getEvidenceArchivePath().isBlank());
                var response=code(changeOracle ? GOOD.replace("assertEquals(\"demo:9090\"", "assertEquals(\"changed\"") : GOOD);
                response.setRepairScope("METHOD_ONLY"); // Runner, not model, must control the shared scope.
                response.setRepairDisposition("REPAIR_TEST"); response.setOracleDecision("KEEP"); response.setOracleAuthority("NONE"); response.setOracleAuthorityRef("NONE");
                response.setDiagnosisRootCauseCategory("DOMAIN_PRECONDITION_FAILURE"); response.setDiagnosisEvidence(List.of("local response misclassification must not override actual initialization evidence"));
                return response;
            }
        });
    }
    public void testGenerationRejectsDetachedObjectBeforeCompilerThenRunsRealSpring() throws Exception {
        generated=GOOD.replace("@Autowired Subject subject;","@Autowired Subject subject; @org.junit.jupiter.api.BeforeEach void setup() { subject = new Subject(); }");
        var result=background(() -> service.generateTestCode(getProject(),source,candidate(generated)));
        assertEquals(result.getValidationResult().getDiagnostics().toString(),ValidationStatus.PASSED,result.getValidationResult().getStatus());
        assertEquals(1,compileRepairs.get()); assertEquals(0,runtimeRepairs.get());
        var regression=background(() -> service.runFinalTestRegression(getProject(),source,result,result.getGeneratedTestFile().getContent(),()->false,null));
        assertEquals(regression.getRuntimeValidationReport().getMessage(),ClassJUnitStatus.PASSED,regression.getRuntimeValidationReport().getStatus());
        assertEquals(3,regression.getRuntimeValidationReport().getFinalExecution().getSucceeded());
    }
    public void testMissingPropertyRepairsOnlySharedConfigurationAndPreservesBodies() throws Exception {
        var result=background(() -> service.runRuntimeValidation(getProject(),source,candidate(MISSING),MISSING,()->false,null));
        var report=result.getRuntimeValidationReport(); assertEquals(report.getMessage()+report.getAttempts().stream().map(a -> a.getRejectionReason()).toList(),ClassJUnitStatus.PASSED,report.getStatus());
        assertEquals(1,runtimeRepairs.get()); assertEquals(1,report.getFixtureRepairRounds()); assertEquals(3,report.getFinalExecution().getSucceeded());
        assertEquals(SpringFixtureAnalyzer.BINDING_FAILURE,report.getOracleAssessment().getRootCauseCategory());
        var diagnostics=report.getInitialExecution().getDiagnostics();
        assertEquals(3,diagnostics.size()); assertEquals(2,diagnostics.stream().filter(RuntimeDiagnostic::isContextFailureSecondary).count());
        var first=diagnostics.stream().filter(d -> !d.isContextFailureSecondary()).findFirst().orElseThrow();
        assertTrue(first.getRootCauseMessage().contains("service.name"));
        assertTrue(Files.exists(Path.of(first.getEvidenceArchivePath(),"execution.json")));
        for(var secondary:diagnostics.stream().filter(RuntimeDiagnostic::isContextFailureSecondary).toList()) {
            assertEquals(RuntimeFailurePhase.TEST_SETUP,secondary.getFailurePhase()); assertEquals(first.getTestId(),secondary.getPrimaryFailureTestId());
        }
    }
    public void testCoverageUsesSameSharedPropertyRepairAndActualMeasurement() throws Exception {
        var config=CoverageConfigManager.load();config.setMaxRounds(0);CoverageConfigManager.save(config);
        var result=background(() -> service.runCoverageLoop(getProject(),source,candidate(MISSING),MISSING,()->false,null));
        var report=result.getCoverageReport();
        assertEquals(report.getMessage(),CoverageStatus.PASSED,report.getStatus());
        assertEquals(1,runtimeRepairs.get());assertEquals(1,report.getRepairRounds());
        assertTrue(report.getGenerated().isAvailable());assertEquals(3,report.getGenerated().getExecution().getSucceeded());
        assertEquals(2,report.getGenerated().getBranch().getTotal());assertEquals(1,report.getGenerated().getBranch().getCovered());
    }

    public void testPropertyAuthorizationRejectsOracleChange() throws Exception {
        changeOracle=true;
        var result=background(() -> service.runRuntimeValidation(getProject(),source,candidate(MISSING),MISSING,()->false,null));
        assertEquals(ClassJUnitStatus.TEST_FAILED,result.getRuntimeValidationReport().getStatus());
        assertEquals(MISSING,result.getGeneratedTestFile().getContent()); assertEquals(1,runtimeRepairs.get());
        assertFalse(result.getRuntimeValidationReport().getCandidateRejections().isEmpty());
    }
    public void testSelectionDoesNotDropTestsBlockedBySharedContextAndArchivesRoot() throws Exception {
        var result=background(() -> service.retainRunnableTests(getProject(),source,candidate(MISSING),MISSING,()->false,null));
        var report=result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(),RunnableTestSelectionReport.Status.BLOCKED,report.getStatus());
        assertEquals(0,report.getExcludedTests().size());assertEquals(1,report.getExecutions());
        assertEquals(MISSING,result.getGeneratedTestFile().getContent()); assertTrue(report.getMessage().contains("service.name"));
        assertEquals(1,report.getRuntimeEvidenceArchives().size());
        assertTrue(Files.exists(Path.of(report.getRuntimeEvidenceArchives().get(0),"execution.json")));
        assertEquals(0,runtimeRepairs.get());
    }
    public void testProgrammaticSharedContextFailureCannotBeSalvagedByDeletingAllMethods() throws Exception {
        String programmatic=GOOD.replace("@ExtendWith(SpringExtension.class)", "")
                .replace("@ContextConfiguration(classes=SubjectTest.Config.class)", "")
                .replace("@TestPropertySource(properties={\"service.name=demo\",\"server.port=9090\",\"feature.exposed=on\"})", "")
                .replace("@Autowired Subject subject;", "Subject subject; @org.junit.jupiter.api.BeforeEach void setup() { try (var context = new AnnotationConfigApplicationContext(Config.class)) { subject = context.getBean(Subject.class); } }");
        var result=background(() -> service.retainRunnableTests(getProject(),source,candidate(programmatic),programmatic,()->false,null));
        var report=result.getRunnableTestSelectionReport();
        assertEquals(report.getMessage(),RunnableTestSelectionReport.Status.BLOCKED,report.getStatus());
        assertTrue(report.getExcludedTests().isEmpty());assertEquals(1,report.getExecutions());
        assertEquals(programmatic,result.getGeneratedTestFile().getContent());
        assertTrue(report.getMessage().contains("service.name"));
        Path archive=Path.of(report.getRuntimeEvidenceArchives().get(0));
        var analysis=new org.json.JSONObject(Files.readString(archive.resolve("source-analysis.json"))).getJSONArray("diagnostics");
        assertEquals(3,analysis.length());
        for(int i=0;i<analysis.length();i++) {
            var diagnostic=analysis.getJSONObject(i);assertTrue(diagnostic.getBoolean("sharedContextFailure"));
            assertEquals("TEST_SETUP",diagnostic.getString("failurePhase"));
            assertEquals(SpringRuntimeFailureAnalyzer.CATEGORY,diagnostic.getString("category"));
        }
        assertEquals(0,runtimeRepairs.get());
    }

    public void testTypeConversionFailureIsPreservedAndNotMisclassifiedAsBusinessPrecondition() throws Exception {
        String invalid=GOOD.replace("server.port=9090","server.port=not-a-number");
        var result=background(() -> service.runFinalTestRegression(getProject(),source,candidate(invalid),invalid,()->false,null));
        var report=result.getRuntimeValidationReport();assertEquals(ClassJUnitStatus.TEST_FAILED,report.getStatus());
        var first=report.getFinalExecution().getDiagnostics().stream().filter(d -> !d.isContextFailureSecondary()).findFirst().orElseThrow();
        assertEquals("java.lang.NumberFormatException",first.getRootCauseType()); assertTrue(first.getRootCauseMessage().contains("not-a-number"));
        assertEquals(SpringRuntimeFailureAnalyzer.CATEGORY,first.getCategory());assertTrue(first.isSharedContextFailure());
        assertEquals(0,runtimeRepairs.get());
    }
    private GenerationResult candidate(String code) {
        var result=new GenerationResult();result.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE,"value"));
        var file=new GeneratedTestFile();file.setClassName("SubjectTest");file.setFileName("SubjectTest.java");file.setPackageName("sample");file.setContent(code);result.setGeneratedTestFile(file);return result;
    }
    private static LlmGenerationResponse code(String code) {var response=new LlmGenerationResponse();response.setTestCode(code);return response;}
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
            try {return action.call();}catch(Exception ex){throw new RuntimeException(ex);}
        },new EmptyProgressIndicator())).get(120,TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() -> ModuleRootModificationUtil.updateModel(getModule(),model -> {
                if(root!=null)for(var entry:model.getContentEntries())if(entry.getUrl().contains(root.getFileName().toString()))model.removeContentEntry(entry);
            }));super.tearDown();
        }finally{if(root!=null)try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
