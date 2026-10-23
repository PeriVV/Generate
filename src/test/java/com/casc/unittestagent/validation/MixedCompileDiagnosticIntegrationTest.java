package com.casc.unittestagent.validation;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.model.*;
import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.CompilerConfigurationImpl;
import com.intellij.psi.PsiFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.PathUtil;
import org.jetbrains.jps.model.java.impl.compiler.ProcessorConfigProfileImpl;

/** Real javac/Lombok: candidate enter errors must not masquerade as a broken production build. */
public class MixedCompileDiagnosticIntegrationTest extends JavaCodeInsightFixtureTestCase {
    private static final String SOURCE = """
            package sample;
            @lombok.extern.slf4j.Slf4j
            public class Subject {
                public static int value() { log.info("value"); return 1; }
            }
            """;
    private static final String BROKEN = "package sample; class SubjectTest implements Subject {}";

    @Override protected void tuneFixture(JavaModuleFixtureBuilder<?> builder) throws Exception {
        super.tuneFixture(builder); builder.setLanguageLevel(LanguageLevel.JDK_17);
        builder.addJdk(System.getProperty("java.home"));
    }
    @Override protected void setUp() throws Exception {
        super.setUp(); addLibrary(lombok.Data.class); addLibrary(org.slf4j.Logger.class);
        processor(PathUtil.getJarPathForClass(lombok.Data.class));
    }

    public void testInvalidTestInheritanceDoesNotTurnLombokCascadeIntoEnvironmentFailure() {
        PsiFile source = myFixture.configureByText("Subject.java", SOURCE);
        var result = validate(source, BROKEN);
        assertEquals(result.getDiagnostics().toString(), ValidationStatus.FAILED, result.getStatus());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> "CLASS_USED_AS_INTERFACE".equals(d.getCode())));
        assertTrue(result.getDiagnostics().stream().allMatch(d -> "TEST_SOURCE".equals(d.getSourceOrigin()) && d.isRepairable()));
        assertEquals(SOURCE, source.getText());
    }

    public void testAnotherTestEnterErrorAlsoKeepsProductionCascadeOutOfRepairContext() {
        var result = validate(myFixture.configureByText("Subject.java", SOURCE),
                "package sample; class SubjectTest extends SubjectTest {}");
        assertEquals(result.getDiagnostics().toString(), ValidationStatus.FAILED, result.getStatus());
        assertFalse(result.getDiagnostics().isEmpty());
        assertTrue(result.getDiagnostics().stream().noneMatch(d -> "TARGET_SOURCE".equals(d.getSourceOrigin())));
    }

    public void testCorrectedCandidateCompilesFreshLombokBytecode() {
        PsiFile source = myFixture.configureByText("Subject.java", SOURCE);
        var environment = new ProjectTestEnvironmentResolver().resolve(getProject(), source);
        String code = "package sample; class SubjectTest { int value() { return Subject.value(); } }";
        try (var artifact = new TestSandboxCompiler().compile(environment, file(), code, "sample.Subject")) {
            assertTrue(artifact.getDiagnostics().toString(), artifact.isSuccessful());
            assertTrue(java.nio.file.Files.isRegularFile(artifact.getClasses().resolve("sample/Subject.class")));
            assertTrue(java.nio.file.Files.isRegularFile(artifact.getClasses().resolve("sample/SubjectTest.class")));
        }
    }

    public void testGenuinelyInvalidProductionSourceStillBlocksRepair() {
        String brokenSource = SOURCE.replace("public static int value()", "MissingType missing; public static int value()");
        var result = validate(myFixture.configureByText("Subject.java", brokenSource), BROKEN);
        assertEquals(ValidationStatus.ENVIRONMENT_ERROR, result.getStatus());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> "TARGET_SOURCE".equals(d.getSourceOrigin()) && !d.isRepairable()));
    }

    public void testUnavailableAnnotationProcessorRemainsEnvironmentFailure() {
        // A valid jar with no processor providers prevents accidental classpath discovery.
        processor(PathUtil.getJarPathForClass(org.junit.jupiter.api.Test.class));
        var result = validate(myFixture.configureByText("Subject.java", SOURCE), BROKEN);
        assertEquals(result.getDiagnostics().toString(), ValidationStatus.ENVIRONMENT_ERROR, result.getStatus());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> "TARGET_SOURCE".equals(d.getSourceOrigin())));
    }

    public void testGenerationCanRepairCandidateAfterProvingProductionBaseline() throws Exception {
        var previous = com.casc.unittestagent.llm.LlmConfigManager.load();
        var config = new com.casc.unittestagent.llm.LlmConfig(); config.setEnabled(true); config.setMaxCompileRepairRounds(1);
        com.casc.unittestagent.llm.LlmConfigManager.save(config, null);
        try {
            PsiFile source = myFixture.configureByText("Subject.java", SOURCE);
            String fixed = "package sample; class SubjectTest { @org.junit.jupiter.api.Test void checks() { org.junit.jupiter.api.Assertions.assertEquals(1, Subject.value()); } }";
            String broken = fixed.replace("class SubjectTest {", "class SubjectTest implements Subject {");
            var repairs = new java.util.concurrent.atomic.AtomicInteger();
            var service = new com.casc.unittestagent.service.UnitTestGenerationService(
                    new com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder(), new ProjectCompileVerifier(),
                    ignored -> new com.casc.unittestagent.llm.LlmClient() {
                        @Override public com.casc.unittestagent.llm.LlmGenerationResponse generateUnitTest(
                                com.casc.unittestagent.llm.LlmGenerationRequest request) {
                            var response = new com.casc.unittestagent.llm.LlmGenerationResponse(); response.setTestCode(broken); return response;
                        }
                        @Override public com.casc.unittestagent.llm.LlmGenerationResponse repairUnitTest(
                                com.casc.unittestagent.llm.LlmGenerationRequest request) {
                            repairs.incrementAndGet();
                            assertTrue(request.getCompilerDiagnostics().stream().anyMatch(d -> "CLASS_USED_AS_INTERFACE".equals(d.getCode())));
                            assertTrue(request.getCompilerDiagnostics().stream().noneMatch(d -> "TARGET_SOURCE".equals(d.getSourceOrigin())));
                            var response = new com.casc.unittestagent.llm.LlmGenerationResponse(); response.setTestCode(fixed); return response;
                        }
                    });
            var candidate = new GenerationResult(); candidate.setMethodModel(new com.casc.unittestagent.parser.JavaSourceAnalyzer().analyze(SOURCE, "value"));
            candidate.setMockStrategy(new MockStrategy());
            var generated = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() ->
                    com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(() -> service.generateTestCode(
                            getProject(), source, candidate), new com.intellij.openapi.progress.EmptyProgressIndicator()))
                    .get(90, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(generated.getValidationResult().getDiagnostics().toString(), ValidationStatus.PASSED, generated.getValidationResult().getStatus());
            assertEquals(1, repairs.get()); assertEquals(1, generated.getValidationResult().getRepairAttempts());
            assertEquals(SOURCE, source.getText()); assertEquals(ClassJUnitStatus.NOT_RUN, generated.getRuntimeValidationReport().getStatus());
        } finally { com.casc.unittestagent.llm.LlmConfigManager.save(previous, null); }
    }

    private ValidationResult validate(PsiFile source, String code) {
        return new ProjectCompileVerifier().validate(getProject(), source, file(), code);
    }
    private GeneratedTestFile file() {
        var file = new GeneratedTestFile(); file.setClassName("SubjectTest");
        file.setPackageName("sample"); file.setFileName("SubjectTest.java"); return file;
    }
    private void addLibrary(Class<?> type) {
        java.io.File jar = new java.io.File(PathUtil.getJarPathForClass(type));
        PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), type.getSimpleName(), jar.getParent(), jar.getName());
    }
    private void processor(String path) {
        var compiler = (CompilerConfigurationImpl) CompilerConfiguration.getInstance(getProject());
        var profile = compiler.getAnnotationProcessingConfiguration(getModule());
        if (profile instanceof ProcessorConfigProfileImpl mutable && "mixed-diagnostic-test".equals(mutable.getName())) {
            mutable.setProcessorPath(path); return;
        }
        var replacement = new ProcessorConfigProfileImpl("mixed-diagnostic-test"); replacement.setEnabled(true);
        replacement.setObtainProcessorsFromClasspath(false); replacement.setProcessorPath(path);
        replacement.addModuleName(getModule().getName()); compiler.addModuleProcessorProfile(replacement);
    }
}
