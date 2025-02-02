package MyUtils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class to extract input and output variables from Java methods.
 */
public class AllVariableExtractor {

    // Set of assertion method names to be excluded from variable extraction
    private static final Set<String> ASSERTION_METHODS = new HashSet<>(Arrays.asList(
            "assertEquals", "assertArrayEquals", "assertNotEquals", "assertNull", "assertNotNull", "assertSame",
            "assertNotSame", "assertFalse", "assertTrue", "assertThat"
    ));

    /**
     * Extracts input variables from the given method signature.
     *
     * @param method Method signature to extract variables from.
     * @return List of VariableInfo objects representing input variables.
     */
    public static List<VariableInfo> extractInputVariables(String method) {
        List<VariableInfo> variableInfoList = new ArrayList<>();
        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(method);

        // Extract boolean variables and literal constants
        List<Expression> expressions = methodDeclaration.findAll(Expression.class);
        for (Expression e : expressions) {
            // 检查表达式是否为数组初始化的一部分
            boolean isPartOfArrayInitialization = e.findAncestor(ArrayInitializerExpr.class).isPresent();
            if (isPartOfArrayInitialization) {
                continue;  // 跳过此元素的处理
            }
            String type;
            String name = e.toString();
            if (!isArgumentOfAssertionMethod(e)) {
                if (e.isBooleanLiteralExpr() || e.isLiteralExpr()) {
                    if (e.isBooleanLiteralExpr()) {
                        type = "boolean";
                    } else if (e.isIntegerLiteralExpr()) {
                        type = "int";
                    } else if (e.isDoubleLiteralExpr()) {
                        type = "double";
                    } else if (e.isCharLiteralExpr()) {
                        type = "char";
                    } else if (e.isLongLiteralExpr()) {
                        type = "long";
                    } else if (e.isStringLiteralExpr()) {
                        type = "String";
                    } else {
                        type = "Object";
                    }

                    StringBuilder callContext = new StringBuilder(); // 使用StringBuilder收集调用上下文信息

                    Node currentNode = e;
                    while (currentNode != null) {
                        if (currentNode instanceof MethodCallExpr) {
                            MethodCallExpr methodCall = (MethodCallExpr) currentNode;
                            callContext.append("Method call: ").append(methodCall.getNameAsString());
                            break; // Found the closest method call context
                        } else if (currentNode instanceof ObjectCreationExpr) {
                            ObjectCreationExpr constructorCall = (ObjectCreationExpr) currentNode;
                            callContext.append("Constructor call: ").append(constructorCall.getTypeAsString());
                            break; // Found the closest constructor call context
                        }
                        currentNode = currentNode.getParentNode().orElse(null);
                    }

                    VariableInfo variableInfo = new VariableInfo(name, type);
                    variableInfo.setStartPosition(e.getBegin().get().line);
                    variableInfo.setEndPosition(e.getEnd().get().line);
                    variableInfoList.add(variableInfo);
                } else if (e instanceof ArrayInitializerExpr) {
                    ArrayInitializerExpr arrayInitializer = (ArrayInitializerExpr) e;
                    type = determineArrayType(arrayInitializer.getValues());
                    name = arrayToString(arrayInitializer.getValues());  // 用修改后的名字包括数组内容
                    System.out.println("name" + name);
                    VariableInfo variableInfo = new VariableInfo(name, type);
                    variableInfo.setStartPosition(e.getBegin().get().line);
                    variableInfo.setEndPosition(e.getEnd().get().line);
                    variableInfoList.add(variableInfo);
                }
            }
        }
        return variableInfoList;
    }

    private static String arrayToString(NodeList<Expression> values) {
        return values.stream()
                .map(Expression::toString)
                .collect(Collectors.joining(", ", "{", "}"));
    }

    // 根据数组中的第一个元素的类型，确定数组类型
    private static String determineArrayType(NodeList<Expression> values) {
        if (values.isEmpty()) {
            return "Object[]";  // 空数组默认为 Object[]
        }
        Expression firstElement = values.get(0);
        if (firstElement.isIntegerLiteralExpr()) {
            return "int[]";
        } else if (firstElement.isDoubleLiteralExpr()) {
            return "double[]";
        } else if (firstElement.isBooleanLiteralExpr()) {
            return "boolean[]";
        } else if (firstElement.isCharLiteralExpr()) {
            return "char[]";
        } else if (firstElement.isStringLiteralExpr()) {
            return "String[]";
        } else if (firstElement.isLongLiteralExpr()) {
            return "long[]";
        } else {
            return "Object[]";  // 如果无法匹配到以上任何一种类型，则默认为 Object[]
        }
    }


    /**
     * Checks if the given expression is an argument of an assertion method.
     *
     * @param expression Expression to check.
     * @return True if the expression is an argument of an assertion method, false otherwise.
     */
    private static boolean isArgumentOfAssertionMethod(Expression expression) {
        // Check if it's an argument of an assertion method
        Node parentNode = expression.getParentNode().orElse(null);
        if (parentNode != null && parentNode instanceof MethodCallExpr) {
            MethodCallExpr methodCallExpr = (MethodCallExpr) parentNode;
            NodeList<Expression> arguments = methodCallExpr.getArguments();
            if (arguments.contains(expression)) {
                String methodName = methodCallExpr.getNameAsString();
                if (ASSERTION_METHODS.contains(methodName)) {
                    return true; // Return true if the expression is an argument of an assertion method
                }
            }
        }
        return false;
    }

    /**
     * Extracts output variables from the given method signature.
     *
     * @param method Method signature to extract variables from.
     * @return List of VariableInfo objects representing output variables.
     */
    public static List<VariableInfo> extractOutputVariables(String method) {

        List<VariableInfo> outputVariables = new ArrayList<>();

        MethodDeclaration methodDeclaration = StaticJavaParser.parseMethodDeclaration(method);
        BlockStmt methodBody = methodDeclaration.getBody().get();
        List<MethodCallExpr> methodCalls = methodBody.findAll(MethodCallExpr.class);

        for (MethodCallExpr methodCall : methodCalls) {
            String methodName = methodCall.getNameAsString();
            if (ASSERTION_METHODS.contains(methodName)) {
                List<Expression> arguments = methodCall.getArguments();

                for (Expression argument : arguments) {
                    if (argument.isNameExpr()) {
                        String variableName = argument.asNameExpr().getNameAsString();

                        // Exclude constants based on convention or predefined list
                        // Here, assuming all constants are in uppercase
                        if (variableName.equals(variableName.toUpperCase())) {
                            continue; // Skip if it's a constant
                        }

                        // Proceed to handle non-constant variables
                        System.out.println(variableName);
                        String variableType = getTypeOfVariable(methodDeclaration, variableName);

                        VariableInfo outputVariable = new VariableInfo(variableName, variableType);
                        outputVariables.add(outputVariable);
                    }
                }
            }
        }

        return outputVariables;
    }

    /**
     * Gets the type of the variable with the given name in the method's context.
     *
     * @param methodDeclaration Method declaration context.
     * @param variableName      Name of the variable to get type for.
     * @return Type of the variable.
     */
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
        // If variable not found in the method body, it may be a method parameter
        List<Parameter> parameters = methodDeclaration.getParameters();

        for (Parameter parameter : parameters) {
            if (parameter.getNameAsString().equals(variableName)) {
                return parameter.getType().toString();
            }
        }

        // Variable type not found, assuming it as String
        return "String";
    }
}
