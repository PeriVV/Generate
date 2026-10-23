package com.casc.unittestagent.service;

import com.casc.unittestagent.llm.LlmClient;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.validation.TestCodeValidator;
import com.intellij.testFramework.LightPlatformTestCase;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

public class UnitTestGenerationServiceRepairTest extends LightPlatformTestCase {
    public void testFirstCompilePassDoesNotCallRepair() {
        Harness harness = new Harness();

        GeneratedTestFile result = harness.run("class SubjectTest { /* PASS */ }");

        assertEquals(ValidationStatus.PASSED, harness.result.getValidationResult().getStatus());
        assertEquals(0, harness.repairCalls.get());
        assertTrue(result.getContent().contains("PASS"));
    }

    public void testFirstRepairCanPass() {
        Harness harness = new Harness("class SubjectTest { /* PASS */ }");

        GeneratedTestFile result = harness.run("class SubjectTest { /* FAIL */ }");

        assertEquals(ValidationStatus.PASSED, harness.result.getValidationResult().getStatus());
        assertEquals(1, harness.result.getValidationResult().getRepairAttempts());
        assertEquals(1, harness.repairCalls.get());
        assertTrue(result.getContent().contains("PASS"));
    }

    public void testSecondRepairCanPass() {
        Harness harness = new Harness(
                "class SubjectTest { /* FAIL_2 */ }",
                "class SubjectTest { /* PASS */ }");

        harness.run("class SubjectTest { /* FAIL_1 */ }");

        assertEquals(ValidationStatus.PASSED, harness.result.getValidationResult().getStatus());
        assertEquals(2, harness.result.getValidationResult().getRepairAttempts());
        assertEquals(2, harness.repairCalls.get());
    }

    public void testStopsAfterTwoFailedRepairs() {
        Harness harness = new Harness(
                "class SubjectTest { /* FAIL_2 */ }",
                "class SubjectTest { /* FAIL_3 */ }");

        harness.run("class SubjectTest { /* FAIL_1 */ }");

        assertEquals(ValidationStatus.FAILED, harness.result.getValidationResult().getStatus());
        assertEquals(2, harness.result.getValidationResult().getRepairAttempts());
        assertEquals(2, harness.repairCalls.get());
    }

    public void testConfigurableBudgetAllowsMoreThanTwoCompileRepairs() {
        Harness harness = new Harness(
                "class SubjectTest { /* FAIL_2 */ }",
                "class SubjectTest { /* FAIL_3 */ }",
                "class SubjectTest { /* FAIL_4 */ }",
                "class SubjectTest { /* PASS */ }");

        harness.run("class SubjectTest { /* FAIL_1 */ }", 4);

        assertEquals(ValidationStatus.PASSED, harness.result.getValidationResult().getStatus());
        assertEquals(4, harness.result.getValidationResult().getRepairAttempts());
        assertEquals(4, harness.repairCalls.get());
    }

    public void testRepeatedOutputStopsEarly() {
        String repeated = "class SubjectTest { /* FAIL */ }";
        Harness harness = new Harness(repeated, repeated);

        harness.run(repeated);

        assertEquals(ValidationStatus.FAILED, harness.result.getValidationResult().getStatus());
        assertEquals(1, harness.repairCalls.get());
        assertTrue(harness.result.getWarnings().stream().anyMatch(text -> text.contains("未取得进展")));
    }

    public void testCompileQualityGateDetectsIncreasedDiagnosticCount() {
        ValidationResult parent = new ValidationResult();
        parent.setStatus(ValidationStatus.FAILED);
        parent.setDiagnostics(List.of(new CompileDiagnostic(
                "ERROR", "CANNOT_FIND_SYMBOL", "one", 1, 1, "missing", true)));
        ValidationResult worse = new ValidationResult();
        worse.setStatus(ValidationStatus.FAILED);
        worse.setDiagnostics(List.of(
                new CompileDiagnostic("ERROR", "CANNOT_FIND_SYMBOL", "one", 1, 1, "missing", true),
                new CompileDiagnostic("ERROR", "INCOMPATIBLE_TYPES", "two", 2, 1, "bad", true)));

        assertTrue(UnitTestGenerationService.isCompileRegression(parent, worse));
        assertFalse(UnitTestGenerationService.isCompileRegression(worse, parent));
    }

    public void testEnvironmentErrorDoesNotCallRepair() {
        Harness harness = new Harness();
        harness.environmentError = true;

        harness.run("class SubjectTest { /* FAIL */ }");

        assertEquals(ValidationStatus.ENVIRONMENT_ERROR, harness.result.getValidationResult().getStatus());
        assertEquals(0, harness.repairCalls.get());
    }

    public void testResolvedSymbolsMayExposeMoreCheckedExceptions() {
        var missing = new CompileDiagnostic("ERROR", "CANNOT_FIND_SYMBOL", "Mock", 1, 1, "@Mock", true);
        var checked = new CompileDiagnostic("ERROR", "UNREPORTED_EXCEPTION", "throws Exception", 3, 1, "client.upload()", true);
        assertFalse(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(missing), List.of(checked, checked)));
        // Still reject a candidate retaining unresolved symbols or introducing other failures.
        assertTrue(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(missing), List.of(missing, checked)));
        var incompatible = new CompileDiagnostic("ERROR", "INCOMPATIBLE_TYPES", "bad type", 4, 1, "bad()", true);
        assertTrue(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(missing), List.of(checked, incompatible)));
        checked.setRepairable(false);
        checked.setSourceOrigin("TARGET_SOURCE");
        assertTrue(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(missing), List.of(checked, checked)));
        assertTrue(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(), List.of(missing)));
    }

    public void testResolvingGenerationPreflightMayRevealMoreCompilerDiagnostics() {
        var preflight = new CompileDiagnostic("ERROR", "FIXTURE_INITIALIZATION_CONFLICT", "Spy order", 2, 1, "@Spy", true);
        preflight.setValidationStage(com.casc.unittestagent.model.ValidationStage.SOURCE_PREFLIGHT);
        var compiler = new CompileDiagnostic("ERROR", "CANNOT_FIND_SYMBOL", "missing", 5, 1, "missing()", true);
        assertFalse(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(preflight), List.of(compiler, compiler)));
        compiler.setValidationStage(com.casc.unittestagent.model.ValidationStage.SOURCE_PREFLIGHT);
        assertTrue(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(preflight), List.of(compiler, compiler)));
        compiler.setValidationStage(com.casc.unittestagent.model.ValidationStage.JAVA_COMPILATION);
        compiler.setRepairable(false); compiler.setSourceOrigin("TARGET_SOURCE");
        assertTrue(UnitTestGenerationService.isCompileDiagnosticRegression(List.of(preflight), List.of(compiler, compiler)));
    }

    public void testClassRepairUsesMethodContextOwningDiagnosticLine() {
        MethodModel first = method("first", "sample.Subject#first()");
        MethodModel second = method("second", "sample.Subject#second()");
        GenerationResult firstResult = methodResult(first);
        GenerationResult secondResult = methodResult(second);
        firstResult.setMockStrategy(mockStrategy("firstDependency"));
        secondResult.setMockStrategy(mockStrategy("secondDependency"));
        MethodGenerationItem firstItem = item(firstResult, 1, 4);
        MethodGenerationItem secondItem = item(secondResult, 5, 12);
        ClassGenerationResult classResult = new ClassGenerationResult();
        classResult.setMethodModel(first);
        classResult.setMethodItems(List.of(firstItem, secondItem));
        classResult.setClassMethodSignatures(List.of(first.getSignature(), second.getSignature()));
        classResult.setSharedFixtureSummary("subjectField=subject");
        classResult.setGeneratedByLlm(true);
        AtomicReference<LlmGenerationRequest> captured = new AtomicReference<>();
        TestCodeValidator validator = (project, sourceFile, generatedFile, code) -> {
            ValidationResult validation = new ValidationResult();
            validation.setCodeHash(CodeFingerprint.sha256(code));
            if (code.contains("PASS")) {
                validation.setStatus(ValidationStatus.PASSED);
            } else {
                validation.setStatus(ValidationStatus.FAILED);
                validation.getDiagnostics().add(new CompileDiagnostic(
                        "ERROR", "CANNOT_FIND_SYMBOL", "missing()", 7, 1, "missing();", true));
            }
            return validation;
        };
        LlmClient client = new LlmClient() {
            @Override public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) {
                throw new AssertionError("should only repair");
            }
            @Override public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                captured.set(request);
                LlmGenerationResponse response = new LlmGenerationResponse();
                response.setTestCode("class SubjectTest { /* PASS */ }");
                return response;
            }
        };
        UnitTestGenerationService service = new UnitTestGenerationService(
                new ProjectSymbolSnapshotBuilder(), validator, config -> client);
        LlmConfig config = new LlmConfig();
        config.setEnabled(true);

        service.validateAndRepair(null, null, classResult,
                Harness.generatedFile("class SubjectTest { /* FAIL */ }"), config);

        assertNotNull(captured.get());
        assertEquals("sample.Subject#second()", captured.get().getMethodId());
        assertEquals("second", captured.get().getMethodName());
        assertTrue(captured.get().getUserInstruction().contains("sample.Subject#second()"));
        assertTrue(captured.get().getUserInstruction().contains("target=secondDependency"));
        assertFalse(captured.get().getUserInstruction().contains("target=firstDependency"));
    }

    public void testBuildingLlmRequestDoesNotMutateOrDuplicateMethodCallContexts() {
        MethodModel method = method("execute", "sample.Subject#execute(sample.Request)");
        CallContextModel context = new CallContextModel();
        context.setKind("PARAMETER_CHAIN_CALL");
        context.setOwner("request: sample.Request");
        context.setMethodName("config");
        context.setExpression("request.config().value()");
        method.setCallContexts(new java.util.ArrayList<>(List.of(context)));
        UnitTestGenerationService service = new UnitTestGenerationService();

        LlmGenerationRequest first = service.buildLlmRequest(
                null, method, List.of(), method.getMethodBodyText(), null);
        LlmGenerationRequest second = service.buildLlmRequest(
                null, method, List.of(), method.getMethodBodyText(), null);

        assertEquals(1, method.getCallContexts().size());
        assertEquals(first.getCallContexts().size(), second.getCallContexts().size());
        assertEquals(first.getCallContexts().stream().map(CallContextModel::getExpression).toList(),
                second.getCallContexts().stream().map(CallContextModel::getExpression).toList());
    }

    private static MockStrategy mockStrategy(String target) {
        MockStrategy strategy = new MockStrategy();
        strategy.setStrategyItems(List.of(new MockStrategyItem(
                true, "字段依赖", target, "load", "source", "mock", "note")));
        return strategy;
    }

    private static MethodModel method(String name, String id) {
        MethodModel method = new MethodModel();
        method.setPackageName("sample");
        method.setClassName("Subject");
        method.setMethodName(name);
        method.setMethodId(id);
        method.setSignature(name + "(): void");
        method.setReturnType("void");
        method.setMethodBodyText("{ }");
        return method;
    }

    private static GenerationResult methodResult(MethodModel method) {
        GenerationResult result = new GenerationResult();
        result.setMethodModel(method);
        return result;
    }

    private static MethodGenerationItem item(GenerationResult result, int start, int end) {
        MethodGenerationItem item = new MethodGenerationItem();
        item.setMethodId(result.getMethodModel().getMethodId());
        item.setSignature(result.getMethodModel().getSignature());
        item.setResult(result);
        item.setGeneratedStartLine(start);
        item.setGeneratedEndLine(end);
        return item;
    }

    private static class Harness {
        private final Deque<String> repairedCodes = new ArrayDeque<>();
        private final AtomicInteger repairCalls = new AtomicInteger();
        private final GenerationResult result = generationResult();
        private boolean environmentError;

        private Harness(String... repairedCodes) {
            for (String repairedCode : repairedCodes) {
                this.repairedCodes.add(repairedCode);
            }
        }

        private GeneratedTestFile run(String initialCode) {
            return run(initialCode, 2);
        }

        private GeneratedTestFile run(String initialCode, int maxRepairRounds) {
            TestCodeValidator validator = (project, sourceFile, generatedFile, code) -> validation(code);
            LlmClient client = new LlmClient() {
                @Override
                public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) {
                    throw new AssertionError("修复闭环不应调用初始生成接口。");
                }

                @Override
                public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) {
                    repairCalls.incrementAndGet();
                    LlmGenerationResponse response = new LlmGenerationResponse();
                    response.setTestCode(repairedCodes.removeFirst());
                    return response;
                }
            };
            UnitTestGenerationService service = new UnitTestGenerationService(
                    new ProjectSymbolSnapshotBuilder(), validator, config -> client);
            LlmConfig config = new LlmConfig();
            config.setEnabled(true);
            config.setMaxCompileRepairRounds(maxRepairRounds);
            return service.validateAndRepair(null, null, result, generatedFile(initialCode), config);
        }

        private ValidationResult validation(String code) {
            ValidationResult validation = new ValidationResult();
            validation.setCodeHash(CodeFingerprint.sha256(code));
            if (environmentError) {
                validation.setStatus(ValidationStatus.ENVIRONMENT_ERROR);
                validation.getDiagnostics().add(new CompileDiagnostic(
                        "ERROR", "SDK_NOT_CONFIGURED", "SDK missing", -1, -1, "", false));
            } else if (code.contains("PASS")) {
                validation.setStatus(ValidationStatus.PASSED);
            } else {
                validation.setStatus(ValidationStatus.FAILED);
                validation.getDiagnostics().add(new CompileDiagnostic(
                        "ERROR", "CANNOT_FIND_SYMBOL", "missing()", 1, 1, code, true));
            }
            return validation;
        }

        private static GenerationResult generationResult() {
            MethodModel method = new MethodModel();
            method.setPackageName("");
            method.setClassName("Subject");
            method.setMethodName("work");
            method.setReturnType("void");
            method.setMethodBodyText("void work() {}");
            GenerationResult result = new GenerationResult();
            result.setMethodModel(method);
            result.setGeneratedByLlm(true);
            return result;
        }

        private static GeneratedTestFile generatedFile(String code) {
            GeneratedTestFile file = new GeneratedTestFile();
            file.setPackageName("");
            file.setClassName("SubjectTest");
            file.setFileName("SubjectTest.java");
            file.setContent(code);
            return file;
        }
    }
}
