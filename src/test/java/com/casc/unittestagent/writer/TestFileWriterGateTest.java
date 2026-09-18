package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.util.CodeFingerprint;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class TestFileWriterGateTest extends LightJavaCodeInsightFixtureTestCase {
    public void testBlocksUnverifiedAndHashMismatchedContentThenAllowsPassedContent() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest {}");
            TestFileWriter writer = new TestFileWriter();
            TestFileWriter.PreparedWrite prepared = writer.prepare(
                    getProject(), source, generated, generated.getContent(),
                    TestWriteMode.CREATE_NEW, "work");

            assertCommitRejected(writer, prepared, ValidationResult.status(ValidationStatus.NOT_RUN));
            ValidationResult mismatched = ValidationResult.status(ValidationStatus.PASSED);
            mismatched.setCodeHash(CodeFingerprint.sha256("different"));
            assertCommitRejected(writer, prepared, mismatched);

            ValidationResult passed = ValidationResult.status(ValidationStatus.PASSED);
            passed.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            writer.commit(getProject(), prepared, passed, false);

            assertEquals(prepared.getContent(),
                    Files.readString(prepared.getTargetFile().toPath(), StandardCharsets.UTF_8));
        } finally {
            delete(root);
        }
    }

    public void testExplicitForceCanWriteFailedDraft() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-force-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest { broken }");
            TestFileWriter writer = new TestFileWriter();
            TestFileWriter.PreparedWrite prepared = writer.prepare(
                    getProject(), source, generated, generated.getContent(),
                    TestWriteMode.CREATE_NEW, "work");

            writer.commit(getProject(), prepared, ValidationResult.status(ValidationStatus.FAILED), true);

            assertTrue(prepared.getTargetFile().exists());
        } finally {
            delete(root);
        }
    }

    public void testRejectsTargetCreatedAfterPrepareEvenWhenForced() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-conflict-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest {}");
            TestFileWriter writer = new TestFileWriter();
            TestFileWriter.PreparedWrite prepared = writer.prepare(
                    getProject(), source, generated, generated.getContent(),
                    TestWriteMode.CREATE_NEW, GenerationTarget.method("sample.Subject#work()"));
            Files.createDirectories(prepared.getTargetFile().toPath().getParent());
            Files.writeString(prepared.getTargetFile().toPath(),
                    "package sample; class SubjectTest { void userEdit() {} }", StandardCharsets.UTF_8);

            try {
                writer.commit(getProject(), prepared, ValidationResult.status(ValidationStatus.FAILED), true);
                fail("concurrent target creation must be rejected");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("校验期间已被修改"));
            }
        } finally {
            delete(root);
        }
    }

    public void testRejectsSourceChangedAfterPrepare() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-source-conflict-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest {}");
            TestFileWriter writer = new TestFileWriter();
            TestFileWriter.PreparedWrite prepared = writer.prepare(
                    getProject(), source, generated, generated.getContent(),
                    TestWriteMode.CREATE_NEW, GenerationTarget.method("sample.Subject#work()"));
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.psi.PsiDocumentManager.getInstance(getProject())
                            .getDocument(source).setText("package sample; public class Subject { int changed; }"));

            ValidationResult passed = ValidationResult.status(ValidationStatus.PASSED);
            passed.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            try {
                writer.commit(getProject(), prepared, passed, false);
                fail("source changes must invalidate a prepared write");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("被测源码"));
            }
        } finally {
            delete(root);
        }
    }

    private void assertCommitRejected(TestFileWriter writer,
                                      TestFileWriter.PreparedWrite prepared,
                                      ValidationResult validation) {
        try {
            writer.commit(getProject(), prepared, validation, false);
            fail("未通过校验的最终文本必须被写入门禁阻止。");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("编译校验"));
        }
    }

    private PsiFile sourceFile(Path root) throws Exception {
        Path source = root.resolve("src/main/java/sample/Subject.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package sample; public class Subject {}", StandardCharsets.UTF_8);
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
        assertNotNull(virtualFile);
        PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(virtualFile);
        assertNotNull(psiFile);
        return psiFile;
    }

    private GeneratedTestFile generatedFile(String content) {
        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName("sample");
        file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java");
        file.setContent(content);
        return file;
    }

    private void delete(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
