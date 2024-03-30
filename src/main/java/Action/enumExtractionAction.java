package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.HashSet;
import java.util.Set;

public class enumExtractionAction extends AnAction {
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
            System.out.println("Analyzing method: " + method.getName());
            HashSet<PsiElement> visited = new HashSet<>();
            exploreElement(method, visited); // Explore the method for enums
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
}
