package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.CallContextModel;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 提取被测方法的一层调用链上下文。
 * 该提取器不依赖具体业务方法名，只按调用形态分类内部调用、静态调用、参数对象调用和链式调用。
 */
public class CallContextExtractor {
    public List<CallContextModel> extract(ClassOrInterfaceDeclaration classDeclaration, MethodDeclaration methodDeclaration) {
        List<CallContextModel> contexts = new ArrayList<>();
        if (classDeclaration == null || methodDeclaration == null) {
            return contexts;
        }

        Map<String, MethodDeclaration> declaredMethods = declaredMethods(classDeclaration);
        Map<String, String> parameterTypes = parameterTypes(methodDeclaration);
        Set<String> seen = new LinkedHashSet<>();

        for (MethodCallExpr call : methodDeclaration.findAll(MethodCallExpr.class)) {
            CallContextModel context = contextForCall(classDeclaration, methodDeclaration, call, declaredMethods, parameterTypes);
            if (context != null && seen.add(context.getKind() + "|" + context.getExpression())) {
                contexts.add(context);
            }
        }
        return contexts;
    }

    private CallContextModel contextForCall(ClassOrInterfaceDeclaration classDeclaration,
                                            MethodDeclaration currentMethod,
                                            MethodCallExpr call,
                                            Map<String, MethodDeclaration> declaredMethods,
                                            Map<String, String> parameterTypes) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            MethodDeclaration internal = declaredMethods.get(call.getNameAsString());
            return internal == null || internal == currentMethod ? null : internalContext(classDeclaration, internal, call);
        }

        String scopeText = scope.get().toString();
        if ("this".equals(scopeText)) {
            MethodDeclaration internal = declaredMethods.get(call.getNameAsString());
            return internal == null || internal == currentMethod ? null : internalContext(classDeclaration, internal, call);
        }
        if (isClassName(scopeText)) {
            return simpleContext("STATIC_CALL", scopeText, call.getNameAsString(), call.toString(), "", "", "", "");
        }

        String root = rootReceiver(scopeText);
        if (parameterTypes.containsKey(root)) {
            String kind = scopeText.contains(".") ? "PARAMETER_CHAIN_CALL" : "PARAMETER_OBJECT_CALL";
            return simpleContext(kind, root + ":" + parameterTypes.get(root), call.getNameAsString(), call.toString(), "", "", "", "");
        }
        return null;
    }

    private CallContextModel internalContext(ClassOrInterfaceDeclaration classDeclaration, MethodDeclaration method, MethodCallExpr call) {
        CallContextModel context = simpleContext("INTERNAL_METHOD_CALL",
                classDeclaration.getNameAsString(),
                method.getNameAsString(),
                call.toString(),
                method.getType().toString(),
                signature(method),
                method.getBody().map(Object::toString).orElse(""),
                parameterUsageSummary(method));
        context.setNestedCalls(nestedCallSummaries(classDeclaration, method));
        return context;
    }

    private List<String> nestedCallSummaries(ClassOrInterfaceDeclaration classDeclaration, MethodDeclaration method) {
        List<String> summaries = new ArrayList<>();
        Map<String, MethodDeclaration> declaredMethods = declaredMethods(classDeclaration);
        Set<String> seen = new LinkedHashSet<>();
        for (MethodCallExpr nestedCall : method.findAll(MethodCallExpr.class)) {
            String name = nestedCall.getNameAsString();
            MethodDeclaration nestedMethod = declaredMethods.get(name);
            if (nestedMethod != null && nestedMethod != method && seen.add(name)) {
                summaries.add(signature(nestedMethod) + "; parameterUsage=" + parameterUsageSummary(nestedMethod));
                continue;
            }
            if (nestedCall.getScope().isPresent()) {
                String scope = nestedCall.getScope().get().toString();
                if (isClassName(scope) && seen.add(scope + "." + name)) {
                    summaries.add(scope + "." + name + "(...)");
                }
            }
        }
        return summaries;
    }

    private CallContextModel simpleContext(String kind,
                                           String owner,
                                           String methodName,
                                           String expression,
                                           String returnType,
                                           String signature,
                                           String sourceCode,
                                           String parameterUsageSummary) {
        CallContextModel context = new CallContextModel();
        context.setKind(kind);
        context.setOwner(owner);
        context.setMethodName(methodName);
        context.setExpression(expression);
        context.setReturnType(returnType);
        context.setSignature(signature);
        context.setSourceCode(sourceCode);
        context.setParameterUsageSummary(parameterUsageSummary);
        return context;
    }

    private Map<String, MethodDeclaration> declaredMethods(ClassOrInterfaceDeclaration classDeclaration) {
        Map<String, MethodDeclaration> methods = new LinkedHashMap<>();
        for (MethodDeclaration method : classDeclaration.getMethods()) {
            methods.put(method.getNameAsString(), method);
        }
        return methods;
    }

    private Map<String, String> parameterTypes(MethodDeclaration methodDeclaration) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Parameter parameter : methodDeclaration.getParameters()) {
            parameters.put(parameter.getNameAsString(), parameter.getType().toString());
        }
        return parameters;
    }

    private String signature(MethodDeclaration method) {
        List<String> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            parameters.add(parameter.getType() + " " + parameter.getNameAsString());
        }
        return method.getType() + " " + method.getNameAsString() + "(" + String.join(", ", parameters) + ")";
    }

    private String parameterUsageSummary(MethodDeclaration method) {
        List<String> usages = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            String name = parameter.getNameAsString();
            Set<String> calls = new LinkedHashSet<>();
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (call.getScope().isPresent()) {
                    String scope = call.getScope().get().toString();
                    if (name.equals(rootReceiver(scope))) {
                        calls.add(scope + "." + call.getNameAsString() + "(...)");
                    }
                }
            }
            if (!calls.isEmpty()) {
                usages.add(name + " -> " + String.join(", ", calls));
            }
        }
        return usages.isEmpty() ? "未发现参数对象方法读取" : String.join("; ", usages);
    }

    private String rootReceiver(String scopeText) {
        int dot = scopeText.indexOf('.');
        return dot < 0 ? scopeText : scopeText.substring(0, dot);
    }

    private boolean isClassName(String text) {
        return text != null && !text.isBlank() && Character.isUpperCase(text.charAt(0));
    }
}
