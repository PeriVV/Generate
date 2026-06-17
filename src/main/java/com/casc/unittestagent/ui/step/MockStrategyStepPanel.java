package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;

/**
 * Mock 策略步骤面板。
 * 页面使用一个统一表格展示字段依赖、参数依赖、静态方法、本类内部调用和局部对象调用。
 */
public class MockStrategyStepPanel extends JBPanel<MockStrategyStepPanel> {
    /**
     * 创建 Mock 策略页。
     *
     * @param result 生成结果
     */
    public MockStrategyStepPanel(GenerationResult result) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        add(new UnitMindSectionPanel("Mock策略", table(model(result))), BorderLayout.CENTER);
    }

    /**
     * 构建统一 Mock 策略表格模型。
     */
    private DefaultTableModel model(GenerationResult result) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"生成", "依赖类型", "对象/类名", "方法", "来源", "Mockito策略", "备注"}, 0) {
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
            model.addRow(new Object[]{false, "尚未分析", "-", "-", "-", "-", "请先点击底部“重新分析”。"});
            return model;
        }
        MockStrategy strategy = result.getMockStrategy();
        if (strategy == null || strategy.getStrategyItems() == null || strategy.getStrategyItems().isEmpty()) {
            model.addRow(new Object[]{false, "不可直接Mock", "-", "-", "-", "不处理", "未识别到字段依赖、静态方法、本类内部调用或局部对象调用。"});
            return model;
        }
        for (MockStrategyItem item : strategy.getStrategyItems()) {
            model.addRow(new Object[]{item.isGenerate(),
                    item.getDependencyType(),
                    item.getObjectName(),
                    item.getMethodName(),
                    item.getSource(),
                    item.getMockitoStrategy(),
                    item.getRemark()});
        }
        return model;
    }

    /**
     * 创建统一表格滚动容器。
     */
    private JScrollPane table(DefaultTableModel model) {
        JBTable table = new JBTable(model);
        table.setRowHeight(26);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_OFF);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        return scrollPane;
    }
}
