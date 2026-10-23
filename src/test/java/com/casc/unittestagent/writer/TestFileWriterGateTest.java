package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.TestExecutionSummary;
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
    public void testCrLfGeneratedCandidateIsValidatedAndSavedAsEditorText() throws Exception {
        Path root = Files.createTempDirectory("unitmind-generated-crlf-");
        try {
            var source = sourceFile(root);
            var writer = new TestFileWriter();
            String expected = "package sample;\nclass SubjectTest {\n @org.junit.jupiter.api.Test void generated() {}\n}\n";
            var generated = generatedFile(expected.replace("\n", "\r\n"));
            var prepared = writer.prepare(getProject(), source, generated, generated.getContent(), TestWriteMode.CREATE_NEW, "work");
            assertEquals(expected, prepared.getContent());
            var validation = ValidationResult.status(ValidationStatus.PASSED);
            validation.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            writer.commit(getProject(), prepared, validation, runtimePassed(prepared, source), false);
            assertEquals(expected, com.casc.unittestagent.util.ProjectFileText.read(prepared.getTargetFile().toPath()));
        } finally { delete(root); }
    }

    public void testCrLfAndGbkSourceAndExistingTestUseTheEditorTextFingerprint() throws Exception {
        Path root = Files.createTempDirectory("unitmind Windows 中文 空格-");
        try {
            var gbk = java.nio.charset.Charset.forName("GBK");
            Path path = root.resolve("src/main/java/sample/Subject.java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, "package sample;\r\npublic class Subject { String work() { return \"中文\"; } }\r\n", gbk);
            var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            virtual.setCharset(gbk);
            var source = PsiManager.getInstance(getProject()).findFile(virtual);
            assertTrue(source.getText(), source.getText().contains("中文"));
            var generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void generated() { String value = \"中文\"; } }");
            var writer = new TestFileWriter();
            Path target = writer.resolveTargetFile(source, generated).toPath();
            Files.createDirectories(target.getParent());
            Files.writeString(target, "package sample;\r\nclass SubjectTest { String existing = \"原有中文\"; }\r\n", gbk);
            var testVirtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            testVirtual.setCharset(gbk);
            var prepared = writer.prepare(getProject(), source, generated, generated.getContent(), TestWriteMode.APPEND_TO_EXISTING, "work");
            var validation = ValidationResult.status(ValidationStatus.PASSED);
            validation.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            writer.commit(getProject(), prepared, validation, runtimePassed(prepared, source), false);
            assertEquals(prepared.getContent(), com.casc.unittestagent.util.ProjectFileText.read(target));
            assertTrue(Files.readString(target, gbk).contains("原有中文"));
            assertEquals(gbk, testVirtual.getCharset());
        } finally { delete(root); }
    }

    public void testUtf8BomAndCrLfSourceCanPassTheNormalWriteGate() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-bom-crlf-");
        try {
            Path path = root.resolve("src/main/java/sample/Subject.java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, "\ufeffpackage sample;\r\npublic class Subject {}\r\n");
            var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            var source = PsiManager.getInstance(getProject()).findFile(virtual);
            var writer = new TestFileWriter();
            var generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void generated() {} }");
            var prepared = writer.prepare(getProject(), source, generated, generated.getContent(), TestWriteMode.CREATE_NEW, "work");
            var validation = ValidationResult.status(ValidationStatus.PASSED);
            validation.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            writer.commit(getProject(), prepared, validation, runtimePassed(prepared, source), false);
            assertTrue(prepared.getTargetFile().exists());
        } finally { delete(root); }
    }

    public void testDefaultPackageWritesUnderTestSourceRoot() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-default-package-");
        try {
            Path path = root.resolve("src/main/java/Subject.java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, "class Subject { int work() { return 1; } }");
            PsiFile source = PsiManager.getInstance(getProject()).findFile(
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path));
            var generated = generatedFile("class SubjectTest {}");
            generated.setPackageName("");
            var writer = new TestFileWriter();
            var prepared = writer.prepare(getProject(), source, generated, generated.getContent(),
                    TestWriteMode.CREATE_NEW, "work");
            assertEquals(root.resolve("src/test/java/SubjectTest.java").toFile().getCanonicalFile(),
                    prepared.getTargetFile().getCanonicalFile());
            writer.commit(getProject(), prepared, null, true);
            assertEquals(generated.getContent(), Files.readString(prepared.getTargetFile().toPath()));
        } finally { delete(root); }
    }

    public void testCancelDoesNotCreateNewFileAndCreateNewCannotOverwriteExistingFile() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-modes-");
        try {
            var source = sourceFile(root);
            var writer = new TestFileWriter();
            var generated = generatedFile("package sample; class SubjectTest {}");
            var cancelled = writer.prepare(getProject(), source, generated, generated.getContent(),
                    TestWriteMode.CANCEL, "work");
            assertTrue(cancelled.isCancelled());
            writer.commit(getProject(), cancelled, null, true);
            assertFalse(cancelled.getTargetFile().exists());
            Path target = writer.resolveTargetFile(source, generated).toPath();
            Files.createDirectories(target.getParent());
            String original = "package sample; class SubjectTest { void humanTest() {} }";
            Files.writeString(target, original);
            try {
                writer.prepare(getProject(), source, generated, generated.getContent(), TestWriteMode.CREATE_NEW, "work");
                fail("CREATE_NEW must require an absent target");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("已存在"));
            }
            assertEquals(original, Files.readString(target));
        } finally { delete(root); }
    }

    public void testExternalDiskChangeCannotBeHiddenByUnsavedTargetDocument() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-disk-and-document-");
        try {
            var source = sourceFile(root);
            var writer = new TestFileWriter();
            var generated = generatedFile("package sample; class SubjectTest { void generated() {} }");
            Path target = writer.resolveTargetFile(source, generated).toPath();
            Files.createDirectories(target.getParent());
            Files.writeString(target, "package sample; class SubjectTest {}");
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
                    document.setText("package sample; class SubjectTest { void editorChange() {} }"));
            var prepared = writer.prepare(getProject(), source, generated, generated.getContent(),
                    TestWriteMode.APPEND_TO_EXISTING, "work");
            String external = "package sample; class SubjectTest { void externalChange() {} }";
            Files.writeString(target, external);
            try {
                writer.commit(getProject(), prepared, null, true);
                fail("unsaved text must not mask a concurrent disk change");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("目标测试文件"));
            }
            assertEquals(external, Files.readString(target));
            assertTrue(document.getText().contains("editorChange"));
        } finally { delete(root); }
    }

    public void testInvalidFileNamesCannotEscapeTheTestDirectory() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-file-name-");
        try {
            var source = sourceFile(root);
            var writer = new TestFileWriter();
            var generated = generatedFile("package sample; class SubjectTest {}");
            for (String name : new String[]{"../SubjectTest.java", "sub/SubjectTest.java", "sub\\SubjectTest.java", "", "class.java"}) {
                generated.setFileName(name);
                try { writer.resolveTargetFile(source, generated); fail("invalid filename accepted: " + name); }
                catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("文件名")); }
            }
        } finally { delete(root); }
    }

    public void testBlocksUnverifiedAndHashMismatchedContentThenAllowsPassedContent() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void exercise() {} }");
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
            writer.commit(getProject(), prepared, passed, runtimePassed(prepared, source), false);

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
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void exercise() {} }");
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
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void exercise() {} }");
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
                writer.commit(getProject(), prepared, passed, runtimePassed(prepared, source), false);
                fail("source changes must invalidate a prepared write");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("被测源码"));
            }
        } finally {
            delete(root);
        }
    }

    public void testCompiledContentRequiresMatchingRuntimeProof() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-runtime-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void exercise() {} }");
            TestFileWriter writer = new TestFileWriter();
            TestFileWriter.PreparedWrite prepared = writer.prepare(getProject(), source, generated,
                    generated.getContent(), TestWriteMode.CREATE_NEW, "work");
            ValidationResult validation = ValidationResult.status(ValidationStatus.PASSED);
            validation.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            assertRuntimeRejected(writer, prepared, validation, null);
            for (ClassJUnitStatus status : ClassJUnitStatus.values()) {
                if (status == ClassJUnitStatus.PASSED) continue;
                ClassJUnitRepairReport report = runtimePassed(prepared, source);
                report.setStatus(status);
                assertRuntimeRejected(writer, prepared, validation, report);
            }
            ClassJUnitRepairReport running = runtimePassed(prepared, source);
            running.setStatus(ClassJUnitStatus.RUNNING);
            try {
                writer.commit(getProject(), prepared, validation, running, true);
                fail("force must not write while execution is still running");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("仍在执行"));
            }
            ClassJUnitRepairReport staleCode = runtimePassed(prepared, source);
            staleCode.setCodeHash(CodeFingerprint.sha256("old candidate"));
            assertRuntimeRejected(writer, prepared, validation, staleCode);
            ClassJUnitRepairReport staleSource = runtimePassed(prepared, source);
            staleSource.setSourceHash(CodeFingerprint.sha256("old source"));
            assertRuntimeRejected(writer, prepared, validation, staleSource);
            ClassJUnitRepairReport empty = runtimePassed(prepared, source);
            empty.getFinalExecution().setFound(0);
            empty.getFinalExecution().setSucceeded(0);
            assertRuntimeRejected(writer, prepared, validation, empty);
            ClassJUnitRepairReport skipped = runtimePassed(prepared, source);
            skipped.getFinalExecution().setSucceeded(0);
            skipped.getFinalExecution().setSkipped(1);
            assertRuntimeRejected(writer, prepared, validation, skipped);
            assertFalse(prepared.getTargetFile().exists());
            writer.commit(getProject(), prepared, validation, runtimePassed(prepared, source), false);
            assertTrue(prepared.getTargetFile().exists());
        } finally {
            delete(root);
        }
    }

    public void testExternalSourceEditInvalidatesRuntimeProof() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-source-disk-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void exercise() {} }");
            TestFileWriter writer = new TestFileWriter();
            TestFileWriter.PreparedWrite prepared = writer.prepare(getProject(), source, generated,
                    generated.getContent(), TestWriteMode.CREATE_NEW, "work");
            ValidationResult validation = ValidationResult.status(ValidationStatus.PASSED);
            validation.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            ClassJUnitRepairReport runtime = runtimePassed(prepared, source);
            Files.writeString(Path.of(source.getVirtualFile().getPath()),
                    "package sample; public class Subject { int externalEdit; }");
            try {
                writer.commit(getProject(), prepared, validation, runtime, false);
                fail("external source edits must invalidate prior execution proof");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("被测源码"));
            }
            assertFalse(prepared.getTargetFile().exists());
        } finally {
            delete(root);
        }
    }

    public void testRepairedMergeRetainsOriginalTargetLock() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-remerge-test-");
        try {
            PsiFile source = sourceFile(root);
            GeneratedTestFile generated = generatedFile("package sample; class SubjectTest { void added() {} }");
            TestFileWriter writer = new TestFileWriter();
            Path target = writer.resolveTargetFile(source, generated).toPath();
            Files.createDirectories(target.getParent());
            Files.writeString(target, "package sample; class SubjectTest { void existing() {} }");
            TestFileWriter.PreparedWrite prepared = writer.prepare(getProject(), source, generated,
                    generated.getContent(), TestWriteMode.APPEND_TO_EXISTING, "work");
            TestFileWriter.PreparedWrite repaired = writer.prepareRepaired(prepared,
                    generated.getContent().replace("added", "repaired"));
            assertTrue(repaired.getContent().contains("existing"));
            assertTrue(repaired.getContent().contains("repaired"));
            Files.writeString(target, "package sample; class SubjectTest { void concurrentEdit() {} }");
            try {
                writer.commit(getProject(), repaired, null, null, true);
                fail("remerging must retain the original optimistic lock");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("目标测试文件"));
            }
        } finally {
            delete(root);
        }
    }

    public void testCommitKeepsUnsavedHumanEditsAndSynchronizesEditorWithVerifiedFile() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-document-");
        try {
            var source = sourceFile(root); var writer = new TestFileWriter();
            var generated = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void generated() {} }");
            var path = writer.resolveTargetFile(source, generated).toPath(); Files.createDirectories(path.getParent());
            Files.writeString(path, "package sample; class SubjectTest { }");
            var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            var documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
            var document = documents.getDocument(virtual);
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
                    document.setText("package sample; class SubjectTest { @org.junit.jupiter.api.Test void humanUnsaved() {} }"));
            var prepared = writer.prepare(getProject(), source, generated, generated.getContent(), TestWriteMode.APPEND_TO_EXISTING, "work");
            assertTrue(prepared.getContent().contains("humanUnsaved"));
            var validation = ValidationResult.status(ValidationStatus.PASSED); validation.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            writer.commit(getProject(), prepared, validation, runtimePassed(prepared, source), false);
            assertEquals(prepared.getContent(), document.getText());
            documents.saveAllDocuments();
            assertEquals(prepared.getContent(), Files.readString(path));
        } finally { delete(root); }
    }

    public void testPassingSummaryCannotAuthorizeAnUndiscoveredTestMethod() throws Exception {
        Path root = Files.createTempDirectory("unitmind-writer-discovery-");
        try {
            var source = sourceFile(root); var writer = new TestFileWriter();
            var file = generatedFile("package sample; class SubjectTest { @org.junit.jupiter.api.Test void visible() {} @org.junit.jupiter.api.Test private void hidden() {} }");
            var prepared = writer.prepare(getProject(), source, file, file.getContent(), TestWriteMode.CREATE_NEW, "work");
            var report = runtimePassed(prepared, source);
            report.getFinalExecution().setTestCases(java.util.List.of(new com.casc.unittestagent.model.TestCaseExecution("sample.SubjectTest#visible()", true, false)));
            report.getFinalExecution().setFound(1); report.getFinalExecution().setSucceeded(1);
            var validation = ValidationResult.status(ValidationStatus.PASSED); validation.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
            try { writer.commit(getProject(), prepared, validation, report, false); fail("Missing method evidence must block commit"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("执行证明")); }
            assertFalse(prepared.getTargetFile().exists());
        } finally { delete(root); }
    }

    private void assertRuntimeRejected(TestFileWriter writer, TestFileWriter.PreparedWrite prepared,
                                       ValidationResult validation, ClassJUnitRepairReport report) {
        try {
            writer.commit(getProject(), prepared, validation, report, false);
            fail("missing, failed or stale runtime proof must block writing");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("运行验证"));
        }
    }

    private ClassJUnitRepairReport runtimePassed(TestFileWriter.PreparedWrite prepared, PsiFile source) {
        ClassJUnitRepairReport report = ClassJUnitRepairReport.status(ClassJUnitStatus.PASSED);
        report.setCodeHash(CodeFingerprint.sha256(prepared.getContent()));
        report.setSourceHash(CodeFingerprint.sha256(source.getText()));
        TestExecutionSummary execution = new TestExecutionSummary();
        execution.setExitCode(0);
        var cases = com.github.javaparser.StaticJavaParser.parse(prepared.getContent())
                .findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(m -> m.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Test")))
                .map(m -> new com.casc.unittestagent.model.TestCaseExecution("sample.SubjectTest#" + m.getSignature().asString(), true, false)).toList();
        execution.setTestCases(cases);
        execution.setFound(Math.max(1, cases.size()));
        execution.setSucceeded(Math.max(1, cases.size()));
        report.setFinalExecution(execution);
        return report;
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
