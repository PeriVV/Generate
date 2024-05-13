package MyUtils;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
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
            if (length1 == length2) {
                break;
            }
            element = element.getParent();
        }
        if (element instanceof PsiExpression) {
            return (PsiExpression) element;
        }
        return null;
    }

    //执行提取变量操作的方法。
    public void extractVariable() {
        if (expr == null) {
            System.out.println("未找到有效的PsiExpression");
            return;
        }
        System.out.println("提取变量的表达式：" + expr.getText());
        IntroduceVariableHandler handler = new IntroduceVariableHandler();
        Map<String, IntroduceVariableBase.JavaReplaceChoice> choices = handler.getPossibleReplaceChoices(project, expr);// handler.getPossibleReplaceChoices(project, expr);
        IntroduceVariableBase.JavaReplaceChoice choice = IntroduceVariableBase.JavaReplaceChoice.NO;
        int maxReplacedExpr = 0;
        for (Map.Entry<String, IntroduceVariableBase.JavaReplaceChoice> e : choices.entrySet()) {
            System.out.println("选项：" + e.getKey() + " -> " + e.getValue());
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
        System.out.println("最终选择：" + choice);
        // invoke the api of idea to conduct extract variable.
        // 执行提取变量操作
        IntroduceVariableBase.JavaReplaceChoice finalChoice = choice;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                handler.invokeImpl(project, expr, null, finalChoice, editor);
                // 保存文档，不可删除这行代码
                FileDocumentManager.getInstance().saveDocument(document);
            } catch (Exception e) {
                e.printStackTrace();
            }

        });
    }

    @Override
    public void run() {

    }

}
