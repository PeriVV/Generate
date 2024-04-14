package Action;


import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.HashSet;

public class ExtractLiteralCallerMethodAction extends AnAction {
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
            trackLiteralArguments(method, new HashSet<>(), 1); // Start tracking from depth 1
        }
    }

    private void trackLiteralArguments(PsiElement element, HashSet<PsiElement> visited, int currentDepth) {
        // 定义最大深度
        final int maxDepth = 3;

        if (currentDepth > maxDepth || visited.contains(element)) {
            return;
        }
        visited.add(element);

        // 特别处理方法调用表达式
        if (element instanceof PsiMethodCallExpression methodCall) {
            PsiExpression[] args = methodCall.getArgumentList().getExpressions();
            for (PsiExpression arg : args) {
                if (arg instanceof PsiLiteralExpression) {
                    PsiMethod method = methodCall.resolveMethod();
                    if (method != null) {
                        System.out.println("Method signature and body for literal " + arg.getText() + " at depth " + currentDepth + ": ");
                        System.out.println(method.getText());
                        trackLiteralArguments(method, new HashSet<>(visited), currentDepth + 1); // 递归调用并增加深度
                    }
                }
            }
        } else if (element instanceof PsiNewExpression newExpression) {
            // 特别处理构造函数调用
            PsiMethod constructor = newExpression.resolveConstructor();
            if (constructor != null) {
                System.out.println("Constructor signature and body at depth " + currentDepth + ": ");
                System.out.println(constructor.getText());
                trackLiteralArguments(constructor, new HashSet<>(visited), currentDepth + 1); // 递归调用并增加深度
            }
        } else if (element instanceof PsiLiteralExpression) {
            PsiElement parentElement = element.getParent();
            while (!(parentElement instanceof PsiFile)) {
                if (parentElement instanceof PsiMethod || parentElement instanceof PsiClassInitializer) {
                    System.out.println("Literal usage found at depth " + currentDepth + ": ");
                    System.out.println(element.getText());
                    break;
                }
                parentElement = parentElement.getParent();
            }
        }

        for (PsiElement child : element.getChildren()) {
            trackLiteralArguments(child, visited, currentDepth); // 对子元素保持当前深度
        }
    }

}
