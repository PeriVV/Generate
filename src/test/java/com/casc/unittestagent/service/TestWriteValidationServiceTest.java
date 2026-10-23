package com.casc.unittestagent.service;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.writer.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import com.casc.unittestagent.coverage.CoverageProgressListener;

public class TestWriteValidationServiceTest extends LightJavaCodeInsightFixtureTestCase {
    public void testCandidateRepairPrecedesFinalMergedRegressionAndDoesNotCommit() throws Exception {
        exercise(false, ClassJUnitStatus.PASSED, List.of("candidate", "final"));
    }

    public void testSelectedSubsetUsesRegressionWithoutAutomaticRepair() throws Exception {
        exercise(true, ClassJUnitStatus.PASSED, List.of("regression", "final"));
    }

    public void testFailedCandidateStopsBeforeMergeAndFinalExecution() throws Exception {
        exercise(false, ClassJUnitStatus.TEST_FAILED, List.of("candidate"));
    }

    private void exercise(boolean exclusions, ClassJUnitStatus status, List<String> expected) throws Exception {
        var root = java.nio.file.Files.createTempDirectory("write-workflow-");
        var path = root.resolve("src/main/java/sample/Subject.java");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, "package sample; class Subject {}");
        var virtual = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        var source = com.intellij.psi.PsiManager.getInstance(getProject()).findFile(virtual);
        try {
        var generated = new GeneratedTestFile(); generated.setPackageName("sample"); generated.setClassName("SubjectTest");
        generated.setFileName("SubjectTest.java"); generated.setContent("package sample; class SubjectTest {}");
        var candidate = new GenerationResult(); candidate.setGeneratedTestFile(generated);
        var calls = new ArrayList<String>();
        var service = new UnitTestGenerationService() {
            @Override public GenerationResult runRuntimeValidation(com.intellij.openapi.project.Project project,
                    com.intellij.psi.PsiFile file, GenerationResult result, String code, BooleanSupplier cancelled,
                    CoverageProgressListener progress) {
                calls.add("candidate"); result.setRuntimeValidationReport(ClassJUnitRepairReport.status(status)); return result;
            }
            @Override public GenerationResult runFinalTestRegression(com.intellij.openapi.project.Project project,
                    com.intellij.psi.PsiFile file, GenerationResult result, String code, BooleanSupplier cancelled,
                    CoverageProgressListener progress) {
                calls.add("regression"); result.setRuntimeValidationReport(ClassJUnitRepairReport.status(status)); return result;
            }
            @Override public GenerationResult runFinalTestRegressionWithCoverage(com.intellij.openapi.project.Project project,
                    com.intellij.psi.PsiFile file, GenerationResult result, String code, String target,
                    BooleanSupplier cancelled, CoverageProgressListener progress) { calls.add("final"); return result; }
        };
        if (exclusions) {
            // Exclusions are an orchestration input; no test is physically removed by this stub.
            candidate.setRunnableTestSelectionReport(new RunnableTestSelectionReport() {
                @Override public boolean hasActiveExclusions() { return true; }
            });
        }
        var writer = new TestFileWriter();
        var prepared = writer.prepare(getProject(), source, generated, generated.getContent(), TestWriteMode.CREATE_NEW, "work");
        var outcome = new TestWriteValidationService(writer, service).validate(getProject(), source, candidate,
                generated.getContent(), prepared, false, () -> false, null);
        assertEquals(expected, calls);
        assertNotNull(outcome.result());
        assertFalse(outcome.preparedWrite().getTargetFile().exists());
        } finally {
            try (var files = java.nio.file.Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(file);
            }
        }
    }
}
