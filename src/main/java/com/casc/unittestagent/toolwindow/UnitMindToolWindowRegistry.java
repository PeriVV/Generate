package com.casc.unittestagent.toolwindow;

import com.intellij.openapi.project.Project;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 保存项目与 UnitMind 工具窗口面板的映射。
 * 右键菜单动作通过该注册表把当前编辑器上下文传递给已打开的右侧工具窗口。
 */
public final class UnitMindToolWindowRegistry {
    /** 使用弱引用语义的项目映射，避免项目关闭后长期持有窗口面板。 */
    private static final Map<Project, UnitMindToolWindowPanel> PANELS = new WeakHashMap<>();

    /**
     * 工具类不允许实例化。
     */
    private UnitMindToolWindowRegistry() {
    }

    /**
     * 注册项目对应的工具窗口面板。
     *
     * @param project 当前项目
     * @param panel UnitMind 工具窗口面板
     */
    public static synchronized void register(Project project, UnitMindToolWindowPanel panel) {
        PANELS.put(project, panel);
    }

    /**
     * 查找项目对应的工具窗口面板。
     *
     * @param project 当前项目
     * @return 已注册的面板，未创建时返回 null
     */
    public static synchronized UnitMindToolWindowPanel get(Project project) {
        return PANELS.get(project);
    }
}
