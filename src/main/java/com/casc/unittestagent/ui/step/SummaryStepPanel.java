package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
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
 * 页面把方法信息、参数信息和分析统计统一放入表格，减少方法确认与概要重复展示。
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
        body.add(new UnitMindSectionPanel("分析统计", table(statisticsModel(result))));
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
        model.addRow(new Object[]{"访问修饰符", method.isProtectedMethod() ? "protected" : "public"});
        model.addRow(new Object[]{"源码位置", empty(sourcePath)});
        return model;
    }

    /**
     * 构建参数信息表。
     */
    private DefaultTableModel parameterInfoModel(GenerationResult result) {
        DefaultTableModel model = readOnlyModel(new String[]{"参数名", "参数类型", "参数角色", "生成策略"});
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
     * 构建分析统计表。
     */
    private DefaultTableModel statisticsModel(GenerationResult result) {
        DefaultTableModel model = readOnlyModel(new String[]{"项目", "数量", "说明"});
        MethodModel method = result == null ? null : result.getMethodModel();
        if (method == null) {
            model.addRow(new Object[]{"分析结果", "0", "尚未生成结构化分析数据。"});
            return model;
        }
        MockStrategy strategy = result.getMockStrategy();
        int staticCount = countStrategyItems(strategy, "静态方法依赖");
        int internalCount = countStrategyItems(strategy, "本类内部调用");
        int localCount = countStrategyItems(strategy, "局部对象依赖");
        model.addRow(new Object[]{"分支数量", method.getBranches().size(), "try/catch、if、throw 等控制流。"});
        model.addRow(new Object[]{"参数约束", method.getConstraints().size(), method.getConstraints().isEmpty() ? "未识别到显式参数约束。" : "来自参数相关条件表达式。"});
        model.addRow(new Object[]{"测试场景", result.getScenarios().size(), "根据分支、依赖调用和返回类型生成。"});
        model.addRow(new Object[]{"Mock对象", countFieldMocks(method), "字段依赖和框架型方法参数。"});
        model.addRow(new Object[]{"静态依赖", staticCount, staticCount == 0 ? "未发现 ClassName.method(...) 形式调用。" : "需要 mockStatic 或人工 TODO。"});
        model.addRow(new Object[]{"本类内部调用", internalCount, internalCount == 0 ? "未发现本类内部方法调用。" : "private 方法通常真实执行。"});
        model.addRow(new Object[]{"局部对象依赖", localCount, localCount == 0 ? "未发现来自静态返回值的局部对象调用。" : "通常需要配合静态方法 Mock。"});
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
        return looksLikeDtoType(type) ? "入参DTO" : "方法参数";
    }

    /**
     * 推断参数生成策略。
     */
    private String parameterStrategy(String type) {
        return looksLikeDtoType(type) ? "构造测试对象，不作为 Mock" : "按类型生成测试值";
    }

    /**
     * 判断是否为 DTO/Request/Result 等业务数据对象。
     */
    private boolean looksLikeDtoType(String type) {
        String simple = simpleType(type);
        return simple.endsWith("Request")
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
                || simple.endsWith("Form")
                || simple.endsWith("Result");
    }

    /**
     * 统计指定类型的 Mock 策略项。
     */
    private int countStrategyItems(MockStrategy strategy, String type) {
        if (strategy == null || strategy.getStrategyItems() == null) {
            return 0;
        }
        int count = 0;
        for (MockStrategyItem item : strategy.getStrategyItems()) {
            if (type.equals(item.getDependencyType())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计会生成 @Mock 的字段依赖数量。
     */
    private int countFieldMocks(MethodModel method) {
        int count = 0;
        for (DependencyModel dependency : method.getDependencies()) {
            if (!dependency.isMethodParameter()) {
                count++;
            }
        }
        return count;
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
