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
            Map<String, Integer> collectionCounts = new HashMap<>();
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitLocalVariable(PsiLocalVariable variable) {
                    super.visitLocalVariable(variable);
                    // 初始化所有局部变量的计数为0，真实集合类型将在后续逻辑中更新
                    collectionCounts.putIfAbsent(variable.getName(), 0);
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
                        PsiType type = null;

                        if (qualifierExpression instanceof PsiReferenceExpression) {
                            PsiElement resolved = ((PsiReferenceExpression) qualifierExpression).resolve();
                            if (resolved instanceof PsiVariable) {
                                type = ((PsiVariable) resolved).getType();
                            }
                        }

                        if (methodName != null && (methodName.contains("copy") || methodName.contains("Copy"))) {
                            PsiElement parent = expression.getParent();

                            while (!(parent instanceof PsiDeclarationStatement) && parent != null) {
                                parent = parent.getParent();

                            }
                            if (parent != null) {
                                for (PsiElement element : ((PsiDeclarationStatement) parent).getDeclaredElements()) {
                                    if (element instanceof PsiLocalVariable localVariable) {
                                        String newVariableName = localVariable.getName(); // 新集合的变量名
                                        PsiExpression[] arguments = expression.getArgumentList().getExpressions();
                                        if (arguments.length >= 2) {
                                            try {
                                                int start = Integer.parseInt(arguments[0].getText());
                                                int end = Integer.parseInt(arguments[1].getText());
                                                int count = Math.abs(end - start) + 1;
                                                collectionCounts.put(newVariableName, count);
                                            } catch (NumberFormatException ex) {
                                                LOG.warn("Failed to parse 'copy' method arguments for collection size adjustment.");
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (type == null || isCollectionType(type) || collectionCounts.containsKey(sourceVariableName)) {
                            // 增加元素的情况
                            if (methodName != null && (methodName.contains("add") || methodName.contains("push"))) {
                                collectionCounts.merge(sourceVariableName, 1, Integer::sum);
                            }
                            // 减少元素的情况
                            else if (methodName != null && (methodName.contains("remove") || methodName.contains("pop") || methodName.contains("poll"))) {
                                collectionCounts.merge(sourceVariableName, -1, Integer::sum);
                            }
                            // 清空集合的情况
                            else if ("clear".equals(methodName)) {
                                collectionCounts.put(sourceVariableName, 0);
                            }
                        }
                    }
                }

                private boolean isCollectionType(PsiType type) {
                    String typeText = type.getCanonicalText();
                    return typeText.contains("List") ||
                            typeText.contains("Set") ||
                            typeText.contains("Map") ||
                            typeText.contains("Collection");
                }
            });

            collectionCounts.forEach((name, count) -> {
                System.out.println("Variable '" + name + "' treated as a collection has " + count + " elements.");
            });
        }
    }
}
