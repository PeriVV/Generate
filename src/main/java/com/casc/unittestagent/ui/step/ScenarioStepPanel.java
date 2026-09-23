package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
import com.casc.unittestagent.ui.component.UnitMindWrapLayout;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试场景步骤面板。
 * 场景页使用统一可编辑表格，用户可增删场景或恢复推荐场景。
 * 表格编辑会实时回写 {@code result.getScenarios()}，后续测试代码生成读取同一份模型，
 * 避免“表格可编辑但生成仍使用旧数据”的不一致。
 */
public class ScenarioStepPanel extends JBPanel<ScenarioStepPanel> {
    private static final int COLUMN_ENABLED = 0;
    private static final int COLUMN_TYPE = 1;
    private static final int COLUMN_NAME = 2;
    private static final int COLUMN_TRIGGER = 3;
    private static final int COLUMN_METHOD_NAME = 5;

    /** 与表格行对齐的场景列表；占位行（尚未分析/暂无推荐）没有对应场景。 */
    private final List<TestScenario> rowScenarios = new ArrayList<>();
    private final GenerationResult result;
    /** 场景被编辑后的通知，用于让上层失效旧的测试代码。 */
    private final Runnable onEdited;
    private boolean syncing;

    /**
     * 创建测试场景页。
     *
     * @param result 生成结果
     * @param onRestore 恢复推荐场景动作
     * @param onEdited 场景编辑动作，场景变化后旧测试代码不再有效
     */
    public ScenarioStepPanel(GenerationResult result, Runnable onRestore, Runnable onEdited) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        this.result = result;
        this.onEdited = onEdited;
        if (result instanceof ClassGenerationResult) {
            DefaultTableModel classModel = classModel((ClassGenerationResult) result);
            JBTable classTable = new JBTable(classModel);
            classTable.setRowHeight(26);
            classTable.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
            add(new UnitMindSectionPanel("整类测试场景", new JScrollPane(classTable)), BorderLayout.CENTER);
            return;
        }
        DefaultTableModel model = model(result);
        JBTable table = new JBTable(model);
        table.setRowHeight(26);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        model.addTableModelListener(new ScenarioWritebackListener(model));
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(new UnitMindSectionPanel("测试场景", scrollPane), BorderLayout.CENTER);
        add(toolbar(model, table, onRestore), BorderLayout.SOUTH);
    }

    private DefaultTableModel classModel(ClassGenerationResult result) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"方法签名", "生成", "场景类型", "场景名称", "触发条件", "期望结果", "测试方法名"}, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex == 1 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (MethodGenerationItem item : result.getMethodItems()) {
            if (item.getResult() == null || item.getResult().getScenarios().isEmpty()) {
                model.addRow(new Object[]{item.getSignature(), false, "未规划", "-", "-", "-", "-"});
                continue;
            }
            for (TestScenario scenario : item.getResult().getScenarios()) {
                model.addRow(new Object[]{item.getSignature(), isExecutable(scenario), scenario.getType(),
                        scenario.getDisplayNameCn(), triggerText(scenario), expectedText(scenario), scenario.getMethodNameEn()});
            }
        }
        return model;
    }

    /**
     * 构建场景表格模型。填充阶段由 {@code syncing} 保护，不触发回写。
     */
    private DefaultTableModel model(GenerationResult result) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"生成", "场景类型", "场景名称", "触发条件", "期望结果", "测试方法名"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == COLUMN_ENABLED ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                // 期望结果列由场景类型推导，属于只读展示，用户应编辑场景类型。
                return column != 4;
            }
        };
        syncing = true;
        if (result == null || result.getMethodModel() == null) {
            model.addRow(new Object[]{false, "尚未分析", "请先分析方法", "-", "-", "-"});
        } else if (result.getScenarios().isEmpty()) {
            model.addRow(new Object[]{false, "未生成", "暂无推荐场景", "未识别到可规划路径", "请手动新增场景", "shouldHandleCustomScenario"});
        } else {
            for (TestScenario scenario : result.getScenarios()) {
                scenario.setEnabled(isExecutable(scenario));
                rowScenarios.add(scenario);
                model.addRow(new Object[]{scenario.isEnabled(),
                        scenario.getType(),
                        scenario.getDisplayNameCn(),
                        triggerText(scenario),
                        expectedText(scenario),
                        scenario.getMethodNameEn()});
            }
        }
        syncing = false;
        return model;
    }

    /**
     * 把单元格编辑写回对应的 TestScenario，使后续生成与用户所见一致。
     */
    private class ScenarioWritebackListener implements TableModelListener {
        private final DefaultTableModel model;

        ScenarioWritebackListener(DefaultTableModel model) {
            this.model = model;
        }

        @Override
        public void tableChanged(TableModelEvent event) {
            if (syncing || event.getType() != TableModelEvent.UPDATE) {
                return;
            }
            int row = event.getFirstRow();
            if (row < 0 || row >= rowScenarios.size()) {
                return; // 占位行没有可回写的场景。
            }
            TestScenario scenario = rowScenarios.get(row);
            int column = event.getColumn();
            Object value = model.getValueAt(row, column);
            if (column == COLUMN_ENABLED && value instanceof Boolean) {
                scenario.setEnabled((Boolean) value);
            } else if (column == COLUMN_TYPE) {
                scenario.setType(String.valueOf(value));
            } else if (column == COLUMN_NAME) {
                scenario.setDisplayNameCn(String.valueOf(value));
            } else if (column == COLUMN_TRIGGER) {
                applyTrigger(scenario, String.valueOf(value));
            } else if (column == COLUMN_METHOD_NAME) {
                scenario.setMethodNameEn(String.valueOf(value));
            } else {
                return;
            }
            notifyEdited();
        }
    }

    /**
     * 场景数据实际变化后通知上层，使旧测试代码失效。
     */
    private void notifyEdited() {
        if (onEdited != null) {
            onEdited.run();
        }
    }

    /**
     * 触发条件文本回写：原有分支列表的场景拆回分支，其余写入描述。
     */
    private void applyTrigger(TestScenario scenario, String text) {
        if (scenario.getCoveredBranches() != null && !scenario.getCoveredBranches().isEmpty()) {
            List<String> branches = new ArrayList<>();
            for (String part : text.split(";")) {
                if (!part.isBlank()) branches.add(part.trim());
            }
            scenario.setCoveredBranches(branches);
        } else {
            scenario.setDescription(text);
        }
    }

    /**
     * 构建场景工具栏。新增/删除按钮同时维护表格、行对齐列表与 result 模型。
     */
    private JPanel toolbar(DefaultTableModel model, JBTable table, Runnable onRestore) {
        JPanel buttons = new JPanel(new UnitMindWrapLayout(FlowLayout.LEFT, 8, 4));
        JButton add = new JButton("新增场景");
        JButton remove = new JButton("删除场景");
        JButton restore = new JButton("恢复推荐");
        add.addActionListener(event -> {
            syncing = true;
            // 场景列表为空时表格只有占位行，先移除占位行保持行与场景对齐。
            if (rowScenarios.isEmpty() && model.getRowCount() > 0) {
                model.removeRow(0);
            }
            Object[] values = {Boolean.TRUE, "NORMAL", "自定义场景", "请填写触发条件", "正常返回或完成调用", "shouldHandleCustomScenario"};
            model.addRow(values);
            syncing = false;
            TestScenario scenario = new TestScenario();
            scenario.setType("NORMAL");
            scenario.setDisplayNameCn("自定义场景");
            scenario.setDescription("请填写触发条件");
            scenario.setMethodNameEn("shouldHandleCustomScenario");
            scenario.setName("customScenario");
            scenario.setEnabled(true);
            rowScenarios.add(scenario);
            if (result != null) {
                result.getScenarios().add(scenario);
            }
            notifyEdited();
        });
        remove.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            int modelRow = table.convertRowIndexToModel(row);
            syncing = true;
            model.removeRow(modelRow);
            syncing = false;
            if (modelRow < rowScenarios.size()) {
                TestScenario removed = rowScenarios.remove(modelRow);
                if (result != null) {
                    result.getScenarios().remove(removed);
                }
                notifyEdited();
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
        if (!isExecutable(scenario)) {
            return "--";
        }
        if ("EXCEPTION".equals(scenario.getType())) {
            return "抛出异常";
        }
        if ("BOUNDARY".equals(scenario.getType())) {
            return "按边界输入正常处理";
        }
        return "正常返回或完成调用";
    }

    /**
     * 判断场景是否需要进入测试代码生成。
     */
    private boolean isExecutable(TestScenario scenario) {
        return scenario.getType() != null && !scenario.getType().endsWith("_REASON");
    }
}
