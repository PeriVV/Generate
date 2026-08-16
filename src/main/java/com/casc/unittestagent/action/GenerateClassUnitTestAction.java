package com.casc.unittestagent.action;

import com.casc.unittestagent.toolwindow.UnitMindToolWindowPanel;
import com.casc.unittestagent.toolwindow.UnitMindToolWindowRegistry;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;

/** IntelliJ 编辑器中的当前类完整测试生成入口。 */
public class GenerateClassUnitTestAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        if (project != null && editor != null && psiFile == null) {
            psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        }
        if (project == null || editor == null || !(psiFile instanceof PsiJavaFile)) {
            Messages.showErrorDialog(project, "请先打开一个 Java 类文件。", "UnitMind 整类测试生成");
            return;
        }
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        documentManager.commitDocument(editor.getDocument());
        PsiFile committedFile = documentManager.getPsiFile(editor.getDocument());
        if (committedFile instanceof PsiJavaFile) psiFile = committedFile;
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("UnitMind");
        if (toolWindow == null) {
            Messages.showErrorDialog(project, "UnitMind 工具窗口尚未初始化。", "UnitMind");
            return;
        }
        PsiFile finalPsiFile = psiFile;
        toolWindow.show(() -> ApplicationManager.getApplication().invokeLater(() -> {
            UnitMindToolWindowPanel panel = UnitMindToolWindowRegistry.get(project);
            if (panel != null) panel.setCurrentClassContextAndAnalyze(editor, finalPsiFile);
        }));
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        Project project = event.getProject();
        if (project != null && editor != null && psiFile == null) {
            psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        }
        event.getPresentation().setEnabledAndVisible(psiFile instanceof PsiJavaFile);
    }
}
