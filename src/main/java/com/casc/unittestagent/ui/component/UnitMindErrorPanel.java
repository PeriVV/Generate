package com.casc.unittestagent.ui.component;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;

import java.awt.BorderLayout;

/**
 * UnitMind 错误详情组件。
 * 失败详情允许使用文本区域，便于展示解析错误和异常信息。
 */
public class UnitMindErrorPanel extends JBPanel<UnitMindErrorPanel> {
    /**
     * 创建错误详情面板。
     *
     * @param title 错误标题
     * @param detail 错误详情
     */
    public UnitMindErrorPanel(String title, String detail) {
        super(new BorderLayout());
        UnitMindCardPanel card = new UnitMindCardPanel(title);
        JBTextArea area = new JBTextArea(detail == null ? "" : detail);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(JBUI.Borders.empty(8));
        area.setForeground(JBColor.RED);
        card.setContent(area);
        add(card, BorderLayout.CENTER);
    }
}
