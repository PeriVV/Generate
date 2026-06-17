package com.casc.unittestagent.ui;

import com.casc.unittestagent.generator.TestDataRangeConfigManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

/**
 * 编辑测试数据生成范围的 IntelliJ 对话框。
 * 该界面让用户调整数值边界、小数位和字符串规则，提交前由表格模型完成校验。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestDataRangeConfigDialog extends DialogWrapper {
    /** 配置表格模型，承载用户编辑后的范围配置和校验逻辑。 */
    private final TestDataRangeTableModel tableModel;

    /**
     * 创建测试数据范围配置对话框。
     * 构造阶段读取当前配置副本作为表格初始值，避免用户编辑时直接修改运行态配置。
     *
     * 异常情况：无主动抛出的受检异常。
     *
     * @param project 当前 IntelliJ 项目上下文，用于消息提示和对话框归属
     */
    public TestDataRangeConfigDialog(@Nullable Project project) {
        super(project);
        setTitle("UnitMind 测试数据生成配置");
        setOKButtonText("确定");
        setCancelButtonText("取消");
        this.tableModel = new TestDataRangeTableModel(TestDataRangeConfigManager.getConfigs());
        init();
    }

    /**
     * 创建对话框主体内容。
     * 所有可视化控件在这里组装，DialogWrapper 会在初始化时调用该方法。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 对话框中心内容组件
     */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        JTable table = new JTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(220);

        JButton resetButton = new JButton("恢复默认");
        resetButton.addActionListener(event -> {
            TestDataRangeConfigManager.resetToDefault();
            tableModel.setConfigs(TestDataRangeConfigManager.getConfigs());
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttonPanel.add(resetButton);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(760, 260));
        return panel;
    }

    /**
     * 处理配置对话框的确认操作。
     * 提交前先校验表格内容，只有配置合法时才写入管理器并关闭窗口。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     */
    @Override
    protected void doOKAction() {
        List<String> errors = tableModel.validateRows();
        if (!errors.isEmpty()) {
            Messages.showErrorDialog(String.join("\n", errors), "配置错误");
            return;
        }
        TestDataRangeConfigManager.updateConfigs(tableModel.getConfigs());
        super.doOKAction();
    }
}
