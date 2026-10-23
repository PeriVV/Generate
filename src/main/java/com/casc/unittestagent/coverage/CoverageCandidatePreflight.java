package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.generator.MockitoReceiverClassifier;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.body.Parameter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 在启动 javac/JUnit 前拒绝已知会造成补测反复回归的高风险结构。
 * 这里只处理能够从 AST 确定的问题，不尝试猜测业务语义。
 */
public final class CoverageCandidatePreflight {
    private static final Set<String> MATCHERS = Set.of(
            "any", "anyInt", "anyLong", "anyBoolean", "anyByte", "anyShort", "anyChar",
            "anyFloat", "anyDouble", "anyString", "anyList", "anySet", "anyMap",
            "anyCollection", "anyIterable", "eq", "same", "isA", "isNull", "notNull",
            "nullable", "contains", "matches", "startsWith", "endsWith", "argThat");
    private static final Set<String> MATCHER_CONTEXTS = Set.of("when", "verify", "given", "will");

    private CoverageCandidatePreflight() { }

    public static List<CompileDiagnostic> inspect(String code) {
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        if (code == null || code.isBlank()) return diagnostics;
        try {
            CompilationUnit unit = StaticJavaParser.parse(code);
            Set<String> mockReceivers = mockReceivers(unit);
            Set<String> spyReceivers = spyReceivers(unit);
            Set<String> realReceivers = realReceivers(unit);
            for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
                if (ReflectiveAssertionNormalizer.isUnsafeAssertThrows(call)) {
                    diagnostics.add(diagnostic("REFLECTION_ASSERT_THROWS_WRAPS_CAUSE", call,
                            "Method.invoke 会把目标异常包装为 InvocationTargetException。"
                                    + "请保持当前 assertThrows 的目标异常类型，并在 lambda 或反射 helper 中捕获 "
                                    + "InvocationTargetException 后抛出 ex.getCause()；不要把期望类型改成包装异常。"));
                } else if (isMockitoWhen(unit, call)) {
                    inspectWhen(call, mockReceivers, spyReceivers, realReceivers, diagnostics);
                } else if ("getDeclaredField".equals(call.getNameAsString())) {
                    diagnostics.add(diagnostic("REFLECTION_FIELD_ACCESS_UNSAFE", call,
                            reflectionFieldMessage(call)));
                } else if (isPotentialMatcher(unit, call) && !insideMockitoMatcherContext(call)) {
                    diagnostics.add(diagnostic("MOCKITO_MATCHER_OUTSIDE_STUBBING", call,
                            "Mockito matcher " + call.getNameAsString()
                                    + "(...) 只能用于 when/given/verify/doReturn().when() 等 stubbing/verification；"
                                    + "普通构造函数和真实方法调用必须传具体值。"));
                }
            }
        } catch (Exception ignored) {
            // Syntax/compiler diagnostics remain the source of truth for unparsable Java.
        }
        return diagnostics;
    }

    /**
     * 写入门禁专用：preflight 引导规则只应用于 UnitMind 生成的测试区间
     * （TestClassMerger 写入的 “// region UnitMind generated tests for …” 到 “// endregion”）。
     * 合并文件中用户既有测试的合法遗留写法（@Spy+when(spy.x())、getDeclaredField 等）
     * 不应被候选引导规则误判为编译失败并阻止写入——既有测试本身是 Oracle 权威。
     * 无任何 region 标记时视为纯生成文件，保持全量检查（覆盖率闭环候选）。
     */
    public static List<CompileDiagnostic> inspectGeneratedRegions(String code) {
        List<CompileDiagnostic> diagnostics = inspect(code);
        if (diagnostics.isEmpty()) return diagnostics;
        List<int[]> regions = generatedRegions(code);
        if (regions.isEmpty()) return diagnostics;
        List<CompileDiagnostic> filtered = new ArrayList<>();
        for (CompileDiagnostic diagnostic : diagnostics) {
            long line = diagnostic.getLine();
            if (line <= 0 || withinAny(regions, (int) line)) filtered.add(diagnostic);
        }
        return filtered;
    }

    private static List<int[]> generatedRegions(String code) {
        List<int[]> regions = new ArrayList<>();
        if (code == null) return regions;
        String[] lines = code.split("\\R", -1);
        int start = -1;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.startsWith("// region UnitMind generated tests for ")) {
                start = index + 1;
            } else if (start > 0 && line.equals("// endregion")) {
                regions.add(new int[]{start, index + 1});
                start = -1;
            }
        }
        return regions;
    }

    private static boolean withinAny(List<int[]> regions, int line) {
        for (int[] region : regions) {
            if (line >= region[0] && line <= region[1]) return true;
        }
        return false;
    }

    private static void inspectWhen(MethodCallExpr when,
                                    Set<String> mockReceivers,
                                    Set<String> spyReceivers,
                                    Set<String> realReceivers,
                                    List<CompileDiagnostic> diagnostics) {
        if (when.getArguments().isEmpty()) {
            diagnostics.add(diagnostic("MOCKITO_WHEN_WITHOUT_INVOCATION", when,
                    "Mockito.when(...) 中必须是对 mock 的方法调用，当前没有调用参数。"));
            return;
        }
        Expression argument = unwrap(when.getArgument(0));
        if (!argument.isMethodCallExpr()) {
            diagnostics.add(diagnostic("MOCKITO_WHEN_WITHOUT_INVOCATION", when,
                    "Mockito.when(...) 的参数不是方法调用：" + argument
                            + "。请删除该 stubbing，或改为 when(mock.method(...))。"));
            return;
        }
        MethodCallExpr invocation = argument.asMethodCallExpr();
        String methodName = invocation.getNameAsString();
        if ("equals".equals(methodName) || "hashCode".equals(methodName)) {
            diagnostics.add(diagnostic("MOCKITO_UNSTUBBABLE_OBJECT_METHOD", when,
                    "Mockito 不允许 stub equals()/hashCode()。请删除该 when，改为构造输入和断言真实行为。"));
            return;
        }
        if (invocation.getScope().isEmpty()) {
            diagnostics.add(diagnostic("MOCKITO_WHEN_ON_REAL_OBJECT", when,
                    "when(" + invocation + ") 调用了测试类自身/未限定的方法，不是 mock 调用。"
                            + "请 stub 被测对象的 @Mock 依赖，或直接构造触发分支的输入。"));
            return;
        }
        Expression scope = unwrap(invocation.getScope().get());
        String receiver = receiverName(scope);
        if (scope.isObjectCreationExpr() || realReceivers.contains(receiver)
                || scope.isThisExpr() || MockitoReceiverClassifier.isTypeReceiver(scope)) {
            String display = scope.toString();
            diagnostics.add(diagnostic("MOCKITO_WHEN_ON_REAL_OBJECT", when,
                    "when(...) 的接收者 '" + display + "' 是真实对象、@InjectMocks 被测对象或静态类型，"
                            + "不是 Mockito mock。请删除该 stubbing；通过构造对象状态/方法输入触发分支，"
                            + "或改为 stub 该对象实际调用的 @Mock 依赖。仅当对象本身应被替身时才用 Mockito.mock 创建它。"));
            return;
        }
        if (!receiver.isBlank() && spyReceivers.contains(receiver)) {
            diagnostics.add(diagnostic("MOCKITO_WHEN_ON_SPY", when,
                    "when(...) 会立即执行 spy '" + receiver + "' 的真实方法，可能在 stubbing 阶段触发副作用、"
                            + "嵌套调用或 WrongTypeOfReturnValue。请改用 doReturn(value).when("
                            + receiver + ")." + methodName + "(...)；返回值必须与方法声明类型一致。"));
            return;
        }
        // 已知 mock 是合法的。未知接收者可能来自 helper 参数或工厂，留给真实 Mockito 运行判断。
        if (!receiver.isBlank() && mockReceivers.contains(receiver)) return;
    }

    /**
     * 反射猜测私有字段的替代路径提示：修复循环必须被引向真实构造器或 mock 注入，
     * 否则会反复回归到 getDeclaredField。构造器签名在 context.symbolSnapshot.types
     * 中提供（被测类字段类型已兜底收录，Lombok 构造器已按语义合成）。
     */
    private static String reflectionFieldMessage(MethodCallExpr call) {
        String field = call.getArguments().size() == 1
                && call.getArgument(0) instanceof LiteralStringValueExpr literal
                ? literal.getValue() : "";
        return "覆盖率补测禁止通过 getDeclaredField 猜测"
                + (field.isBlank() ? "私有字段" : "私有字段 '" + field + "'")
                + "。请改用：context.symbolSnapshot.types 中该字段类型的真实构造器签名构造对象状态，"
                + "或通过可见 API/setter、方法输入命中分支；"
                + "被测类依赖字段应使用 @Mock + @InjectMocks 注入，不要反射改写。";
    }

    private static boolean isMockitoWhen(CompilationUnit unit, MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!"when".equals(name) && !"given".equals(name)) return false;
        String owner = "given".equals(name) ? "org.mockito.BDDMockito" : "org.mockito.Mockito";
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            return owner.equals(scope) || owner.substring(owner.lastIndexOf('.') + 1).equals(scope);
        }
        return unit.getImports().stream().anyMatch(declaration -> declaration.isStatic()
                && ((owner + "." + name).equals(declaration.getNameAsString())
                || declaration.isAsterisk()
                && owner.equals(declaration.getNameAsString())));
    }

    private static Set<String> mockReceivers(CompilationUnit unit) {
        Set<String> names = new LinkedHashSet<>();
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            if (hasAnyAnnotation(field, "Mock", "Spy", "MockBean", "MockitoBean")) {
                field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
            }
        }
        for (Parameter parameter : unit.findAll(Parameter.class)) {
            if (hasAnyAnnotation(parameter, "Mock", "Spy", "MockBean", "MockitoBean")) {
                names.add(parameter.getNameAsString());
            }
        }
        for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
            variable.getInitializer().filter(CoverageCandidatePreflight::isMockFactory)
                    .ifPresent(ignored -> names.add(variable.getNameAsString()));
        }
        for (AssignExpr assignment : unit.findAll(AssignExpr.class)) {
            if (assignment.getTarget().isNameExpr() && isMockFactory(assignment.getValue())) {
                names.add(assignment.getTarget().asNameExpr().getNameAsString());
            }
        }
        return names;
    }

    private static Set<String> spyReceivers(CompilationUnit unit) {
        Set<String> names = new LinkedHashSet<>();
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            if (hasAnyAnnotation(field, "Spy")) {
                field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
            }
        }
        for (Parameter parameter : unit.findAll(Parameter.class)) {
            if (hasAnyAnnotation(parameter, "Spy")) names.add(parameter.getNameAsString());
        }
        for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
            variable.getInitializer().filter(CoverageCandidatePreflight::isSpyFactory)
                    .ifPresent(ignored -> names.add(variable.getNameAsString()));
        }
        for (AssignExpr assignment : unit.findAll(AssignExpr.class)) {
            if (assignment.getTarget().isNameExpr() && isSpyFactory(assignment.getValue())) {
                names.add(assignment.getTarget().asNameExpr().getNameAsString());
            }
        }
        return names;
    }

    private static Set<String> realReceivers(CompilationUnit unit) {
        Set<String> names = new LinkedHashSet<>();
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            if (hasAnyAnnotation(field, "InjectMocks")) {
                field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
            }
        }
        for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
            variable.getInitializer().map(CoverageCandidatePreflight::unwrap)
                    .filter(Expression::isObjectCreationExpr)
                    .ifPresent(ignored -> names.add(variable.getNameAsString()));
        }
        for (AssignExpr assignment : unit.findAll(AssignExpr.class)) {
            if (unwrap(assignment.getValue()).isObjectCreationExpr()) {
                String target = receiverName(assignment.getTarget());
                if (!target.isBlank()) names.add(target);
            }
        }
        names.removeAll(mockReceivers(unit));
        return names;
    }

    private static boolean hasAnyAnnotation(Node node, String... names) {
        Set<String> expected = Set.of(names);
        if (node instanceof FieldDeclaration field) {
            return field.getAnnotations().stream()
                    .anyMatch(annotation -> expected.contains(annotation.getName().getIdentifier()));
        }
        if (node instanceof Parameter parameter) {
            return parameter.getAnnotations().stream()
                    .anyMatch(annotation -> expected.contains(annotation.getName().getIdentifier()));
        }
        return false;
    }

    private static boolean isMockFactory(Expression expression) {
        Expression value = unwrap(expression);
        if (!value.isMethodCallExpr()) return false;
        String name = value.asMethodCallExpr().getNameAsString();
        return "mock".equals(name) || "spy".equals(name);
    }

    private static boolean isSpyFactory(Expression expression) {
        Expression value = unwrap(expression);
        return value.isMethodCallExpr() && "spy".equals(value.asMethodCallExpr().getNameAsString());
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current.isEnclosedExpr() || current.isCastExpr()) {
            current = current.isEnclosedExpr()
                    ? current.asEnclosedExpr().getInner() : current.asCastExpr().getExpression();
        }
        return current;
    }

    private static String receiverName(Expression expression) {
        Expression current = unwrap(expression);
        if (current.isNameExpr()) return current.asNameExpr().getNameAsString();
        if (current.isFieldAccessExpr()) {
            FieldAccessExpr field = current.asFieldAccessExpr();
            if (unwrap(field.getScope()) instanceof ThisExpr) return field.getNameAsString();
            return receiverName(field.getScope());
        }
        if (current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()) {
            return receiverName(current.asMethodCallExpr().getScope().get());
        }
        return "";
    }

    private static boolean isPotentialMatcher(CompilationUnit unit, MethodCallExpr call) {
        if (!MATCHERS.contains(call.getNameAsString())) return false;
        if (call.getScope().isEmpty()) return hasMockitoStaticImport(unit, call.getNameAsString());
        String scope = call.getScope().get().toString();
        return scope.endsWith("ArgumentMatchers") || scope.endsWith("Mockito")
                || scope.endsWith("AdditionalMatchers");
    }

    private static boolean hasMockitoStaticImport(CompilationUnit unit, String matcher) {
        return unit.getImports().stream().anyMatch(declaration -> {
            if (!declaration.isStatic()) return false;
            String imported = declaration.getNameAsString();
            boolean mockitoOwner = imported.equals("org.mockito.Mockito")
                    || imported.equals("org.mockito.ArgumentMatchers")
                    || imported.equals("org.mockito.AdditionalMatchers");
            if (declaration.isAsterisk()) return mockitoOwner;
            return imported.equals("org.mockito.Mockito." + matcher)
                    || imported.equals("org.mockito.ArgumentMatchers." + matcher)
                    || imported.equals("org.mockito.AdditionalMatchers." + matcher);
        });
    }

    private static boolean insideMockitoMatcherContext(MethodCallExpr matcher) {
        Node current = matcher;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (!(current instanceof MethodCallExpr)) continue;
            MethodCallExpr call = (MethodCallExpr) current;
            String name = call.getNameAsString().toLowerCase(Locale.ROOT);
            if (MATCHER_CONTEXTS.contains(name)) return true;
            String expression = call.toString();
            if (expression.contains("when(") || expression.contains("verify(")
                    || expression.contains("given(") || expression.contains("will(")
                    || expression.startsWith("doReturn(")
                    || expression.startsWith("doThrow(") || expression.startsWith("doAnswer(")
                    || expression.startsWith("doNothing(")) {
                return true;
            }
        }
        return false;
    }

    private static CompileDiagnostic diagnostic(String code, MethodCallExpr call, String message) {
        int line = call.getRange().map(range -> range.begin.line).orElse(-1);
        int column = call.getRange().map(range -> range.begin.column).orElse(-1);
        var diagnostic = new CompileDiagnostic("ERROR", code, message, line, column, call.toString(), true);
        diagnostic.setValidationStage(com.casc.unittestagent.model.ValidationStage.SOURCE_PREFLIGHT);
        return diagnostic;
    }
}
