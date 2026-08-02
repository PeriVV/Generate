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
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.validation.TestCodeValidator;
import com.intellij.testFramework.LightPlatformTestCase;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

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

    public void testRepeatedOutputStopsEarly() {
        String repeated = "class SubjectTest { /* FAIL */ }";
        Harness harness = new Harness(repeated, repeated);

        harness.run(repeated);

        assertEquals(ValidationStatus.FAILED, harness.result.getValidationResult().getStatus());
        assertEquals(1, harness.repairCalls.get());
        assertTrue(harness.result.getWarnings().stream().anyMatch(text -> text.contains("未取得进展")));
    }

    public void testEnvironmentErrorDoesNotCallRepair() {
        Harness harness = new Harness();
        harness.environmentError = true;

        harness.run("class SubjectTest { /* FAIL */ }");

        assertEquals(ValidationStatus.ENVIRONMENT_ERROR, harness.result.getValidationResult().getStatus());
        assertEquals(0, harness.repairCalls.get());
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
