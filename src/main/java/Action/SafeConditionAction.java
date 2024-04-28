package Action;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
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
            Map<String, HashSet<String>> conditionsMap = new HashMap<>();
            trackLiteralArguments(method, new HashSet<>(), 1, conditionsMap);
            conditionsMap.forEach((literal, conditions) -> {
                System.out.println("Parameter value: " + literal);
                conditions.forEach(condition -> System.out.println("Safe condition: " + condition));
            });
        }
    }

    private void trackLiteralArguments(PsiElement element, Set<PsiElement> visited, int currentDepth, Map<String, HashSet<String>> conditionsMap) {
        if (currentDepth > 3 || visited.contains(element)) return;
        visited.add(element);

        if (element instanceof PsiMethodCallExpression || element instanceof PsiNewExpression) {
            PsiMethod calledMethod = (element instanceof PsiMethodCallExpression)
                    ? ((PsiMethodCallExpression) element).resolveMethod()
                    : ((PsiNewExpression) element).resolveConstructor();

            if (calledMethod != null) {
                calledMethod.getParameterList();
                PsiClass containingClass = calledMethod.getContainingClass();
                if (containingClass != null) {
                    Map<String, String> constants = extractConstants(containingClass);
                    PsiExpression[] args = element instanceof PsiMethodCallExpression
                            ? ((PsiMethodCallExpression) element).getArgumentList().getExpressions()
                            : Objects.requireNonNull(((PsiNewExpression) element).getArgumentList()).getExpressions();
                    PsiParameter[] parameters = calledMethod.getParameterList().getParameters();
                    for (int i = 0; i < args.length; i++) {
                        PsiExpression arg = args[i];
                        if (arg instanceof PsiLiteralExpression && i < parameters.length) {
                            String paramName = parameters[i].getName();
                            String literalValue = paramName + ": " + arg.getText();
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
        if (psiClass == null || processedClasses.contains(psiClass.getQualifiedName())) return;
        processedClasses.add(psiClass.getQualifiedName());

        String className = psiClass.getName();  // 获取简短类名
        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
                PsiExpression initializer = field.getInitializer();
                String valueText = extractValue(initializer);
                if (valueText != null) {
                    String name = className + "." + field.getName();
                    constantValues.put(name, valueText);
                }
            }
        }
    }

    private String extractValue(PsiExpression expression) {
        if (expression instanceof PsiLiteralExpression) {
            return expression.getText();
        } else if (expression instanceof PsiPrefixExpression) {
            PsiPrefixExpression prefixExpr = (PsiPrefixExpression) expression;
            PsiExpression operand = prefixExpr.getOperand();
            if (operand instanceof PsiLiteralExpression && prefixExpr.getOperationSign().getText().equals("-")) {
                return "-" + operand.getText();  // Handle negative literals
            }
        }
        return null;
    }


    private Map<String, String> extractConstants(PsiClass psiClass) {
        Map<String, String> constantValues = new HashMap<>();
        Set<String> processedClasses = new HashSet<>();

        extractFieldsFromClass(psiClass, constantValues, processedClasses);
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

                        // 替换常量
                        conditionText = replaceConstantsInCondition(conditionText, constants);

                        // 反转条件来表示非异常的情况
                        String negatedCondition = negateCondition(conditionText);
                        conditionsMap.computeIfAbsent(literalValue, k -> new HashSet<>()).add(negatedCondition);
                        conditions.add(negatedCondition);
                        System.out.println("conditionText：" + negatedCondition);
                    }
                }
            }
        }
        return conditions;
    }

    private String negateCondition(String condition) {
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
