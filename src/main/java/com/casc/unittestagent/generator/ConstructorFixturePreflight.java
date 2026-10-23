package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;

import java.util.*;

/** Generation-only repair of explicit null DI arguments. Never writes project PSI or changes assertions. */
public final class ConstructorFixturePreflight {
    public record Result(String code, int changes, List<String> warnings, List<CompileDiagnostic> diagnostics) { }
    private record Slot(PsiType type, String field, boolean required) { }
    private record Constructor(List<Slot> slots, PsiMethod method) { }
    private final Project project;
    private final CompilationUnit test;
    private final GlobalSearchScope scope;
    private final boolean mocks;
    private final Map<PsiClass, List<Constructor>> constructorCache = new HashMap<>();
    private final Map<PsiField, Boolean> requiredCache = new HashMap<>();
    private final Set<MethodDeclaration> negativeHelpers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> negativeOnlyTypes = Collections.newSetFromMap(new IdentityHashMap<>());
    private int allocations;

    private ConstructorFixturePreflight(Project project, PsiFile source, CompilationUnit test, boolean mocks) {
        this.project = project; this.test = test; this.mocks = mocks;
        var module = ProjectFileIndex.getInstance(project).getModuleForFile(source.getVirtualFile());
        scope = module == null ? GlobalSearchScope.projectScope(project)
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true);
        protectNegativeFixtures();
    }

    public static Result prepare(Project project, PsiFile source, String code, boolean allowMocks) {
        if (project == null || source == null || source.getVirtualFile() == null || code == null || code.isBlank())
            return new Result(code, 0, List.of(), List.of());
        CompilationUnit unit;
        try { unit = StaticJavaParser.parse(code); }
        catch (RuntimeException syntax) { return new Result(code, 0, List.of(), List.of()); }
        LexicalPreservingPrinter.setup(unit);
        return ReadAction.compute(() -> {
            if (DumbService.isDumb(project)) return new Result(code, 0, List.of(), List.of());
            return new ConstructorFixturePreflight(project, source, unit, allowMocks).repair(code);
        });
    }

    private Result repair(String original) {
        List<String> warnings = new ArrayList<>();
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        int changes = 0;
        for (ObjectCreationExpr creation : new ArrayList<>(test.findAll(ObjectCreationExpr.class))) {
            if (creation.getAnonymousClassBody().isPresent() || creation.getArguments().stream().noneMatch(arg -> arg.isNullLiteralExpr()) || intentionalNull(creation)) continue;
            PsiClass owner = resolve(creation.getType().getNameWithScope());
            if (!projectSource(owner)) continue;
            List<Constructor> candidates = constructors(owner).stream()
                    .filter(item -> item.slots().size() == creation.getArguments().size()).toList();
            if (candidates.size() != 1) {
                if (constructors(owner).stream().flatMap(item -> item.slots().stream()).anyMatch(Slot::required))
                    diagnostics.add(diagnostic(creation, "构造器重载无法唯一确定：" + owner.getQualifiedName() + "。请显式准备依赖或消除重载歧义。"));
                continue;
            }
            Constructor constructor = candidates.get(0);
            for (int i = 0; i < creation.getArguments().size(); i++) {
                if (!creation.getArgument(i).isNullLiteralExpr()) continue;
                Slot slot = constructor.slots().get(i);
                if (!slot.required()) continue;
                try {
                    String value = existingFixture(creation, slot);
                    if (value == null) { allocations = 0; value = construct(slot.type(), new LinkedHashSet<>(), 0); }
                    creation.getArgument(i).replace(StaticJavaParser.parseExpression(value));
                    changes++;
                    warnings.add("生成前检查已补齐 " + owner.getQualifiedName() + "." + slot.field()
                            + " 的构造依赖（" + slot.type().getCanonicalText() + "）。仍需编译和真实运行验证。");
                } catch (Unresolved failure) {
                    diagnostics.add(diagnostic(creation, owner.getQualifiedName() + "." + slot.field()
                            + " 的构造依赖不能为 null：" + failure.getMessage()));
                }
            }
        }
        return new Result(changes == 0 ? original : LexicalPreservingPrinter.print(test), changes,
                List.copyOf(warnings), List.copyOf(diagnostics));
    }

    private String construct(PsiType value, Set<String> path, int depth) {
        if (!(value instanceof PsiClassType classType) || classType.resolve() == null)
            throw new Unresolved("依赖类型未解析，不能猜测构造方式。");
        PsiClass type = classType.resolve();
        String name = type.getQualifiedName();
        if (name == null || depth >= 6 || ++allocations > 64 || !path.add(name))
            throw new Unresolved("构造依赖循环或超过深度/数量限制，请显式提供 fixture。");
        try {
            if (!accessible(type)) throw new Unresolved("依赖类型不可访问，请使用项目已有工厂或 fixture。");
            if (type.isInterface() || type.hasModifierProperty(PsiModifier.ABSTRACT)) {
                if (!mocks) throw new Unresolved("当前策略禁止新增 Mockito mock，请选择允许的 Mock 策略或提供真实替身。");
                return "org.mockito.Mockito.mock(" + name + ".class)";
            }
            if (!projectSource(type) || type.isEnum() || type.isRecord())
                throw new Unresolved("没有可确认的项目构造依赖信息，请显式提供对象。");
            if (type.getContainingClass() != null && !type.hasModifierProperty(PsiModifier.STATIC))
                throw new Unresolved("非静态内部类需要外部实例，请显式提供 fixture。");
            List<Constructor> constructors = constructors(type);
            if (constructors.size() != 1 || !safeConstructor(constructors.get(0), type))
                throw new Unresolved("构造器存在歧义或包含业务动作，不能自动创建真实依赖。");
            List<String> args = new ArrayList<>();
            for (Slot slot : constructors.get(0).slots()) {
                if (!slot.required()) throw new Unresolved("构造器含值参数或可空参数，无法推断正确业务值。");
                args.add(construct(slot.type(), path, depth + 1));
            }
            return "new " + name + "(" + String.join(", ", args) + ")";
        } finally { path.remove(name); }
    }

    private boolean safeConstructor(Constructor constructor, PsiClass type) {
        if (type.getSuperClass() != null && !"java.lang.Object".equals(type.getSuperClass().getQualifiedName())) return false;
        if (constructor.method() != null) {
            if (!accessible(constructor.method())) return false;
            PsiCodeBlock body = constructor.method().getBody();
            if (body != null) for (PsiStatement statement : body.getStatements()) {
                if (!(statement instanceof PsiExpressionStatement expression)
                        || !(expression.getExpression() instanceof PsiAssignmentExpression assignment)
                        || !(assignment.getLExpression() instanceof PsiReferenceExpression left)
                        || assignment.getOperationTokenType() != JavaTokenType.EQ
                        || !(left.resolve() instanceof PsiField field) || field.getContainingClass() != type
                        || field.hasModifierProperty(PsiModifier.STATIC)
                        || !(assignment.getRExpression() instanceof PsiReferenceExpression right)
                        || !(right.resolve() instanceof PsiParameter)) return false;
            }
        }
        for (PsiField field : type.getFields()) {
            if (required(field) && constructor.slots().stream().noneMatch(slot -> slot.required() && slot.field().equals(field.getName()))) return false;
            if (!field.hasModifierProperty(PsiModifier.STATIC) && field.getInitializer() != null
                    && !(field.getInitializer() instanceof PsiLiteralExpression)) return false;
        }
        return true;
    }

    private List<Constructor> constructors(PsiClass owner) {
        return constructorCache.computeIfAbsent(owner, this::readConstructors);
    }

    private List<Constructor> readConstructors(PsiClass owner) {
        List<Constructor> result = new ArrayList<>();
        for (PsiMethod constructor : owner.getConstructors()) {
            List<Slot> slots = new ArrayList<>();
            for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                PsiField field = null;
                if (constructor.getBody() != null) for (PsiAssignmentExpression assignment :
                        PsiTreeUtil.findChildrenOfType(constructor.getBody(), PsiAssignmentExpression.class)) {
                    if (assignment.getLExpression() instanceof PsiReferenceExpression left
                            && left.resolve() instanceof PsiField assigned && assigned.getContainingClass() == owner
                            && assignment.getRExpression() instanceof PsiReferenceExpression right && right.resolve() == parameter)
                        field = assigned;
                }
                if (constructor.getBody() == null || !constructor.isPhysical()
                        && (annotation(owner, "RequiredArgsConstructor") || annotation(owner, "AllArgsConstructor")))
                    field = owner.findFieldByName(parameter.getName(), false);
                slots.add(new Slot(parameter.getType(), field == null ? parameter.getName() : field.getName(),
                        field != null && !nullable(parameter) && required(field)));
            }
            result.add(new Constructor(List.copyOf(slots), constructor));
        }
        if (!result.isEmpty()) return result;
        boolean all = annotation(owner, "AllArgsConstructor");
        boolean required = annotation(owner, "RequiredArgsConstructor");
        if (all || required) {
            List<Slot> slots = new ArrayList<>();
            for (PsiField field : owner.getFields()) {
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
                if (field.getInitializer() != null && (field.hasModifierProperty(PsiModifier.FINAL) || !all)) continue;
                if (all || field.hasModifierProperty(PsiModifier.FINAL) || annotation(field, "NonNull"))
                    slots.add(new Slot(field.getType(), field.getName(), required(field)));
            }
            return List.of(new Constructor(List.copyOf(slots), null));
        }
        return List.of(new Constructor(List.of(), null));
    }

    private boolean required(PsiField field) {
        return requiredCache.computeIfAbsent(field, this::readRequired);
    }

    private boolean readRequired(PsiField field) {
        if (field.hasModifierProperty(PsiModifier.STATIC) || nullable(field) || !(field.getType() instanceof PsiClassType classType)) return false;
        PsiClass dependency = classType.resolve();
        if (dependency == null || dependency.isEnum() || dependency.isRecord()) return false;
        String name = dependency.getQualifiedName();
        String simple = dependency.getName();
        if (name == null || name.startsWith("java.lang.") || name.startsWith("java.util.")
                || simple == null || simple.matches(".*(?:DTO|Dto|Request|Response|Entity|Payload)$")) return false;
        PsiClass owner = field.getContainingClass();
        if (owner == null) return false;
        for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(owner, PsiReferenceExpression.class)) {
            if (reference.resolve() != field || !(reference.getParent() instanceof PsiReferenceExpression member)
                    || member.getQualifierExpression() != reference) continue;
            PsiMethod method = PsiTreeUtil.getParentOfType(reference, PsiMethod.class);
            if (method == null || method.isConstructor() || nullGuarded(reference, field, method)) continue;
            return true;
        }
        return false;
    }

    private boolean nullGuarded(PsiReferenceExpression reference, PsiField field, PsiMethod method) {
        // Be conservative: any explicit null check of this field makes it an optional dependency.
        for (PsiBinaryExpression condition : PsiTreeUtil.findChildrenOfType(method, PsiBinaryExpression.class)) {
            var left = PsiUtil.skipParenthesizedExprDown(condition.getLOperand());
            var right = PsiUtil.skipParenthesizedExprDown(condition.getROperand());
            if (condition.getOperationTokenType() != JavaTokenType.EQEQ && condition.getOperationTokenType() != JavaTokenType.NE) continue;
            if (left instanceof PsiReferenceExpression ref && ref.resolve() == field && right instanceof PsiLiteralExpression literal && literal.getValue() == null
                    || right instanceof PsiReferenceExpression otherRef && otherRef.resolve() == field && left instanceof PsiLiteralExpression otherLiteral && otherLiteral.getValue() == null) return true;
        }
        return false;
    }

    private String existingFixture(ObjectCreationExpr creation, Slot slot) {
        String local = localFixture(creation, slot);
        if (local != null) return local;
        var method = creation.findAncestor(MethodDeclaration.class);
        var owner = creation.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        if (owner.isEmpty()) return null;
        boolean extension = owner.get().getAnnotations().stream().anyMatch(item ->
                item.getName().getIdentifier().equals("ExtendWith") && item.findAll(com.github.javaparser.ast.expr.ClassExpr.class).stream()
                .anyMatch(value -> value.getTypeAsString().equals("org.mockito.junit.jupiter.MockitoExtension")
                        || value.getTypeAsString().equals("MockitoExtension") && test.getImports().stream().anyMatch(imp ->
                        imp.getNameAsString().equals("org.mockito.junit.jupiter.MockitoExtension")
                                || imp.isAsterisk() && imp.getNameAsString().equals("org.mockito.junit.jupiter"))));
        List<String> matching = new ArrayList<>();
        for (var field : owner.get().getFields()) {
            if (field.isStatic() || field.getAnnotations().stream().noneMatch(item -> item.getName().getIdentifier().equals("Mock"))) continue;
            for (var variable : field.getVariables()) {
                PsiClass type = resolve(variable.getTypeAsString());
                PsiClass wanted = slot.type() instanceof PsiClassType t ? t.resolve() : null;
                if (type != null && wanted != null && type.isEquivalentTo(wanted)) matching.add(variable.getNameAsString());
            }
        }
        if (matching.isEmpty()) return null;
        boolean junitInitialized = method.isPresent() && !method.get().isStatic() && extension
                && method.get().getAnnotations().stream().anyMatch(item -> List.of("BeforeEach", "Test", "ParameterizedTest", "RepeatedTest")
                .contains(item.getName().getIdentifier()));
        if (!junitInitialized && !explicitMockInitialization(creation))
            throw new Unresolved("已有同类型 @Mock，但无法证明构造对象前已初始化；请在 Mockito 初始化之后准备 fixture。");
        if (matching.contains(slot.field())) return "this." + slot.field();
        if (matching.size() == 1) return "this." + matching.get(0);
        if (matching.size() > 1) throw new Unresolved("已有多个同类型 Mock，无法确认应注入哪个字段。");
        return null;
    }

    private boolean explicitMockInitialization(ObjectCreationExpr creation) {
        var block = creation.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class);
        if (block.isEmpty()) return false;
        for (var statement : block.get().getStatements()) {
            if (statement.isAncestorOf(creation)) break;
            if (!statement.isExpressionStmt()) continue;
            var expression = statement.asExpressionStmt().getExpression();
            List<com.github.javaparser.ast.expr.Expression> values = new ArrayList<>();
            if (expression.isVariableDeclarationExpr()) expression.asVariableDeclarationExpr().getVariables()
                    .forEach(variable -> variable.getInitializer().ifPresent(values::add));
            else values.add(expression.isAssignExpr() ? expression.asAssignExpr().getValue() : expression);
            for (var value : values) {
                if (!value.isMethodCallExpr()) continue;
                var call = value.asMethodCallExpr();
                if (!List.of("openMocks", "initMocks").contains(call.getNameAsString()) || call.getArguments().size() != 1
                        || !call.getArgument(0).isThisExpr() || call.findAncestor(com.github.javaparser.ast.expr.ConditionalExpr.class).isPresent()) continue;
                String scope = call.getScope().map(Object::toString).orElse("");
                if (scope.equals("org.mockito.MockitoAnnotations") || scope.equals("MockitoAnnotations")
                        && test.getImports().stream().anyMatch(item -> item.getNameAsString().equals("org.mockito.MockitoAnnotations")
                        || item.isAsterisk() && item.getNameAsString().equals("org.mockito"))) return true;
            }
        }
        return false;
    }

    private String localFixture(ObjectCreationExpr creation, Slot slot) {
        var block = creation.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class);
        if (block.isEmpty()) return null;
        List<String> matches = new ArrayList<>();
        List<com.github.javaparser.ast.stmt.Statement> preceding = new ArrayList<>();
        for (var statement : block.get().getStatements()) {
            if (statement.isAncestorOf(creation)) break;
            preceding.add(statement);
        }
        for (var statement : preceding) {
            if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isVariableDeclarationExpr()) continue;
            for (var variable : statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables()) {
                PsiClass declared = resolve(variable.getTypeAsString());
                PsiClass wanted = slot.type() instanceof PsiClassType type ? type.resolve() : null;
                if (declared == null || wanted == null || !declared.isEquivalentTo(wanted) || variable.getInitializer().isEmpty()) continue;
                var initializer = variable.getInitializer().get();
                boolean nonNull = initializer.isObjectCreationExpr();
                if (initializer.isMethodCallExpr()) {
                    var call = initializer.asMethodCallExpr();
                    String owner = call.getScope().map(Object::toString).orElse("");
                    nonNull = List.of("mock", "spy").contains(call.getNameAsString()) && (owner.equals("org.mockito.Mockito")
                            || owner.equals("Mockito") && test.getImports().stream().anyMatch(item -> item.getNameAsString().equals("org.mockito.Mockito"))
                            || owner.isEmpty() && test.getImports().stream().anyMatch(item -> item.isStatic()
                            && (item.getNameAsString().equals("org.mockito.Mockito." + call.getNameAsString())
                            || item.isAsterisk() && item.getNameAsString().equals("org.mockito.Mockito"))));
                    if (owner.isEmpty() && test.findAll(MethodDeclaration.class).stream()
                            .anyMatch(item -> item.getNameAsString().equals(call.getNameAsString()))) nonNull = false;
                }
                if (!nonNull) continue;
                String name = variable.getNameAsString();
                boolean reassigned = preceding.stream().flatMap(item -> item.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream())
                        .anyMatch(item -> item.getTarget().isNameExpr() && item.getTarget().asNameExpr().getNameAsString().equals(name));
                if (!reassigned) matches.add(name);
            }
        }
        if (matches.contains(slot.field())) return slot.field();
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) throw new Unresolved("已有多个同类型局部对象，无法确认应注入哪个对象。");
        return null;
    }

    private boolean intentionalNull(ObjectCreationExpr creation) {
        if (creation.findAncestor(com.github.javaparser.ast.expr.MethodCallExpr.class,
                call -> call.getNameAsString().startsWith("assert")).isPresent()) return true;
        var method = creation.findAncestor(MethodDeclaration.class);
        return method.map(negativeHelpers::contains).orElse(false) || creation.findAncestor(
                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).map(negativeOnlyTypes::contains).orElse(false);
    }

    private void protectNegativeFixtures() {
        for (var owner : test.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
            ArrayDeque<MethodDeclaration> pending = new ArrayDeque<>();
            for (var method : owner.getMethods()) {
                if (method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream().anyMatch(call ->
                        List.of("assertThrows", "assertThrowsExactly", "assertDoesNotThrow").contains(call.getNameAsString()))
                        || method.getAnnotations().stream().anyMatch(annotation -> annotation.getName().getIdentifier().equals("NullSource"))) pending.add(method);
            }
            while (!pending.isEmpty()) {
                var method = pending.removeFirst();
                if (!negativeHelpers.add(method)) continue;
                Set<String> names = new HashSet<>();
                method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                        .filter(call -> call.getScope().isEmpty() || call.getScope().get().isThisExpr())
                        .forEach(call -> names.add(call.getNameAsString()));
                method.findAll(com.github.javaparser.ast.expr.MethodReferenceExpr.class)
                        .forEach(reference -> names.add(reference.getIdentifier()));
                owner.getMethods().stream().filter(item -> names.contains(item.getNameAsString()) && !negativeHelpers.contains(item)).forEach(pending::add);
            }
            var tests = owner.getMethods().stream().filter(method -> method.getAnnotations().stream().anyMatch(annotation ->
                    List.of("Test", "ParameterizedTest", "RepeatedTest", "TestTemplate", "TestFactory").contains(annotation.getName().getIdentifier()))).toList();
            if (!tests.isEmpty() && tests.stream().allMatch(negativeHelpers::contains)) negativeOnlyTypes.add(owner);
        }
    }

    private PsiClass resolve(String name) {
        String raw = name.replaceAll("<.*>", "").trim();
        Set<String> names = new LinkedHashSet<>(); names.add(raw);
        test.getPackageDeclaration().ifPresent(item -> names.add(item.getNameAsString() + "." + raw));
        for (var item : test.getImports()) {
            if (item.isStatic()) continue;
            if (item.isAsterisk()) names.add(item.getNameAsString() + "." + raw);
            else if (item.getName().getIdentifier().equals(raw)) names.add(item.getNameAsString());
        }
        Set<PsiClass> found = new LinkedHashSet<>();
        for (String candidate : names) {
            PsiClass type = JavaPsiFacade.getInstance(project).findClass(candidate, scope);
            if (type != null) found.add(type);
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }
    private boolean projectSource(PsiClass type) {
        return type != null && type.getContainingFile() != null && type.getContainingFile().getVirtualFile() != null
                && ProjectFileIndex.getInstance(project).isInSourceContent(type.getContainingFile().getVirtualFile());
    }
    private boolean accessible(PsiModifierListOwner member) {
        if (member.hasModifierProperty(PsiModifier.PRIVATE)) return false;
        if (member.hasModifierProperty(PsiModifier.PUBLIC)) return true;
        var file = member.getContainingFile();
        return file instanceof PsiJavaFile java && java.getPackageName().equals(test.getPackageDeclaration()
                .map(item -> item.getNameAsString()).orElse(""));
    }
    private static boolean nullable(PsiModifierListOwner owner) {
        return annotation(owner, "Nullable") || annotation(owner, "CheckForNull");
    }
    private static boolean annotation(PsiModifierListOwner owner, String name) {
        var list = owner.getModifierList();
        return list != null && Arrays.stream(list.getAnnotations()).anyMatch(item ->
                item.getNameReferenceElement() != null && name.equals(item.getNameReferenceElement().getReferenceName()));
    }
    private static CompileDiagnostic diagnostic(ObjectCreationExpr creation, String message) {
        var position = creation.getBegin();
        return new CompileDiagnostic("ERROR", "FIXTURE_CONSTRUCTOR_DEPENDENCY", message,
                position.map(item -> item.line).orElse(-1), position.map(item -> item.column).orElse(-1), creation.toString(), true);
    }
    private static final class Unresolved extends RuntimeException {
        Unresolved(String message) { super(message); }
    }
}
