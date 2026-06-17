package com.casc.unittestagent.ui.component;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * UnitMind 通用卡片组件。
 * 卡片使用 IntelliJ 主题色和统一内边距，承载概要、空状态、表格分组等正式 UI 内容。
 */
public class UnitMindCardPanel extends JBPanel<UnitMindCardPanel> {
    /** 卡片内容区域。 */
    private final JBPanel<?> contentPanel = new JBPanel<>(new BorderLayout());

    /**
     * 创建带标题的卡片。
     *
     * @param title 卡片标题
     */
    public UnitMindCardPanel(String title) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10, 12));
        setBackground(UIUtil.getPanelBackground());
        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(JBUI.Borders.emptyBottom(8));
        add(titleLabel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
    }

    /**
     * 设置卡片主体内容。
     *
     * @param component 主体组件
     */
    public void setContent(JComponent component) {
        contentPanel.removeAll();
        contentPanel.add(component, BorderLayout.CENTER);
    }
}
