package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class CombinedAction extends AnAction {

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

    static class MethodCallDetail {
        String methodName;
        Set<String> arguments; // 使用 Set 而不是 List
        int position;

        public MethodCallDetail(String methodName, Set<String> arguments, int position) {
            this.methodName = methodName;
            this.arguments = arguments;
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
            System.out.println("Analyzing method: " + method.getName());
            HashSet<PsiElement> visited = new HashSet<>();
            exploreElement(method, visited); // Explore the method for enums

            // 解析集合大小
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
                                                ex.printStackTrace();
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

            Map<String, Set<String>> argumentValuesByType = new HashMap<>();
            List<MethodCallDetail> methodCalls = new ArrayList<>();

            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    PsiReferenceExpression methodExpression = expression.getMethodExpression();
                    String methodName = methodExpression.getReferenceName();
                    PsiExpression[] args = expression.getArgumentList().getExpressions();

                    if (methodName != null && methodName.startsWith("add")) {
                        for (PsiExpression arg : args) {
                            argumentValuesByType.computeIfAbsent(arg.getType().getPresentableText(), k -> new HashSet<>()).add(arg.getText());
                        }
                    }

                    if (methodName != null && methodName.startsWith("remove")) {
                        Set<String> relevantArguments = new HashSet<>(); // 使用 Set 而不是 List
                        for (PsiExpression arg : args) {
                            Set<String> possibleValues = argumentValuesByType.get(arg.getType().getPresentableText());
                            if (possibleValues != null) {
                                relevantArguments.addAll(possibleValues);
                            }
                        }
                        int position = expression.getTextRange().getStartOffset();
                        methodCalls.add(new MethodCallDetail(methodName, relevantArguments, position));
                    }
                }
            });

            // 输出remove方法调用的详细信息
            methodCalls.forEach(call ->
                    System.out.println("Method Call: " + call.methodName + ", Arguments: " + String.join(", ", call.arguments) + ", Position: " + call.position));
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
