package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CoverageProgressListener;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.util.OperationContext;
import com.casc.unittestagent.writer.TestFileWriter;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import java.util.function.BooleanSupplier;

/** Prepares reviewable write output; committing remains a separate optimistic-lock operation on the EDT. */
public final class TestWriteValidationService {
    private final TestFileWriter writer;
    private final UnitTestGenerationService generation;

    public TestWriteValidationService(TestFileWriter writer, UnitTestGenerationService generation) {
        this.writer = writer;
        this.generation = generation;
    }

    public record Outcome(TestFileWriter.PreparedWrite preparedWrite, GenerationResult result) { }

    public Outcome validate(Project project, PsiFile source, GenerationResult candidate, String editedCode,
                            TestFileWriter.PreparedWrite prepared, boolean force, BooleanSupplier cancelled,
                            CoverageProgressListener progress) {
        var operation = OperationContext.capture(cancelled);
        operation.checkCancelled();
        var listener = progress == null ? CoverageProgressListener.NONE : progress;
        if (force) {
            var draft = GenerationSnapshotFactory.runtimeCandidate(candidate, prepared.getContent());
            draft.setValidationResult(generation.validateCode(project, source, draft.getGeneratedTestFile(),
                    prepared.getContent(), cancelled));
            return new Outcome(prepared, draft);
        }
        CoverageProgressListener candidateProgress = (text, fraction) ->
                listener.update("运行验证：" + text, Math.max(0d, Math.min(0.7d, fraction * 0.7d)));
        if (candidate.getRunnableTestSelectionReport().hasActiveExclusions())
            generation.runFinalTestRegression(project, source, candidate, editedCode, cancelled, candidateProgress);
        else generation.runRuntimeValidation(project, source, candidate, editedCode, cancelled, candidateProgress);
        operation.checkCancelled();
        if (candidate.getRuntimeValidationReport().getStatus() != ClassJUnitStatus.PASSED)
            return new Outcome(prepared, candidate);
        var finalWrite = writer.prepareRepaired(prepared, candidate.getGeneratedTestFile().getContent());
        var finalResult = GenerationSnapshotFactory.runtimeCandidate(candidate, finalWrite.getContent());
        generation.runFinalTestRegressionWithCoverage(project, source, finalResult, finalWrite.getContent(),
                finalWrite.getTargetFile().getAbsolutePath(), cancelled, (text, fraction) ->
                        listener.update("最终合并回归：" + text, 0.7d + Math.max(0d, Math.min(1d, fraction)) * 0.3d));
        operation.checkCancelled();
        return new Outcome(finalWrite, finalResult);
    }
}
