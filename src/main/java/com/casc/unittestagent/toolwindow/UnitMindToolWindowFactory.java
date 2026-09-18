package com.casc.unittestagent.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 创建 UnitMind 右侧工具窗口内容。
 * IDEA 打开项目后通过 plugin.xml 注册该工厂，用户点击右侧 UnitMind 时会展示此面板。
 */
public class UnitMindToolWindowFactory implements ToolWindowFactory {
    /**
     * 创建工具窗口内容并注册面板。
     *
     * @param project 当前 IntelliJ 项目
     * @param toolWindow UnitMind 工具窗口实例
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        UnitMindToolWindowPanel panel = new UnitMindToolWindowPanel(project);
        UnitMindToolWindowRegistry.register(project, panel);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(panel, "", false);
        // 面板强引用 Project/Editor/PSI，注册表条目必须在内容销毁或项目关闭时注销，
        // 否则静态集合会在项目关闭后长期持有整个面板。
        content.setDisposer(() -> UnitMindToolWindowRegistry.unregister(project));
        toolWindow.getContentManager().addContent(content);
        Disposer.register(project, new Disposable() {
            @Override
            public void dispose() {
                UnitMindToolWindowRegistry.unregister(project);
            }
        });
    }
}
