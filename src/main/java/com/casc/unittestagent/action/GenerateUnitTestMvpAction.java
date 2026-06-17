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

/**
 * IntelliJ 编辑器中的单元测试生成入口。
 * 该动作负责校验当前 IDE 上下文，并把源码分析、用例规划和预览展示交给服务层完成。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class GenerateUnitTestMvpAction extends AnAction {
    /**
     * 响应用户在 IDE 菜单或按钮上的动作。
     * 方法只做上下文校验和流程入口分发，业务生成逻辑由服务层或对话框承担。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param event IDE 或 Swing 触发的动作事件，提供当前项目、编辑器或按钮上下文
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        if (project != null && editor != null && psiFile == null) {
            psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        }

        if (project == null || editor == null || !(psiFile instanceof PsiJavaFile)) {
            Messages.showErrorDialog(project, "请先打开一个 Java 文件，再生成单元测试。", "单元测试生成预览");
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("UnitMind");
        if (toolWindow == null) {
            Messages.showErrorDialog(project, "UnitMind 工具窗口尚未初始化。", "UnitMind");
            return;
        }

        PsiFile finalPsiFile = psiFile;
        toolWindow.show(() -> ApplicationManager.getApplication().invokeLater(() -> {
            UnitMindToolWindowPanel panel = UnitMindToolWindowRegistry.get(project);
            if (panel != null) {
                panel.setCurrentContext(editor, finalPsiFile);
            }
        }));
    }

    /**
     * 根据当前编辑器上下文控制动作是否可见可用。
     * 只在 Java 文件中展示入口，避免用户在不支持的文件类型上触发生成流程。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param event IDE 或 Swing 触发的动作事件，提供当前项目、编辑器或按钮上下文
     */
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
