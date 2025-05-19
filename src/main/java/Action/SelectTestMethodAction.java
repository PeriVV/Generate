package Action;

import MyUtils.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SelectTestMethodAction extends AnAction {

    public void actionPerformed(@NotNull AnActionEvent e) {
        final Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }
        String methodBody = null;
        try {
            methodBody = String.valueOf(MethodExtractor.parseSelectedMethod(editor.getDocument().getText(), selectedText));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        if (methodBody == null) {
            return;
        }
        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(methodBody);

        // Extract variables from selected method
        List<VariableInfo> inputVariables = AllVariableExtractor.extractInputVariables(methodBody);
        List<VariableInfo> outputVariables = AllVariableExtractor.extractOutputVariables(methodBody);

        // Create input/output panel with variable checkboxes and dropdowns
        JPanel inputOutputPanel = new JPanel(new GridBagLayout());
        inputOutputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(5);
        inputOutputPanel.add(new JLabel("Select inputVariables:"), gbc);

        // 创建表格
        JTable inputVariableTable = new JTable(new VariableTableModel(inputVariables));

        // 设置列宽
        TableColumnModel columnModel = inputVariableTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(50);
        columnModel.getColumn(1).setPreferredWidth(100);
        columnModel.getColumn(2).setPreferredWidth(100);

        // 添加监听器
        inputVariableTable.getSelectionModel().addListSelectionListener(a -> {
            int selectedRow = inputVariableTable.getSelectedRow();
            if (selectedRow != -1) {
                VariableInfo variableInfo = inputVariables.get(selectedRow);
                variableInfo.setSelected(!variableInfo.isSelected());
                inputVariableTable.repaint();
            }
        });

        // 显示表格
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        JPanel inputVariableTablePanel = new JPanel(new BorderLayout());
        inputVariableTablePanel.add(new JScrollPane(inputVariableTable), BorderLayout.CENTER);
        inputOutputPanel.add(inputVariableTablePanel, gbc);

        // 添加输出变量表格
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(5);
        inputOutputPanel.add(new JLabel("Select outputVariables:"), gbc);

        // 创建表格
        JTable outputVariableTable = new JTable(new VariableTableModel(outputVariables));

        // 设置列宽
        columnModel = outputVariableTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(50);
        columnModel.getColumn(1).setPreferredWidth(100);
        columnModel.getColumn(2).setPreferredWidth(100);

        // 添加监听器
        outputVariableTable.getSelectionModel().addListSelectionListener(a -> {
            int selectedRow = outputVariableTable.getSelectedRow();
            if (selectedRow != -1) {
                VariableInfo variableInfo = outputVariables.get(selectedRow);
                variableInfo.setSelected(!variableInfo.isSelected());
                outputVariableTable.repaint();
            }
        });

        // 显示表格
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        JPanel outputVariableTablePanel = new JPanel(new BorderLayout());
        outputVariableTablePanel.add(new JScrollPane(outputVariableTable), BorderLayout.CENTER);
        inputOutputPanel.add(outputVariableTablePanel, gbc);

        // Add confirm button to show selected variables
        JButton confirmButton = new JButton("Confirm");
        confirmButton.addActionListener(a -> {
            // 获取用户选择的输入变量和输出变量
            List<VariableInfo> selectedInputVariables = new ArrayList<>();
            List<VariableInfo> selectedOutputVariables = new ArrayList<>();
            // 创建表格模型
            DefaultTableModel tableModel = new DefaultTableModel();
            tableModel.addColumn("Variable Name");
            tableModel.addColumn("Variable Type");
            tableModel.addColumn("Input/Output");
            tableModel.addColumn("MinValue");
            tableModel.addColumn("MaxValue");
            tableModel.addColumn("DoubleScale");

            // 创建表格并添加到面板上
            JTable table = new JTable(tableModel);
            for (VariableInfo variable : inputVariables) {
                if (variable.isSelected()) {
                    selectedInputVariables.add(variable);
                    Object[] row = {variable.getName(), variable.getType(), "Input", "", "", ""};
                    tableModel.addRow(row);
                }
            }
            for (VariableInfo variable : outputVariables) {
                if (variable.isSelected()) {
                    selectedOutputVariables.add(variable);
                    Object[] row = {variable.getName(), variable.getType(), "Output", "", "", ""};
                    tableModel.addRow(row);
                }
            }
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.setPreferredScrollableViewportSize(new Dimension(850, 400));
            table.getColumnModel().getColumn(0).setPreferredWidth(150);
            table.getColumnModel().getColumn(1).setPreferredWidth(150);
            table.getColumnModel().getColumn(2).setPreferredWidth(100);
            table.getColumnModel().getColumn(3).setPreferredWidth(150);
            table.getColumnModel().getColumn(4).setPreferredWidth(150);
            table.getColumnModel().getColumn(5).setPreferredWidth(150);
            JScrollPane scrollPane = new JScrollPane(table);
            //添加一个generate按钮
            JButton generateButton = new JButton("Generate");
            generateButton.addActionListener(b -> {
                //添加一个新的测试方法
                // 解析选定的方法
                MethodExtractor methodExtractor = new MethodExtractor();

                // 获取结束行号
                int methodEndLine = methodExtractor.getEndLine();

                // 修改方法名
                methodDeclaration.setName(methodDeclaration.getNameAsString() + "forGenerate");
                //去掉assert语句
                String newMethodCode = methodDeclaration.toString().replaceAll(".*assertEquals.*\n?", "").replaceAll(".*assertTrue.*\n?", "").replaceAll(".*assertFalse.*\n?", "");

                //修改input和output
                // 获取表格数据
                DefaultTableModel newModel = (DefaultTableModel) table.getModel();
                int rowCount = newModel.getRowCount();
                String[][] data = new String[rowCount][6];
                for (int i = 0; i < rowCount; i++) {
                    for (int j = 0; j < 6; j++) {
                        data[i][j] = newModel.getValueAt(i, j).toString();
                        System.out.println(data[i][j]);
                    }
                }

                for (int i = 0; i < rowCount; i++) {
                    String variableName = data[i][0];
                    String variableType = data[i][1];
                    String InputOutput = data[i][2];
                    String start = data[i][3];
                    String end = data[i][4];
                    String scale = data[i][5];
                    String replaceValue = null;
                    if (InputOutput.equals("Input")) {
                        if (variableType.equals("int")) {
                            try {
                                int lower = Integer.parseInt(start);
                                int upper = Integer.parseInt(end);
                                replaceValue = "DataGenerator.generateInteger(" + lower + ", " + upper + ")";
                            } catch (NumberFormatException c) {
                                // 处理转换异常
                            }
                        } else if (variableType.equals("double")) {
                            try {
                                double lower = Double.parseDouble(start);
                                double upper = Double.parseDouble(end);
                                replaceValue = "DataGenerator.generateDouble(" + lower + ", " + upper + "," + Integer.parseInt(scale) + ")";
                            } catch (NumberFormatException f) {
                                // 处理转换异常
                            }
                        } else if (variableType.equals("String")) {
                            //需要更换为正则表达式
                            try {
                                String lower = start;
                                String upper = end;
                                replaceValue = "DataGenerator.generateString(\"" + lower + "\", \"" + upper + "\")";
                            } catch (NumberFormatException f) {
                                // 处理转换异常
                            }
                        }  //replaceValue = variableName;
                        if (replaceValue != null) {
                            String regex = "(?<![a-zA-Z0-9_-])" + variableName + "(?![a-zA-Z0-9_-])";
                            newMethodCode = newMethodCode.replaceAll(regex, replaceValue);
                        }
                        if (start.contains("[")) {
                            // 处理数组类型
                            String lower = start;
                            String upper = end;
                            replaceValue = "DataGenerator.generateArray(\"" + lower + "\", \"" + upper + "\")";
                            Pattern pattern = Pattern.compile("([a-zA-Z0-9_-]+)\\s*=\\s*(.+?);");
                            Matcher matcher = pattern.matcher(newMethodCode);
                            while (matcher.find()) {
                                String Name = matcher.group(1);
                                String assignment = matcher.group(2);
                                if (Name.equals(variableName)) {
                                    newMethodCode = newMethodCode.replace(assignment, replaceValue);
                                }
                            }
                        }

                    } else if (InputOutput.equals("Output")) {
                        int lastIndex = newMethodCode.lastIndexOf("}");
                        if (lastIndex != -1) {
                            newMethodCode = newMethodCode.substring(0, lastIndex) + newMethodCode.substring(lastIndex + 1);
                        }
                        newMethodCode += "\tDataGenerator.getOutput(" + variableName + ");\n}";
                    }
                }
                newMethodCode += "\n\t@Test\n\tpublic void testData(){\n\t\ttry {\n\t\t\tint iteration = 1100000;\n\t\t\tDataGenerator.init();\n\t\t\tfor (int i=0;i<iteration;i++){\n\t\t\t\t//put your test method invocation here.\n\t\t\t\t" + methodDeclaration.getNameAsString() + "();\n\t\t\t\tDataGenerator.finishTestCase();\n\t\t\t}\n\t\t}catch (Exception exception){\n\t\t\texception.printStackTrace();\n\t\t}finally {\n\t\t\tDataGenerator.close();\n\t\t}\n\t}";

                int methodEndIndex = methodEndLine;
                int insertOffset = editor.getDocument().getLineStartOffset(methodEndIndex);

                if (methodEndIndex != -1) {
                    String insertText = "\n" + newMethodCode + "\n";
                    Runnable runnable = () -> editor.getDocument().insertString(insertOffset, insertText);
                    WriteCommandAction.runWriteCommandAction(e.getData(PlatformDataKeys.PROJECT), runnable);
                    // 调用生成的测试方法
                    runCurrentProject(project);
                }
            });
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(scrollPane, BorderLayout.CENTER);
            panel.add(generateButton, BorderLayout.SOUTH);
            // 显示面板
            JDialog dialog = new JDialog();
            dialog.setContentPane(panel);
            dialog.setTitle("Selected Input/Output Variables");
            dialog.setModal(true);
            dialog.pack();
            dialog.setVisible(true);
        });
        JBPopupFactory popupFactory = JBPopupFactory.getInstance();
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(inputOutputPanel, BorderLayout.CENTER);
        panel.add(confirmButton, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(1000, 600));
        popupFactory.createComponentPopupBuilder(panel, inputOutputPanel).setTitle("Select Variables").setRequestFocus(true).setNormalWindowLevel(false).setResizable(true).setMovable(true).createPopup().showInFocusCenter();
    }

    public static void runCurrentProject(Project project) {
        // 获取当前项目的根目录
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) {
            return;
        }

        // 创建运行配置
        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings configuration = runManager.getSelectedConfiguration();
        if (configuration == null) {
            return;
        }
        // 设置运行配置的工作目录为当前项目的根目录
        configuration.setFolderName(baseDir.getPath());

        // 创建执行环境
        ExecutionEnvironment executionEnvironment = ExecutionUtil.createEnvironment(DefaultRunExecutor.getRunExecutorInstance(), configuration).build();

        // 运行项目
        ExecutionManager.getInstance(project).restartRunProfile(executionEnvironment);
    }
}