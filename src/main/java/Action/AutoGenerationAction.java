package Action;

import MyUtils.*;
import Service.ChatGPTRequester;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.google.gson.Gson;
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
import org.json.JSONObject;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private List<CollectionState> collectionStatesList = new ArrayList<>();

    public void actionPerformed(@NotNull AnActionEvent e) {
        final Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        assert editor != null;
        assert project != null;

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
        if (methodBody == null) return;

        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(methodBody);

        String absoluteJavaFilePath = getAbsoluteFilePath(editor);
        Document document = Editors.getCurrentDocument(absoluteJavaFilePath);

        BlockStmt methods = methodDeclaration.getBody().get();
        List<MethodCallExpr> methodCalls = methods.findAll(MethodCallExpr.class);

        final int startOffset = editor.getSelectionModel().getSelectionStart();

        // 这一步把断言中以表达式为输出的actual output提取为变量
        extractActualOutputAsVariables(project, absoluteJavaFilePath, document, methodCalls, startOffset);

        // 在进行变量提取或进一步操作之前，提交所有文档更改
        PsiDocumentManager.getInstance(project).commitDocument(document);

        /*开始Combine*/
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) return;

        int offset = editor.getSelectionModel().getSelectionStart();
        PsiElement elementAt = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, false);

        PsiDocumentManager.getInstance(project).commitAllDocuments();

        // 解析集合大小
        final Map<String, CollectionState> collectionStates = new HashMap<>();

        if (method != null) {
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitLocalVariable(PsiLocalVariable variable) {
                    super.visitLocalVariable(variable);
                    // Assume every local variable could be a collection with initial size 0
                    int position = variable.getTextRange().getStartOffset();
                    collectionStates.putIfAbsent(variable.getName(), new CollectionState(variable.getName(), 0, position));
                }

                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    PsiReferenceExpression methodExpression = expression.getMethodExpression();
                    String methodName = methodExpression.getReferenceName();
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
                                                collectionStates.put(newVariableName, new CollectionState(newVariableName, count, position));
                                            } catch (NumberFormatException ex) {
                                                ex.printStackTrace();
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            if (collectionStates.containsKey(sourceVariableName)) {
                                CollectionState state = collectionStates.get(sourceVariableName);
                                if (methodName != null && (methodName.contains("add") || methodName.contains("push"))) {
                                    state.size++;
                                } else if (methodName != null && (methodName.contains("remove") || methodName.contains("pop") || methodName.contains("poll"))) {
                                    state.size = Math.max(0, state.size - 1);
                                } else if ("clear".equals(methodName)) {
                                    state.size = 0;
                                }
                                // 更新集合状态
                                collectionStates.put(sourceVariableName, new CollectionState(sourceVariableName, state.size, position));

                                // 存储选中方法中每个状态到列表
                                collectionStatesList.add(new CollectionState(sourceVariableName, state.size, position));
                            }
                        }
                    }
                }
            });

            System.out.println("Printing all collection states:");
            for (CollectionState state : collectionStatesList) {
                System.out.println("Variable Name: " + state.variableName +
                        ", Size: " + state.size +
                        ", Position: " + state.position);
            }

            System.out.println("collectionStates.size(): " + collectionStates.size());
            Map<String, Set<String>> argumentValuesByType = new HashMap<>();
            List<MethodCallDetail> methodCalls1 = new ArrayList<>();

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
                        methodCalls1.add(new MethodCallDetail(methodName, relevantArguments, position));
                    }
                }
            });

            // 输出remove方法调用的详细信息
            methodCalls1.forEach(call ->
                    System.out.println("Method Call: " + call.methodName + ", Arguments: " + String.join(", ", call.arguments) + ", Position: " + call.position));
        }


        /*结束Combine*/

        // 在进行变量提取或进一步操作之前，提交所有文档更改
        PsiDocumentManager.getInstance(project).commitDocument(document);


        // 确定选中的方法
        PsiMethod selectedMethod = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, false);
        if (selectedMethod == null) return;

        // 获取方法的起始和结束位置
        int methodStartOffset = selectedMethod.getTextRange().getStartOffset();
        // 从选择的方法中提取输入和输出
        List<VariableInfo> inputVariables = AllVariableExtractor.extractInputVariables(methodBody, methodStartOffset);
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
        for (
                VariableInfo variable : inputVariables) {
            String variableType = variable.getType();
            String regexPattern = "";  // 初始化正则表达式字符串

            if (variableType.endsWith("[]")) {
                if (arrayTypes.containsKey(variableType)) {
                    String generatorFunction = arrayTypes.get(variableType);
                    // 正确转义数组类型名称以用于正则表达式
                    regexPattern = "new\\s+" + variableType.replace("[]", "\\[\\]") + "\\s*\\{.*?\\}";
                    replaceValue = "DataGenerator.generate" + generatorFunction + "Array(0, 10, -1, 3)";

                    // 替换数组初始化
                    newMethodCode = newMethodCode.replaceAll(regexPattern, replaceValue);
                }
            }
            // 在进行变量提取或进一步操作之前，提交所有文档更改
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }
        // 声明start和end变量
        String variableDeclarations = "int start = 0;\nint end = 0;\n";
        // 找到第一个大括号的位置
        int firstBraceIndex = newMethodCode.indexOf('{');
        if (firstBraceIndex == -1) {
            throw new IllegalArgumentException("Method code does not contain an opening brace");
        }
        newMethodCode = newMethodCode.substring(0, firstBraceIndex + 1) + "\n" + variableDeclarations + newMethodCode.substring(firstBraceIndex + 1);
        // 再处理非数组基本类型
        for (VariableInfo variable : inputVariables) {
            String variableName = variable.getName();
            String variableType = variable.getType();
            String regexPattern = "";

            if (!variableType.endsWith("[]")) {

                // 针对基本类型的处理，确保替换的是独立的变量或字面量，而非方法的一部分
//                System.out.println("非数组的变量：" + variableName);
                regexPattern = "(?<!\\w|\\.)\\Q" + variableName + "\\E(?!\\w|\\.)";

                if (variableType.equals("int") && isLiteralUsedAsIndex(variableName, method)) {
                    int literalPosition = variable.getStartPosition();
//                    System.out.println("这是索引字面量的偏移量：" + literalPosition);
                    String arrayName = findArrayByPosition(literalPosition, method);
//                    System.out.println("这是索引字面量属于的数组：" + arrayName);
                    // 获取最接近的数组大小
                    int arraySize = findClosestSizeBeforePosition(literalPosition, arrayName);
                    if (arraySize != -1 && isLiteralUsedAsStart(variableName, method)) {
                        replaceValue = "int start = DataGenerator.generateInteger(0, " + (arraySize - 1) + ");";
                        newMethodCode = newMethodCode.replace("int start = 0;", replaceValue);
                        replaceValue = "start";
                        newMethodCode = newMethodCode.replaceAll(regexPattern, replaceValue);
                    }
                    if (arraySize != -1 && isLiteralUsedAsEnd(variableName, method)) {
                        System.out.println("我被识别为end啦。" + regexPattern);
                        replaceValue = "int end = DataGenerator.generateInteger(start, " + (arraySize - 1) + ");";
                        newMethodCode = newMethodCode.replace("int end = 0;", replaceValue);
                        replaceValue = "end";
                        newMethodCode = newMethodCode.replaceAll(regexPattern, replaceValue);
                    }
                    if (arraySize != -1) {
                        replaceValue = "DataGenerator.generateInteger(0, " + (arraySize - 1) + ")";
                        // 进行文本替换
                        newMethodCode = newMethodCode.replaceAll(regexPattern, replaceValue);
                    }

                } else if (variableType.equals("int") && isLiteralUsedAsYear(variableName, method)) {
                    replaceValue = "DataGenerator.generateInteger(-1, 2024)";
                } else if (variableType.equals("int") && isLiteralUsedAsMonth(variableName, method)) {
                    replaceValue = "DataGenerator.generateInteger(-1, 12)";
                } else if (variableType.equals("int") && isLiteralUsedAsDay(variableName, method)) {
                    replaceValue = "DataGenerator.generateInteger(-1, 31)";
                } else if (variableType.equals("int") && isLiteralUsedAsHour(variableName, method)) {
                    replaceValue = "DataGenerator.generateInteger(-1, 23)";
                } else if (variableType.equals("int") && isLiteralUsedAsMinute(variableName, method)) {
                    replaceValue = "DataGenerator.generateInteger(-1, 59)";
                } else if (variableType.equals("int") && isLiteralUsedAsSecond(variableName, method)) {
                    replaceValue = "DataGenerator.generateInteger(-1, 59)";
                } else {
                    replaceValue = switch (variableType) {
                        case "int" ->
                                "DataGenerator.generateInteger(" + Integer.MIN_VALUE + "," + Integer.MAX_VALUE + ")";
                        case "double" ->
                                "DataGenerator.generateDouble(" + Double.MIN_VALUE + "," + Double.MAX_VALUE + ", 2)";
                        case "String" -> "DataGenerator.generateString(\"[a-zA-Z0-9]{0,5}\")";
                        case "byte" -> "DataGenerator.generateByte(" + Byte.MIN_VALUE + ", " + Byte.MAX_VALUE + ")";
                        case "short" -> "DataGenerator.generateShort(" + Short.MIN_VALUE + ", " + Short.MAX_VALUE + ")";
                        case "long" ->
                                "DataGenerator.generateLong(" + Integer.MIN_VALUE + ", " + Integer.MAX_VALUE + ")";
                        case "float" ->
                                "DataGenerator.generateFloat(" + Float.MIN_VALUE + ", " + Float.MAX_VALUE + ", 2)";
                        case "char" -> "DataGenerator.generateChar()";
                        case "boolean" -> "DataGenerator.generateBoolean()";
                        default -> replaceValue;
                    };
                }
            }
            if (replaceValue != null && !regexPattern.isEmpty()) {
                newMethodCode = newMethodCode.replaceAll(regexPattern, replaceValue);
            }
        }

        for (
                VariableInfo variable : outputVariables) {
            String variableName = variable.getName();
            int lastIndex = newMethodCode.lastIndexOf("}");
            if (lastIndex != -1) {
                newMethodCode = newMethodCode.substring(0, lastIndex) + newMethodCode.substring(lastIndex + 1);
            }
            newMethodCode += "\tDataGenerator.getOutput(" + variableName + ");\n}";
        }

        newMethodCode += "\n\t@Test\n\tpublic void testData(){\n\t\ttry {\n\t\t\tint iteration = 1100000;\n\t\t\tDataGenerator.init();\n\t\t\tfor (int i=0;i<iteration;i++){\n\t\t\t\t//put your test method invocation here.\n\t\t\t\t" + "\n\t\ttry {\n\t\t\t" + methodDeclaration.getNameAsString() + "();\n\t\t} catch (Exception exception) {\n\t\t\tDataGenerator.getOutput(\"ERROR\");\n\t\t\t}\n\t\t\t\tDataGenerator.finishTestCase();\n\t\t\t}\n\t\t}catch (Exception exception){\n\t\t\texception.printStackTrace();\n\t\t}finally {\n\t\t\tDataGenerator.close();\n\t\t}\n\t}";


        String finalNewMethodCode = newMethodCode;
        WriteCommandAction.runWriteCommandAction(project, () ->

        {
            int endOffset = findMethodEndOffset(document, startOffset);
            if (endOffset != -1) {
                String insertText = "\n" + finalNewMethodCode + "\n";
                document.insertString(endOffset, insertText);
            }
        });

        psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);

        // 确定新代码的偏移量
        int newCodeStartOffset = findMethodEndOffset(document, startOffset) + 1;
        int newCodeEndOffset = newCodeStartOffset + newMethodCode.length();

        if (psiFile != null) {
            PsiElement newCodeElement = PsiTreeUtil.findElementOfClassAtRange(psiFile, newCodeStartOffset, newCodeEndOffset, PsiElement.class);
            Set<TextReplacement> replacements = new HashSet<>();

            if (newCodeElement != null) {
                newCodeElement.accept(new JavaRecursiveElementVisitor() {
                    @Override
                    public void visitElement(PsiElement element) {
                        super.visitElement(element);
                        if (element instanceof PsiJavaCodeReferenceElement) {
                            PsiElement resolved = ((PsiJavaCodeReferenceElement) element).resolve();
                            if (resolved instanceof PsiEnumConstant) {
                                PsiEnumConstant enumConstant = (PsiEnumConstant) resolved;
                                PsiClass enumClass = enumConstant.getContainingClass();
                                if (enumClass != null && enumClass.isEnum()) {
                                    String enumClassName = enumClass.getName();  // 获取枚举类的名字
                                    List<String> enumNames = Stream.of(enumClass.getFields())
                                            .filter(f -> f instanceof PsiEnumConstant)
                                            .map(PsiField::getName)
                                            .map(name -> "\"" + name + "\"")
                                            .collect(Collectors.toList());
                                    String replacementText = enumClassName + ".valueOf(DataGenerator.generateEnumConstantFromList(Arrays.asList(" + String.join(", ", enumNames) + ")))";
                                    replacements.add(new TextReplacement(element.getTextRange(), replacementText));
                                }
                            }
                        }
                    }
                });
            }

            // 替换文本，从后往前以避免偏移量问题
            List<TextReplacement> sortedReplacements = new ArrayList<>(replacements);
            sortedReplacements.sort(Comparator.comparingInt(a -> -a.getRange().getStartOffset()));

            WriteCommandAction.runWriteCommandAction(project, () -> {
                for (TextReplacement replacement : sortedReplacements) {
                    document.replaceString(replacement.getRange().getStartOffset(), replacement.getRange().getEndOffset(), replacement.getReplacementText());
                }
                // 提交更改并刷新
                PsiDocumentManager.getInstance(project).commitDocument(document);
                VirtualFileManager.getInstance().syncRefresh();
            });
        }

        // 调用生成的测试方法
        runCurrentProject(project);

    }

    private boolean isLiteralUsedAsIndex(String variableName, PsiMethod method) {
        final boolean[] isIndexUsed = {false};
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiExpression[] arguments = expression.getArgumentList().getExpressions();
                PsiMethod calledMethod = expression.resolveMethod();
                if (calledMethod != null) {
                    PsiParameter[] parameters = calledMethod.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i < arguments.length) {
                            PsiExpression arg = arguments[i];
                            if (arg instanceof PsiLiteralExpression && arg.getText().equals(variableName)) {
                                PsiParameter param = parameters[i];
                                String lowerCase = param.getName().toLowerCase();
                                if (lowerCase.contains("index") || lowerCase.contains("start") || lowerCase.contains("end")) {
                                    isIndexUsed[0] = true;
                                }
                            }
                        }
                    }
                }
            }
        });
        return isIndexUsed[0];
    }

    private boolean isLiteralUsedAsStart(String variableName, PsiMethod method) {
        final boolean[] isIndexUsed = {false};
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiExpression[] arguments = expression.getArgumentList().getExpressions();
                PsiMethod calledMethod = expression.resolveMethod();
                if (calledMethod != null) {
                    PsiParameter[] parameters = calledMethod.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i < arguments.length) {
                            PsiExpression arg = arguments[i];
                            if (arg instanceof PsiLiteralExpression && arg.getText().equals(variableName)) {
                                PsiParameter param = parameters[i];
                                String lowerCase = param.getName().toLowerCase();
                                if (lowerCase.contains("start")) {
                                    isIndexUsed[0] = true;
                                }
                            }
                        }
                    }
                }
            }
        });
        return isIndexUsed[0];
    }

    private boolean isLiteralUsedAsEnd(String variableName, PsiMethod method) {
        final boolean[] isIndexUsed = {false};
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiExpression[] arguments = expression.getArgumentList().getExpressions();
                PsiMethod calledMethod = expression.resolveMethod();
                if (calledMethod != null) {
                    PsiParameter[] parameters = calledMethod.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i < arguments.length) {
                            PsiExpression arg = arguments[i];
                            if (arg instanceof PsiLiteralExpression && arg.getText().equals(variableName)) {
                                PsiParameter param = parameters[i];
                                String lowerCase = param.getName().toLowerCase();
                                if (lowerCase.contains("end")) {
                                    isIndexUsed[0] = true;
                                }
                            }
                        }
                    }
                }
            }
        });
        return isIndexUsed[0];
    }

    private List<IndexUsageInfo> getArrayUsageInfo(PsiMethod method) {
        List<IndexUsageInfo> usageInfos = new ArrayList<>();
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiExpression[] arguments = expression.getArgumentList().getExpressions();
                PsiMethod calledMethod = expression.resolveMethod();

                if (calledMethod != null) {
                    PsiParameter[] parameters = calledMethod.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i < arguments.length) {
                            PsiExpression arg = arguments[i];
                            PsiParameter param = parameters[i];
                            String paramName = param.getName().toLowerCase();

                            if (paramName.contains("index") || paramName.contains("start") || paramName.contains("end")) {
                                PsiExpression qualifierExpression = expression.getMethodExpression().getQualifierExpression();
                                if (qualifierExpression != null && arg instanceof PsiLiteralExpression) {
                                    String arrayName = qualifierExpression.getText();
                                    String indexValue = arg.getText();
                                    int position = arg.getTextOffset();
                                    IndexUsageInfo info = new IndexUsageInfo(indexValue, arrayName, position);
                                    usageInfos.add(info);
                                }
                            }
                        }
                    }
                }
            }
        });
        return usageInfos;
    }

    private String findArrayByPosition(int position, PsiMethod method) {
        List<IndexUsageInfo> indexInfos = getArrayUsageInfo(method); // 假设method是已经定义好的PsiMethod对象
        for (IndexUsageInfo info : indexInfos) {
            // 允许±2的偏差
            if (Math.abs(info.getPosition() - position) <= 2) {
                return info.getArrayName();
            }
        }
        return null; // 如果没有找到对应的数组，返回null
    }


    private int findClosestSizeBeforePosition(int position, String arrayName) {
        int closestSize = -1;
        int closestPosition = -1;

        for (CollectionState state : collectionStatesList) {
            if (state.variableName.equals(arrayName) && state.position <= position) {
                if (closestPosition == -1 || (position - state.position < position - closestPosition)) {
                    closestPosition = state.position;
                    closestSize = state.size;
                }
            }
        }

        return closestSize;
    }


    // 通用的日期单位检测函数
    private boolean isLiteralUsedForDateUnit(String variableName, PsiMethod method, String[] keywords) {
        final boolean[] isUnitUsed = {false};

        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                inspectArguments(expression.getArgumentList().getExpressions(), expression.resolveMethod());
            }

            @Override
            public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                inspectArguments(expression.getArgumentList().getExpressions(), expression.resolveConstructor());
            }

            private void inspectArguments(PsiExpression[] arguments, PsiMethod method) {
                if (method != null) {
                    PsiParameter[] parameters = method.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i < arguments.length) {
                            PsiExpression arg = arguments[i];
                            PsiParameter param = parameters[i];
                            String paramNameLowerCase = param.getName().toLowerCase();

                            // 检查形参名称是否包含目标关键字
                            boolean matchesKeyword = false;
                            for (String keyword : keywords) {
                                if (paramNameLowerCase.contains(keyword.toLowerCase())) {
                                    matchesKeyword = true;
                                    break;
                                }
                            }

                            // 检查与指定变量名匹配的字面量
                            if (matchesKeyword && arg instanceof PsiLiteralExpression && arg.getText().equals(variableName)) {
                                isUnitUsed[0] = true;
                            }
                        }
                    }
                }
            }
        });

        return isUnitUsed[0];
    }

    // 检查字面量是否作为年份参数使用
    private boolean isLiteralUsedAsYear(String variableName, PsiMethod method) {
        return isLiteralUsedForDateUnit(variableName, method, new String[]{"year"});
    }

    private boolean isLiteralUsedAsMonth(String variableName, PsiMethod method) {
        return isLiteralUsedForDateUnit(variableName, method, new String[]{"month"});
    }

    private boolean isLiteralUsedAsDay(String variableName, PsiMethod method) {
        return isLiteralUsedForDateUnit(variableName, method, new String[]{"day"});
    }

    private boolean isLiteralUsedAsHour(String variableName, PsiMethod method) {
        return isLiteralUsedForDateUnit(variableName, method, new String[]{"hour"});
    }

    private boolean isLiteralUsedAsMinute(String variableName, PsiMethod method) {
        return isLiteralUsedForDateUnit(variableName, method, new String[]{"minute"});
    }

    private boolean isLiteralUsedAsSecond(String variableName, PsiMethod method) {
        return isLiteralUsedForDateUnit(variableName, method, new String[]{"second"});
    }

    private void extractActualOutputAsVariables(Project project, String absoluteJavaFilePath, Document document, List<MethodCallExpr> methodCalls, int startOffset) {
        // 在进行任何更改之前保存所有文档
        FileDocumentManager.getInstance().saveAllDocuments();
        // 这个代码块负责把断言中的真实输出提取为变量
        for (MethodCallExpr methodCall : methodCalls) {
            String methodName = methodCall.getNameAsString();
            if (ASSERTION_METHODS.contains(methodName)) {
                List<Expression> arguments = methodCall.getArguments();
                for (Expression argument : arguments) {
                    // 判断表达式类型

                    String expression = argument.toString();
                    // 然后将 startOffset 传递给 getOffset 方法
                    int offset = getOffset(absoluteJavaFilePath, expression, startOffset);
                    if (offset != -1) {  // 确保找到了有效的偏移量
                        // 根据光标位置替换
                        idea_parseEachJavaFileOfSpecificCommit(document, project, absoluteJavaFilePath, offset, expression);
                    } else {
//                        System.out.println("未找到有效偏移量");
                    }
                }
            }
        }
        writeToFile(absoluteJavaFilePath, document, document.getText());
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


    public String generateStringWithOpenAI(String string) {
        System.out.println("传进来的参数是：" + string);
        // 你可以在这里设置你想要请求的具体问题或者指令
        String prompt = "This is a string literal in the test case:" + string.replaceAll("\"", "") + ". Please generate a regular expression for me. I can randomly generate strings based on the regular expression. Please use [0-9] to represent numbers. Your reply only returns the regular expression, no other content is needed.";

        String response = null;
        try {
            System.out.println("prompt是：" + prompt);
            response = ChatGPTRequester.getRegex(prompt);
            System.out.println("Generated Regex: " + response);
            // 这里处理得到的结果，例如显示到用户界面或进一步处理数据
        } catch (IOException ex) {
            System.err.println("Error while calling ChatGPT API: " + ex.getMessage());
            // 在这里处理可能的错误情况，例如网络错误或API服务问题
        }

        // 提取 content 字段的值
        String extractedContent = extractContentWithJSON(response);

        // 需要进一步转义反斜杠
        String escapedRegex = extractedContent.replace("\\", "\\\\\\\\");
        System.out.println("Extracted Content: " + escapedRegex);


        return escapedRegex;
    }

    public static String extractContentWithJSON(String response) {
        try {
            JSONObject jsonResponse = new JSONObject(response);
            String content = jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            return content;
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
            return "Error in JSON parsing";
        }
    }
}
