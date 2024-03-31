package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.HashMap;
import java.util.Map;

public class CollectionAnalysisAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(CollectionAnalysisAction.class);

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
            final Map<String, CollectionState> collectionStates = new HashMap<>();
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

                    // 获取调用方法的对象名称
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
                                                LOG.warn("Failed to parse 'copy' method arguments for collection size adjustment.");
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // 处理其他集合操作，如“add”、“remove”等，并在每次操作时输出
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
                                System.out.println("Variable '" + sourceVariableName + "' has " + state.size + " elements at position " + position);
                            }
                        }
                    }
                }

            });

        }
    }
}
