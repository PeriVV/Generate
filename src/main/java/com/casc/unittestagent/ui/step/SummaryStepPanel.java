package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;

/**
 * 方法概要步骤面板。
 * 页面只展示当前被测方法的静态识别信息，后续步骤的统计结果不在这里提前展示。
 */
public class SummaryStepPanel extends JBPanel<SummaryStepPanel> {
    /**
     * 创建方法概要页。
     *
     * @param result 生成结果
     * @param sourcePath 被测源码路径
     */
    public SummaryStepPanel(GenerationResult result, String sourcePath) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        JBPanel<?> body = new JBPanel<>(new GridLayout(0, 1, 0, 8));
        body.add(new UnitMindSectionPanel("方法信息", table(methodInfoModel(result, sourcePath))));
        body.add(new UnitMindSectionPanel("参数信息", table(parameterInfoModel(result))));
        add(new JScrollPane(body), BorderLayout.CENTER);
    }

    /**
     * 构建方法信息表。
     */
    private DefaultTableModel methodInfoModel(GenerationResult result, String sourcePath) {
        DefaultTableModel model = readOnlyModel(new String[]{"项目", "内容"});
        MethodModel method = result == null ? null : result.getMethodModel();
        if (method == null) {
            model.addRow(new Object[]{"状态", "尚未分析，请点击底部“重新分析”开始生成。"});
            model.addRow(new Object[]{"源码位置", empty(sourcePath)});
            return model;
        }
        model.addRow(new Object[]{"被测类", method.getClassName()});
        model.addRow(new Object[]{"被测方法", method.getMethodName()});
        model.addRow(new Object[]{"方法签名", signature(method)});
        model.addRow(new Object[]{"返回类型", method.getReturnType()});
        model.addRow(new Object[]{"访问修饰符", accessModifier(method)});
        model.addRow(new Object[]{"源码位置", empty(sourcePath)});
        return model;
    }

    /**
     * 构建参数信息表。
     */
    private DefaultTableModel parameterInfoModel(GenerationResult result) {
        DefaultTableModel model = readOnlyModel(new String[]{"参数名", "参数类型", "参数角色", "建议处理方式"});
        MethodModel method = result == null ? null : result.getMethodModel();
        if (method == null || method.getParameters().isEmpty()) {
            model.addRow(new Object[]{"-", "-", "无参数", "当前方法没有可展示的入参。"});
            return model;
        }
        for (ParameterModel parameter : method.getParameters()) {
            model.addRow(new Object[]{parameter.getName(), parameter.getType(), parameterRole(parameter.getType()), parameterStrategy(parameter.getType())});
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
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        return scrollPane;
    }

    /**
     * 生成方法签名文本。
     */
    private String signature(MethodModel method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getReturnType()).append(" ").append(method.getMethodName()).append("(");
        for (int i = 0; i < method.getParameters().size(); i++) {
            ParameterModel parameter = method.getParameters().get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameter.getType()).append(" ").append(parameter.getName());
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * 推断参数角色。
     */
    private String parameterRole(String type) {
        if (looksLikeRequestDtoType(type)) {
            return "方法入参 / Request DTO";
        }
        if (looksLikeDtoType(type)) {
            return "方法入参 / 业务数据对象";
        }
        if (looksLikeCollectionType(type)) {
            return "方法入参 / 集合数据";
        }
        return "方法入参";
    }

    /**
     * 推断参数生成策略。
     */
    private String parameterStrategy(String type) {
        if (looksLikeDtoType(type)) {
            return "优先构造；必要时局部 mock";
        }
        if (looksLikeCollectionType(type)) {
            return "优先构造集合测试数据；必要时局部 mock 元素";
        }
        return "按类型生成测试值";
    }

    /**
     * 判断是否为 DTO/Request/Result 等业务数据对象。
     */
    private boolean looksLikeDtoType(String type) {
        String simple = simpleType(type);
        return looksLikeRequestDtoType(type)
                || simple.endsWith("Response")
                || simple.endsWith("DTO")
                || simple.endsWith("VO")
                || simple.endsWith("BO")
                || simple.endsWith("PO")
                || simple.endsWith("Entity")
                || simple.endsWith("Model")
                || simple.endsWith("Command")
                || simple.endsWith("Query")
                || simple.endsWith("Param")
                || simple.endsWith("Profile")
                || simple.endsWith("Form")
                || simple.endsWith("Result");
    }

    /**
     * 判断是否为 Request DTO。
     */
    private boolean looksLikeRequestDtoType(String type) {
        return simpleType(type).endsWith("Request");
    }

    /**
     * 判断是否为集合参数。
     */
    private boolean looksLikeCollectionType(String type) {
        String simple = simpleType(type);
        return simple.startsWith("List")
                || simple.startsWith("Set")
                || simple.startsWith("Collection")
                || simple.startsWith("Map");
    }

    /**
     * 读取访问修饰符。
     */
    private String accessModifier(MethodModel method) {
        String modifier = method.getAccessModifier();
        if (modifier == null || modifier.isBlank()) {
            return method.isProtectedMethod() ? "protected" : "package-private";
        }
        return modifier;
    }

    /**
     * 提取简单类型名。
     */
    private String simpleType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
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
