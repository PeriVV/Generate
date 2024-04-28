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
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

public class AutoGenerationAction extends AnAction {

    // 内部类用于记录集合状态
    static class CollectionState {
        String variableName;
        int size;
        int position;

        public CollectionState(String variableName, int size, int position) {
            this.variableName = variableName;
            this.size = size;
            this.position = position;
        }
    }

    static class MethodCallDetail {
        String methodName;
        Set<String> arguments; // 使用 Set 而不是 List
        int position;

        public MethodCallDetail(String methodName, Set<String> arguments, int position) {
            this.methodName = methodName;
            this.arguments = arguments;
            this.position = position;
        }
    }

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

        // 这一步把断言中以表达式为输出的actual output提取为变量
        extractActualOutputAsVariables(project, absoluteJavaFilePath, document, methodCalls, startOffset);


        /*开始Combine*/
        final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

        if (psiFile == null) return;

        int offset = editor.getSelectionModel().getSelectionStart();
        PsiElement elementAt = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, false);

        if (method != null) {
            HashSet<PsiElement> visited = new HashSet<>();
            exploreElement(method, visited); // Explore the method for enums

            // 解析集合大小
            final Map<String, CombinedAction.CollectionState> collectionStates = new HashMap<>();
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitLocalVariable(PsiLocalVariable variable) {
                    super.visitLocalVariable(variable);
                    // Assume every local variable could be a collection with initial size 0
                    int position = variable.getTextRange().getStartOffset();
                    collectionStates.putIfAbsent(variable.getName(), new CombinedAction.CollectionState(variable.getName(), 0, position));
                }

                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    PsiReferenceExpression methodExpression = expression.getMethodExpression();
                    String methodName = methodExpression.getReferenceName();

                    // 获取调用方法的对象名称
                    PsiExpression qualifierExpression = methodExpression.getQualifierExpression();
                    if (qualifierExpression != null) {
                        String sourceVariableName = qualifierExpression.getText();
                        int position = expression.getTextRange().getStartOffset();

                        // 针对“copy”或“Copy”方法的特殊处理
                        if (methodName != null && (methodName.contains("copy") || methodName.contains("Copy"))) {
                            PsiElement parent = expression.getParent();

                            while (!(parent instanceof PsiDeclarationStatement) && parent != null) {
                                parent = parent.getParent();
                            }
                            if (parent != null) {
                                for (PsiElement element : ((PsiDeclarationStatement) parent).getDeclaredElements()) {
                                    if (element instanceof PsiLocalVariable) {
                                        PsiLocalVariable localVariable = (PsiLocalVariable) element;
                                        String newVariableName = localVariable.getName(); // 新集合的变量名
                                        PsiExpression[] arguments = expression.getArgumentList().getExpressions();
                                        if (arguments.length >= 2) {
                                            try {
                                                int start = Integer.parseInt(arguments[0].getText());
                                                int end = Integer.parseInt(arguments[1].getText());
                                                int count = Math.abs(end - start) + 1;
                                                collectionStates.put(newVariableName, new CombinedAction.CollectionState(newVariableName, count, position));
                                            } catch (NumberFormatException ex) {
                                                ex.printStackTrace();
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // 处理其他集合操作，如“add”、“remove”等，并在每次操作时输出
                            if (collectionStates.containsKey(sourceVariableName)) {
                                CombinedAction.CollectionState state = collectionStates.get(sourceVariableName);
                                if (methodName != null && (methodName.contains("add") || methodName.contains("push"))) {
                                    state.size++;
                                } else if (methodName != null && (methodName.contains("remove") || methodName.contains("pop") || methodName.contains("poll"))) {
                                    state.size = Math.max(0, state.size - 1);
                                } else if ("clear".equals(methodName)) {
                                    state.size = 0;
                                }
                                // 更新集合状态
                                collectionStates.put(sourceVariableName, new CombinedAction.CollectionState(sourceVariableName, state.size, position));
                                System.out.println("Variable '" + sourceVariableName + "' has " + state.size + " elements at position " + position);
                            }
                        }
                    }
                }

            });

            Map<String, Set<String>> argumentValuesByType = new HashMap<>();
            List<CombinedAction.MethodCallDetail> methodCalls1 = new ArrayList<>();

            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    PsiReferenceExpression methodExpression = expression.getMethodExpression();
                    String methodName = methodExpression.getReferenceName();
                    PsiExpression[] args = expression.getArgumentList().getExpressions();

                    if (methodName != null && methodName.startsWith("add")) {
                        for (PsiExpression arg : args) {
                            argumentValuesByType.computeIfAbsent(arg.getType().getPresentableText(), k -> new HashSet<>()).add(arg.getText());
                        }
                    }

                    if (methodName != null && methodName.startsWith("remove")) {
                        Set<String> relevantArguments = new HashSet<>(); // 使用 Set 而不是 List
                        for (PsiExpression arg : args) {
                            Set<String> possibleValues = argumentValuesByType.get(arg.getType().getPresentableText());
                            if (possibleValues != null) {
                                relevantArguments.addAll(possibleValues);
                            }
                        }
                        int position = expression.getTextRange().getStartOffset();
                        methodCalls1.add(new CombinedAction.MethodCallDetail(methodName, relevantArguments, position));
                    }
                }
            });

            // 输出remove方法调用的详细信息
            methodCalls1.forEach(call ->
                    System.out.println("Method Call: " + call.methodName + ", Arguments: " + String.join(", ", call.arguments) + ", Position: " + call.position));
        }
        /*结束Combine*/


        // 从选择的方法中提取输入和输出
        List<VariableInfo> inputVariables = AllVariableExtractor.extractInputVariables(methodBody);
        List<VariableInfo> outputVariables = AllVariableExtractor.extractOutputVariables(methodBody);

        // 修改方法名
        methodDeclaration.setName(methodDeclaration.getNameAsString() + "forGenerate");
        // 去掉assert语句
        String newMethodCode = methodDeclaration.toString().replaceAll(".*assertEquals.*\n?", "").replaceAll(".*assertTrue.*\n?", "").replaceAll(".*assertFalse.*\n?", "");

        String replaceValue = null;
        // 数组类型和相应的生成函数映射
        Map<String, String> arrayTypes = new HashMap<>();
        arrayTypes.put("double[]", "Double");
        arrayTypes.put("int[]", "Integer");
        arrayTypes.put("boolean[]", "Boolean");
        arrayTypes.put("String[]", "String");

        // 先处理数组
        for (VariableInfo variable : inputVariables) {
            String variableType = variable.getType();
            String regexPattern = "";  // 初始化正则表达式字符串

            if (variableType.endsWith("[]")) {
                if (arrayTypes.containsKey(variableType)) {
                    String generatorFunction = arrayTypes.get(variableType);
                    // 正确转义数组类型名称以用于正则表达式
                    regexPattern = "new\\s+" + variableType.replace("[]", "\\[\\]") + "\\s*\\{.*?\\}";
                    replaceValue = "DataGenerator.generate" + generatorFunction + "Array(1, 10, 1, 1000)";

                    // 替换数组初始化
                    newMethodCode = newMethodCode.replaceAll(regexPattern, replaceValue);
                }
            }
        }

        // 再处理非数组基本类型
        for (VariableInfo variable : inputVariables) {
            String variableName = variable.getName();
            String variableType = variable.getType();
            String regexPattern = "";

            if (!variableType.endsWith("[]")) {
                // 针对基本类型的处理，确保替换的是独立的变量或字面量，而非方法的一部分
                System.out.println("非数组的变量：" + variableName);
                regexPattern = "(?<!\\.)\\b" + Pattern.quote(variableName) + "\\b(?![\\w.])";
                System.out.println(regexPattern);
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
                        replaceValue = "DataGenerator.generateByte(" + Byte.MIN_VALUE + ", " + Byte.MAX_VALUE + ")";
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
                }

                if (replaceValue != null && !regexPattern.isEmpty()) {
                    newMethodCode = newMethodCode.replaceAll(regexPattern, replaceValue);
                }
            }
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

        int endOffset = findMethodEndOffset(document, startOffset);

        if (endOffset != -1) {
            String insertText = "\n" + newMethodCode + "\n";
            Runnable runnable = () -> editor.getDocument().insertString(endOffset, insertText);
            WriteCommandAction.runWriteCommandAction(project, runnable);
            // 调用生成的测试方法
            assert project != null;
            runCurrentProject(project);
        }
    }

    private void extractActualOutputAsVariables(Project project, String absoluteJavaFilePath, Document document, List<MethodCallExpr> methodCalls, int startOffset) {
        // 在进行任何更改之前保存所有文档
        FileDocumentManager.getInstance().saveAllDocuments();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            // 这个代码块负责把断言中的真实输出提取为变量
            for (MethodCallExpr methodCall : methodCalls) {
                String methodName = methodCall.getNameAsString();
                if (ASSERTION_METHODS.contains(methodName)) {
                    List<Expression> arguments = methodCall.getArguments();
                    for (Expression argument : arguments) {
                        // 判断表达式类型
                        if (argument instanceof MethodCallExpr) {
                            String expression = argument.toString();

                            // 然后将 startOffset 传递给 getOffset 方法
                            int offset = getOffset(absoluteJavaFilePath, expression, startOffset);
                            if (offset != -1) {  // 确保找到了有效的偏移量
                                // 根据光标位置替换
                                idea_parseEachJavaFileOfSpecificCommit(document, project, absoluteJavaFilePath, offset, expression);
                            } else {
                            }
                        }
                    }
                }
            }

            writeToFile(absoluteJavaFilePath, document, document.getText());

            // 保存文档
            FileDocumentManager.getInstance().saveDocument(document);
            // 同步文件系统，以立即呈现更改的内容
            VirtualFileManager.getInstance().syncRefresh();
        });
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

    private int findMethodEndOffset(Document document, int startOffset) {
        CharSequence text = document.getCharsSequence();
        int depth = 0;
        for (int i = startOffset; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                depth++;
            } else if (text.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;  // If no valid end found
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


    private void exploreElement(PsiElement element, Set<PsiElement> visited) {
        if (element == null || visited.contains(element)) {
            return;
        }
        visited.add(element);

        if (element instanceof PsiReferenceExpression) {
            PsiElement resolvedElement = ((PsiReferenceExpression) element).resolve();
            if (resolvedElement instanceof PsiEnumConstant) {
                reportEnumUsage((PsiEnumConstant) resolvedElement);
            }
        }

        for (PsiElement child : element.getChildren()) {
            exploreElement(child, visited);
        }
    }

    private void reportEnumUsage(PsiEnumConstant enumConstant) {
        PsiClass enumClass = enumConstant.getContainingClass();
        if (enumClass != null) {
            System.out.println("Enum value used: " + enumClass.getName() + "." + enumConstant.getName());
            System.out.println("All enum values in " + enumClass.getName() + ":");
            for (PsiField field : enumClass.getFields()) {
                if (field instanceof PsiEnumConstant) {
                    System.out.println(" - " + field.getName());
                }
            }
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
