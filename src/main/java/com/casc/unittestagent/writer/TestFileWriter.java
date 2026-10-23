package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ClassJUnitRepairReport;
import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.RunnableTestSelectionReport;
import com.casc.unittestagent.ui.TestWriteModeDialog;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.util.ProjectFileText;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 将生成的测试源码写回 IntelliJ 项目目录。
 * 该类负责根据源文件路径推导测试文件位置，并通过写命令和 VFS API 保持 IDE 状态同步。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestFileWriter {
    /** 测试类合并器，负责追加时去重 import、字段和测试方法。 */
    private static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(TestFileWriter.class);
    private final TestClassMerger merger = new TestClassMerger();

    /**
     * 计算本次真正准备落盘的最终内容，追加模式会在此阶段完成合并。
     */
    public PreparedWrite prepare(Project project,
                                 PsiFile sourceFile,
                                 GeneratedTestFile generatedTestFile,
                                 String editedContent,
                                 TestWriteMode requestedMode,
                                 String testedMethodName) {
        return prepare(project, sourceFile, generatedTestFile, editedContent, requestedMode,
                GenerationTarget.method(testedMethodName));
    }

    public PreparedWrite prepare(Project project,
                                 PsiFile sourceFile,
                                 GeneratedTestFile generatedTestFile,
                                 String editedContent,
                                 TestWriteMode requestedMode,
                                 GenerationTarget generationTarget) {
        return prepare(project, sourceFile, generatedTestFile, editedContent, requestedMode, generationTarget, null);
    }

    public PreparedWrite prepare(Project project, PsiFile sourceFile, GeneratedTestFile generatedTestFile,
                                 String editedContent, TestWriteMode requestedMode, GenerationTarget generationTarget,
                                 RunnableTestSelectionReport selection) {
        if (sourceFile == null || sourceFile.getVirtualFile() == null) {
            throw new IllegalStateException("源文件不可用。");
        }
        if (generatedTestFile == null || generatedTestFile.getContent() == null) {
            throw new IllegalStateException("生成的测试内容为空。");
        }

        File targetFile = resolveTargetFile(sourceFile, generatedTestFile);
        if (editedContent != null && editedContent.trim().isEmpty()) {
            throw new IllegalStateException("测试代码为空，不能写入测试文件。");
        }
        String content = editedContent == null ? generatedTestFile.getContent() : editedContent;
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalStateException("测试代码为空，不能写入测试文件。");
        }
        GenerationTarget effectiveTarget = generationTarget == null ? GenerationTarget.method("未知方法") : generationTarget;
        String sourceHash = CodeFingerprint.sha256(sourceContent(sourceFile));
        SelectionExclusionGuard exclusions = SelectionExclusionGuard.snapshot(selection, sourceHash);
        exclusions.verifyAbsent(content);
        TargetSnapshot targetSnapshot = snapshot(targetFile);
        WriteDecision decision = decideWriteMode(project, targetFile, targetSnapshot.content,
                requestedMode, effectiveTarget);
        if (decision.mode == TestWriteMode.CANCEL) {
            return PreparedWrite.cancelled(targetFile);
        }
        try {
            String finalContent = contentForWrite(decision.mode == TestWriteMode.APPEND_TO_EXISTING
                            ? exclusions.removeExcludedFromExisting(targetSnapshot.content) : targetSnapshot.content,
                    content, decision, effectiveTarget);
            exclusions.verifyAbsent(finalContent);
            return new PreparedWrite(targetFile, finalContent, decision.mode, false,
                    targetSnapshot.exists, targetSnapshot.hash, targetSnapshot.diskHash, sourceFile,
                    sourceHash, targetSnapshot.content,
                    effectiveTarget, decision.replaceExistingRegion, exclusions);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("准备最终测试文件失败：" + ex.getMessage(), ex);
        }
    }

    /** 使用同一次准备时的目标快照重新合并修复结果，保留源文件与目标文件的乐观锁。 */
    public PreparedWrite prepareRepaired(PreparedWrite prepared, String generatedContent) {
        if (prepared == null || prepared.cancelled) throw new IllegalStateException("缺少写入快照。");
        prepared.exclusions.verifyAbsent(generatedContent);
        String content = contentForWrite(prepared.mode == TestWriteMode.APPEND_TO_EXISTING
                        ? prepared.exclusions.removeExcludedFromExisting(prepared.existingContent) : prepared.existingContent, generatedContent,
                new WriteDecision(prepared.mode, prepared.replaceExistingRegion), prepared.generationTarget);
        prepared.exclusions.verifyAbsent(content);
        return new PreparedWrite(prepared.targetFile, content, prepared.mode, false,
                prepared.targetExisted, prepared.targetContentHash, prepared.targetDiskHash, prepared.sourceFile,
                prepared.sourceContentHash, prepared.existingContent, prepared.generationTarget,
                prepared.replaceExistingRegion, prepared.exclusions);
    }

    /** 兼容旧调用方；没有运行报告时只允许显式保存草稿。 */
    public void commit(Project project, PreparedWrite preparedWrite,
                       ValidationResult validationResult, boolean force) {
        commit(project, preparedWrite, validationResult, null, force);
    }

    /** 正常写入必须同时具备最终完整文本的编译与真实运行证明。 */
    public void commit(Project project, PreparedWrite preparedWrite,
                       ValidationResult validationResult,
                       ClassJUnitRepairReport runtimeReport, boolean force) {
        if (preparedWrite == null || preparedWrite.cancelled) return;
        preparedWrite.exclusions.verifyAbsent(preparedWrite.content);
        String finalHash = CodeFingerprint.sha256(preparedWrite.content);
        if (runtimeReport != null && runtimeReport.getStatus() == ClassJUnitStatus.RUNNING) {
            throw new IllegalStateException("完整 JUnit 运行验证仍在执行，已阻止写入。");
        }
        if (!force && (validationResult == null || !validationResult.isPassedFor(finalHash))) {
            throw new IllegalStateException("最终测试文件尚未通过编译校验，已阻止写入。");
        }
        if (!force && (runtimeReport == null
                || !runtimeReport.isPassedFor(finalHash, preparedWrite.sourceContentHash))) {
            throw new IllegalStateException("最终测试文件尚未通过完整 JUnit 运行验证，已阻止写入。");
        }
        if (!force && !com.casc.unittestagent.validation.JUnitExecutionEvidence.unexecutedMethods(
                preparedWrite.content, runtimeReport.getFinalExecution()).isEmpty()) {
            throw new IllegalStateException("最终测试文件存在未获真实执行证明的方法组，已阻止写入。");
        }
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                if (preparedWrite.sourceFile != null && !preparedWrite.sourceContentHash.equals(
                        CodeFingerprint.sha256(sourceContent(preparedWrite.sourceFile)))) {
                    throw new IllegalStateException(
                            "被测源码在校验期间已被修改，已阻止写入。请重新分析并校验生成结果。");
                }
                TargetSnapshot current = snapshot(preparedWrite.targetFile);
                if (current.exists != preparedWrite.targetExisted
                        || !current.hash.equals(preparedWrite.targetContentHash)
                        || !current.diskHash.equals(preparedWrite.targetDiskHash)) {
                    throw new IllegalStateException(
                            "目标测试文件在校验期间已被修改，已阻止覆盖。请重新执行写入并校验最新合并结果。");
                }
                writeText(preparedWrite.targetFile, preparedWrite.content);
                String diskHash = CodeFingerprint.sha256(ProjectFileText.read(preparedWrite.targetFile.toPath()));
                if (!finalHash.equals(diskHash)) throw new IOException("写入后文件内容与已验证快照不一致，请重新验证。");
                LOG.info("UnitMind file committed: mode=" + preparedWrite.mode + ", draft=" + force
                        + ", codeHash=" + diskHash + ", sourceHash=" + preparedWrite.sourceContentHash);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    /**
     * 根据源文件推导测试文件路径。
     * 标准 Gradle/Maven 目录写入 src/test/java，非标准目录落到 generated-test 子目录。
     */
    public File resolveTargetFile(PsiFile sourceFile, GeneratedTestFile generatedTestFile) {
        String fileName = generatedTestFile == null ? null : generatedTestFile.getFileName();
        if (fileName == null || !fileName.endsWith(".java")
                || !javax.lang.model.SourceVersion.isIdentifier(fileName.substring(0, fileName.length() - 5))
                || javax.lang.model.SourceVersion.isKeyword(fileName.substring(0, fileName.length() - 5))) {
            throw new IllegalStateException("测试文件名必须是有效的 Java 简单类名加 .java。");
        }
        String sourcePath = ReadAction.compute(() -> sourceFile.getVirtualFile().getPath());
        String normalized = sourcePath.replace('\\', '/');
        int index = normalized.indexOf("/src/main/java/");
        if (index >= 0) {
            String root = normalized.substring(0, index);
            String relativePath = normalized.substring(index + "/src/main/java/".length());
            int separator = relativePath.lastIndexOf('/');
            String relativeDir = separator < 0 ? "" : relativePath.substring(0, separator);
            return new File(root + "/src/test/java/" + relativeDir, fileName);
        }

        File sourceIoFile = new File(sourcePath);
        File parent = sourceIoFile.getParentFile();
        return new File(new File(parent, "generated-test"), fileName);
    }

    /**
     * 决定最终写入模式。
     * 目标文件不存在时直接新建；目标文件存在时默认弹窗选择追加、覆盖或取消。
     */
    private WriteDecision decideWriteMode(Project project,
                                          File targetFile,
                                          String existingContent,
                                          TestWriteMode requestedMode,
                                          GenerationTarget generationTarget) {
        if (requestedMode == TestWriteMode.CANCEL) {
            return new WriteDecision(TestWriteMode.CANCEL, true);
        }
        if (!targetFile.exists()) {
            return new WriteDecision(TestWriteMode.CREATE_NEW, true);
        }
        if (requestedMode == TestWriteMode.CREATE_NEW) {
            throw new IllegalStateException("目标测试文件已存在，请选择追加或覆盖。");
        }
        if (requestedMode != null && requestedMode != TestWriteMode.CANCEL) {
            return new WriteDecision(requestedMode, true);
        }
        boolean hasRegion = merger.hasRegion(existingContent, generationTarget);
        TestWriteModeDialog dialog = new TestWriteModeDialog(project, targetFile, hasRegion);
        if (!dialog.showAndGet()) {
            return new WriteDecision(TestWriteMode.CANCEL, true);
        }
        return new WriteDecision(dialog.getSelectedMode(), dialog.isReplaceExistingRegion());
    }

    /**
     * 根据写入模式生成最终落盘内容。
     * 追加模式会走测试类合并器，覆盖和新建模式直接使用当前编辑器内容。
     */
    private String contentForWrite(String existingContent, String generatedContent,
                                   WriteDecision decision, GenerationTarget generationTarget) {
        if (decision.mode == TestWriteMode.CREATE_NEW || decision.mode == TestWriteMode.OVERWRITE_EXISTING) {
            return generatedContent;
        }
        if (decision.mode == TestWriteMode.APPEND_TO_EXISTING) {
            MergeOptions options = new MergeOptions();
            options.setReplaceExistingRegionForSameMethod(decision.replaceExistingRegion);
            return merger.merge(existingContent, generatedContent, generationTarget, options);
        }
        return generatedContent;
    }

    /**
     * 通过 IntelliJ VFS 写入测试源码。
     * 写入前确保目录存在并刷新虚拟文件，避免 IDE 看不到新文件。
     */
    private void writeText(File targetFile, String content) throws IOException {
        File parent = targetFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("创建目录失败：" + parent.getAbsolutePath());
        }

        VirtualFile parentVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parent.toPath());
        if (parentVirtualFile == null) {
            throw new IOException("刷新目录失败：" + parent.getAbsolutePath());
        }

        VirtualFile targetVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile.toPath());
        if (targetVirtualFile == null) {
            targetVirtualFile = parentVirtualFile.createChildData(this, targetFile.getName());
        }
        // Update the live document as part of the same write command. Leaving an unsaved editor
        // on the old text would let a later Save All overwrite the verified file.
        var documents = FileDocumentManager.getInstance();
        var charset = targetVirtualFile.getCharset();
        if (!charset.newEncoder().canEncode(content)) {
            throw new IOException("目标测试文件编码 " + charset.name()
                    + " 无法表示当前内容，请在 IDEA 将该文件改为 UTF-8 后重新验证。");
        }
        Document document = documents.getDocument(targetVirtualFile);
        if (document != null) {
            document.setText(com.intellij.openapi.util.text.StringUtil.convertLineSeparators(content));
            documents.saveDocument(document);
        }
        // saveDocument preserves the old file's line separators. Persist the exact verified text
        // after the document is clean, so CRLF settings cannot change the measured fingerprint.
        // Saving a document may choose another default encoding. Preserve the encoding used by
        // the prepared file and encode the verified text consistently with its VFS metadata.
        targetVirtualFile.setCharset(charset);
        VfsUtil.saveText(targetVirtualFile, content);
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile.toPath());
        targetVirtualFile.setCharset(charset);
    }

    /**
     * 捕获目标文件当前内容，用于写入前后的乐观锁校验。
     * 优先读取 IDE 文档以包含未保存的编辑；文档读取必须处于读动作内，
     * 而同步 VFS 刷新必须处于读动作外，两者不能放在同一个读动作中。
     */
    private TargetSnapshot snapshot(File targetFile) {
        if (targetFile == null || !targetFile.exists()) {
            return new TargetSnapshot(false, "", "");
        }
        try {
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile.toPath());
            String diskContent = ProjectFileText.read(targetFile.toPath());
            String diskHash = ProjectFileText.diskHash(targetFile.toPath());
            if (virtualFile != null) {
                String documentContent = ReadAction.compute(() -> {
                    Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
                    return document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)
                            ? document.getText() : null;
                });
                if (documentContent != null) return new TargetSnapshot(true, documentContent, diskHash);
            }
            return new TargetSnapshot(true, diskContent, diskHash);
        } catch (IOException ex) {
            throw new IllegalStateException("读取目标测试文件失败：" + ex.getMessage(), ex);
        }
    }

    private String sourceContent(PsiFile sourceFile) {
        if (sourceFile == null) return "";
        String unsaved = ReadAction.compute(() -> {
            VirtualFile virtualFile = sourceFile.getVirtualFile();
            if (virtualFile == null) return null;
            Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            return document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)
                    ? document.getText() : null;
        });
        if (unsaved != null) return unsaved;
        VirtualFile virtualFile = sourceFile.getVirtualFile();
        if (virtualFile != null && virtualFile.isInLocalFileSystem()) {
            try {
                return ProjectFileText.read(new File(virtualFile.getPath()).toPath());
            } catch (IOException ex) {
                throw new IllegalStateException("读取被测源码失败：" + ex.getMessage(), ex);
            }
        }
        return ReadAction.compute(sourceFile::getText);
    }

    private static class TargetSnapshot {
        private final boolean exists;
        private final String content;
        private final String hash;
        private final String diskHash;

        private TargetSnapshot(boolean exists, String content, String diskHash) {
            this.exists = exists;
            this.content = content == null ? "" : content;
            this.hash = CodeFingerprint.sha256(this.content);
            this.diskHash = diskHash;
        }
    }

    /**
     * 写入模式决策结果。
     * 除模式本身外，还记录同一方法旧 region 是否需要替换。
     */
    private static class WriteDecision {
        /** 最终写入模式。 */
        private final TestWriteMode mode;
        /** 追加时是否替换同方法旧 UnitMind region。 */
        private final boolean replaceExistingRegion;

        /**
         * 创建写入决策。
         *
         * @param mode 最终写入模式
         * @param replaceExistingRegion 追加时是否替换同方法旧 region
         */
        private WriteDecision(TestWriteMode mode, boolean replaceExistingRegion) {
            this.mode = mode;
            this.replaceExistingRegion = replaceExistingRegion;
        }
    }

    /**
     * 写入模式决策后形成的最终文件快照。
     */
    public static class PreparedWrite {
        private final File targetFile;
        private final String content;
        private final TestWriteMode mode;
        private final boolean cancelled;
        private final boolean targetExisted;
        private final String targetContentHash;
        private final String targetDiskHash;
        private final PsiFile sourceFile;
        private final String sourceContentHash;
        private final String existingContent;
        private final GenerationTarget generationTarget;
        private final boolean replaceExistingRegion;
        private final SelectionExclusionGuard exclusions;

        private PreparedWrite(File targetFile, String content, TestWriteMode mode, boolean cancelled,
                              boolean targetExisted, String targetContentHash, String targetDiskHash, PsiFile sourceFile,
                              String sourceContentHash, String existingContent,
                              GenerationTarget generationTarget, boolean replaceExistingRegion,
                              SelectionExclusionGuard exclusions) {
            this.targetFile = targetFile;
            this.content = content == null ? "" : content;
            this.mode = mode;
            this.cancelled = cancelled;
            this.targetExisted = targetExisted;
            this.targetContentHash = targetContentHash == null ? "" : targetContentHash;
            this.targetDiskHash = targetDiskHash == null ? "" : targetDiskHash;
            this.sourceFile = sourceFile;
            this.sourceContentHash = sourceContentHash == null ? "" : sourceContentHash;
            this.existingContent = existingContent;
            this.generationTarget = generationTarget;
            this.replaceExistingRegion = replaceExistingRegion;
            this.exclusions = exclusions;
        }

        private static PreparedWrite cancelled(File targetFile) {
            return new PreparedWrite(targetFile, "", TestWriteMode.CANCEL, true,
                    targetFile != null && targetFile.exists(), "", "", null, "", "", null, false,
                    SelectionExclusionGuard.snapshot(null, ""));
        }

        public File getTargetFile() {
            return targetFile;
        }

        public String getContent() {
            return content;
        }

        public TestWriteMode getMode() {
            return mode;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
