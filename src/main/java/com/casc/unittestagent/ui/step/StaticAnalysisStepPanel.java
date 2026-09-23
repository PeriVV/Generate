package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.SkippedMethodModel;
import com.casc.unittestagent.ui.component.UnitMindSectionPanel;
import com.casc.unittestagent.ui.component.UnitMindEmptyStatePanel;
import com.casc.unittestagent.ui.component.UnitMindErrorPanel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 静态分析结果步骤面板。
 * 只展示源码静态分析得到的客观事实，不展示 Mock 决策、测试建议或生成结果。
 * 未分析时提供“分析当前方法”入口，分析失败时展示具体失败原因，便于用户自助恢复。
 */
public class StaticAnalysisStepPanel extends JBPanel<StaticAnalysisStepPanel> {
    public StaticAnalysisStepPanel(GenerationResult result, String sourcePath) {
        this(result, sourcePath, null, null);
    }

    /**
     * 创建静态分析结果步骤面板。
     *
     * @param result 生成结果
     * @param sourcePath 源码路径
     * @param errorText 分析失败原因，非失败状态传 null
     * @param onAnalyze “分析当前方法 / 重新分析”按钮动作，传 null 时不显示按钮
     */
    public StaticAnalysisStepPanel(GenerationResult result, String sourcePath, String errorText, Runnable onAnalyze) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(10));
        if (result instanceof ClassGenerationResult) {
            ClassGenerationResult classResult = (ClassGenerationResult) result;
            ClassModel classModel = classResult.getClassModel();
            if (classModel == null) {
                String reason = classResult.getWarnings().isEmpty()
                        ? "未能定位可分析的顶层 Java 具体类。"
                        : String.join("\n", classResult.getWarnings());
                add(new UnitMindErrorPanel("整类静态分析未通过", reason), BorderLayout.CENTER);
                return;
            }
            JBPanel<?> body = new JBPanel<>(new GridLayout(0, 1, 0, 8));
            body.add(new UnitMindSectionPanel("类信息", table(classInfoModel(classModel, sourcePath), sourcePath)));
            body.add(new UnitMindSectionPanel("方法范围", table(classMethodModel(classModel), null)));
            add(new JScrollPane(body), BorderLayout.CENTER);
            return;
        }
        MethodModel method = result == null ? null : result.getMethodModel();
        if (method == null) {
            add(pendingPanel(result, errorText, onAnalyze), BorderLayout.CENTER);
            return;
        }
        JBPanel<?> body = new JBPanel<>(new GridLayout(0, 1, 0, 8));
        body.add(new UnitMindSectionPanel("方法信息", table(methodInfoModel(method, sourcePath), sourcePath)));
        body.add(new UnitMindSectionPanel("参数信息", table(parameterInfoModel(method), null)));
        body.add(new UnitMindSectionPanel("参数约束", table(constraintModel(method), null)));
        body.add(new UnitMindSectionPanel("控制流分支", table(branchModel(method), null)));
        body.add(new UnitMindSectionPanel("依赖调用", table(dependencyModel(method), null)));
        add(new JScrollPane(body), BorderLayout.CENTER);
    }

    /**
     * 未分析时展示带操作入口的空状态；分析失败时展示失败原因和重新分析按钮。
     */
    private JBPanel<?> pendingPanel(GenerationResult result, String errorText, Runnable onAnalyze) {
        String detail = errorText == null ? "" : errorText.trim();
        if (result != null && detail.isEmpty() && !result.getWarnings().isEmpty()) {
            detail = String.join("\n", result.getWarnings());
        }
        if (detail.isEmpty()) {
            return new UnitMindEmptyStatePanel(
                    "尚未分析方法",
                    "将光标放到编辑器中的 Java 方法内，点击下方按钮开始分析。\n也可以在编辑器右键菜单选择 “UnitMind：生成单元测试”。",
                    onAnalyze == null ? null : "分析当前方法",
                    onAnalyze);
        }
        JBPanel<?> panel = new JBPanel<>(new BorderLayout(0, 8));
        panel.add(new UnitMindErrorPanel("静态分析未通过", detail), BorderLayout.CENTER);
        if (onAnalyze != null) {
            JButton retry = new JButton("重新分析");
            retry.addActionListener(event -> onAnalyze.run());
            JBPanel<?> buttonPanel = new JBPanel<>(new FlowLayoutLeft());
            buttonPanel.add(retry);
            panel.add(buttonPanel, BorderLayout.SOUTH);
        }
        return panel;
    }

    private static final class FlowLayoutLeft extends java.awt.FlowLayout {
        private FlowLayoutLeft() {
            super(java.awt.FlowLayout.LEFT, 0, 0);
        }
    }

    private DefaultTableModel classInfoModel(ClassModel model, String sourcePath) {
        DefaultTableModel table = readOnlyModel(new String[]{"项目", "内容"});
        if (model == null) {
            table.addRow(new Object[]{"状态", "尚未分析当前类。"});
            return table;
        }
        table.addRow(new Object[]{"被测类", model.getQualifiedName()});
        table.addRow(new Object[]{"纳入方法", model.getMethods().size()});
        table.addRow(new Object[]{"跳过方法", model.getSkippedMethods().size()});
        table.addRow(new Object[]{"源码范围", model.getSourceStartLine() + " - " + model.getSourceEndLine()});
        table.addRow(new Object[]{"源码位置", empty(sourcePath)});
        return table;
    }

    private DefaultTableModel classMethodModel(ClassModel model) {
        DefaultTableModel table = readOnlyModel(new String[]{"是否生成", "方法签名", "类型", "原因/说明"});
        if (model == null) {
            table.addRow(new Object[]{false, "-", "-", "请先分析当前类。"});
            return table;
        }
        for (MethodModel method : model.getMethods()) {
            table.addRow(new Object[]{true, method.getSignature(), method.isStaticMethod() ? "static" : "instance", "符合整类生成范围"});
        }
        for (SkippedMethodModel skipped : model.getSkippedMethods()) {
            table.addRow(new Object[]{false, skipped.getSignature(), "skipped", skipped.getReason()});
        }
        return table;
    }

    private DefaultTableModel methodInfoModel(MethodModel method, String sourcePath) {
        DefaultTableModel model = readOnlyModel(new String[]{"项目", "内容"});
        model.addRow(new Object[]{"被测类", method.getClassName()});
        model.addRow(new Object[]{"被测方法", method.getMethodName()});
        model.addRow(new Object[]{"方法签名", signature(method)});
        model.addRow(new Object[]{"返回类型", method.getReturnType()});
        model.addRow(new Object[]{"访问修饰符", empty(method.getAccessModifier())});
        model.addRow(new Object[]{"源码行范围", method.getSourceStartLine() > 0
                ? method.getSourceStartLine() + " - " + method.getSourceEndLine() : "-"});
        model.addRow(new Object[]{"声明异常", method.getThrownExceptions().isEmpty()
                ? "无" : String.join(", ", method.getThrownExceptions())});
        model.addRow(new Object[]{"源码位置", empty(sourcePath)});
        return model;
    }

    private DefaultTableModel parameterInfoModel(MethodModel method) {
        DefaultTableModel model = readOnlyModel(new String[]{"参数名", "参数类型", "参数角色", "静态识别结果"});
        if (method.getParameters().isEmpty()) {
            model.addRow(new Object[]{"-", "-", "无参数", "当前方法没有方法入参。"});
            return model;
        }
        for (ParameterModel parameter : method.getParameters()) {
            model.addRow(new Object[]{parameter.getName(), parameter.getType(), "方法参数", parameterUsage(method, parameter.getName())});
        }
        return model;
    }

    private DefaultTableModel constraintModel(MethodModel method) {
        DefaultTableModel model = readOnlyModel(new String[]{"参数", "类型", "约束类型", "条件表达式", "来源"});
        if (!method.getConstraints().isEmpty()) {
            for (ConstraintModel constraint : method.getConstraints()) {
                model.addRow(new Object[]{constraint.getParameterName(),
                        parameterType(method, constraint.getParameterName()),
                        empty(constraint.getKind()),
                        empty(constraint.getExpression()),
                        empty(constraint.getSource())});
            }
            return model;
        }
        if (method.getParameters().isEmpty()) {
            model.addRow(new Object[]{"-", "-", "未识别到显式约束", "-", "当前方法没有方法参数。"});
            return model;
        }
        for (ParameterModel parameter : method.getParameters()) {
            model.addRow(new Object[]{parameter.getName(),
                    parameter.getType(),
                    "未识别到显式约束",
                    "-",
                    "未发现对该参数的 if / throw / requireNonNull 判断"});
        }
        return model;
    }

    /**
     * 控制流分支表：展示方法体内识别到的条件分支及其行范围、动作和抛出异常。
     */
    private DefaultTableModel branchModel(MethodModel method) {
        DefaultTableModel model = readOnlyModel(new String[]{"行范围", "条件", "分支动作", "抛出异常"});
        List<BranchModel> branches = method.getBranches();
        if (branches == null || branches.isEmpty()) {
            model.addRow(new Object[]{"-", "-", "未识别到条件分支", "方法体中未发现 if / switch / 循环等分支结构。"});
            return model;
        }
        for (BranchModel branch : branches) {
            String range = branch.getSourceStartLine() > 0
                    ? branch.getSourceStartLine() + (branch.getSourceEndLine() > 0
                    ? " - " + branch.getSourceEndLine() : "") : "-";
            model.addRow(new Object[]{range,
                    firstNonBlank(branch.getCondition(), branch.getSafeCondition()),
                    empty(branch.getAction()),
                    empty(branch.getExceptionType())});
        }
        return model;
    }

    /**
     * 依赖调用表：展示被测方法实际调用的字段或参数依赖，是 Mock 策略的静态依据。
     */
    private DefaultTableModel dependencyModel(MethodModel method) {
        DefaultTableModel model = readOnlyModel(new String[]{"对象", "类型", "业务类型", "调用方法", "调用表达式", "来源"});
        List<DependencyModel> dependencies = method.getDependencies();
        if (dependencies == null || dependencies.isEmpty()) {
            model.addRow(new Object[]{"-", "-", "-", "-", "-", "未识别到字段或参数依赖调用。"});
            return model;
        }
        for (DependencyModel dependency : dependencies) {
            model.addRow(new Object[]{
                    empty(dependency.getFieldName()),
                    empty(dependency.getFieldType()),
                    empty(dependency.getDependencyType()),
                    dependency.getCalledMethods().isEmpty() ? "-" : String.join(", ", dependency.getCalledMethods()),
                    dependency.getCallExpressions().isEmpty() ? "-" : String.join("; ", dependency.getCallExpressions()),
                    dependency.isMethodParameter() ? "方法参数" : "字段"});
        }
        return model;
    }

    private String parameterUsage(MethodModel method, String parameterName) {
        List<String> calls = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(parameterName) + "\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                .matcher(method.getMethodBodyText() == null ? "" : method.getMethodBodyText());
        while (matcher.find()) {
            String call = matcher.group(1) + "()";
            if (!calls.contains(call)) {
                calls.add(call);
            }
        }
        return calls.isEmpty() ? "未识别到该参数上的方法调用" : "调用了 " + String.join("、", calls);
    }

    private DefaultTableModel readOnlyModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private JScrollPane table(DefaultTableModel model, String tooltip) {
        JBTable table = new JBTable(model);
        table.setRowHeight(26);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
        if (tooltip != null && !tooltip.isBlank()) {
            table.setToolTipText(tooltip);
        }
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        return scrollPane;
    }

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

    private String parameterType(MethodModel method, String name) {
        for (ParameterModel parameter : method.getParameters()) {
            if (parameter.getName().equals(name)) {
                return parameter.getType();
            }
        }
        return "-";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "-";
    }

    private String empty(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
