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
                            extractConditions(calledMethod, literalValue, conditionsMap, constants);
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
                if (initializer instanceof PsiLiteralExpression) {
                    String name = className + "." + field.getName();
                    String text = ((PsiLiteralExpression) initializer).getText();
                    constantValues.put(name, text);
                }
            }
        }
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

    private void extractConditions(PsiMethod method, String literalValue, Map<String, HashSet<String>> conditionsMap, Map<String, String> constants) {
        if (method.getBody() == null) return;
        PsiClass methodClass = method.getContainingClass();
        String methodClassName = methodClass != null ? methodClass.getName() : "";

        for (PsiStatement statement : method.getBody().getStatements()) {
            if (statement instanceof PsiIfStatement) {
                PsiIfStatement ifStmt = (PsiIfStatement) statement;
                PsiExpression condition = ifStmt.getCondition();
                String conditionText = condition.getText();

                // 替换常量
                for (Map.Entry<String, String> entry : constants.entrySet()) {
                    String fullFieldName = entry.getKey();
                    String fieldValue = entry.getValue();
                    String[] parts = fullFieldName.split("\\.");
                    String className = parts.length > 1 ? parts[0] : "";
                    String fieldName = parts[parts.length - 1];

                    if (className.equals(methodClassName)) {
                        conditionText = conditionText.replaceAll("\\b" + Pattern.quote(fieldName) + "\\b", fieldValue);
                    } else {
                        conditionText = conditionText.replaceAll("\\b" + Pattern.quote(fullFieldName) + "\\b", fieldValue);
                    }
                }

                conditionText = negateCondition(conditionText);
                conditionsMap.computeIfAbsent(literalValue, k -> new HashSet<>()).add(conditionText);

                // 检查 `then` 分支是否包含 `throw`
                PsiStatement[] thenStatements = null;
                if (ifStmt.getThenBranch() instanceof PsiBlockStatement) {
                    thenStatements = ((PsiBlockStatement) ifStmt.getThenBranch()).getCodeBlock().getStatements();
                } else if (ifStmt.getThenBranch() != null) {
                    thenStatements = new PsiStatement[]{ifStmt.getThenBranch()};
                }

                if (thenStatements != null) {
                    boolean hasThrow = Arrays.stream(thenStatements)
                            .anyMatch(s -> s instanceof PsiThrowStatement);
                    if (hasThrow) {
                        // 添加到条件映射中
                        String throwCondition = "Throw condition: " + ifStmt.getCondition().getText();
                        conditionsMap.computeIfAbsent(literalValue, k -> new HashSet<>()).add(throwCondition);
                    }
                }
            }
        }
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

}
