package com.casc.unittestagent.action;

import com.casc.unittestagent.ui.TestDataRangeConfigDialog;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 该动作只负责打开配置对话框，将具体校验和持久化交给 UI 与配置管理模块处理。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class ConfigureTestDataRangeAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        new TestDataRangeConfigDialog(event.getProject()).show();
    }
}
