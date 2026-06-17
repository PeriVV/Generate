package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;

/**
 * 参数约束步骤面板。
 * 页面始终使用表格展示约束；未识别到约束时也展示原因行，避免空白页。
 */
public class ConstraintStepPanel extends JBPanel<ConstraintStepPanel> {
    /**
     * 创建参数约束页。
     *
     * @param result 生成结果
     */
    public ConstraintStepPanel(GenerationResult result) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        MethodModel method = result == null ? null : result.getMethodModel();
        add(new UnitMindSectionPanel("参数约束", table(model(method))), BorderLayout.CENTER);
        JBLabel hint = new JBLabel(hintText(method));
        hint.setBorder(JBUI.Borders.emptyTop(6));
        add(hint, BorderLayout.SOUTH);
    }

    /**
     * 构建约束表格模型。
     */
    private DefaultTableModel model(MethodModel method) {
        DefaultTableModel model = readOnlyModel(new String[]{"参数", "类型", "约束类型", "条件表达式", "安全输入", "异常输入", "来源"});
        if (method == null) {
            model.addRow(new Object[]{"-", "-", "尚未分析", "-", "-", "-", "请先点击底部“重新分析”。"});
            return model;
        }
        if (!method.getConstraints().isEmpty()) {
            for (ConstraintModel constraint : method.getConstraints()) {
                model.addRow(new Object[]{constraint.getParameterName(),
                        parameterType(method, constraint.getParameterName()),
                        constraint.getKind(),
                        empty(constraint.getExpression()),
                        empty(constraint.getSafeExpression()),
                        unsafeInput(constraint),
                        empty(constraint.getSource())});
            }
            return model;
        }
        if (method.getParameters().isEmpty()) {
            model.addRow(new Object[]{"-", "-", "未识别到显式约束", "-", "-", "-", "当前方法没有方法参数。"});
            return model;
        }
        for (ParameterModel parameter : method.getParameters()) {
            model.addRow(new Object[]{parameter.getName(),
                    parameter.getType(),
                    "未识别到显式约束",
                    "-",
                    "构造默认 " + simpleType(parameter.getType()),
                    parameter.isNullable() ? "null 或缺字段对象" : "边界值或非法值",
                    "方法体未发现直接 if(" + parameter.getName() + "...) 判断"});
        }
        return model;
    }

    /**
     * 创建只读表格模型。
     */
    private DefaultTableModel readOnlyModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
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

    /**
     * 根据参数名查找参数类型。
     */
    private String parameterType(MethodModel method, String name) {
        for (ParameterModel parameter : method.getParameters()) {
            if (parameter.getName().equals(name)) {
                return parameter.getType();
            }
        }
        return "";
    }

    /**
     * 生成异常输入说明。
     */
    private String unsafeInput(ConstraintModel constraint) {
        return empty(constraint.getExpression());
    }

    /**
     * 生成简短提示。
     */
    private String hintText(MethodModel method) {
        if (method == null) {
            return "说明：完成方法分析后会在此展示参数约束。";
        }
        if (method.getConstraints().isEmpty() && !method.getParameters().isEmpty()) {
            return "说明：当前方法未直接对参数做显式非空或字段判断，测试场景将根据方法调用和异常分支生成。";
        }
        return "说明：约束来源于参数相关条件表达式，后续测试数据会优先覆盖安全和异常输入。";
    }

    /**
     * 提取简单类型名。
     */
    private String simpleType(String type) {
        if (type == null || type.isBlank()) {
            return "对象";
        }
        int genericStart = type.indexOf('<');
        String value = genericStart >= 0 ? type.substring(0, genericStart) : type;
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    /**
     * 空文本兜底。
     */
    private String empty(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
