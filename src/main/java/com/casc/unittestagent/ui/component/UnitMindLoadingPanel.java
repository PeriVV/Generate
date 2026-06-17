package com.casc.unittestagent.ui.component;

import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import java.awt.FlowLayout;

/**
 * UnitMind 加载状态组件。
 * 生成或分析过程中显示明确的进行中状态，避免用户误以为工具窗口卡死。
 */
public class UnitMindLoadingPanel extends JBPanel<UnitMindLoadingPanel> {
    /**
     * 创建加载面板。
     *
     * @param message 加载提示
     */
    public UnitMindLoadingPanel(String message) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 8));
        setBorder(JBUI.Borders.empty(12));
        add(new JBLabel(new AnimatedIcon.Default()));
        add(new JBLabel(message));
    }
}
