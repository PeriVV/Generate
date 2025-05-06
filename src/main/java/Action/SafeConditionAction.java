package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;
import java.util.regex.Pattern;

public class SafeConditionAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

        if (psiFile == null) return;

        int offset = editor.getSelectionModel().getSelectionStart();
        PsiElement elementAt = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, false);

        if (method != null) {
            System.out.println("Method found: " + method.getName());  // 调试：打印找到的方法，成功输出
            Map<String, HashSet<String>> conditionsMap = new HashMap<>();
            trackLiteralArguments(method, new HashSet<>(), 1, conditionsMap);
            conditionsMap.forEach((literal, conditions) -> {
                System.out.println("Parameter value: " + literal);
                conditions.forEach(condition -> System.out.println("Safe condition: " + condition));
            });
        } else {
            System.out.println("No method found at the current cursor position");
        }
    }

    private void trackLiteralArguments(PsiElement element, Set<PsiElement> visited, int currentDepth, Map<String, HashSet<String>> conditionsMap) {
        if (currentDepth > 3 || visited.contains(element)) return;
        visited.add(element);

//        System.out.println("Visiting element:" + element.getText());
        if (element instanceof PsiMethodCallExpression) {
            System.out.println("Method call: " + element.getText());
        } else if (element instanceof PsiNewExpression) {
            System.out.println("New expression: " + element.getText());
        } else {
            System.out.println("Not a method call or new expression: " + element.getText());
        }


        if (element instanceof PsiMethodCallExpression || element instanceof PsiNewExpression) {
            PsiMethod calledMethod = (element instanceof PsiMethodCallExpression)
                    ? ((PsiMethodCallExpression) element).resolveMethod()
                    : ((PsiNewExpression) element).resolveConstructor();
            System.out.println("我到这里了哦！！！！！！！！！！1");
//            System.out.println(calledMethod.toString());
            if (calledMethod == null) {
                System.out.println("Could not resolve method for element: " + element.getText());
            } else {
                System.out.println("Resolved method: " + calledMethod.getName());
            }


            if (calledMethod != null) {
                System.out.println("我到这里了哦！！！！！！！！！！2");

                calledMethod.getParameterList();
                PsiClass containingClass = calledMethod.getContainingClass();
                if (containingClass != null) {
                    Map<String, String> constants = extractConstants(containingClass);
                    System.out.println("我到这里了哦！！3");
                    // 添加调试输出，打印所有提取的常量
                    if (!constants.isEmpty()) {
                        System.out.println("Constants in class " + containingClass.getName() + ":");
                        constants.forEach((name, value) -> System.out.println("Constant: " + name + " = " + value));  // 调试：打印提取的常量
                    } else {
                        System.out.println("No constants found in class: " + containingClass.getName());
                    }

                    PsiExpression[] args = element instanceof PsiMethodCallExpression
                            ? ((PsiMethodCallExpression) element).getArgumentList().getExpressions()
                            : Objects.requireNonNull(((PsiNewExpression) element).getArgumentList()).getExpressions();
                    PsiParameter[] parameters = calledMethod.getParameterList().getParameters();

                    if (args.length == 0) {
                        System.out.println("Error: No arguments found for method call.");
                    }

                    for (int i = 0; i < args.length; i++) {
                        PsiExpression arg = args[i];
                        if (arg instanceof PsiLiteralExpression && i < parameters.length) {
                            String paramName = parameters[i].getName();
                            String literalValue = paramName + ": " + arg.getText();
                            System.out.println("Found literal argument: " + literalValue);  // 调试：打印找到的字面量参数
                            List<String> conditions = extractConditions(calledMethod, literalValue, conditionsMap, constants);
                            parseConditions(conditions);
                        }
                    }
                }
                trackLiteralArguments(calledMethod, new HashSet<>(visited), currentDepth + 1, conditionsMap);
            }
        }

        for (PsiElement child : element.getChildren()) {
            trackLiteralArguments(child, visited, currentDepth, conditionsMap);
        }
    }

    private void extractFieldsFromClass(PsiClass psiClass, Map<String, String> constantValues, Set<String> processedClasses) {
        if (psiClass == null || processedClasses.contains(psiClass.getQualifiedName())) {
            System.out.println("Error: psiClass is null or already processed.");
            return;
        }

        processedClasses.add(psiClass.getQualifiedName());

        String className = psiClass.getName();  // 获取简短类名
        if (className == null) {
            System.out.println("Error: Class name is null.");
            return;
        }
        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
                PsiExpression initializer = field.getInitializer();
                String valueText = extractValue(initializer);
                if (valueText != null) {
                    String name = className + "." + field.getName();
                    constantValues.put(name, valueText);
                    System.out.println("Found constant: " + name + " = " + valueText);  // 调试：打印每个常量
                }
            }
        }
    }

    private String extractValue(PsiExpression expression) {
        if (expression == null) {
            System.out.println("Error: expression is null.");
            return null;
        }

        if (expression instanceof PsiLiteralExpression) {
            String value = expression.getText();
            System.out.println("Extracted value: " + value);  // 调试：打印提取的常量值
            return value;
        } else if (expression instanceof PsiPrefixExpression) {
            PsiPrefixExpression prefixExpr = (PsiPrefixExpression) expression;
            PsiExpression operand = prefixExpr.getOperand();
            if (operand instanceof PsiLiteralExpression && prefixExpr.getOperationSign().getText().equals("-")) {
                String value = "-" + operand.getText();
                System.out.println("Extracted negative value: " + value);  // 调试：打印负数常量值
                return value;
            }
        }
        return null;
    }


    private Map<String, String> extractConstants(PsiClass psiClass) {
        if (psiClass == null) {
            System.out.println("Error: psiClass is null.");
            return Collections.emptyMap();
        }
        Map<String, String> constantValues = new HashMap<>();
        Set<String> processedClasses = new HashSet<>();

        extractFieldsFromClass(psiClass, constantValues, processedClasses);
        // 打印当前类中提取到的常量
        if (constantValues.isEmpty()) {
            System.out.println("Error: No constants found in class " + psiClass.getName());
        } else {
            constantValues.forEach((name, value) -> System.out.println("Constant: " + name + " = " + value));  // 调试：打印提取的常量
        }

        PsiReferenceExpression[] references = PsiTreeUtil.collectElementsOfType(psiClass, PsiReferenceExpression.class).toArray(new PsiReferenceExpression[0]);
        for (PsiReferenceExpression reference : references) {
            PsiElement resolvedElement = reference.resolve();
            if (resolvedElement instanceof PsiField) {
                PsiField field = (PsiField) resolvedElement;
                if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
                    PsiClass containingClass = field.getContainingClass();
                    if (containingClass != null) {
                        extractFieldsFromClass(containingClass, constantValues, processedClasses);
                    }
                }
            }
        }

        return constantValues;
    }

    private boolean containsThrowStatement(PsiIfStatement ifStmt) {
        PsiStatement[] thenStatements = null;
        if (ifStmt.getThenBranch() instanceof PsiBlockStatement) {
            thenStatements = ((PsiBlockStatement) ifStmt.getThenBranch()).getCodeBlock().getStatements();
        } else if (ifStmt.getThenBranch() != null) {
            thenStatements = new PsiStatement[]{ifStmt.getThenBranch()};
        }

        if (thenStatements != null) {
            for (PsiStatement stmt : thenStatements) {
                if (stmt instanceof PsiThrowStatement) {
                    return true;
                }
            }
        }
        return false;
    }

    private String replaceConstantsInCondition(String conditionText, Map<String, String> constants) {
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            String fullFieldName = entry.getKey();
            String fieldValue = entry.getValue();
            // 处理全限定名的匹配，确保前后可以是空格、操作符或表达式的起始/结束
            String regex = "(?<=\\s|\\W|^)" + Pattern.quote(fullFieldName) + "(?=\\s|\\W|$)";
            conditionText = conditionText.replaceAll(regex, fieldValue);
        }
        return conditionText;
    }


    private List<String> extractConditions(PsiMethod method, String literalValue, Map<String, HashSet<String>> conditionsMap, Map<String, String> constants) {
        List<String> conditions = new ArrayList<>();
        if (method.getBody() != null) {
            for (PsiStatement statement : method.getBody().getStatements()) {
                if (statement instanceof PsiIfStatement) {
                    PsiIfStatement ifStmt = (PsiIfStatement) statement;
                    if (containsThrowStatement(ifStmt)) {
                        PsiExpression condition = ifStmt.getCondition();
                        String conditionText = condition.getText();
                        System.out.println("Original condition: " + conditionText);  // 调试：打印原始条件

                        // 替换常量
                        conditionText = replaceConstantsInCondition(conditionText, constants);
                        System.out.println("Condition after constant replacement: " + conditionText);  // 调试：打印替换后的条件

                        // 反转条件来表示非异常的情况
                        String negatedCondition = negateCondition(conditionText);
                        conditionsMap.computeIfAbsent(literalValue, k -> new HashSet<>()).add(negatedCondition);
                        conditions.add(negatedCondition);
                        System.out.println("Negated condition: " + negatedCondition);  // 调试：打印反转后的条件
                    }
                }
            }
        }
        return conditions;
    }

    private String negateCondition(String condition) {
        System.out.println("Original condition before negation: " + condition);  // 调试：打印条件反转前的内容

        // 反转操作
        // 使用简单的字符串替换来处理常见的比较运算符
        condition = condition.replaceAll(">", " tempGT ");
        condition = condition.replaceAll("<", " tempLT ");
        condition = condition.replaceAll(">=", " tempGE ");
        condition = condition.replaceAll("<=", " tempLE ");

        // 替换回正确的运算符
        condition = condition.replaceAll(" tempGT ", "<=");
        condition = condition.replaceAll(" tempLT ", ">=");
        condition = condition.replaceAll(" tempGE ", "<");
        condition = condition.replaceAll(" tempLE ", ">");

        // 处理逻辑运算符
        condition = condition.replaceAll("\\|\\|", " tempOr ");
        condition = condition.replaceAll("&&", " tempAnd ");

        condition = condition.replaceAll(" tempOr ", " && ");
        condition = condition.replaceAll(" tempAnd ", " || ");

        System.out.println("Condition after negation: " + condition);  // 调试：打印反转后的条件
        return condition;
    }

    public static Map<String, String> parseConditions(List<String> conditions) {
        Map<String, String> parsedConditions = new HashMap<>();
        for (String condition : conditions) {
            // 仅处理形如 "variable1 operator variable2" 的条件
            String[] parts = condition.split("\\s+");
            if (parts.length == 3) {
                String leftVar = parts[0];
                String operator = parts[1];
                String rightVar = parts[2];

                // 检查操作符是否是比较大小的操作符
                if (Arrays.asList("<=", "<", ">=", ">").contains(operator)) {
                    // 如果条件是两个变量之间的比较
                    if (Character.isLetter(leftVar.charAt(0)) && Character.isLetter(rightVar.charAt(0))) {
                        // 构建值字符串，这里确保值是包含操作符和比较值的完整字符串
                        String conditionExpression = operator + " " + rightVar;
                        parsedConditions.put(leftVar, conditionExpression);
                        System.out.println("parsedConditions:" + leftVar + conditionExpression);
                    }
                }
            }
        }
        return parsedConditions;
    }


}
