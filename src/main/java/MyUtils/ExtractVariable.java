package MyUtils;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase;
import com.intellij.refactoring.introduceVariable.IntroduceVariableHandler;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ExtractVariable implements Runnable {

    private PsiExpression expr;
    private PsiFile psiFile;
    private Document document;
    private Editor editor;
    private Project project;

    //构造函数
    public ExtractVariable(Project project, int offset, String filePath, String expression) {
        this.project = project;
        PsiDocumentManager instance = PsiDocumentManager.getInstance(project);
        document = Editors.getCurrentDocument(filePath);
        editor = Editors.createSourceEditor(project, filePath, "java", false);
        psiFile = instance.getPsiFile(document);
        expr = findPsiExpression(offset, expression);
    }

    public PsiExpression findPsiExpression(int offset, String expression) {
        PsiElement element = psiFile.findElementAt(offset);
        while (element != null) {
            int length1 = element.getTextLength();
            int length2 = expression.length();
//            System.out.println("length1:" + length1);
//            System.out.println("length2:" + length2);
            if (length1 == length2) {
                break;
            }
            element = element.getParent();
//            System.out.println("parent element:" + element);
        }
        if (element instanceof PsiExpression) {
            return (PsiExpression) element;
        }
        return null;
    }

    //执行提取变量操作的方法。
    public void extractVariable() {
        if (expr == null) {
            return;
        }
        IntroduceVariableHandler handler = new IntroduceVariableHandler();
        Map<String, IntroduceVariableBase.JavaReplaceChoice> choices = handler.getPossibleReplaceChoices(project, expr);// handler.getPossibleReplaceChoices(project, expr);
        IntroduceVariableBase.JavaReplaceChoice choice = IntroduceVariableBase.JavaReplaceChoice.NO;
        int maxReplacedExpr = 0;
        for (Map.Entry<String, IntroduceVariableBase.JavaReplaceChoice> e : choices.entrySet()) {
            IntroduceVariableBase.JavaReplaceChoice c = e.getValue();
            if (choice == IntroduceVariableBase.JavaReplaceChoice.NO && !e.getKey().contains("will change")) {
                choice = c;
            }
            if (e.getKey().contains("all occurrences but write")) {
                choice = c;
                break;
            } else if (e.getKey().contains("occurrences") && !e.getKey().contains("will change")) {
                String regEx = "[^0-9]";
                Pattern p = Pattern.compile(regEx);
                Matcher m = p.matcher(e.getKey());
                int v = Integer.parseInt(m.replaceAll("").trim());
                if (v >= maxReplacedExpr) {
                    choice = c;
                    maxReplacedExpr = v;
                }
            }
        }
        // invoke the api of idea to conduct extract variable.
        try {
            handler.invokeImpl(project, expr, null, choice, editor);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {

    }

}
