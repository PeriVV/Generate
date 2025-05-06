package Action;

import MyUtils.Editors;
import MyUtils.ExtractVariable;
import MyUtils.MethodExtractor;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.List;

/**
 * author: weiwei
 * usage: 这个类用于将断言中的实际输出表达式，提取为一个变量，方便在测试用例生成的时候记录输出
 * date: 2025-02-13
 * **/
public class ExtractVariableAction extends AnAction {
    public void actionPerformed(@NotNull AnActionEvent e) {
        final Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        String absoluteJavaFilePath = getAbsoluteFilePath(editor);
        Document document = Editors.getCurrentDocument(absoluteJavaFilePath);
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }
        String methodBody = null;
        try {
            methodBody = String.valueOf(MethodExtractor.parseSelectedMethod(editor.getDocument().getText(), selectedText));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        if (methodBody == null) {
            return;
        }
        // Find assert statements in the method body
        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(methodBody);
        BlockStmt methods = methodDeclaration.getBody().get();
        List<MethodCallExpr> methodCalls = methods.findAll(MethodCallExpr.class);
        MethodExtractor methodExtractor = new MethodExtractor();

        for (MethodCallExpr methodCall : methodCalls) {
            String methodName = methodCall.getNameAsString();
            if (methodName.equals("assertEquals") || methodName.equals("assertArrayEquals")) {
                List<Expression> arguments = methodCall.getArguments();
                for (Expression argument : arguments) {
                    // 判断表达式类型
                    if (argument instanceof LiteralExpr || argument instanceof NameExpr || (argument instanceof UnaryExpr && ((UnaryExpr) argument).getOperator() == UnaryExpr.Operator.MINUS)) {
                        // 忽略字面量表达式、变量名表达式和负数表达式
                        continue;
                    }
                    String expression = String.valueOf(argument);
                    int offset = getOffset(absoluteJavaFilePath, expression);
                    // 根据光标位置替换
//                    Messages.showMessageDialog(project, offset + " ", expression, Messages.getInformationIcon());
                    idea_parseEachJavaFileOfSpecificCommit(document, project, absoluteJavaFilePath, offset, expression);
                }
                // 将替换后的文本写入文件
                writeToFile(absoluteJavaFilePath, document.getText());
            }
        }
    }

    public static int getOffset(String filePath, String searchString) {
        try {
            File file = new File(filePath);
            BufferedReader reader = new BufferedReader(new FileReader(file));

            StringBuilder fileContent = new StringBuilder();
            String line;
            int currentOffset = 0;

            while ((line = reader.readLine()) != null) {
                fileContent.append(line).append(System.lineSeparator());

                int index = line.indexOf(searchString);
                if (index != -1) {
                    // Found the target string in the current line
                    return currentOffset + index;
                }
                currentOffset += line.length() + System.lineSeparator().length();
            }
            reader.close();

            // The target string was not found
            return -1;
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
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
//        Messages.showMessageDialog(project, extractVariable.findPsiExpression(offset, expression).toString(),
//                "Introduce Variable", Messages.getInformationIcon());
        extractVariable.extractVariable();
    }

    private void writeToFile(String filePath, String content) {
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
            out.write(content);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
