package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RuntimeFailurePhase;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;

import java.util.*;
import java.util.regex.Pattern;

/** Facts about fixture initialization are separate from business execution and null dependencies. */
public final class FixtureLifecycleAnalyzer {
    public static final String CATEGORY = "MOCKITO_FIXTURE_INITIALIZATION_FAILURE";
    private FixtureLifecycleAnalyzer() {}

    /** Generation preflight: a proven parameter-only constructor cannot serve an uninitialized @Spy. */
    public static List<CompileDiagnostic> preflight(String productionSource, String code) {
        try {
            var production = StaticJavaParser.parse(productionSource);
            var test = StaticJavaParser.parse(code);
            List<CompileDiagnostic> diagnostics = new ArrayList<>();
            for (ClassOrInterfaceDeclaration owner : test.findAll(ClassOrInterfaceDeclaration.class)) {
                for (FieldDeclaration field : owner.getFields()) {
                    if (!mockitoAnnotation(field, "Spy") || mockitoAnnotation(field, "InjectMocks")) continue;
                    for (VariableDeclarator variable : field.getVariables()) {
                        if (variable.getInitializer().isPresent() && !variable.getInitializer().get().isNullLiteralExpr()) continue;
                        if (!extension(owner, test) && !manualAnnotationInitialization(owner, variable.getNameAsString(), test)) continue;
                        if (!parameterOnlyConstructor(production, test, variable.getType().asString())) continue;
                        int line = field.getBegin().map(p -> p.line).orElse(-1);
                        diagnostics.add(new CompileDiagnostic("ERROR", "FIXTURE_INITIALIZATION_CONFLICT",
                                "@Spy 字段 " + variable.getNameAsString() + " 的真实类型 " + variable.getTypeAsString()
                                        + " 没有无参构造器，但 Mockito 注解初始化会在该字段手动赋值前尝试创建它。"
                                        + "在依赖初始化后手动构造并显式 Mockito.spy，或按既定策略去掉不需要的 Spy；"
                                        + "不要给生产类添加无参构造器，不要把依赖提前读取为 null，保持测试正文和 Oracle。",
                                line, -1, field.toString(), true));
                    }
                }
            }
            return diagnostics;
        } catch (RuntimeException ignored) { return List.of(); }
    }

    /** Requires actual Mockito initialization evidence AND the matching test declaration. */
    public static void enrich(RuntimeDiagnostic diagnostic, String code) {
        if (diagnostic != null) enrich(List.of(diagnostic), code);
    }

    public static void enrich(List<RuntimeDiagnostic> diagnostics, String code) {
        if (diagnostics == null || diagnostics.stream().filter(Objects::nonNull).noneMatch(d -> d.getMessage().contains("Unable to initialize @Spy annotated field"))) return;
        try {
            var unit = StaticJavaParser.parse(code);
            diagnostics.forEach(d -> enrich(d, unit));
        } catch (RuntimeException ignored) { /* syntax/unknown binding remains with the compiler or observed category */ }
    }

    private static void enrich(RuntimeDiagnostic diagnostic, CompilationUnit unit) {
        if (diagnostic == null) return;
        String evidence = diagnostic.getExceptionType() + "\n" + diagnostic.getMessage() + "\n"
                + diagnostic.getRootCauseType() + "\n" + diagnostic.getFailureDetails() + "\n" + String.join("\n", diagnostic.getStackTrace());
        if (!diagnostic.getExceptionType().startsWith("org.mockito.")
                || !evidence.contains("Unable to initialize @Spy annotated field")
                || !(evidence.contains("NoSuchMethodException") || diagnostic.getCauseChain().stream().anyMatch(c -> c.contains("NoSuchMethodException")))
                || !(evidence.contains("SpyAnnotationEngine") || evidence.contains("no-arg constructor"))) return;
        try {
            String testClass = diagnostic.getTestId().split("#")[0].replace('$', '.');
            String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
            var owners = unit.findAll(ClassOrInterfaceDeclaration.class).stream().filter(c -> c.getNameAsString().equals(simple)).toList();
            if (owners.size() != 1) return;
            var owner = owners.get(0);
            var frames = diagnostic.getStackTrace().stream().filter(f -> f.strip().startsWith("at ")).toList();
            if (frames.stream().noneMatch(f -> f.contains("org.mockito.internal.configuration.SpyAnnotationEngine."))) return;
            String methodName = diagnostic.getTestId().substring(diagnostic.getTestId().indexOf('#') + 1).replaceFirst("\\(.*", "");
            if (frames.stream().anyMatch(f -> sourceMethodFrame(f, owner.getNameAsString(), methodName))) return;
            boolean setupFrame = frames.stream().anyMatch(f -> f.contains("org.mockito.junit.jupiter.MockitoExtension.beforeEach(")
                    || f.contains("org.mockito.internal.runners.")) || owner.getMethods().stream()
                    .filter(m -> m.getAnnotations().stream().anyMatch(a -> annotationIs(a, unit, "org.junit.jupiter.api.BeforeEach") || annotationIs(a, unit, "org.junit.Before")))
                    .anyMatch(m -> frames.stream().anyMatch(f -> sourceMethodFrame(f, owner.getNameAsString(), m.getNameAsString())));
            if (!setupFrame) return;
            for (FieldDeclaration field : owner.getFields()) {
                if (!mockitoAnnotation(field, "Spy") || mockitoAnnotation(field, "InjectMocks")) continue;
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    if (!Pattern.compile("@Spy annotated field ['\"]" + Pattern.quote(name) + "['\"]").matcher(diagnostic.getMessage()).find()) continue;
                    if (variable.getInitializer().isPresent() && !variable.getInitializer().get().isNullLiteralExpr()) continue;
                    // A constructor which WAS entered and threw a reflection error is not a missing default constructor.
                    String type = variable.getType().asString().replaceFirst("<.*", "");
                    String simpleType = type.substring(type.lastIndexOf('.') + 1);
                    if (!Pattern.compile(Pattern.quote(simpleType) + "\\.<init>\\(\\)").matcher(evidence).find()) continue;
                    if (diagnostic.getStackTrace().stream().anyMatch(frame -> frame.strip().startsWith("at ")
                            && Pattern.compile("[.$]" + Pattern.quote(simpleType) + "\\.<init>\\(").matcher(frame).find())) continue;
                    diagnostic.setCategory(CATEGORY);
                    diagnostic.setFailurePhase(RuntimeFailurePhase.TEST_SETUP);
                    diagnostic.setFixtureField(name);
                    diagnostic.setSourceLine(field.getBegin().map(p -> p.line).orElse(-1));
                    diagnostic.setSourceSnippet(field.toString());
                    diagnostic.setLifecycleEvidence(List.of("Mockito @Spy 初始化异常及 NoSuchMethodException", "当前测试类声明未初始化的 @Spy " + variable.getTypeAsString() + " " + name));
                    diagnostic.setSuggestion("失败发生在测试初始化阶段，测试方法尚未执行。修复共享字段/生命周期："
                            + "按真实构造器在 @Mock 依赖初始化后构造 " + name
                            + "，需要保留 Spy 时显式 Mockito.spy；不要依赖稍后的 @BeforeEach 赋值去阻止更早的自动构造。"
                            + "测试方法正文、断言、verify、期望异常和生产源码必须保持不变；不得归类为构造依赖 NPE。");
                    return;
                }
            }
        } catch (RuntimeException ignored) { /* retain observed runtime diagnosis */ }
    }

    public static boolean allowsSharedFixtureRepair(List<RuntimeDiagnostic> diagnostics) {
        return diagnostics != null && !diagnostics.isEmpty() && diagnostics.stream().filter(Objects::nonNull)
                .anyMatch(d -> CATEGORY.equals(d.getCategory()) && d.getFailurePhase() == RuntimeFailurePhase.TEST_SETUP
                        && !d.getFixtureField().isBlank() && !d.getLifecycleEvidence().isEmpty());
    }

    private static boolean sourceMethodFrame(String frame, String type, String method) {
        return Pattern.compile("[.$]" + Pattern.quote(type) + "\\." + Pattern.quote(method) + "\\(").matcher(frame).find();
    }

    private static boolean mockitoAnnotation(FieldDeclaration field, String name) {
        var unit = field.findCompilationUnit().orElseThrow();
        if (unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> name.equals(t.getNameAsString()))) return false;
        return field.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("org.mockito." + name)
                || a.getNameAsString().equals(name) && unit.getImports().stream().anyMatch(i -> !i.isStatic()
                && (i.getNameAsString().equals("org.mockito." + name) || i.isAsterisk() && i.getNameAsString().equals("org.mockito")))
                && unit.getImports().stream().noneMatch(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name)
                && !i.getNameAsString().equals("org.mockito." + name)));
    }

    private static boolean extension(ClassOrInterfaceDeclaration owner, CompilationUnit test) {
        return owner.getAnnotations().stream().anyMatch(a -> annotationIs(a, test, "org.junit.jupiter.api.extension.ExtendWith")
                && (a.toString().contains("org.mockito.junit.jupiter.MockitoExtension.class")
                || a.toString().contains("MockitoExtension.class") && test.getImports().stream().anyMatch(i ->
                i.getNameAsString().equals("org.mockito.junit.jupiter.MockitoExtension"))
                && test.findAll(TypeDeclaration.class).stream().noneMatch(t -> t.getNameAsString().equals("MockitoExtension"))));
    }

    private static boolean annotationIs(AnnotationExpr annotation, CompilationUnit unit, String type) {
        if (annotation.getNameAsString().equals(type)) return true;
        String name = type.substring(type.lastIndexOf('.') + 1);
        if (!annotation.getNameAsString().equals(name) || unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> t.getNameAsString().equals(name))) return false;
        var exact = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name)).toList();
        if (!exact.isEmpty()) return exact.stream().allMatch(i -> i.getNameAsString().equals(type));
        return unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk() && i.getNameAsString().equals(type.substring(0, type.lastIndexOf('.'))));
    }

    private static boolean manualAnnotationInitialization(ClassOrInterfaceDeclaration owner, String field, CompilationUnit test) {
        for (MethodDeclaration method : owner.getMethods()) {
            if (method.getBody().isEmpty() || method.getAnnotations().stream().noneMatch(a -> annotationIs(a, test, "org.junit.jupiter.api.BeforeEach")
                    || annotationIs(a, test, "org.junit.Before"))) continue;
            for (var statement : method.getBody().get().getStatements()) {
                if (!statement.isExpressionStmt()) continue;
                if (statement.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().toString().equals(field) || a.getTarget().toString().equals("this." + field))) break;
                boolean initialized = statement.findAll(MethodCallExpr.class).stream().anyMatch(c ->
                        c.findAncestor(LambdaExpr.class).isEmpty() && Set.of("openMocks", "initMocks").contains(c.getNameAsString())
                                && c.getArguments().size() == 1 && c.getArgument(0).isThisExpr()
                                && (c.getScope().map(Object::toString).orElse("").equals("org.mockito.MockitoAnnotations")
                                || c.getScope().map(Object::toString).orElse("").equals("MockitoAnnotations")
                                && test.getImports().stream().anyMatch(i -> i.getNameAsString().equals("org.mockito.MockitoAnnotations"))
                                && test.findAll(VariableDeclarator.class).stream().noneMatch(v -> v.getNameAsString().equals("MockitoAnnotations"))
                                && test.findAll(TypeDeclaration.class).stream().noneMatch(t -> t.getNameAsString().equals("MockitoAnnotations"))));
                if (initialized) return true;
            }
        }
        return false;
    }

    private static boolean parameterOnlyConstructor(CompilationUnit production, CompilationUnit test, String type) {
        for (ClassOrInterfaceDeclaration declaration : production.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqn = declaration.getFullyQualifiedName().orElse("");
            var imports = test.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk()
                    && i.getName().getIdentifier().equals(declaration.getNameAsString())).toList();
            boolean sameType = type.equals(fqn) || type.equals(declaration.getNameAsString())
                    && (!imports.isEmpty() ? imports.stream().allMatch(i -> i.getNameAsString().equals(fqn))
                    : declaration.getParentNode().filter(CompilationUnit.class::isInstance).isPresent()
                    && production.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("").equals(test.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("")));
            if (!sameType || declaration.isInterface() || declaration.isAbstract()) continue;
            var annotations = declaration.getAnnotations().stream().map(a -> a.getName().getIdentifier()).toList();
            if (annotations.contains("NoArgsConstructor")) return false;
            if (!declaration.getConstructors().isEmpty()) return declaration.getConstructors().stream().noneMatch(c -> c.getParameters().isEmpty());
            if (annotations.contains("RequiredArgsConstructor")) return declaration.getFields().stream().filter(f -> !f.isStatic())
                    .anyMatch(f -> (f.isFinal() || f.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("NonNull")))
                            && f.getVariables().stream().anyMatch(v -> v.getInitializer().isEmpty()));
            if (annotations.contains("AllArgsConstructor")) return declaration.getFields().stream().anyMatch(f -> !f.isStatic());
        }
        return false;
    }
}
