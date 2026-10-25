package com.casc.unittestagent.ui.component;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * UnitMind 顶部 Header 区。
 * Header 保持紧凑，两行展示工具名、当前方法和源码位置，并提供设置与重新分析入口。
 */
public class UnitMindHeaderPanel extends JBPanel<UnitMindHeaderPanel> {
    /** 当前方法标签。 */
    private final JBLabel methodLabel = new JBLabel("当前方法：未选择");
    /** 源码位置标签。 */
    private final JBLabel pathLabel = new JBLabel("源码位置：-");
    private final JButton dependenciesButton = new JButton("配置测试依赖");
    private final JBLabel dependencyStatus = new JBLabel();

    /**
     * 创建 Header。
     *
     * @param onSettings 设置按钮动作
     * @param onAnalyze 重新分析按钮动作
     */
    public UnitMindHeaderPanel(Runnable onSettings, Runnable onAnalyze) {
        this(onSettings, onAnalyze, () -> {});
    }

    public UnitMindHeaderPanel(Runnable onSettings, Runnable onAnalyze, Runnable onDependencies) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(8, 10));
        setBackground(UIUtil.getPanelBackground());

        JBPanel<?> titlePanel = new JBPanel<>(new BorderLayout());
        JBPanel<?> titleTexts = new JBPanel<>(new BorderLayout());
        JBLabel title = new JBLabel("UnitMind");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JBLabel subtitle = new JBLabel("智能单元测试生成助手");
        titleTexts.add(title, BorderLayout.NORTH);
        titleTexts.add(subtitle, BorderLayout.CENTER);

        JBPanel<?> actions = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton settingsButton = new JButton("设置");
        JButton analyzeButton = new JButton("重新分析");
        settingsButton.addActionListener(event -> onSettings.run());
        analyzeButton.addActionListener(event -> onAnalyze.run());
        dependenciesButton.addActionListener(event -> onDependencies.run());
        actions.add(settingsButton);
        actions.add(analyzeButton);
        titlePanel.add(titleTexts, BorderLayout.CENTER);
        titlePanel.add(actions, BorderLayout.EAST);

        JBPanel<?> contextPanel = new JBPanel<>(new BorderLayout(0, 2));
        contextPanel.setBorder(JBUI.Borders.emptyTop(8));
        contextPanel.add(methodLabel, BorderLayout.NORTH);
        contextPanel.add(pathLabel, BorderLayout.CENTER);
        JBPanel<?> dependencyActions = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 0, 4));
        dependenciesButton.setToolTipText("为当前 Java 文件所属模块配置 JUnit / Mockito 测试依赖");
        dependencyActions.add(dependenciesButton);
        dependencyStatus.setForeground(UIUtil.getContextHelpForeground());
        dependencyActions.add(dependencyStatus);
        contextPanel.add(dependencyActions, BorderLayout.SOUTH);

        add(titlePanel, BorderLayout.NORTH);
        add(contextPanel, BorderLayout.SOUTH);
    }

    public void setDependenciesEnabled(boolean enabled) { dependenciesButton.setEnabled(enabled); }
    public void setDependencyStatus(String text) { dependencyStatus.setText(text == null ? "" : text); }

    /**
     * 更新当前方法与源码位置。
     *
     * @param methodText 当前方法文本
     * @param pathText 源码路径文本
     */
    public void updateContext(String methodText, String pathText) {
        methodLabel.setText("当前方法：" + (methodText == null || methodText.isBlank() ? "未选择" : methodText));
        pathLabel.setText("源码位置：" + (pathText == null || pathText.isBlank() ? "-" : pathText));
    }
}
