package MyUtils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PrimeVariablesExtractor {

    public static List<VariableInfo> extractInputVariables(String method) {
        List<VariableInfo> variableInfoList = new ArrayList<>();
        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(method);
        Set<String> addedVariableNames = new HashSet<>();

        // Get method parameters
        List<Parameter> parameters = methodDeclaration.findAll(Parameter.class);
        for (Parameter p : parameters) {
            Type type = p.getType();
            if (type.isPrimitiveType()) { // Only handle primitive types
                String name = p.getNameAsString();
                if (!addedVariableNames.contains(name)) {
                    VariableInfo variableInfo = new VariableInfo(name, type.toString());
                    variableInfo.setStartPosition(p.getBegin().get().line);
                    variableInfo.setEndPosition(p.getEnd().get().line);
                    variableInfoList.add(variableInfo);
                    addedVariableNames.add(name); // 记录已添加的变量名
                }
            }
        }
        // Get method local variables
        List<VariableDeclarationExpr> variables = methodDeclaration.findAll(VariableDeclarationExpr.class);
        for (VariableDeclarationExpr v : variables) {
            Type type = v.getElementType();
            if (type.isPrimitiveType()) { // Only handle primitive types
                for (int i = 0; i < v.getVariables().size(); i++) {
                    String name = v.getVariables().get(i).getNameAsString();
                    VariableInfo variableInfo = new VariableInfo(name, type.toString());
                    variableInfo.setStartPosition(v.getBegin().get().line);
                    variableInfo.setEndPosition(v.getEnd().get().line);
                    variableInfoList.add(variableInfo);
                }
            }
        }

        // Get method call arguments
        List<MethodCallExpr> methodCalls = methodDeclaration.findAll(MethodCallExpr.class);
        for (MethodCallExpr methodCall : methodCalls) {
            List<Expression> arguments = methodCall.getArguments();
            for (Expression argument : arguments) {
                if (argument.isLiteralExpr()) { // Only handle literal expressions
                    LiteralExpr literal = argument.asLiteralExpr();
                    String argumentName = argument.toString();
                    String type = "String";
                    if (literal instanceof BooleanLiteralExpr) {
                        type = "boolean";
                    } else if (literal instanceof StringLiteralExpr) {
                        type = "String";
                    } else if (literal.isIntegerLiteralExpr()) {
                        type = "int";
                        int value = Integer.parseInt(argumentName);
                        if (value < 0) { // Check if the argument is a negative integer
                            argumentName = "-" + argumentName; // Add the negative sign
                        }
                    } else if (literal.isDoubleLiteralExpr()) {
                        type = "double";
                    } else if (literal.isCharLiteralExpr()) {
                        type = "char";
                    } else if (isByteLiteralExpr(argumentName)) {
                        type = "byte";
                    }
                    if (!addedVariableNames.contains(argumentName)) {
                        VariableInfo variableInfo = new VariableInfo(argumentName, type);
                        variableInfo.setStartPosition(argument.getBegin().get().line);
                        variableInfo.setEndPosition(argument.getEnd().get().line);
                        variableInfoList.add(variableInfo);
                        addedVariableNames.add(argumentName); // 记录已添加的变量名
                    }
                }
            }
        }

        // 布尔变量和字面常量
        List<Expression> expressions = methodDeclaration.findAll(Expression.class);
        for (Expression e : expressions) {
            if (e.isBooleanLiteralExpr() || e.isLiteralExpr()) {
                String name = e.toString();
                String type;
                if (e.isBooleanLiteralExpr()) {
                    type = "boolean";
                } else if (e.isIntegerLiteralExpr()) {
                    type = "int";
                } else if (e.isDoubleLiteralExpr()) {
                    type = "double";
                } else if (e.isCharLiteralExpr()) {
                    type = "char";
                } else if (isByteLiteralExpr(name)) {
                    type = "byte";
                } else {
                    type = "String";
                }
                if (!addedVariableNames.contains(name)) {
                    VariableInfo variableInfo = new VariableInfo(name, type);
                    variableInfo.setStartPosition(e.getBegin().get().line);
                    variableInfo.setEndPosition(e.getEnd().get().line);
                    variableInfoList.add(variableInfo);
                }
            }
        }
        return variableInfoList;
    }


    public static List<VariableInfo> extractOutputVariables(String method) {
        List<VariableInfo> outputVariables = new ArrayList<>();

        // Find assert statements in the method body
        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(method);
        BlockStmt methodBody = methodDeclaration.getBody().get();
        List<MethodCallExpr> methodCalls = methodBody.findAll(MethodCallExpr.class);

        for (MethodCallExpr methodCall : methodCalls) {
            String methodName = methodCall.getNameAsString();
            if (methodName.equals("assertEquals") || methodName.equals("assertArrayEquals") || methodName.equals("assertFalse") || methodName.equals("assertTrue")) {
                List<Expression> arguments = methodCall.getArguments();

                for (Expression argument : arguments) {
                    if (argument.isNameExpr()) {
                        String outputVariableName = argument.asNameExpr().getNameAsString();
                        String outputVariableType = getTypeOfVariable(methodDeclaration, outputVariableName);

                        VariableInfo outputVariable = new VariableInfo(outputVariableName, outputVariableType);
                        outputVariables.add(outputVariable);
                    }
                }
            }
        }

        return outputVariables;
    }

    private static String getTypeOfVariable(MethodDeclaration methodDeclaration, String variableName) {
        // Find the variable declaration in the method body
        BlockStmt methodBody = methodDeclaration.getBody().get();
        List<VariableDeclarationExpr> variables = methodBody.findAll(VariableDeclarationExpr.class);

        for (VariableDeclarationExpr variable : variables) {
            for (VariableDeclarator variableDeclarator : variable.getVariables()) {
                if (variableDeclarator.getNameAsString().equals(variableName)) {
                    return variable.getElementType().toString();
                }
            }
        }
        // Variable not found in the method body, it may be a method parameter
        List<Parameter> parameters = methodDeclaration.getParameters();

        for (Parameter parameter : parameters) {
            if (parameter.getNameAsString().equals(variableName)) {
                return parameter.getType().toString();
            }
        }

        // Variable type not found, assuming it as String
        return "String";
    }

    private static boolean isByteLiteralExpr(String argumentName) {
        try {
            byte value = Byte.parseByte(argumentName);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }


}
