package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TypeSymbol;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class LlmPromptBuilderTest {
    @Test
    public void generationPromptContainsResolvedCallableCatalog() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setClassName("OrderService");
        request.setMethodName("load");
        request.setSymbolSnapshot(snapshot());

        String prompt = new LlmPromptBuilder().userPrompt(request);

        assertTrue(prompt.contains("com.example.OrderRepository#findById(java.lang.Long)"));
        assertTrue(prompt.contains("\"parameterTypes\": ["));
        assertTrue(prompt.contains("projectSymbolSnapshot"));
    }

    @Test
    public void repairPromptContainsOnlyCandidateDiagnosticsAndSnapshot() {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setSymbolSnapshot(snapshot());
        request.setExistingTestCodeSummary("class OrderServiceTest { void broken() {} }");
        request.setRepairAttempt(2);
        request.setCompilerDiagnostics(List.of(new CompileDiagnostic(
                "ERROR", "CANNOT_FIND_SYMBOL", "cannot find symbol: method missing()", 8, 12,
                "repository.missing()", true)));

        String prompt = new LlmPromptBuilder().repairPrompt(request);

        assertTrue(prompt.contains("\"repairAttempt\": 2"));
        assertTrue(prompt.contains("CANNOT_FIND_SYMBOL"));
        assertTrue(prompt.contains("repository.missing()"));
        assertTrue(prompt.contains("OrderServiceTest"));
    }

    private ProjectSymbolSnapshot snapshot() {
        CallableSymbol method = new CallableSymbol();
        method.setId("com.example.OrderRepository#findById(java.lang.Long)");
        method.setOwnerQualifiedName("com.example.OrderRepository");
        method.setName("findById");
        method.setReturnType("java.util.Optional<com.example.Order>");
        method.setParameterTypes(List.of("java.lang.Long"));

        TypeSymbol type = new TypeSymbol();
        type.setQualifiedName("com.example.OrderRepository");
        type.setKind("interface");
        type.setMethods(List.of(method));

        ProjectSymbolSnapshot snapshot = new ProjectSymbolSnapshot();
        snapshot.setTargetType("com.example.OrderService");
        snapshot.setTargetMethod(method);
        snapshot.setTypes(List.of(type));
        return snapshot;
    }
}
