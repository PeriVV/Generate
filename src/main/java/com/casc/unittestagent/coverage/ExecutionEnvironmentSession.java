package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CandidateEvidence;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.util.OperationContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import java.util.function.BooleanSupplier;

/** Task-owned cache. Only environment export is reused; every candidate is compiled and run independently. */
public final class ExecutionEnvironmentSession {
    private final ProjectTestEnvironmentResolver resolver;
    private Project project;
    private PsiFile source;
    private String sourceHash;
    private ProjectTestEnvironment environment;
    private CandidateEvidence snapshot;

    public ExecutionEnvironmentSession(ProjectTestEnvironmentResolver resolver) { this.resolver = resolver; }

    public ProjectTestEnvironment resolve(Project project, PsiFile source, BooleanSupplier cancelled) {
        OperationContext.capture(cancelled).checkCancelled();
        String hash = ReadAction.compute(() -> CodeFingerprint.sha256(source.getText()));
        if (this.project == project && this.source == source && hash.equals(sourceHash)
                && environment != null && CandidateEvidenceFactory.isCurrent(project, snapshot)) return environment;
        var resolved = resolver.resolveForExecution(project, source, cancelled);
        if (!resolved.isUsable()) return resolved;
        var evidence = CandidateEvidenceFactory.capture(resolved, "");
        OperationContext.capture(cancelled).checkCancelled();
        this.project = project; this.source = source; this.sourceHash = hash;
        this.environment = resolved; this.snapshot = evidence;
        return resolved;
    }
}
