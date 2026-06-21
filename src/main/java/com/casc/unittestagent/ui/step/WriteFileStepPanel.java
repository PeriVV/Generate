package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
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
    public WriteFileStepPanel(GenerationResult result, File targetFile, java.util.function.Consumer<TestWriteMode> onWrite) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        boolean exists = targetFile != null && targetFile.exists();
        add(new UnitMindSectionPanel("写入设置", table(model(result, targetFile, exists))), BorderLayout.CENTER);
        add(bottomPanel(exists, onWrite), BorderLayout.SOUTH);
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
        model.addRow(new Object[]{"默认写入模式", exists ? "追加到已有测试类" : "新建测试类"});
        model.addRow(new Object[]{"当前写入模式", exists ? "追加 / 覆盖 / 取消" : "新建 / 取消"});
        return model;
    }

    /**
     * 构建底部写入模式区域。
     */
    private JPanel bottomPanel(boolean exists, java.util.function.Consumer<TestWriteMode> onWrite) {
        JPanel container = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton create = new JButton("新建测试类");
        JButton append = new JButton("追加到已有测试类");
        JButton overwrite = new JButton("覆盖整个测试类");
        JButton cancel = new JButton("取消写入");
        create.setEnabled(!exists);
        append.setEnabled(exists);
        overwrite.setEnabled(exists);
        create.addActionListener(event -> onWrite.accept(TestWriteMode.CREATE_NEW));
        append.addActionListener(event -> onWrite.accept(TestWriteMode.APPEND_TO_EXISTING));
        overwrite.addActionListener(event -> onWrite.accept(TestWriteMode.OVERWRITE_EXISTING));
        cancel.addActionListener(event -> onWrite.accept(TestWriteMode.CANCEL));
        buttons.add(create);
        buttons.add(append);
        buttons.add(overwrite);
        buttons.add(cancel);
        JBLabel hint = new JBLabel("追加模式会自动合并 import、@Mock、@InjectMocks 和测试方法，避免覆盖已有测试。");
        hint.setBorder(JBUI.Borders.emptyTop(6));
        container.add(buttons, BorderLayout.NORTH);
        container.add(hint, BorderLayout.SOUTH);
        return container;
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
