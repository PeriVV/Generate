package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.ui.TestWriteModeDialog;
import com.casc.unittestagent.util.CodeFingerprint;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 将生成的测试源码写回 IntelliJ 项目目录。
 * 该类负责根据源文件路径推导测试文件位置，并通过写命令和 VFS API 保持 IDE 状态同步。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestFileWriter {
    /** 测试类合并器，负责追加时去重 import、字段和测试方法。 */
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
        TargetSnapshot targetSnapshot = snapshot(targetFile);
        WriteDecision decision = decideWriteMode(project, targetFile, targetSnapshot.content,
                requestedMode, effectiveTarget);
        if (decision.mode == TestWriteMode.CANCEL) {
            return PreparedWrite.cancelled(targetFile);
        }
        try {
            String finalContent = contentForWrite(targetSnapshot.content, content, decision, effectiveTarget);
            return new PreparedWrite(targetFile, finalContent, decision.mode, false,
                    targetSnapshot.exists, targetSnapshot.hash, sourceFile,
                    CodeFingerprint.sha256(sourceContent(sourceFile)));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("准备最终测试文件失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 写入已经完成最终内容编译校验的文件。
     */
    public void commit(Project project,
                       PreparedWrite preparedWrite,
                       ValidationResult validationResult,
                       boolean force) {
        if (preparedWrite == null || preparedWrite.cancelled) {
            return;
        }
        String finalHash = CodeFingerprint.sha256(preparedWrite.content);
        if (!force && (validationResult == null || !validationResult.isPassedFor(finalHash))) {
            throw new IllegalStateException("最终测试文件尚未通过编译校验，已阻止写入。");
        }
        if (preparedWrite.sourceFile != null
                && !preparedWrite.sourceContentHash.equals(
                CodeFingerprint.sha256(sourceContent(preparedWrite.sourceFile)))) {
            throw new IllegalStateException(
                    "被测源码在校验期间已被修改，已阻止写入。请重新分析并校验生成结果。");
        }
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                TargetSnapshot current = snapshot(preparedWrite.targetFile);
                if (current.exists != preparedWrite.targetExisted
                        || !current.hash.equals(preparedWrite.targetContentHash)) {
                    throw new IllegalStateException(
                            "目标测试文件在校验期间已被修改，已阻止覆盖。请重新执行写入并校验最新合并结果。");
                }
                writeText(preparedWrite.targetFile, preparedWrite.content);
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
        String sourcePath = ReadAction.compute(() -> sourceFile.getVirtualFile().getPath());
        String normalized = sourcePath.replace('\\', '/');
        int index = normalized.indexOf("/src/main/java/");
        if (index >= 0) {
            String root = normalized.substring(0, index);
            String relativeDir = normalized.substring(index + "/src/main/java/".length(), normalized.lastIndexOf('/'));
            return new File(root + "/src/test/java/" + relativeDir, generatedTestFile.getFileName());
        }

        File sourceIoFile = new File(sourcePath);
        File parent = sourceIoFile.getParentFile();
        return new File(new File(parent, "generated-test"), generatedTestFile.getFileName());
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
        if (!targetFile.exists()) {
            return new WriteDecision(TestWriteMode.CREATE_NEW, true);
        }
        if (requestedMode == TestWriteMode.CANCEL) {
            return new WriteDecision(TestWriteMode.CANCEL, true);
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

        VirtualFile parentVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent);
        if (parentVirtualFile == null) {
            throw new IOException("刷新目录失败：" + parent.getAbsolutePath());
        }

        VirtualFile targetVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile);
        if (targetVirtualFile == null) {
            targetVirtualFile = parentVirtualFile.createChildData(this, targetFile.getName());
        }
        VfsUtil.saveText(targetVirtualFile, content);
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile);
    }

    /**
     * 捕获目标文件当前内容，用于写入前后的乐观锁校验。
     * 优先读取 IDE 文档以包含未保存的编辑；文档读取必须处于读动作内，
     * 而同步 VFS 刷新必须处于读动作外，两者不能放在同一个读动作中。
     */
    private TargetSnapshot snapshot(File targetFile) {
        if (targetFile == null || !targetFile.exists()) {
            return new TargetSnapshot(false, "");
        }
        try {
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile);
            if (virtualFile != null) {
                String documentContent = ReadAction.compute(() -> {
                    Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
                    return document == null ? null : document.getText();
                });
                if (documentContent != null) return new TargetSnapshot(true, documentContent);
            }
            return new TargetSnapshot(true, Files.readString(targetFile.toPath(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("读取目标测试文件失败：" + ex.getMessage(), ex);
        }
    }

    private String sourceContent(PsiFile sourceFile) {
        if (sourceFile == null) return "";
        return ReadAction.compute(() -> {
            VirtualFile virtualFile = sourceFile.getVirtualFile();
            if (virtualFile != null) {
                Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
                if (document != null) return document.getText();
            }
            return sourceFile.getText();
        });
    }

    private static class TargetSnapshot {
        private final boolean exists;
        private final String content;
        private final String hash;

        private TargetSnapshot(boolean exists, String content) {
            this.exists = exists;
            this.content = content == null ? "" : content;
            this.hash = CodeFingerprint.sha256(this.content);
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
        private final PsiFile sourceFile;
        private final String sourceContentHash;

        private PreparedWrite(File targetFile, String content, TestWriteMode mode, boolean cancelled,
                              boolean targetExisted, String targetContentHash, PsiFile sourceFile,
                              String sourceContentHash) {
            this.targetFile = targetFile;
            this.content = content == null ? "" : content;
            this.mode = mode;
            this.cancelled = cancelled;
            this.targetExisted = targetExisted;
            this.targetContentHash = targetContentHash == null ? "" : targetContentHash;
            this.sourceFile = sourceFile;
            this.sourceContentHash = sourceContentHash == null ? "" : sourceContentHash;
        }

        private static PreparedWrite cancelled(File targetFile) {
            return new PreparedWrite(targetFile, "", TestWriteMode.CANCEL, true,
                    targetFile != null && targetFile.exists(), "", null, "");
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
