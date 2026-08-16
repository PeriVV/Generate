package com.casc.unittestagent.service;

import com.casc.unittestagent.generator.ClassTestAssembler;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MethodGenerationStatus;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.psi.PsiFile;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class ClassUnitTestGenerationServiceTest extends TestCase {
    public void testContinuesAfterOneMethodFailsAndUsesRuleFallback() {
        FakeMethodService methodService = new FakeMethodService();
        ClassUnitTestGenerationService service = new ClassUnitTestGenerationService(
                new JavaSourceAnalyzer(), new TestDataGenerator(), methodService, new ClassTestAssembler());
        ClassGenerationResult input = classResult();

        ClassGenerationResult generated = service.generateClassTest(null, null, input, null);

        assertTrue(generated.isComplete());
        assertEquals(ValidationStatus.PASSED, generated.getValidationResult().getStatus());
        assertEquals(MethodGenerationStatus.SUCCESS, generated.getMethodItems().get(0).getStatus());
        assertEquals(MethodGenerationStatus.RULE_FALLBACK, generated.getMethodItems().get(1).getStatus());
        assertTrue(generated.getGeneratedTestFile().getContent().contains("shouldFirst"));
        assertTrue(generated.getGeneratedTestFile().getContent().contains("shouldSecondFallback"));
        assertTrue(generated.getGeneratedTestFile().getContent().contains("TODO: UnitMind 已使用规则兜底"));
        assertTrue(generated.isGeneratedByLlm());
        assertTrue(generated.isRuleBasedFallback());
    }

    public void testCancellationKeepsPartialDraftButDoesNotMarkClassComplete() {
        FakeMethodService methodService = new FakeMethodService();
        ClassUnitTestGenerationService service = new ClassUnitTestGenerationService(
                new JavaSourceAnalyzer(), new TestDataGenerator(), methodService, new ClassTestAssembler());
        ProgressIndicatorBase indicator = new ProgressIndicatorBase();
        indicator.cancel();

        ClassGenerationResult generated = service.generateClassTest(null, null, classResult(), indicator);

        assertTrue(generated.isCancelled());
        assertFalse(generated.isComplete());
        assertNotNull(generated.getGeneratedTestFile());
        assertTrue(generated.getMethodItems().stream()
                .allMatch(item -> item.getStatus() == MethodGenerationStatus.CANCELLED));
    }

    public void testFallsBackBeforeAssemblyWhenMethodCandidateStillDoesNotCompile() {
        FakeMethodService methodService = new FakeMethodService(true);
        ClassUnitTestGenerationService service = new ClassUnitTestGenerationService(
                new JavaSourceAnalyzer(), new TestDataGenerator(), methodService, new ClassTestAssembler());

        ClassGenerationResult generated = service.generateClassTest(null, null, classResult(), null);

        assertEquals(MethodGenerationStatus.RULE_FALLBACK, generated.getMethodItems().get(0).getStatus());
        assertTrue(generated.getMethodItems().get(0).getMessage().contains("CANNOT_FIND_SYMBOL"));
        assertTrue(generated.getGeneratedTestFile().getContent().contains("shouldFirstFallback"));
        assertFalse(generated.getGeneratedTestFile().getContent().contains("brokenCandidate"));
    }

    public void testValidatesEveryIntegratedPrefixAndFallsBackOnlyConflictingMethod() {
        IntegrationConflictMethodService methodService = new IntegrationConflictMethodService(false);
        ClassUnitTestGenerationService service = new ClassUnitTestGenerationService(
                new JavaSourceAnalyzer(), new TestDataGenerator(), methodService, new ClassTestAssembler());

        ClassGenerationResult generated = service.generateClassTest(null, null, classResult(), null);

        assertEquals(MethodGenerationStatus.SUCCESS, generated.getMethodItems().get(0).getStatus());
        assertEquals(MethodGenerationStatus.RULE_FALLBACK, generated.getMethodItems().get(1).getStatus());
        assertEquals(ValidationStatus.PASSED, generated.getValidationResult().getStatus());
        assertTrue(generated.getGeneratedTestFile().getContent().contains("shouldFirst"));
        assertTrue(generated.getGeneratedTestFile().getContent().contains("shouldSecondFallback"));
        assertFalse(generated.getGeneratedTestFile().getContent().contains("void shouldSecond()"));
        assertTrue(methodService.validatedCandidates.stream().anyMatch(code ->
                code.contains("void shouldFirst()") && code.contains("void shouldSecond()")));
    }

    public void testRollsBackCurrentMethodWhenCandidateAndFallbackBothBreakIntegratedClass() {
        IntegrationConflictMethodService methodService = new IntegrationConflictMethodService(true);
        ClassUnitTestGenerationService service = new ClassUnitTestGenerationService(
                new JavaSourceAnalyzer(), new TestDataGenerator(), methodService, new ClassTestAssembler());

        ClassGenerationResult generated = service.generateClassTest(null, null, classResult(), null);

        assertEquals(MethodGenerationStatus.SUCCESS, generated.getMethodItems().get(0).getStatus());
        assertEquals(MethodGenerationStatus.FAILED, generated.getMethodItems().get(1).getStatus());
        assertEquals(ValidationStatus.PASSED, generated.getValidationResult().getStatus());
        assertTrue(generated.getGeneratedTestFile().getContent().contains("shouldFirst"));
        assertFalse(generated.getGeneratedTestFile().getContent().contains("shouldSecond"));
        assertTrue(generated.getMethodItems().get(1).getMessage().contains("上一可编译检查点")
                || generated.getWarnings().stream().anyMatch(message -> message.contains("上一可编译检查点")));
    }

    private ClassGenerationResult classResult() {
        ClassModel model = new ClassModel();
        model.setPackageName("sample");
        model.setClassName("Subject");
        model.setQualifiedName("sample.Subject");
        List<MethodModel> methods = new ArrayList<>();
        List<MethodGenerationItem> items = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            MethodModel method = new MethodModel();
            method.setPackageName("sample");
            method.setClassName("Subject");
            method.setMethodName(name);
            method.setMethodId("sample.Subject#" + name + "()");
            method.setSignature(name + "(): void");
            method.setReturnType("void");
            methods.add(method);
            GenerationResult methodResult = new GenerationResult();
            methodResult.setMethodModel(method);
            MethodGenerationItem item = new MethodGenerationItem();
            item.setMethodId(method.getMethodId());
            item.setSignature(method.getSignature());
            item.setResult(methodResult);
            items.add(item);
        }
        model.setMethods(methods);
        ClassGenerationResult result = new ClassGenerationResult();
        result.setClassModel(model);
        result.setMethodItems(items);
        result.setMethodModel(methods.get(0));
        return result;
    }

    private static class FakeMethodService extends UnitTestGenerationService {
        private final boolean failFirstValidation;

        private FakeMethodService() {
            this(false);
        }

        private FakeMethodService(boolean failFirstValidation) {
            this.failFirstValidation = failFirstValidation;
        }

        @Override
        public GenerationResult planScenarios(GenerationResult result) {
            TestScenario scenario = new TestScenario();
            scenario.setType("NORMAL");
            scenario.setMethodNameEn("should" + capitalize(result.getMethodModel().getMethodName()));
            result.setScenarios(List.of(scenario));
            return result;
        }

        @Override
        public GenerationResult planMockStrategy(GenerationResult result) {
            result.setMockStrategy(new MockStrategy());
            return result;
        }

        @Override
        public GenerationResult generateTestCode(Project project, PsiFile psiFile, GenerationResult result) {
            if ("second".equals(result.getMethodModel().getMethodName())) {
                throw new IllegalStateException("real candidate failure");
            }
            result.setGeneratedByLlm(true);
            result.setGeneratedTestFile(file(failFirstValidation ? "brokenCandidate" : "shouldFirst"));
            if (failFirstValidation) {
                ValidationResult validation = ValidationResult.status(ValidationStatus.FAILED);
                validation.getDiagnostics().add(new CompileDiagnostic(
                        "ERROR", "CANNOT_FIND_SYMBOL", "missing()", 4, 1, "missing();", true));
                result.setValidationResult(validation);
            } else {
                result.setValidationResult(ValidationResult.status(ValidationStatus.PASSED));
            }
            return result;
        }

        @Override
        public GeneratedTestFile generateRuleBasedTestFile(GenerationResult result) {
            return file("should" + capitalize(result.getMethodModel().getMethodName()) + "Fallback");
        }

        @Override
        GeneratedTestFile validateAndRepair(Project project, PsiFile psiFile, GenerationResult result,
                                            GeneratedTestFile initialFile, LlmConfig config) {
            result.setValidationResult(ValidationResult.status(ValidationStatus.PASSED));
            return initialFile;
        }

        protected static GeneratedTestFile file(String methodName) {
            GeneratedTestFile file = new GeneratedTestFile();
            file.setPackageName("sample");
            file.setClassName("SubjectTest");
            file.setFileName("SubjectTest.java");
            file.setContent("""
                    package sample;
                    import org.junit.jupiter.api.Test;
                    class SubjectTest {
                        @Test void %s() { }
                    }
                    """.formatted(methodName));
            return file;
        }

        protected static String capitalize(String value) {
            return Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }
    }

    private static class IntegrationConflictMethodService extends FakeMethodService {
        private final boolean rejectFallback;
        private final List<String> validatedCandidates = new ArrayList<>();

        private IntegrationConflictMethodService(boolean rejectFallback) {
            this.rejectFallback = rejectFallback;
        }

        @Override
        public GenerationResult generateTestCode(Project project, PsiFile psiFile, GenerationResult result) {
            String methodName = result.getMethodModel().getMethodName();
            result.setGeneratedByLlm(true);
            result.setRuleBasedFallback(false);
            result.setGeneratedTestFile(file("should" + capitalize(methodName)));
            result.setValidationResult(ValidationResult.status(ValidationStatus.PASSED));
            return result;
        }

        @Override
        GeneratedTestFile validateAndRepair(Project project, PsiFile psiFile, GenerationResult result,
                                            GeneratedTestFile initialFile, LlmConfig config) {
            String code = initialFile.getContent();
            validatedCandidates.add(code);
            boolean originalConflict = code.contains("void shouldFirst()")
                    && code.contains("void shouldSecond()");
            boolean fallbackConflict = rejectFallback && code.contains("shouldSecondFallback");
            if (originalConflict || fallbackConflict) {
                ValidationResult validation = ValidationResult.status(ValidationStatus.FAILED);
                validation.getDiagnostics().add(new CompileDiagnostic(
                        "ERROR", "INCREMENTAL_CONFLICT", "conflicting fixture", 8, 1,
                        "void shouldSecond()", true));
                result.setValidationResult(validation);
            } else {
                result.setValidationResult(ValidationResult.status(ValidationStatus.PASSED));
            }
            return initialFile;
        }
    }
}
