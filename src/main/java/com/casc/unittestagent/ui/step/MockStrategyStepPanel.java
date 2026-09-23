package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
import com.casc.unittestagent.ui.component.UnitMindWrapLayout;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;

/**
 * Mock 策略步骤面板。
 * 页面同时展示静态策略和用户手动调用得到的大模型候选策略，由用户选择后续测试代码生成使用哪一份。
 */
public class MockStrategyStepPanel extends JBPanel<MockStrategyStepPanel> {
    /** 策略项被编辑后的通知，用于让上层失效旧的测试代码。 */
    private final Runnable onEdited;

    /**
     * 创建 Mock 策略页。
     *
     * @param result 生成结果
     * @param llmRunning 是否正在调用大模型生成 Mock 策略
     * @param onGenerateLlmMockStrategy 调用大模型生成候选 Mock 策略
     * @param onUseStaticMockStrategy 选择静态 Mock 策略
     * @param onUseLlmMockStrategy 选择大模型候选 Mock 策略
     * @param onEdited 策略项编辑动作，策略变化后旧测试代码不再有效
     */
    public MockStrategyStepPanel(GenerationResult result,
                                 boolean llmRunning,
                                 Runnable onGenerateLlmMockStrategy,
                                 Runnable onUseStaticMockStrategy,
                                 Runnable onUseLlmMockStrategy,
                                 Runnable onEdited) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        this.onEdited = onEdited;
        if (result instanceof ClassGenerationResult) {
            add(new JLabel("整类模式会按方法使用以下静态策略，并在聚合时去重共享字段 Mock。"), BorderLayout.NORTH);
            add(new UnitMindSectionPanel("整类 Mock 策略", table(classModel((ClassGenerationResult) result))), BorderLayout.CENTER);
            return;
        }
        add(toolbar(result, llmRunning, onGenerateLlmMockStrategy, onUseStaticMockStrategy, onUseLlmMockStrategy), BorderLayout.NORTH);

        JPanel body = new JPanel(new GridLayout(0, 1, 0, 8));
        body.add(new UnitMindSectionPanel("静态分析 Mock 策略", table(model(result == null ? null : result.getMockStrategy(), false))));
        if (result != null && result.getLlmMockStrategy() != null) {
            body.add(new UnitMindSectionPanel("大模型候选 Mock 策略", table(model(result.getLlmMockStrategy(), true))));
        } else {
            body.add(new UnitMindSectionPanel("大模型候选 Mock 策略", table(emptyLlmModel(llmRunning))));
        }
        add(body, BorderLayout.CENTER);
    }

    private DefaultTableModel classModel(ClassGenerationResult result) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"方法签名", "生成", "依赖类型", "对象/类名", "方法", "Mockito策略", "备注"}, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex == 1 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (MethodGenerationItem method : result.getMethodItems()) {
            MockStrategy strategy = method.getResult() == null ? null : method.getResult().getMockStrategy();
            if (strategy == null || strategy.getStrategyItems().isEmpty()) {
                model.addRow(new Object[]{method.getSignature(), false, "未识别", "-", "-", "不处理", "当前方法无字段依赖"});
                continue;
            }
            for (MockStrategyItem item : strategy.getStrategyItems()) {
                model.addRow(new Object[]{method.getSignature(), item.isGenerate(), item.getDependencyType(),
                        item.getObjectName(), item.getMethodName(), item.getMockitoStrategy(), item.getRemark()});
            }
        }
        return model;
    }

    /**
     * 构建顶部操作栏。
     * 状态标签与按钮放进同一个自适应换行容器：宽时一行排开，窄时自动换行，不会互相裁切。
     */
    private JPanel toolbar(GenerationResult result,
                           boolean llmRunning,
                           Runnable onGenerateLlmMockStrategy,
                           Runnable onUseStaticMockStrategy,
                           Runnable onUseLlmMockStrategy) {
        JPanel panel = new JPanel(new UnitMindWrapLayout(FlowLayout.LEFT, 8, 4));
        JLabel selected = new JLabel("当前用于测试代码生成：" + selectedText(result));
        panel.add(selected);

        JButton generate = new JButton(llmRunning ? "生成中..." : "生成大模型策略");
        generate.setToolTipText("调用大模型生成候选 Mock 策略，不覆盖静态策略");
        JButton useStatic = new JButton("用静态策略");
        JButton useLlm = new JButton("用大模型策略");
        generate.setEnabled(!llmRunning && result != null && result.getMethodModel() != null);
        useStatic.setEnabled(result != null && result.getMockStrategy() != null);
        useLlm.setEnabled(result != null && result.getLlmMockStrategy() != null);
        generate.addActionListener(event -> onGenerateLlmMockStrategy.run());
        useStatic.addActionListener(event -> onUseStaticMockStrategy.run());
        useLlm.addActionListener(event -> onUseLlmMockStrategy.run());
        panel.add(generate);
        panel.add(useStatic);
        panel.add(useLlm);
        return panel;
    }

    private String selectedText(GenerationResult result) {
        if (result != null && result.isUseLlmMockStrategy() && result.getLlmMockStrategy() != null) {
            return "大模型候选策略";
        }
        return "静态分析策略";
    }

    /**
     * 构建统一 Mock 策略表格模型。
     * 单元格编辑会实时回写 {@code strategy.getStrategyItems()}，确保后续测试代码生成
     * 使用用户在表格中确认后的策略，而不是规划阶段的旧数据。
     */
    private DefaultTableModel model(MockStrategy strategy, boolean fromLlm) {
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
        if (strategy == null || strategy.getStrategyItems() == null || strategy.getStrategyItems().isEmpty()) {
            String remark = fromLlm ? "尚未调用大模型生成候选策略。" : "未识别到字段依赖、静态方法、本类内部调用或局部对象调用。";
            model.addRow(new Object[]{false, fromLlm ? "未生成" : "不可直接Mock", "-", "-", "-", "不处理", remark});
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
        List<MockStrategyItem> items = strategy.getStrategyItems();
        model.addTableModelListener(event -> {
            if (event.getType() != javax.swing.event.TableModelEvent.UPDATE) {
                return;
            }
            int row = event.getFirstRow();
            if (row < 0 || row >= items.size()) {
                return; // 占位行没有可回写的策略项。
            }
            MockStrategyItem item = items.get(row);
            int column = event.getColumn();
            Object value = model.getValueAt(row, column);
            if (column == 0 && value instanceof Boolean) {
                item.setGenerate((Boolean) value);
            } else if (column == 1) {
                item.setDependencyType(String.valueOf(value));
            } else if (column == 2) {
                item.setObjectName(String.valueOf(value));
            } else if (column == 3) {
                item.setMethodName(String.valueOf(value));
            } else if (column == 4) {
                item.setSource(String.valueOf(value));
            } else if (column == 5) {
                item.setMockitoStrategy(String.valueOf(value));
            } else if (column == 6) {
                item.setRemark(String.valueOf(value));
            } else {
                return;
            }
            // 策略项已实际变化，通知上层失效旧测试代码。
            if (onEdited != null) {
                onEdited.run();
            }
        });
        return model;
    }

    private DefaultTableModel emptyLlmModel(boolean llmRunning) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"生成", "依赖类型", "对象/类名", "方法", "来源", "Mockito策略", "备注"}, 0);
        model.addRow(new Object[]{false, llmRunning ? "生成中" : "未生成", "-", "-", "-", "-", llmRunning ? "正在调用大模型生成候选 Mock 策略。" : "点击上方按钮后显示大模型候选策略。"});
        return model;
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
