package com.casc.unittestagent.ui.component;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * UnitMind 分区组件。
 * Mock 策略等页面使用该组件纵向组织多个紧凑分区。
 */
public class UnitMindSectionPanel extends JBPanel<UnitMindSectionPanel> {
    /**
     * 创建分区。
     *
     * @param title 分区标题
     * @param content 分区内容
     */
    public UnitMindSectionPanel(String title, JComponent content) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(8, 0));
        JBLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(JBUI.Borders.emptyBottom(6));
        add(label, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }
}
