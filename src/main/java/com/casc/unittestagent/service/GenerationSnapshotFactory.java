package com.casc.unittestagent.service;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GeneratedTestFile;

/** Background tasks own editable inputs and reports; live UI state is never the task's output buffer. */
public final class GenerationSnapshotFactory {
    private GenerationSnapshotFactory() { }
    public static GenerationResult runtimeCandidate(GenerationResult original, String code) {
        var candidate = generationCandidate(original);
        candidate.getGeneratedTestFile().setContent(code);
        return candidate;
    }
    public static GenerationResult generationCandidate(GenerationResult original) {
        GenerationResult copy = new GenerationResult();
        copy.setGenerationScope(original.getGenerationScope());
        copy.setClassMethodSignatures(new java.util.ArrayList<>(original.getClassMethodSignatures()));
        copy.setSharedFixtureSummary(original.getSharedFixtureSummary());
        copy.setMethodModel(original.getMethodModel());
        copy.setScenarios(new java.util.ArrayList<>(original.getScenarios().stream().map(com.casc.unittestagent.model.TestScenario::copy).toList()));
        copy.setSymbolSnapshot(original.getSymbolSnapshot());
        copy.setMockStrategy(original.getMockStrategy() == null ? null : original.getMockStrategy().copy());
        copy.setLlmMockStrategy(original.getLlmMockStrategy() == null ? null : original.getLlmMockStrategy().copy());
        copy.setUseLlmMockStrategy(original.isUseLlmMockStrategy());
        copy.setWarnings(new java.util.ArrayList<>(original.getWarnings()));
        copy.setEvidenceResumeCount(original.getEvidenceResumeCount());
        copy.setGeneratedByLlm(original.isGeneratedByLlm());
        copy.setRuleBasedFallback(original.isRuleBasedFallback());
        copy.setLlmMessage(original.getLlmMessage());
        copy.setRunnableTestSelectionReport(original.getRunnableTestSelectionReport().copy());
        GeneratedTestFile template = original.getGeneratedTestFile();
        if (template != null) {
            GeneratedTestFile file = new GeneratedTestFile();
            file.setPackageName(template.getPackageName());
            file.setClassName(template.getClassName());
            file.setFileName(template.getFileName());
            file.setContent(template.getContent());
            copy.setGeneratedTestFile(file);
        }
        return copy;
    }
}
