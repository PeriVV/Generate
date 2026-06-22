package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.MethodUsageModel;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * 提取被测方法在当前类源码中的调用点。
 * 该提取器不做跨文件索引搜索，只基于当前 JavaParser AST 识别同名、同参数个数的调用。
 */
public class MethodUsageExtractor {
    /**
     * 提取当前类内对目标方法的调用点。
     *
     * @param classDeclaration 当前类声明
     * @param targetMethod 目标被测方法
     * @return 调用点列表
     */
    public List<MethodUsageModel> extract(ClassOrInterfaceDeclaration classDeclaration, MethodDeclaration targetMethod) {
        List<MethodUsageModel> usages = new ArrayList<>();
        if (classDeclaration == null || targetMethod == null) {
            return usages;
        }

        String methodName = targetMethod.getNameAsString();
        int parameterCount = targetMethod.getParameters().size();
        for (MethodCallExpr call : classDeclaration.findAll(MethodCallExpr.class)) {
            if (!methodName.equals(call.getNameAsString())) {
                continue;
            }
            if (call.getArguments().size() != parameterCount) {
                continue;
            }
            usages.add(usage(classDeclaration, call));
        }
        return usages;
    }

    /**
     * 将 JavaParser 调用表达式转换成结构化调用点。
     */
    private MethodUsageModel usage(ClassOrInterfaceDeclaration classDeclaration, MethodCallExpr call) {
        MethodUsageModel usage = new MethodUsageModel();
        usage.setCallerClassName(classDeclaration.getNameAsString());
        MethodDeclaration callerMethod = parentMethod(call);
        usage.setCallerMethodName(callerMethod == null ? "<initializer>" : callerMethod.getNameAsString());
        usage.setCallerSignature(callerMethod == null ? "" : methodSignature(callerMethod));
        usage.setCallExpression(call.toString());
        String receiver = call.getScope().map(Object::toString).orElse("");
        usage.setReceiver(receiver);
        usage.setCallStyle(callStyle(receiver, classDeclaration.getNameAsString()));
        usage.setArgumentExpressions(argumentExpressions(call));
        usage.setLineNumber(call.getBegin().map(position -> position.line).orElse(-1));
        usage.setConfidence(confidence(receiver, classDeclaration.getNameAsString()));
        usage.setSourceSnippet(sourceSnippet(call));
        return usage;
    }

    /**
     * 构造调用者方法签名。
     */
    private String methodSignature(MethodDeclaration method) {
        List<String> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            parameters.add(parameter.getType() + " " + parameter.getNameAsString());
        }
        return method.getType() + " " + method.getNameAsString() + "(" + String.join(", ", parameters) + ")";
    }

    /**
     * 提取实参表达式。
     */
    private List<String> argumentExpressions(MethodCallExpr call) {
        List<String> arguments = new ArrayList<>();
        call.getArguments().forEach(argument -> arguments.add(argument.toString()));
        return arguments;
    }

    /**
     * 判断调用风格。
     */
    private String callStyle(String receiver, String className) {
        if (receiver == null || receiver.isBlank()) {
            return "unqualified";
        }
        if ("this".equals(receiver)) {
            return "this";
        }
        if ("super".equals(receiver)) {
            return "super";
        }
        if (className.equals(receiver)) {
            return "class";
        }
        return "externalReceiver";
    }

    /**
     * 给调用关系打置信度。
     * 直接调用、this 调用和类名限定调用基本可以认为是当前类方法；其他 receiver 可能只是同名方法。
     */
    private String confidence(String receiver, String className) {
        if (receiver == null || receiver.isBlank() || "this".equals(receiver) || "super".equals(receiver) || className.equals(receiver)) {
            return "HIGH";
        }
        return "LOW";
    }

    /**
     * 获取包含调用表达式的语句片段；如果找不到语句，则退回调用表达式本身。
     */
    private String sourceSnippet(MethodCallExpr call) {
        Statement statement = parentStatement(call);
        return statement == null ? call.toString() : statement.toString();
    }

    /**
     * 查找调用点所在方法，避免 JavaParser 泛型 ancestor API 在当前编译配置下产生 unchecked warning。
     */
    private MethodDeclaration parentMethod(Node node) {
        Node current = node;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (current instanceof MethodDeclaration) {
                return (MethodDeclaration) current;
            }
        }
        return null;
    }

    /**
     * 查找调用点所在语句。
     */
    private Statement parentStatement(Node node) {
        Node current = node;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (current instanceof Statement) {
                return (Statement) current;
            }
        }
        return null;
    }
}
