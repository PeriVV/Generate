package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationStatus;
import com.casc.unittestagent.ui.component.UnitMindEmptyStatePanel;
import com.casc.unittestagent.ui.component.UnitMindErrorPanel;
import com.casc.unittestagent.ui.component.UnitMindLoadingPanel;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

/**
 * 测试代码步骤面板。
 * 成功状态下使用 IntelliJ EditorTextField 提供 Java 高亮、可编辑和滚动能力。
 */
public class TestCodeStepPanel extends JBPanel<TestCodeStepPanel> {
    /** 代码编辑器。 */
    private EditorTextField editorTextField;
    /** 原始生成代码。 */
    private final String generatedCode;
    /** 用户补充要求输入框。 */
    private JTextArea feedbackArea;

    /**
     * 创建测试代码页。
     *
     * @param project 当前项目
     * @param status 测试代码生成状态
     * @param code 当前测试代码
     * @param error 错误详情
     * @param generationNote 测试代码来源提示
     * @param onRegenerate 带补充要求再次生成动作
     */
    public TestCodeStepPanel(Project project,
                             GenerationStatus status,
                             String code,
                             String error,
                             String generationNote,
                             Consumer<String> onRegenerate) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        this.generatedCode = code == null ? "" : code;
        if (status == GenerationStatus.IDLE) {
            add(new UnitMindEmptyStatePanel("尚未生成测试代码", "请先完成方法分析，或通过上一步修改场景后重新进入本步骤。", null, null), BorderLayout.CENTER);
            return;
        }
        if (status == GenerationStatus.RUNNING) {
            add(new UnitMindLoadingPanel("正在生成测试代码..."), BorderLayout.CENTER);
            return;
        }
        if (status == GenerationStatus.FAILED) {
            add(new UnitMindErrorPanel("测试代码生成失败", error == null ? "未知错误" : error), BorderLayout.CENTER);
            return;
        }

        if (generationNote != null && !generationNote.isBlank()) {
            JBLabel note = new JBLabel(generationNote);
            note.setBorder(JBUI.Borders.emptyBottom(8));
            add(note, BorderLayout.NORTH);
        }

        Document document = EditorFactory.getInstance().createDocument(this.generatedCode);
        editorTextField = new EditorTextField(document, project, JavaFileType.INSTANCE, false, false);
        editorTextField.setOneLineMode(false);
        editorTextField.addSettingsProvider(editor -> editor.getSettings().setLineNumbersShown(true));
        JScrollPane scrollPane = new JScrollPane(editorTextField);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        JPanel feedbackPanel = new JPanel(new BorderLayout(6, 0));
        feedbackArea = new JTextArea(2, 60);
        feedbackArea.setLineWrap(true);
        feedbackArea.setWrapStyleWord(true);
        feedbackPanel.add(new JLabel("补充要求"), BorderLayout.WEST);
        feedbackPanel.add(new JScrollPane(feedbackArea), BorderLayout.CENTER);
        bottom.add(feedbackPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton copy = new JButton("复制代码");
        JButton restore = new JButton("恢复生成代码");
        JButton regenerate = new JButton("再次生成");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(getCode()), null));
        restore.addActionListener(event -> setCode(generatedCode));
        regenerate.addActionListener(event -> onRegenerate.accept(feedbackArea.getText()));
        buttons.add(copy);
        buttons.add(restore);
        buttons.add(regenerate);
        bottom.add(buttons, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    /**
     * 读取当前编辑器文本。
     *
     * @return 用户编辑后的测试代码
     */
    public String getCode() {
        return editorTextField == null ? generatedCode : editorTextField.getText();
    }

    /**
     * 判断当前页面是否已创建可编辑代码组件。
     *
     * @return 存在编辑器时返回 true
     */
    public boolean hasEditor() {
        return editorTextField != null;
    }

    /**
     * 更新编辑器文本。
     *
     * @param code 新代码
     */
    public void setCode(String code) {
        if (editorTextField != null) {
            editorTextField.setText(code == null ? "" : code);
        }
    }
}
