package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
import com.casc.unittestagent.ui.component.UnitMindWrapLayout;
import com.casc.unittestagent.writer.TestWriteMode;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;

/**
 * 写入测试文件步骤面板。
 * 页面使用表格展示写入设置，并在表格下方保留写入模式选择动作。
 */
public class WriteFileStepPanel extends JBPanel<WriteFileStepPanel> {
    /**
     * 创建写入页。
     *
     * @param result 生成结果
     * @param targetFile 目标测试文件
     * @param onWrite 写入动作
     */
    public WriteFileStepPanel(GenerationResult result,
                              File targetFile,
                              java.util.function.BiConsumer<TestWriteMode, Boolean> onWrite) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        boolean exists = targetFile != null && targetFile.exists();
        add(new UnitMindSectionPanel("写入设置", table(model(result, targetFile, exists))), BorderLayout.CENTER);
        add(bottomPanel(result, exists, onWrite), BorderLayout.SOUTH);
    }

    /**
     * 构建写入设置表格模型。
     */
    private DefaultTableModel model(GenerationResult result, File targetFile, boolean exists) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"项目", "内容"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        if (result == null || result.getGeneratedTestFile() == null || targetFile == null) {
            model.addRow(new Object[]{"状态", "尚未生成可写入的测试代码"});
            model.addRow(new Object[]{"目标路径", "-"});
            return model;
        }
        model.addRow(new Object[]{"测试类名", result.getGeneratedTestFile().getClassName()});
        model.addRow(new Object[]{"目标路径", targetFile.getAbsolutePath()});
        model.addRow(new Object[]{"文件状态", exists ? "已存在" : "不存在"});
        model.addRow(new Object[]{"默认写入模式", exists ? "追加" : "新建"});
        model.addRow(new Object[]{"当前写入模式", exists ? "追加 / 覆盖 / 取消" : "新建 / 取消"});
        ValidationResult validation = result.getValidationResult();
        model.addRow(new Object[]{"编译校验", validation == null ? "尚未执行" : validationText(validation)});
        return model;
    }

    /**
     * 构建底部写入模式区域。
     */
    private JPanel bottomPanel(GenerationResult result,
                               boolean exists,
                               java.util.function.BiConsumer<TestWriteMode, Boolean> onWrite) {
        JPanel container = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new UnitMindWrapLayout(FlowLayout.LEFT, 8, 4));
        JButton create = new JButton("新建测试类");
        JButton append = new JButton("追加");
        append.setToolTipText("合并进已有测试类，保留 UnitMind 区域外的人工测试和 fixture");
        JButton overwrite = new JButton("覆盖");
        overwrite.setToolTipText("删除已有测试类全部内容，写入本次生成内容");
        JButton force = new JButton("强制写入");
        force.setToolTipText("跳过编译/执行门禁写入草稿，写入前会再次确认");
        JButton cancel = new JButton("取消");
        boolean generated = result != null && result.getGeneratedTestFile() != null;
        boolean complete = !(result instanceof ClassGenerationResult) || ((ClassGenerationResult) result).isComplete();
        create.setEnabled(!exists && generated && complete);
        append.setEnabled(exists && generated && complete);
        overwrite.setEnabled(exists && generated && complete);
        ValidationResult validation = result == null ? null : result.getValidationResult();
        force.setEnabled(generated && (!complete || validation == null || validation.getStatus() != ValidationStatus.PASSED));
        create.addActionListener(event -> onWrite.accept(TestWriteMode.CREATE_NEW, false));
        append.addActionListener(event -> onWrite.accept(TestWriteMode.APPEND_TO_EXISTING, false));
        overwrite.addActionListener(event -> onWrite.accept(TestWriteMode.OVERWRITE_EXISTING, false));
        force.addActionListener(event -> onWrite.accept(exists
                ? TestWriteMode.APPEND_TO_EXISTING : TestWriteMode.CREATE_NEW, true));
        cancel.addActionListener(event -> onWrite.accept(TestWriteMode.CANCEL, false));
        buttons.add(create);
        buttons.add(append);
        buttons.add(overwrite);
        buttons.add(force);
        buttons.add(cancel);
        JBLabel hint = new JBLabel("正常写入要求最终文件编译通过；“强制写入”会再次确认。");
        hint.setBorder(JBUI.Borders.emptyTop(6));
        container.add(buttons, BorderLayout.NORTH);
        container.add(hint, BorderLayout.SOUTH);
        return container;
    }

    private String validationText(ValidationResult validation) {
        if (validation.getStatus() == ValidationStatus.PASSED) {
            return "已通过";
        }
        if (validation.getStatus() == ValidationStatus.RUNNING) {
            return "校验中";
        }
        if (validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR) {
            return "环境错误";
        }
        if (validation.getStatus() == ValidationStatus.FAILED) {
            return "未通过（" + validation.getDiagnostics().size() + " 个错误）";
        }
        if (validation.getStatus() == ValidationStatus.STALE) {
            return "代码已修改，需重新校验";
        }
        return "尚未执行";
    }

    /**
     * 创建统一表格滚动容器。
     */
    private JScrollPane table(DefaultTableModel model) {
        JBTable table = new JBTable(model);
        table.setRowHeight(26);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        return scrollPane;
    }
}
