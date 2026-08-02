package com.casc.unittestagent.ui;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.writer.TestFileWriter;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * 展示单元测试生成结果的 IntelliJ 对话框。
 * 该界面集中展示摘要、约束、场景、Mock 策略和测试源码，并提供复制或写入测试文件的操作。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class GenerationPreviewDialog extends DialogWrapper {
    /** 当前 IntelliJ 项目，用于弹窗、写命令和消息提示。 */
    private final Project project;
    /** 当前被测源码文件，写入测试文件时需要根据它推导目录。 */
    private final PsiFile sourceFile;
    /** 服务层返回的生成结果，预览页所有标签内容都从这里读取。 */
    private final GenerationResult result;
    /** 测试源码预览文本框，复制和写入操作会读取它的当前内容。 */
    private JTextArea codeArea;

    /**
     * 创建生成结果预览对话框。
     * 构造阶段保存项目、源文件和生成结果，并调用 DialogWrapper 初始化 UI 生命周期。
     *
     * 异常情况：无主动抛出的受检异常。
     *
     * @param project 当前 IntelliJ 项目上下文，用于消息提示、写命令和对话框归属
     * @param sourceFile 被测源码文件，用于推导测试文件写入位置
     * @param result 服务层返回的生成结果，预览页所有标签内容都从这里读取
     */
    public GenerationPreviewDialog(Project project, PsiFile sourceFile, GenerationResult result) {
        super(project);
        this.project = project;
        this.sourceFile = sourceFile;
        this.result = result;
        setTitle("UnitMind 单元测试生成预览");
        setCancelButtonText("取消");
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
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("概要", scroll(summaryPanel()));
        tabs.addTab("参数约束", scroll(constraintsPanel()));
        tabs.addTab("测试场景", scroll(scenariosPanel()));
        tabs.addTab("Mock策略", scroll(mockPanel()));

        codeArea = createCodeTextArea(result.getGeneratedTestFile() == null
                ? "未生成测试代码。请先修复源码解析失败问题。"
                : result.getGeneratedTestFile().getContent());
        tabs.addTab("测试代码", new JScrollPane(codeArea));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(tabs, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(1100, 760));
        return panel;
    }

    /**
     * 创建对话框底部动作。
     * 自定义复制和写入按钮与取消按钮一起返回给 IntelliJ 平台。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 对话框底部动作按钮数组
     */
    @Override
    protected Action[] createActions() {
        return new Action[]{
                new AbstractAction("复制代码") {
    /**
     * 响应用户在 IDE 菜单或按钮上的动作。
     * 方法只做上下文校验和流程入口分发，业务生成逻辑由服务层或对话框承担。
     *
      * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
      *
     * @param event IDE 或 Swing 触发的动作事件，提供当前项目、编辑器或按钮上下文
     */
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        copyCode();
                    }
                },
                new AbstractAction("写入测试文件") {
    /**
     * 响应用户在 IDE 菜单或按钮上的动作。
     * 方法只做上下文校验和流程入口分发，业务生成逻辑由服务层或对话框承担。
     *
      * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
      *
     * @param event IDE 或 Swing 触发的动作事件，提供当前项目、编辑器或按钮上下文
     */
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        writeTestFile();
                    }
                },
                getCancelAction()
        };
    }

    /**
     * 为只读文本创建滚动容器。
     * 多个预览标签都使用同一包装方式，保持对话框交互一致。
     */
    private JScrollPane scroll(JComponent component) {
        return new JScrollPane(component);
    }

    /**
     * 创建说明文本区域。
     * 中文说明使用 IDE 默认 UI 字体，避免等宽字体缺少中文字形导致乱码或方块。
     */
    private JTextArea createMessageTextArea(String text) {
        JTextArea textArea = new JTextArea(text);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(getUiFont());
        textArea.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        textArea.setBackground(UIManager.getColor("TextArea.background"));
        textArea.setForeground(UIManager.getColor("TextArea.foreground"));
        return textArea;
    }

    /**
     * 创建代码文本区域。
     * 代码页不自动换行，保持缩进结构，字体使用等宽字体。
     */
    private JTextArea createCodeTextArea(String text) {
        JTextArea textArea = new JTextArea(text);
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setWrapStyleWord(false);
        textArea.setFont(getCodeFont());
        textArea.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        textArea.setBackground(UIManager.getColor("TextArea.background"));
        textArea.setForeground(UIManager.getColor("TextArea.foreground"));
        return textArea;
    }

    /**
     * 获取中文说明区域字体。
     * 说明区优先使用 IDE UI 字体，回退到常见中文 UI 字体，避免内容区出现乱码。
     */
    private Font getUiFont() {
        Font font = UIManager.getFont("Label.font");
        return font == null ? new Font("Microsoft YaHei UI", Font.PLAIN, 14) : font.deriveFont(Font.PLAIN, 14f);
    }

    /**
     * 获取代码预览字体。
     * 优先使用 JetBrains Mono；系统不可用时回退到平台等宽字体，保证代码对齐可读。
     */
    private Font getCodeFont() {
        String preferredFont = "JetBrains Mono";
        Font font = new Font(preferredFont, Font.PLAIN, 13);
        if (!preferredFont.equals(font.getFamily())) {
            return new Font(Font.MONOSPACED, Font.PLAIN, 13);
        }
        return font;
    }

    /**
     * 构造概要标签页。
     * 概要用分行字段展示核心信息，避免中文内容挤在原始文本框中难以扫描。
     */
    private JComponent summaryPanel() {
        if (result.getMethodModel() == null) {
            return createMessageTextArea(failureSummary());
        }
        MethodModel method = result.getMethodModel();
        JPanel panel = verticalPanel();
        panel.add(label("被测类：" + method.getClassName()));
        panel.add(label("被测方法：" + method.getMethodName()));
        panel.add(label("返回类型：" + method.getReturnType()));
        panel.add(label(" "));
        panel.add(label("方法参数："));
        for (ParameterModel parameter : method.getParameters()) {
            panel.add(label("- " + parameter.getType() + " " + parameter.getName()));
        }
        panel.add(label(" "));
        panel.add(label("分支数量：" + method.getBranches().size()));
        panel.add(label("依赖数量：" + method.getDependencies().size()));
        if (!result.getWarnings().isEmpty()) {
            panel.add(label(" "));
            panel.add(label("告警：" + String.join("；", result.getWarnings())));
        }
        return panel;
    }

    /**
     * 构造参数约束标签页。
     * 有约束时使用表格展示，没有约束时显示中文说明。
     */
    private JComponent constraintsPanel() {
        if (result.getMethodModel() == null) {
            return createMessageTextArea("未生成参数约束。\n\n原因：源码解析失败，且 PSI 兜底未能构造当前方法模型。\n\n" + warningText());
        }
        if (result.getMethodModel().getConstraints().isEmpty()) {
            return createMessageTextArea("未识别到显式参数约束。\n\n说明：\n当前方法中没有发现 if 判断、throw 异常分支、数值比较、非空判断等显式约束。\n后续测试场景将基于参数类型、方法调用和默认测试数据规则生成。");
        }
        DefaultTableModel model = new DefaultTableModel(new String[]{"参数", "类型", "约束类型", "异常条件", "安全条件", "来源"}, 0);
        for (ConstraintModel constraint : result.getMethodModel().getConstraints()) {
            model.addRow(new Object[]{
                    constraint.getParameterName(),
                    parameterType(constraint.getParameterName()),
                    constraint.getKind(),
                    constraint.getExpression(),
                    constraint.getSafeExpression(),
                    sourceText(constraint.getSource())
            });
        }
        return table(model);
    }

    /**
     * 构造测试场景标签页。
     * 中文场景名只用于 UI，代码生成使用英文 methodNameEn。
     */
    private JComponent scenariosPanel() {
        if (result.getMethodModel() == null) {
            return createMessageTextArea("未生成测试场景。\n\n原因：源码解析失败，无法可靠识别当前方法、参数和分支。\n\n" + warningText());
        }
        DefaultTableModel model = new DefaultTableModel(new String[]{"类型", "场景名称", "说明"}, 0);
        for (TestScenario scenario : result.getScenarios()) {
            model.addRow(new Object[]{scenario.getType(), scenario.getDisplayNameCn(), scenario.getDescription()});
        }
        return model.getRowCount() == 0 ? createMessageTextArea("未生成测试场景。") : table(model);
    }

    /**
     * 构造 Mock 策略标签页。
     * 按分组展示 Mock 对象和规则，减少空标题或乱码带来的误解。
     */
    private JComponent mockPanel() {
        if (result.getMethodModel() == null) {
            return createMessageTextArea("未生成 Mock 策略。\n\n原因：源码解析失败，无法可靠识别字段依赖和方法调用。\n\n" + warningText());
        }
        StringBuilder text = new StringBuilder();
        text.append("Mock对象：\n");
        for (DependencyModel dependency : result.getMethodModel().getDependencies()) {
            text.append("- ").append(dependency.getFieldType()).append(" ").append(dependency.getFieldName()).append("\n");
        }
        MockStrategy strategy = result.getMockStrategy();
        if (result.getMethodModel().getDependencies().isEmpty()) {
            return createMessageTextArea("未识别到需要 Mock 的外部依赖。\n\n说明：\n当前方法未发现字段依赖、接口型方法参数或外部服务调用。");
        }
        text.append("\nwhen规则：\n");
        appendRules(text, strategy == null ? null : strategy.getWhenRules());
        text.append("\nverify规则：\n");
        appendRules(text, strategy == null ? null : strategy.getVerifyRules());
        text.append("\nnever规则：\n");
        appendRules(text, strategy == null ? null : strategy.getNeverRules());
        return createMessageTextArea(text.toString());
    }

    /**
     * 创建带统一边距的纵向面板。
     * 概要页使用分行展示，比大文本框更清晰。
     */
    private JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        panel.setBackground(UIManager.getColor("Panel.background"));
        return panel;
    }

    /**
     * 创建概要页标签。
     * 标签使用 UI 字体，保证中文内容由平台字体渲染。
     */
    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(getUiFont());
        label.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        return label;
    }

    /**
     * 创建只读表格组件。
     * 约束和场景页使用表格展示，提升信息对齐和扫描效率。
     */
    private JComponent table(DefaultTableModel model) {
        JTable table = new JTable(model) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.setFont(getUiFont());
        table.setRowHeight(26);
        table.getTableHeader().setFont(getUiFont().deriveFont(Font.BOLD));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        panel.add(table.getTableHeader(), BorderLayout.NORTH);
        panel.add(table, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 根据参数名查找参数类型。
     * 参数约束表需要把约束和原始参数模型关联起来展示。
     */
    private String parameterType(String parameterName) {
        if (result.getMethodModel() == null) {
            return "";
        }
        for (ParameterModel parameter : result.getMethodModel().getParameters()) {
            if (parameter.getName().equals(parameterName)) {
                return parameter.getType();
            }
        }
        return "";
    }

    /**
     * 将约束来源转换为中文展示文本。
     * 解析器内部保留英文分类，UI 层负责转换为用户可读说明。
     */
    private String sourceText(String source) {
        if ("if-throw".equals(source)) {
            return "if 异常分支";
        }
        if ("if".equals(source)) {
            return "if 判断";
        }
        return source == null ? "" : source;
    }

    /**
     * 追加 Mock 规则列表。
     * 空列表显示“无”，避免预览页出现空标题让用户误以为生成失败。
     */
    private void appendRules(StringBuilder text, java.util.List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            text.append("- 无\n");
            return;
        }
        for (String rule : rules) {
            text.append("- ").append(rule).append("\n");
        }
    }

    /**
     * 构造解析失败概要。
     * 失败时仍给出原因、可能处理方向和原始错误，避免用户面对空白预览页无法判断下一步。
     */
    private String failureSummary() {
        return "生成失败。\n\n"
                + "原因：源码解析失败，且 PSI 兜底未能构造可继续生成的当前方法模型。\n\n"
                + "可能原因：\n"
                + "- 当前源码使用了插件暂不支持的 Java 新语法；\n"
                + "- JavaParser 版本或语言级别配置过低；\n"
                + "- 源码存在语法错误；\n"
                + "- 光标没有位于需要生成测试的方法内部。\n\n"
                + warningText();
    }

    /**
     * 汇总服务层告警。
     * 多条告警逐行展示，既包含 JavaParser 原始错误，也包含 PSI 降级失败信息。
     */
    private String warningText() {
        if (result.getWarnings().isEmpty()) {
            return "详细错误：无。";
        }
        return "详细错误：\n" + String.join("\n", result.getWarnings());
    }

    /**
     * 复制当前生成源码到系统剪贴板。
     * 用户可以先复制再手工粘贴修改，而不必立即写入项目文件。
     */
    private void copyCode() {
        String code = codeArea == null ? "" : codeArea.getText();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
        Messages.showInfoMessage(project, "测试代码已复制。", "单元测试生成预览");
    }

    /**
     * 把生成源码写入测试目录。
     * 写入失败时转为对话框错误提示，避免异常直接冒泡到 IDE。
     */
    private void writeTestFile() {
        try {
            TestFileWriter writer = new TestFileWriter();
            TestFileWriter.PreparedWrite prepared = writer.prepare(
                    project,
                    sourceFile,
                    result.getGeneratedTestFile(),
                    codeArea.getText(),
                    null,
                    result.getMethodModel() == null ? "" : result.getMethodModel().getMethodName());
            if (prepared.isCancelled()) {
                return;
            }
            ValidationResult[] validation = new ValidationResult[1];
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> validation[0] = new UnitTestGenerationService().validateCode(
                            project, sourceFile, result.getGeneratedTestFile(), prepared.getContent()),
                    "UnitMind 正在校验最终测试文件",
                    true,
                    project);
            if (validation[0] == null || validation[0].getStatus() != ValidationStatus.PASSED) {
                Messages.showErrorDialog(project, "最终测试文件未通过编译校验，已阻止写入。", "写入测试文件失败");
                return;
            }
            writer.commit(project, prepared, validation[0], false);
            Messages.showInfoMessage(project, "测试文件写入成功。", "单元测试生成预览");
        } catch (Exception ex) {
            Messages.showErrorDialog(project, ex.getMessage(), "写入测试文件失败");
        }
    }
}
