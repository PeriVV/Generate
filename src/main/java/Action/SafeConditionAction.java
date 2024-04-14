package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class SafeConditionAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent e) {
        final Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        final Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

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

    private void trackLiteralArguments(PsiElement element, HashSet<PsiElement> visited, int currentDepth, Map<String, HashSet<String>> conditionsMap) {
        if (currentDepth > 3 || visited.contains(element)) return;
        visited.add(element);

        if (element instanceof PsiMethodCallExpression || element instanceof PsiNewExpression) {
            PsiMethod calledMethod = (element instanceof PsiMethodCallExpression)
                    ? ((PsiMethodCallExpression) element).resolveMethod()
                    : ((PsiNewExpression) element).resolveConstructor();

            if (calledMethod != null) {
                PsiClass containingClass = calledMethod.getContainingClass();
                if (containingClass != null) {
                    Map<String, String> constants = extractConstants(containingClass);
                    PsiExpression[] args = element instanceof PsiMethodCallExpression
                            ? ((PsiMethodCallExpression) element).getArgumentList().getExpressions()
                            : ((PsiNewExpression) element).getArgumentList().getExpressions();
                    for (PsiExpression arg : args) {
                        if (arg instanceof PsiLiteralExpression) {
                            String literalValue = ((PsiLiteralExpression) arg).getText();
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

    private Map<String, String> extractConstants(PsiClass psiClass) {
        Map<String, String> constantValues = new HashMap<>();
        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
                PsiExpression initializer = field.getInitializer();
                if (initializer instanceof PsiLiteralExpression) {
                    constantValues.put(field.getName(), initializer.getText());
                }
            }
        }
        return constantValues;
    }

    private void extractConditions(PsiMethod method, String literalValue, Map<String, HashSet<String>> conditionsMap, Map<String, String> constants) {
        if (method.getBody() == null) return;
        for (PsiStatement statement : method.getBody().getStatements()) {
            if (statement instanceof PsiIfStatement) {
                PsiIfStatement ifStmt = (PsiIfStatement) statement;
                PsiExpression condition = ifStmt.getCondition();
                String conditionText = condition.getText();
                for (Map.Entry<String, String> entry : constants.entrySet()) {
                    conditionText = conditionText.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
                }
                conditionsMap.computeIfAbsent(literalValue, k -> new HashSet<>()).add(conditionText);
            }
        }
    }
}
