package com.casc.unittestagent.ui.component;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * UnitMind 空状态组件。
 * 当某个步骤尚未生成数据或没有识别到内容时，用紧凑卡片替代空白区域或大段文本框。
 */
public class UnitMindEmptyStatePanel extends JBPanel<UnitMindEmptyStatePanel> {
    /**
     * 创建空状态面板。
     *
     * @param title 空状态标题
     * @param description 空状态说明
     * @param actionText 可选按钮文本，为空时不显示按钮
     * @param action 可选按钮动作
     */
    public UnitMindEmptyStatePanel(String title, String description, String actionText, Runnable action) {
        super(new BorderLayout(0, 8));
        setBorder(JBUI.Borders.empty(16, 14));
        setBackground(UIUtil.getPanelBackground());
        JBPanel<?> textPanel = new JBPanel<>(new BorderLayout(0, 6));
        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        JBLabel descriptionLabel = new JBLabel("<html>" + description.replace("\n", "<br>") + "</html>");
        textPanel.add(titleLabel, BorderLayout.NORTH);
        textPanel.add(descriptionLabel, BorderLayout.CENTER);
        add(textPanel, BorderLayout.CENTER);
        if (actionText != null && !actionText.isBlank() && action != null) {
            JButton button = new JButton(actionText);
            button.addActionListener(event -> action.run());
            add(button, BorderLayout.SOUTH);
        }
    }
}
