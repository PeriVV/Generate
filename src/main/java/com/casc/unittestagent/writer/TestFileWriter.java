package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.ui.TestWriteModeDialog;
import com.casc.unittestagent.util.CodeFingerprint;
import com.intellij.openapi.command.WriteCommandAction;
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
     * 将生成的测试文件写入项目目录。
     * 写入动作通过 IntelliJ 写命令执行，确保 IDE 的文档模型和虚拟文件系统保持一致。
     * @param project 当前 IntelliJ 项目上下文，用于消息提示、写命令和对话框归属
     * @param sourceFile 被测源码文件，用于推导测试文件写入位置
     * @param generatedTestFile 准备写入磁盘的测试文件模型
     * @throws IllegalStateException 当源文件或生成内容不可用时抛出
     * @throws RuntimeException 当底层文件写入失败时抛出
     */
    public void write(Project project, PsiFile sourceFile, GeneratedTestFile generatedTestFile) {
        throw new IllegalStateException("写入前必须先调用 prepare，并使用通过编译校验的 ValidationResult 执行 commit。");
    }

    /**
     * 将用户编辑后的测试源码写入目标测试文件。
     * 当目标文件已存在且未指定模式时会弹出选择框，默认追加而不是覆盖。
     *
     * @param project 当前 IntelliJ 项目上下文，用于消息提示、写命令和对话框归属
     * @param sourceFile 被测源码文件，用于推导测试文件写入位置
     * @param generatedTestFile 准备写入磁盘的测试文件模型
     * @param editedContent 用户在工具窗口中编辑后的测试源码，非空时优先写入该内容
     * @param requestedMode 调用方指定的写入模式，传 null 时由写入服务按目标文件状态决定
     * @param testedMethodName 本次被测方法名，追加时用于 UnitMind region 标记
     */
    public void write(Project project,
                      PsiFile sourceFile,
                      GeneratedTestFile generatedTestFile,
                      String editedContent,
                      TestWriteMode requestedMode,
                      String testedMethodName) {
        throw new IllegalStateException("写入前必须先调用 prepare，并使用通过编译校验的 ValidationResult 执行 commit。");
    }

    /**
     * 计算本次真正准备落盘的最终内容，追加模式会在此阶段完成合并。
     */
    public PreparedWrite prepare(Project project,
                                 PsiFile sourceFile,
                                 GeneratedTestFile generatedTestFile,
                                 String editedContent,
                                 TestWriteMode requestedMode,
                                 String testedMethodName) {
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
        WriteDecision decision = decideWriteMode(project, targetFile, content, requestedMode, testedMethodName);
        if (decision.mode == TestWriteMode.CANCEL) {
            return PreparedWrite.cancelled(targetFile);
        }
        try {
            String finalContent = contentForWrite(targetFile, content, decision, testedMethodName);
            return new PreparedWrite(targetFile, finalContent, decision.mode, false);
        } catch (IOException ex) {
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
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
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
        String sourcePath = sourceFile.getVirtualFile().getPath();
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
     * 判断目标文件是否存在。
     * 工具窗口写入预览需要提前展示模式，因此提供轻量查询方法。
     *
     * @param sourceFile 被测源码文件
     * @param generatedTestFile 生成测试文件模型
     * @return 目标测试文件已存在时返回 true
     */
    public boolean targetExists(PsiFile sourceFile, GeneratedTestFile generatedTestFile) {
        return resolveTargetFile(sourceFile, generatedTestFile).exists();
    }

    /**
     * 决定最终写入模式。
     * 目标文件不存在时直接新建；目标文件存在时默认弹窗选择追加、覆盖或取消。
     */
    private WriteDecision decideWriteMode(Project project,
                                          File targetFile,
                                          String generatedContent,
                                          TestWriteMode requestedMode,
                                          String testedMethodName) {
        if (!targetFile.exists()) {
            return new WriteDecision(TestWriteMode.CREATE_NEW, true);
        }
        if (requestedMode == TestWriteMode.CANCEL) {
            return new WriteDecision(TestWriteMode.CANCEL, true);
        }
        if (requestedMode != null && requestedMode != TestWriteMode.CANCEL) {
            return new WriteDecision(requestedMode, true);
        }
        boolean hasRegion = false;
        try {
            hasRegion = merger.hasRegionForMethod(Files.readString(targetFile.toPath(), StandardCharsets.UTF_8), testedMethodName);
        } catch (IOException ignored) {
            hasRegion = false;
        }
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
    private String contentForWrite(File targetFile, String generatedContent, WriteDecision decision, String testedMethodName) throws IOException {
        if (decision.mode == TestWriteMode.CREATE_NEW || decision.mode == TestWriteMode.OVERWRITE_EXISTING) {
            return generatedContent;
        }
        if (decision.mode == TestWriteMode.APPEND_TO_EXISTING) {
            MergeOptions options = new MergeOptions();
            options.setReplaceExistingRegionForSameMethod(decision.replaceExistingRegion);
            String existing = Files.readString(targetFile.toPath(), StandardCharsets.UTF_8);
            return merger.merge(existing, generatedContent, testedMethodName, options);
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

        private PreparedWrite(File targetFile, String content, TestWriteMode mode, boolean cancelled) {
            this.targetFile = targetFile;
            this.content = content == null ? "" : content;
            this.mode = mode;
            this.cancelled = cancelled;
        }

        private static PreparedWrite cancelled(File targetFile) {
            return new PreparedWrite(targetFile, "", TestWriteMode.CANCEL, true);
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
