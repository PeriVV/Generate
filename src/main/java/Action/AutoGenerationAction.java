package Action;

import MyUtils.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
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
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.*;
import java.util.List;

public class AutoGenerationAction extends AnAction {

    // 定义断言方法名称集合
    private static final Set<String> ASSERTION_METHODS = new HashSet<>(Arrays.asList(
            "assertEquals", "assertArrayEquals", "assertNotEquals", "assertNull", "assertNotNull", "assertSame",
            "assertNotSame", "assertFalse", "assertTrue", "assertThat"
    ));

    public void actionPerformed(@NotNull AnActionEvent e) {
        final Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        assert editor != null;
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }
        String methodBody;
        try {
            methodBody = String.valueOf(MethodExtractor.parseSelectedMethod(editor.getDocument().getText(), selectedText));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        if (methodBody == null) {
            return;
        }
        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(methodBody);

        String absoluteJavaFilePath = getAbsoluteFilePath(editor);
        Document document = Editors.getCurrentDocument(absoluteJavaFilePath);

        BlockStmt methods = methodDeclaration.getBody().get();
        List<MethodCallExpr> methodCalls = methods.findAll(MethodCallExpr.class);

        final int startOffset = editor.getSelectionModel().getSelectionStart();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            // 这个代码块负责把断言中的真实输出提取为变量
            for (MethodCallExpr methodCall : methodCalls) {
                String methodName = methodCall.getNameAsString();
                if (ASSERTION_METHODS.contains(methodName)) {
                    List<Expression> arguments = methodCall.getArguments();
                    System.out.println("处理断言方法：" + methodName);
                    for (Expression argument : arguments) {
                        // 判断表达式类型
                        if (argument instanceof MethodCallExpr) {
                            String expression = argument.toString();

                            // 然后将 startOffset 传递给 getOffset 方法
                            int offset = getOffset(absoluteJavaFilePath, expression, startOffset);
                            System.out.println("表达式：" + expression + " 偏移量：" + offset);
                            if (offset != -1) {  // 确保找到了有效的偏移量
                                // 根据光标位置替换
                                idea_parseEachJavaFileOfSpecificCommit(document, project, absoluteJavaFilePath, offset, expression);
                            } else {
                                System.out.println("未找到表达式的位置：" + expression);
                            }
                        }
                    }
                    // 将替换后的文本写入文件，最好是在循环结束后执行一次
                }
            }

            writeToFile(absoluteJavaFilePath, document, document.getText());

            // 保存文档
            FileDocumentManager.getInstance().saveDocument(document);
        });



        // 从选择的方法中提取输入和输出
        List<VariableInfo> inputVariables = AllVariableExtractor.extractInputVariables(methodBody);
        List<VariableInfo> outputVariables = AllVariableExtractor.extractOutputVariables(methodBody);

        //添加一个新的测试方法
        // 解析选定的方法
        MethodExtractor methodExtractor = new MethodExtractor();

        // 获取结束行号
        int methodEndLine = methodExtractor.getEndLine();

        // 修改方法名
        methodDeclaration.setName(methodDeclaration.getNameAsString() + "forGenerate");
        //去掉assert语句
        String newMethodCode = methodDeclaration.toString().replaceAll(".*assertEquals.*\n?", "").replaceAll(".*assertTrue.*\n?", "").replaceAll(".*assertFalse.*\n?", "");

        String replaceValue = null;
        for (VariableInfo variable : inputVariables) {
            String variableName = variable.getName();
            String variableType = variable.getType();
            switch (variableType) {
                case "int":
                    replaceValue = "DataGenerator.generateInteger(" + Integer.MIN_VALUE + ", " + Integer.MAX_VALUE + ")";
                    break;
                case "double":
                    replaceValue = "DataGenerator.generateDouble(" + Double.MIN_VALUE + ", " + Double.MAX_VALUE + ", 2)";
                    break;
                case "String":
                    replaceValue = "DataGenerator.generateString(\"[a-z]{5,10}\")";
                    break;
                case "byte":
                    replaceValue = "DataGenerator.generateDouble(" + Byte.MIN_VALUE + ", " + Byte.MAX_VALUE + ")";
                    break;
                case "short":
                    replaceValue = "DataGenerator.generateShort(" + Short.MIN_VALUE + ", " + Short.MAX_VALUE + ")";
                    break;
                case "long":
                    replaceValue = "DataGenerator.generateLong(" + Long.MIN_VALUE + ", " + Long.MAX_VALUE + ")";
                    break;
                case "float":
                    replaceValue = "DataGenerator.generateFloat(" + Float.MIN_VALUE + ", " + Float.MAX_VALUE + ", 2)";
                    break;
                case "char":
                    replaceValue = "DataGenerator.generateChar()";
                    break;
                case "boolean":
                    replaceValue = "DataGenerator.generateBoolean()";
                    break;
                case "int[]":
                    replaceValue = "DataGenerator.generateIntArray(1, 10, " + Integer.MIN_VALUE + ", " + Integer.MAX_VALUE + ")";
                    break;
                case "double[]":
                    replaceValue = "DataGenerator.generateDoubleArray(1, 10, " + Double.MIN_VALUE + ", " + Double.MAX_VALUE + ")";
                    break;
                case "String[]":
                    replaceValue = "DataGenerator.generateStringArray(1, 10, 5, 10)";
                    break;
                case "boolean[]":
                    replaceValue = "DataGenerator.generateBooleanArray(1, 10)";
                    break;
                case "char[]":
                    replaceValue = "DataGenerator.generateCharArray(1, 10, 'a', 'z')";
                    break;
                default:
                    break;
            }
            assert replaceValue != null;
            System.out.println(variableName);
            // 使用更严格的正则表达式来精确匹配变量名
            String regex = "\\b" + variableName + "\\b";
            newMethodCode = newMethodCode.replaceAll(regex, replaceValue);
            System.out.println(newMethodCode);
        }
        for (VariableInfo variable : outputVariables) {
            String variableName = variable.getName();
            int lastIndex = newMethodCode.lastIndexOf("}");
            if (lastIndex != -1) {
                newMethodCode = newMethodCode.substring(0, lastIndex) + newMethodCode.substring(lastIndex + 1);
            }
            newMethodCode += "\tDataGenerator.getOutput(" + variableName + ");\n}";
        }

        newMethodCode += "\n\t@Test\n\tpublic void testData(){\n\t\ttry {\n\t\t\tint iteration = 1100000;\n\t\t\tDataGenerator.init();\n\t\t\tfor (int i=0;i<iteration;i++){\n\t\t\t\t//put your test method invocation here.\n\t\t\t\t" + methodDeclaration.getNameAsString() + "();\n\t\t\t\tDataGenerator.finishTestCase();\n\t\t\t}\n\t\t}catch (Exception exception){\n\t\t\texception.printStackTrace();\n\t\t}finally {\n\t\t\tDataGenerator.close();\n\t\t}\n\t}";

        int insertOffset = editor.getDocument().getLineStartOffset(methodEndLine);

        if (methodEndLine != -1) {
            String insertText = "\n" + newMethodCode + "\n";
            Runnable runnable = () -> editor.getDocument().insertString(insertOffset, insertText);
            WriteCommandAction.runWriteCommandAction(e.getData(PlatformDataKeys.PROJECT), runnable);
            // 调用生成的测试方法
            assert project != null;
            runCurrentProject(project);
        }
    }

    public static int getOffset(String filePath, String searchString, int startOffset) {
        try {
            File file = new File(filePath);
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            int currentOffset = 0;

            while ((line = reader.readLine()) != null) {
                if (currentOffset >= startOffset) {
                    int index = line.indexOf(searchString);
                    if (index != -1) {
                        return currentOffset + index;  // 确认此处返回的偏移量是否正确
                    }
                }
                currentOffset += line.length() + System.lineSeparator().length();
            }
            reader.close();

            return -1;  // 如果找不到字符串，返回-1
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    private String getAbsoluteFilePath(Editor editor) {
        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile != null) {
            return virtualFile.getPath();
        }
        return null;
    }

    private void idea_parseEachJavaFileOfSpecificCommit(Document document, Project project, String newFilePath, int offset, String expression) {
        if (document == null) return;
        ExtractVariable extractVariable = new ExtractVariable(project, offset, newFilePath, expression);
        extractVariable.extractVariable();
    }

    private void writeToFile(String filePath, Document document, String content) {
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
            out.write(content);
            out.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
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
