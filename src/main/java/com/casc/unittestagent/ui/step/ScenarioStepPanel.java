package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
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

/**
 * 测试场景步骤面板。
 * 场景页使用统一可编辑表格，用户可增删场景或恢复推荐场景。
 */
public class ScenarioStepPanel extends JBPanel<ScenarioStepPanel> {
    /**
     * 创建测试场景页。
     *
     * @param result 生成结果
     * @param onRestore 恢复推荐场景动作
     */
    public ScenarioStepPanel(GenerationResult result, Runnable onRestore) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        DefaultTableModel model = model(result);
        JBTable table = new JBTable(model);
        table.setRowHeight(26);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(new UnitMindSectionPanel("测试场景", scrollPane), BorderLayout.CENTER);
        add(toolbar(model, table, onRestore), BorderLayout.SOUTH);
    }

    /**
     * 构建场景表格模型。
     */
    private DefaultTableModel model(GenerationResult result) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"生成", "场景类型", "场景名称", "触发条件", "期望结果", "测试方法名"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        if (result == null || result.getMethodModel() == null) {
            model.addRow(new Object[]{false, "尚未分析", "请先分析方法", "-", "-", "-"});
            return model;
        }
        if (result.getScenarios().isEmpty()) {
            model.addRow(new Object[]{false, "未生成", "暂无推荐场景", "未识别到可规划路径", "请手动新增场景", "shouldHandleCustomScenario"});
            return model;
        }
        for (TestScenario scenario : result.getScenarios()) {
            model.addRow(new Object[]{true,
                    scenario.getType(),
                    scenario.getDisplayNameCn(),
                    triggerText(scenario),
                    expectedText(scenario),
                    scenario.getMethodNameEn()});
        }
        return model;
    }

    /**
     * 构建场景工具栏。
     */
    private JPanel toolbar(DefaultTableModel model, JBTable table, Runnable onRestore) {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton add = new JButton("新增场景");
        JButton remove = new JButton("删除场景");
        JButton restore = new JButton("恢复推荐");
        add.addActionListener(event -> model.addRow(new Object[]{true, "NORMAL", "自定义场景", "请填写触发条件", "请填写期望结果", "shouldHandleCustomScenario"}));
        remove.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeRow(table.convertRowIndexToModel(row));
            }
        });
        restore.addActionListener(event -> onRestore.run());
        buttons.add(add);
        buttons.add(remove);
        buttons.add(restore);
        return buttons;
    }

    /**
     * 生成触发条件文本。
     */
    private String triggerText(TestScenario scenario) {
        if (scenario.getCoveredBranches() != null && !scenario.getCoveredBranches().isEmpty()) {
            return String.join("; ", scenario.getCoveredBranches());
        }
        return scenario.getDescription() == null || scenario.getDescription().isBlank() ? "-" : scenario.getDescription();
    }

    /**
     * 生成期望结果文本。
     */
    private String expectedText(TestScenario scenario) {
        if ("EXCEPTION".equals(scenario.getType())) {
            return "抛出异常";
        }
        if ("BOUNDARY".equals(scenario.getType())) {
            return "按边界输入正常处理";
        }
        return "正常返回或完成调用";
    }
}
