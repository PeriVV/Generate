package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.ConstraintModel;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.Node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 JavaParser AST 中提取参数约束和异常分支。
 * 该类负责把源码中的 if 条件转为结构化模型，供后续场景规划和安全输入生成使用。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class ConstraintExtractor {
    /**
     * 从目标方法中提取参数约束。
     * 该方法只处理能关联到参数的二元表达式，避免把无关条件误导入测试数据规划。
     *
      * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
      *
     * @param methodDeclaration JavaParser 解析出的目标方法声明节点
     * @return 提取到的约束模型列表
     */
    public List<ConstraintModel> extract(MethodDeclaration methodDeclaration) {
        List<ConstraintModel> constraints = new ArrayList<>();
        if (methodDeclaration == null) {
            return constraints;
        }

        Set<String> parameterNames = new HashSet<>();
        methodDeclaration.getParameters().forEach(parameter -> parameterNames.add(parameter.getNameAsString()));

        for (IfStmt ifStmt : methodDeclaration.findAll(IfStmt.class)) {
            boolean throwsInThen = firstDirectThrow(ifStmt.getThenStmt()) != null;
            String source = throwsInThen ? "if-throw" : "if";
            for (BinaryExpr binaryExpr : ifStmt.getCondition().findAll(BinaryExpr.class)) {
                ConstraintModel constraint = fromBinaryExpr(binaryExpr, parameterNames, source, throwsInThen);
                if (constraint != null) {
                    constraints.add(constraint);
                }
            }
        }

        return constraints;
    }

    /**
     * 从目标方法中提取会抛出异常的保护分支。
     * 异常分支会被转换为可覆盖的场景，同时生成安全条件用于正常路径。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param methodDeclaration JavaParser 解析出的目标方法声明节点
     * @return 提取到的异常分支列表
     */
    public List<BranchModel> extractBranches(MethodDeclaration methodDeclaration) {
        List<BranchModel> branches = new ArrayList<>();
        if (methodDeclaration == null) {
            return branches;
        }

        for (IfStmt ifStmt : methodDeclaration.findAll(IfStmt.class)) {
            ThrowStmt directThrow = firstDirectThrow(ifStmt.getThenStmt());
            BranchModel branch = new BranchModel();
            int startLine = ifStmt.getBegin().map(position -> position.line).orElse(-1);
            int endLine = ifStmt.getEnd().map(position -> position.line).orElse(startLine);
            branch.setBranchId("IF:" + startLine + ":" + Integer.toHexString(ifStmt.getCondition().toString().hashCode()));
            branch.setSourceStartLine(startLine);
            branch.setSourceEndLine(endLine);
            branch.setCondition(ifStmt.getCondition().toString());
            branch.setSafeCondition(negate(ifStmt.getCondition()));
            if (directThrow != null) {
                branch.setAction("throw");
                branch.setExceptionType(extractExceptionType(directThrow));
                List<String> preconditions = enclosingPreconditions(ifStmt);
                preconditions.addAll(precedingThrowGuardSafeConditions(methodDeclaration, ifStmt));
                branch.setPreconditions(preconditions);
            } else if (hasDirectReturn(ifStmt.getThenStmt())) {
                branch.setAction("return");
                branch.setExceptionType("");
                branch.setPreconditions(enclosingPreconditions(ifStmt));
            } else {
                branch.setAction("conditional");
                branch.setExceptionType("");
                branch.setPreconditions(enclosingPreconditions(ifStmt));
            }
            branches.add(branch);
        }

        for (SwitchExpr switchExpr : methodDeclaration.findAll(SwitchExpr.class)) {
            appendSwitchBranches(branches, switchExpr.getSelector().toString(), switchExpr.getEntries());
        }

        for (SwitchStmt switchStmt : methodDeclaration.findAll(SwitchStmt.class)) {
            appendSwitchBranches(branches, switchStmt.getSelector().toString(), switchStmt.getEntries());
        }

        return branches;
    }

    /**
     * 追加 switch 分支模型。
     * switch expression 的 case 和 default 是分支覆盖的重要路径，必须进入场景规划视图。
     */
    private void appendSwitchBranches(List<BranchModel> branches, String selector, List<SwitchEntry> entries) {
        for (SwitchEntry entry : entries) {
            BranchModel branch = new BranchModel();
            String condition = switchCondition(selector, entry);
            int startLine = entry.getBegin().map(position -> position.line).orElse(-1);
            int endLine = entry.getEnd().map(position -> position.line).orElse(startLine);
            branch.setBranchId("SWITCH:" + startLine + ":" + Integer.toHexString(condition.hashCode()));
            branch.setSourceStartLine(startLine);
            branch.setSourceEndLine(endLine);
            branch.setCondition(condition);
            branch.setSafeCondition(condition);
            ThrowStmt throwStmt = firstThrowStatement(entry);
            if (throwStmt == null) {
                branch.setAction("return");
                branch.setExceptionType("");
            } else {
                branch.setAction("throw");
                branch.setExceptionType(extractExceptionType(throwStmt));
            }
            branches.add(branch);
        }
    }

    /** 计算嵌套 if 带来的控制依赖；else 子树使用父条件的反向条件。 */
    private List<String> enclosingPreconditions(IfStmt target) {
        List<String> result = new ArrayList<>();
        Node child = target;
        Node current = target.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof IfStmt parent) {
                Node nestedNode = child;
                boolean inElse = parent.getElseStmt()
                    .map(node -> node == nestedNode || node.isAncestorOf(nestedNode))
                    .orElse(false);
                result.add(0, inElse ? negate(parent.getCondition()) : parent.getCondition().toString());
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return result;
    }

    /**
     * 收集目标 throw 分支之前的异常保护条件的安全反向条件。
     * 这些条件用于生成目标异常路径时先绕过更早的无关异常分支。
     */
    private List<String> precedingThrowGuardSafeConditions(MethodDeclaration methodDeclaration, IfStmt targetIf) {
        List<String> preconditions = new ArrayList<>();
        if (targetIf.getBegin().isEmpty()) {
            return preconditions;
        }
        int targetLine = targetIf.getBegin().get().line;
        for (IfStmt ifStmt : methodDeclaration.findAll(IfStmt.class)) {
            if (ifStmt == targetIf || ifStmt.getBegin().isEmpty()) {
                continue;
            }
            if (ifStmt.getBegin().get().line >= targetLine) {
                continue;
            }
            if (firstDirectThrow(ifStmt.getThenStmt()) != null) {
                preconditions.add(negate(ifStmt.getCondition()));
            }
        }
        return preconditions;
    }

    /**
     * 构造 switch case 的可读条件。
     * default 分支单独标记，方便 UI 和异常场景说明用户输入会落入兜底分支。
     */
    private String switchCondition(String selector, SwitchEntry entry) {
        if (entry.getLabels().isEmpty()) {
            return selector + " default";
        }
        List<String> labels = new ArrayList<>();
        entry.getLabels().forEach(label -> labels.add(selector + " == " + label));
        return String.join(" || ", labels);
    }

    /**
     * 查找 switch 分支中的直接 throw，避免把嵌套 if 的条件 throw 错当成整个 case 的动作。
     */
    private ThrowStmt firstThrowStatement(SwitchEntry entry) {
        for (Statement statement : entry.getStatements()) {
            ThrowStmt direct = firstDirectThrow(statement);
            if (direct != null) return direct;
        }
        return null;
    }

    /** 只沿当前 block 的顶层语句查找，明确排除嵌套控制结构里的 throw。 */
    private ThrowStmt firstDirectThrow(Statement statement) {
        if (statement == null) return null;
        if (statement.isThrowStmt()) return statement.asThrowStmt();
        if (!statement.isBlockStmt()) return null;
        for (Statement nested : statement.asBlockStmt().getStatements()) {
            if (nested.isThrowStmt()) return nested.asThrowStmt();
        }
        return null;
    }

    private boolean hasDirectReturn(Statement statement) {
        if (statement == null) return false;
        if (statement.isReturnStmt()) return true;
        if (!statement.isBlockStmt()) return false;
        return statement.asBlockStmt().getStatements().stream().anyMatch(Statement::isReturnStmt);
    }

    /**
     * 对简单条件表达式生成安全反向条件。
     * 优先替换常见比较运算符，复杂表达式退回为整体取反以保留语义。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param expression 需要分析或取反的表达式节点
     * @return 取反后的条件表达式文本
     */
    public String negate(Expression expression) {
        if (expression != null && expression.isBinaryExpr()) {
            BinaryExpr binary = expression.asBinaryExpr();
            if (binary.getOperator() == BinaryExpr.Operator.AND) {
                return "(" + negate(binary.getLeft()) + ") || (" + negate(binary.getRight()) + ")";
            }
            if (binary.getOperator() == BinaryExpr.Operator.OR) {
                return "(" + negate(binary.getLeft()) + ") && (" + negate(binary.getRight()) + ")";
            }
            String inverse = inverseOperator(binary.getOperator());
            if (inverse != null) {
                return binary.getLeft() + " " + inverse + " " + binary.getRight();
            }
        }
        return negate(expression == null ? "" : expression.toString());
    }

    /**
     * 对简单条件表达式生成安全反向条件。
     * 优先替换常见比较运算符，复杂表达式退回为整体取反以保留语义。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param condition 需要取反或标准化的条件表达式文本
     * @return 取反后的条件表达式文本
     */
    public String negate(String condition) {
        if (condition == null || condition.isBlank()) {
            return "";
        }

        String trimmed = trimOuterParentheses(condition.trim());
        String replacement = replaceOperator(trimmed, "==", "!=");
        if (replacement != null) {
            return replacement;
        }
        replacement = replaceOperator(trimmed, "!=", "==");
        if (replacement != null) {
            return replacement;
        }
        replacement = replaceOperator(trimmed, ">=", "<");
        if (replacement != null) {
            return replacement;
        }
        replacement = replaceOperator(trimmed, ">", "<=");
        if (replacement != null) {
            return replacement;
        }
        replacement = replaceOperator(trimmed, "<=", ">");
        if (replacement != null) {
            return replacement;
        }
        replacement = replaceOperator(trimmed, "<", ">=");
        if (replacement != null) {
            return replacement;
        }
        return "!(" + trimmed + ")";
    }

    /**
     * 把二元表达式转换为约束模型。
     * 仅当表达式至少一侧是方法参数时才生成约束，减少无关条件污染生成结果。
     */
    private ConstraintModel fromBinaryExpr(BinaryExpr expression, Set<String> parameterNames, String source, boolean exceptionGuard) {
        String left = expression.getLeft().toString();
        String right = expression.getRight().toString();
        boolean leftIsParameter = parameterNames.contains(left);
        boolean rightIsParameter = parameterNames.contains(right);

        if (!leftIsParameter && !rightIsParameter) {
            return null;
        }

        String kind = kindOf(expression, leftIsParameter, rightIsParameter, left, right);
        if (kind == null) {
            return null;
        }

        ConstraintModel constraint = new ConstraintModel();
        String parameterName = leftIsParameter ? left : right;
        boolean nullComparison = isNullComparison(expression, left, right);
        constraint.setParameterName(parameterName);
        constraint.setExpression(nullComparison
                ? parameterName + " " + expression.getOperator().asString() + " null"
                : expression.toString());
        constraint.setSafeExpression(nullComparison
                ? parameterName + " " + inverseOperator(expression.getOperator()) + " null"
                : negate(expression));
        constraint.setKind(exceptionGuard ? "EXCEPTION_GUARD" : kind);
        constraint.setSource(source);
        constraint.setConfidence(0.8d);
        return constraint;
    }

    /**
     * 判断约束表达式的业务分类。
     * 分类结果决定后续是按空值、范围还是参数关系处理测试输入。
     */
    private String kindOf(BinaryExpr expression, boolean leftIsParameter, boolean rightIsParameter, String left, String right) {
        BinaryExpr.Operator operator = expression.getOperator();
        if ((operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS)
                && ("null".equals(left) || "null".equals(right))) {
            return operator == BinaryExpr.Operator.EQUALS ? "NULL_CHECK" : "NOT_NULL_CHECK";
        }
        if (leftIsParameter && rightIsParameter) {
            return "RELATION";
        }
        if (operator == BinaryExpr.Operator.LESS || operator == BinaryExpr.Operator.LESS_EQUALS
                || operator == BinaryExpr.Operator.GREATER || operator == BinaryExpr.Operator.GREATER_EQUALS) {
            return "RANGE";
        }
        return null;
    }

    private boolean isNullComparison(BinaryExpr expression, String left, String right) {
        return (expression.getOperator() == BinaryExpr.Operator.EQUALS
                || expression.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                && ("null".equals(left) || "null".equals(right));
    }

    private String inverseOperator(BinaryExpr.Operator operator) {
        if (operator == BinaryExpr.Operator.EQUALS) return "!=";
        if (operator == BinaryExpr.Operator.NOT_EQUALS) return "==";
        if (operator == BinaryExpr.Operator.GREATER) return "<=";
        if (operator == BinaryExpr.Operator.GREATER_EQUALS) return "<";
        if (operator == BinaryExpr.Operator.LESS) return ">=";
        if (operator == BinaryExpr.Operator.LESS_EQUALS) return ">";
        return null;
    }

    /**
     * 从 throw 语句中提取异常类型。
     * 只识别直接 new 出来的异常；其他复杂写法使用 RuntimeException 兜底。
     */
    private String extractExceptionType(ThrowStmt throwStmt) {
        if (throwStmt.getExpression().isObjectCreationExpr()) {
            return throwStmt.getExpression().asObjectCreationExpr().getType().asString();
        }
        return "RuntimeException";
    }

    /**
     * 尝试替换简单比较运算符。
     * 遇到复合逻辑时返回 null，让上层使用整体取反，避免错误改写语义。
     */
    private String replaceOperator(String condition, String operator, String inverse) {
        int index = condition.indexOf(operator);
        if (index < 0) {
            return null;
        }
        String left = condition.substring(0, index).trim();
        String right = condition.substring(index + operator.length()).trim();
        if (left.isEmpty() || right.isEmpty() || right.contains("&&") || right.contains("||")) {
            return null;
        }
        return left + " " + inverse + " " + right;
    }

    /**
     * 去除条件表达式最外层括号。
     * 只做一层轻量处理，避免在不了解完整表达式结构时破坏源码语义。
     */
    private String trimOuterParentheses(String condition) {
        if (condition.startsWith("(") && condition.endsWith(")")) {
            return condition.substring(1, condition.length() - 1).trim();
        }
        return condition;
    }
}
