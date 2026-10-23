package com.casc.unittestagent.coverage;

import com.casc.unittestagent.generator.RepairCandidateRejectedException;
import com.casc.unittestagent.generator.TestCodeNormalizationPipeline;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.util.CodeFingerprint;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.*;

import java.util.*;

/** Source-backed, deliberately partial object graph. Unknown wiring is never proof of absence. */
public final class MockWiringAnalyzer {
    public static final String WIRING = "MOCKITO_FIXTURE_WIRING_FAILURE";
    public static final String INTERNAL = "TEST_INTERNAL_STATE_ORACLE";
    private record Finding(MockWiringEvidence evidence) {}
    private MockWiringAnalyzer() {}

    /** Includes source-backed superclasses; no project files or PSI are modified. */
    public static List<String> sources(PsiFile source) {
        if (source == null) return List.of();
        return ReadAction.compute(() -> {
            LinkedHashSet<String> texts = new LinkedHashSet<>();
            texts.add(source.getText());
            if (source instanceof PsiJavaFile java) for (PsiClass type : java.getClasses()) {
                Set<PsiClass> seen = new HashSet<>();
                for (PsiClass base = type.getSuperClass(); base != null && seen.add(base); base = base.getSuperClass()) {
                    PsiFile file = base.getContainingFile();
                    if (file != null && !(file instanceof PsiCompiledFile)) texts.add(file.getText());
                }
            }
            return List.copyOf(texts);
        });
    }

    public static List<CompileDiagnostic> preflight(List<String> sources, String code) {
        return findings(sources, code).stream().map(f -> {
            var e = f.evidence();
            var d = new CompileDiagnostic("ERROR", e.category(), e.fact() + guidance(e.category()),
                    e.line(), -1, e.snippet(), true);
            d.setValidationStage(ValidationStage.SOURCE_PREFLIGHT);
            return d;
        }).toList();
    }

    /** Requires the real verification failure at the same method AND source line, not model prose. */
    public static void enrich(List<String> sources, String code, List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null) return;
        var findings = findings(sources, code);
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            diagnostic.setMockWiringEvidence(List.of());
            boolean missingInteraction = diagnostic.getExceptionType().equals("org.mockito.exceptions.verification.WantedButNotInvoked");
            boolean realReceiver = diagnostic.getExceptionType().equals("org.mockito.exceptions.misusing.NotAMockException");
            if (!missingInteraction && !realReceiver) continue;
            var matches = findings.stream().map(Finding::evidence).filter(e ->
                    diagnostic.getTestId().replace('$', '.').startsWith(e.testClass() + "#" + e.method() + "(")
                            && diagnostic.getSourceLine() == e.line()
                            && (!realReceiver || INTERNAL.equals(e.category()) && e.subject().isEmpty())).toList();
            if (matches.isEmpty()) continue;
            // An internal-state Oracle cannot be repaired by pretending it is an injectable dependency.
            String category = matches.stream().anyMatch(e -> INTERNAL.equals(e.category())) ? INTERNAL : WIRING;
            diagnostic.setCategory(category);
            diagnostic.setMockWiringEvidence(matches);
            diagnostic.setSourceSnippet(matches.get(0).snippet());
            diagnostic.setSuggestion(matches.get(0).fact() + guidance(category));
        }
    }

    public static boolean allowsWiringRepair(List<RuntimeDiagnostic> diagnostics, String code) {
        if (diagnostics == null || diagnostics.isEmpty()) return false;
        String hash = CodeFingerprint.sha256(code);
        return diagnostics.stream().allMatch(d -> d != null && WIRING.equals(d.getCategory())
                && !d.getMockWiringEvidence().isEmpty() && d.getMockWiringEvidence().stream().allMatch(e ->
                WIRING.equals(e.category()) && hash.equals(e.testHash()) && !e.productionHash().isBlank()
                        && !e.removableAssignment().isBlank()));
    }

    /** Authorization is the exact source-backed removal, never an arbitrary Arrange rewrite. */
    public static void validateWiringRepair(String current, String proposed, List<RuntimeDiagnostic> diagnostics) {
        if (!allowsWiringRepair(diagnostics, current)) {
            if (diagnostics != null && diagnostics.stream().anyMatch(d -> d != null && WIRING.equals(d.getCategory())))
                throw new RepairCandidateRejectedException(RepairCandidateRejectedException.Stage.REPAIR_SCOPE,
                        "当前候选与装配证据不匹配，不能授权装配修复。");
            return;
        }
        var expected = StaticJavaParser.parse(current);
        for (MockWiringEvidence evidence : diagnostics.stream().flatMap(d -> d.getMockWiringEvidence().stream()).distinct().toList()) {
            expected.findAll(AssignExpr.class).stream().filter(a -> a.toString().equals(evidence.removableAssignment()))
                    .filter(a -> a.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> c.getFullyQualifiedName().orElse("").equals(evidence.testClass())).orElse(false))
                    .filter(a -> a.getParentNode().orElse(null) instanceof ExpressionStmt)
                    .filter(a -> a.findAncestor(MethodDeclaration.class).map(MockWiringAnalyzer::beforeEach).orElse(false))
                    .map(a -> a.getParentNode().orElseThrow()).toList().forEach(Node::remove);
        }
        if (!canonical(expected.toString()).equals(canonical(proposed)))
            throw new RepairCandidateRejectedException(RepairCandidateRejectedException.Stage.REPAIR_SCOPE,
                    "装配修复仅允许移除有源码证据的 @InjectMocks 覆盖赋值；不得修改测试正文、stub、输入、断言或其他 Fixture。");
    }

    private static String canonical(String code) {
        var unit = StaticJavaParser.parse(TestCodeNormalizationPipeline.normalize(code).code());
        unit.getAllContainedComments().forEach(Node::remove);
        return unit.toString();
    }

    private static String guidance(String category) {
        return WIRING.equals(category)
                ? " 生成时必须显式连接同一个依赖或保留 Mockito 已构造的被测对象。运行时仅在 removableAssignment 证据完整时可删除该覆盖赋值；其他装配问题不获得更改输入、stub 或 Oracle 的授权。"
                : " 生成阶段应通过公开入口建立真实状态，再验证返回值、对外状态或异常；不要反射替换私有 final 字段、验证脱离被测对象的 mock，或猜测内部 getter 调用次数。运行修复若需改变 Oracle，仍须独立契约授权。";
    }

    private static List<Finding> findings(List<String> sources, String code) {
        try { return new Graph(sources, code).inspect(); }
        catch (RuntimeException unknown) { return List.of(); } // Parsing/binding uncertainty belongs to the compiler.
    }

    private static final class Graph {
        final CompilationUnit test;
        final List<CompilationUnit> production;
        final String testHash;
        final String productionHash;
        final List<Finding> result = new ArrayList<>();
        Graph(List<String> sources, String code) {
            test = StaticJavaParser.parse(code);
            production = sources.stream().map(StaticJavaParser::parse).toList();
            testHash = CodeFingerprint.sha256(code);
            productionHash = CodeFingerprint.sha256(String.join("\n", sources));
        }
        List<Finding> inspect() {
            for (ClassOrInterfaceDeclaration owner : test.findAll(ClassOrInterfaceDeclaration.class)) {
                Map<String, VariableDeclarator> mocks = new LinkedHashMap<>();
                for (FieldDeclaration field : owner.getFields()) if (annotation(field, "org.mockito.Mock"))
                    field.getVariables().forEach(v -> mocks.put(v.getNameAsString(), v));
                for (FieldDeclaration field : owner.getFields()) for (VariableDeclarator subject : field.getVariables()) {
                    boolean inject = annotation(field, "org.mockito.InjectMocks");
                    boolean constructed = subject.getInitializer().filter(Expression::isObjectCreationExpr).isPresent()
                            || owner.getMethods().stream().flatMap(m -> m.findAll(AssignExpr.class).stream())
                            .anyMatch(a -> fieldReference(a.getTarget(), subject) && a.getValue().isObjectCreationExpr());
                    if (!inject && !constructed) continue;
                    var type = resolve(test, subject.getTypeAsString());
                    if (type == null) continue;
                    List<FieldDeclaration> fields = fields(type, new HashSet<>());
                    for (VariableDeclarator mock : mocks.values()) {
                        var compatible = fields.stream().flatMap(f -> f.getVariables().stream()).filter(v -> sameType(v, mock)).toList();
                        var named = compatible.stream().filter(v -> v.getNameAsString().equals(mock.getNameAsString())).toList();
                        var matches = named.isEmpty() ? compatible : named;
                        if (matches.size() != 1 || !onlyMockitoUses(owner, mock)) continue;
                        VariableDeclarator actual = matches.get(0);
                        FieldDeclaration dependency = actual.findAncestor(FieldDeclaration.class).orElseThrow();
                        boolean ambiguousMocks = mocks.values().stream().filter(v -> sameType(v, mock)).count() > 1;
                        boolean owned = (dependency.isFinal() || dependency.isStatic()) && actual.getInitializer().filter(Expression::isObjectCreationExpr).isPresent();
                        // Constructor injection may legitimately supply a final field; never infer disconnection then.
                        if (owned && constructorWrites(type, actual.getNameAsString(), new HashSet<>())) continue;
                        for (MethodDeclaration method : owner.getMethods()) {
                            if (!testMethod(method) || !callsSubject(method, subject)) continue;
                            for (MethodCallExpr verify : method.findAll(MethodCallExpr.class)) {
                                if (verify.getArguments().isEmpty()) continue;
                                Expression observed;
                                if (mockito(verify, "verify")) observed = verify.getArgument(0);
                                else if (mockito(verify, "when") && verify.getArgument(0).isMethodCallExpr()
                                        && verify.getArgument(0).asMethodCallExpr().getScope().isPresent())
                                    observed = verify.getArgument(0).asMethodCallExpr().getScope().orElseThrow();
                                else continue;
                                String receiver = observed.toString();
                                boolean observes = fieldReference(observed, mock)
                                        || returnedFromMock(owner, method, observed, mock);
                                if (!observes) continue;
                                if (owned) add(INTERNAL, owner, method, subject, receiver, verify,
                                        "生产字段 " + type.getNameAsString() + "." + actual.getNameAsString()
                                                + " 为自行初始化的 " + (dependency.isStatic() ? "static" : "final")
                                                + " 字段；当前 stub/verify 的 mock 未接入该字段，零交互不能证明生产行为缺失。", "");
                                else if (wrongConstructorMock(owner, type, subject, actual))
                                    add(WIRING, owner, method, subject, receiver, verify,
                                            "当前手动构造器把另一个 Mockito.mock 实例传入生产字段 " + actual.getNameAsString()
                                                    + "，而 stub/verify 使用的是测试字段 " + mock.getNameAsString()
                                                    + "；两个对象未连接。生成时应显式传入同一个依赖，运行时不得凭零交互换输入。", "");
                                else if (inject && !ambiguousMocks && activeMockito(owner) && !dependency.isFinal() && !dependency.isStatic()
                                        && noArgConstructionSafe(type)) {
                                    List<AssignExpr> overwrites = owner.getMethods().stream().filter(MockWiringAnalyzer::beforeEach)
                                            .flatMap(m -> m.findAll(AssignExpr.class).stream()).filter(a -> fieldReference(a.getTarget(), subject)
                                                    && a.getOperator() == AssignExpr.Operator.ASSIGN && a.getValue().isObjectCreationExpr()
                                                    && a.getValue().asObjectCreationExpr().getArguments().isEmpty()
                                                    && resolve(test, a.getValue().asObjectCreationExpr().getTypeAsString()) == type
                                                    && a.getParentNode().orElse(null) instanceof ExpressionStmt
                                                    && a.getParentNode().flatMap(Node::getParentNode).orElse(null) == a.findAncestor(MethodDeclaration.class).flatMap(MethodDeclaration::getBody).orElse(null))
                                            .toList();
                                    if (overwrites.size() == 1 && noOtherWiring(owner, subject, mock, overwrites.get(0)))
                                        add(WIRING, owner, method, subject, receiver, verify,
                                                "MockitoExtension 初始化后，@BeforeEach 以无参 new 覆盖 @InjectMocks 字段 "
                                                        + subject.getNameAsString() + "，原注入依赖 " + mock.getNameAsString() + " 被丢弃。", overwrites.get(0).toString());
                                }
                            }
                        }
                    }
                }
                inspectRealVerification(owner);
            }
            return result;
        }
        void inspectRealVerification(ClassOrInterfaceDeclaration owner) {
            for (MethodDeclaration method : owner.getMethods()) for (MethodCallExpr verify : method.findAll(MethodCallExpr.class)) {
                if (!mockito(verify, "verify") || verify.getArguments().isEmpty()) continue;
                Expression value = verify.getArgument(0);
                boolean real = value.isObjectCreationExpr();
                if (value.isNameExpr()) {
                    var vars = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(value.toString())).toList();
                    real = vars.size() == 1 && vars.get(0).getInitializer().filter(Expression::isObjectCreationExpr).isPresent()
                            && method.findAll(AssignExpr.class).stream().noneMatch(a -> a.getTarget().toString().equals(value.toString()));
                }
                if (value.isMethodCallExpr()) {
                    List<MethodCallExpr> returns = owner.getMethods().stream().filter(m -> m == method || beforeEach(m))
                            .flatMap(m -> m.findAll(MethodCallExpr.class).stream()).filter(c -> c.getNameAsString().equals("thenReturn")
                                    && c.getScope().filter(Expression::isMethodCallExpr).map(e -> e.asMethodCallExpr()).filter(w -> mockito(w, "when")
                                    && w.getArguments().size() == 1 && w.getArgument(0).toString().equals(value.toString())).isPresent()).toList();
                    real = returns.size() == 1 && returns.get(0).getArguments().size() == 1
                            && (returns.get(0).getArgument(0).isObjectCreationExpr() || returns.get(0).getArgument(0).isLiteralExpr());
                }
                if (real) add(INTERNAL, owner, method, null, value.toString(), verify,
                        "Mockito.verify 的接收者是当前测试中直接 new 的真实对象，不能用 Mockito 验证其内部调用。", "");
            }
        }
        boolean wrongConstructorMock(ClassOrInterfaceDeclaration owner, ClassOrInterfaceDeclaration type,
                                     VariableDeclarator subject, VariableDeclarator dependency) {
            for (MethodDeclaration setup : owner.getMethods()) {
                if (!beforeEach(setup)) continue;
                for (AssignExpr assignment : setup.findAll(AssignExpr.class)) {
                    if (!fieldReference(assignment.getTarget(), subject) || !assignment.getValue().isObjectCreationExpr()) continue;
                    var creation = assignment.getValue().asObjectCreationExpr();
                    if (resolve(test, creation.getTypeAsString()) != type) continue;
                    var constructors = type.getConstructors().stream().filter(c -> c.getParameters().size() == creation.getArguments().size()).toList();
                    if (constructors.size() != 1) continue;
                    var constructor = constructors.get(0);
                    for (AssignExpr binding : constructor.findAll(AssignExpr.class)) {
                        if (!binding.getTarget().toString().equals("this." + dependency.getNameAsString()) || !binding.getValue().isNameExpr()) continue;
                        String parameter = binding.getValue().asNameExpr().getNameAsString();
                        for (int i = 0; i < constructor.getParameters().size(); i++) {
                            if (!constructor.getParameter(i).getNameAsString().equals(parameter)) continue;
                            var value = creation.getArgument(i);
                            if (value.isMethodCallExpr() && mockito(value.asMethodCallExpr(), "mock")) return true;
                        }
                    }
                }
            }
            return false;
        }
        boolean onlyMockitoUses(ClassOrInterfaceDeclaration owner, VariableDeclarator mock) {
            for (MethodDeclaration method : owner.findAll(MethodDeclaration.class)) {
                List<Expression> uses = new ArrayList<>(method.findAll(NameExpr.class));
                uses.addAll(method.findAll(FieldAccessExpr.class));
                for (Expression use : uses) {
                    if (!fieldReference(use, mock)) continue;
                    boolean allowed = false;
                    for (Node node = use.getParentNode().orElse(null); node != null && node != method; node = node.getParentNode().orElse(null)) {
                        if (node instanceof MethodCallExpr call && (mockito(call, "when") || mockito(call, "verify"))) {
                            allowed = true; break;
                        }
                        if (node instanceof AssignExpr) break;
                    }
                    if (!allowed) return false;
                }
            }
            return true;
        }
        boolean returnedFromMock(ClassOrInterfaceDeclaration owner, MethodDeclaration method, Expression receiver, VariableDeclarator mock) {
            if (!receiver.isNameExpr()) return false;
            var vars = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(receiver.toString())
                    && v.getInitializer().filter(e -> e.isMethodCallExpr() && mockito(e.asMethodCallExpr(), "mock")).isPresent()).toList();
            if (vars.size() != 1) return false;
            return method.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals("thenReturn")
                    && c.getArguments().size() == 1 && c.getArgument(0).toString().equals(receiver.toString())
                    && c.getScope().filter(Expression::isMethodCallExpr).map(e -> e.asMethodCallExpr()).filter(w -> mockito(w, "when")
                    && w.getArguments().size() == 1 && w.getArgument(0).isMethodCallExpr()
                    && w.getArgument(0).asMethodCallExpr().getScope().map(s -> fieldReference(s, mock)).orElse(false)).isPresent());
        }
        boolean noOtherWiring(ClassOrInterfaceDeclaration owner, VariableDeclarator subject, VariableDeclarator mock, AssignExpr assignment) {
            // No helper/setter/reflective setup, local shadowing, annotation reinitialization or alternate SUT assignment.
            return owner.getMethods().stream().allMatch(m ->
                    m.findAll(AssignExpr.class).stream().allMatch(a -> a == assignment || !fieldReference(a.getTarget(), subject)))
                    && owner.getMethods().stream().filter(m -> !testMethod(m)).allMatch(m ->
                    m.findAll(MethodCallExpr.class).stream().allMatch(c -> c.getScope().map(s -> !fieldReference(s, subject)).orElse(true)
                    && !Set.of("openMocks", "initMocks", "setField", "setAccessible").contains(c.getNameAsString())))
                    && owner.getMethods().stream().allMatch(m -> m.getParameters().stream().noneMatch(p -> p.getNameAsString().equals(mock.getNameAsString()))
                    && m.findAll(VariableDeclarator.class).stream().noneMatch(v -> v.getNameAsString().equals(subject.getNameAsString()) || v.getNameAsString().equals(mock.getNameAsString())));
        }
        boolean callsSubject(MethodDeclaration method, VariableDeclarator subject) {
            return method.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getScope().map(s -> fieldReference(s, subject)).orElse(false));
        }
        void add(String category, ClassOrInterfaceDeclaration owner, MethodDeclaration method, VariableDeclarator subject,
                 String receiver, Node node, String fact, String assignment) {
            result.add(new Finding(new MockWiringEvidence(category, owner.getFullyQualifiedName().orElse(""), method.getNameAsString(),
                    subject == null ? "" : subject.getNameAsString(), receiver, node.getBegin().map(p -> p.line).orElse(-1),
                    node.toString(), fact, testHash, productionHash, assignment)));
        }
        ClassOrInterfaceDeclaration resolve(CompilationUnit context, String raw) {
            String name = raw.replaceFirst("<.*", "");
            List<ClassOrInterfaceDeclaration> candidates = new ArrayList<>();
            List<CompilationUnit> units = new ArrayList<>(production); units.add(test);
            for (CompilationUnit unit : units) for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (name.equals(type.getFullyQualifiedName().orElse(""))) candidates.add(type);
                else if (name.equals(type.getNameAsString())) {
                    var exact = context.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name)).toList();
                    if (!exact.isEmpty() ? exact.stream().allMatch(i -> i.getNameAsString().equals(type.getFullyQualifiedName().orElse("")))
                            : unit == context || unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("").equals(context.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""))) candidates.add(type);
                }
            }
            return candidates.size() == 1 ? candidates.get(0) : null;
        }
        List<FieldDeclaration> fields(ClassOrInterfaceDeclaration type, Set<Node> seen) {
            if (!seen.add(type)) return List.of();
            List<FieldDeclaration> fields = new ArrayList<>(type.getFields());
            for (var base : type.getExtendedTypes()) {
                var parent = resolve(type.findCompilationUnit().orElseThrow(), base.asString());
                if (parent != null) fields.addAll(fields(parent, seen));
            }
            return fields;
        }
        boolean constructorWrites(ClassOrInterfaceDeclaration type, String name, Set<Node> seen) {
            if (!seen.add(type)) return true;
            if (type.getConstructors().stream().flatMap(c -> c.findAll(AssignExpr.class).stream())
                    .anyMatch(a -> a.getTarget().toString().equals(name) || a.getTarget().toString().equals("this." + name))) return true;
            return type.getExtendedTypes().stream().anyMatch(t -> {
                var base = resolve(type.findCompilationUnit().orElseThrow(), t.asString());
                return base != null && constructorWrites(base, name, seen);
            });
        }
        boolean noArgConstructionSafe(ClassOrInterfaceDeclaration type) {
            return type.getExtendedTypes().isEmpty() && !type.isAbstract() && !type.isInterface()
                    && type.getAnnotations().isEmpty() && (type.getConstructors().isEmpty()
                    || type.getConstructors().size() == 1 && type.getConstructors().get(0).getParameters().isEmpty()
                    && type.getConstructors().get(0).getBody().isEmpty());
        }
        boolean sameType(VariableDeclarator first, VariableDeclarator second) {
            String a = typeName(first.getType(), first.findCompilationUnit().orElseThrow());
            String b = typeName(second.getType(), second.findCompilationUnit().orElseThrow());
            return a != null && a.equals(b);
        }
        String typeName(com.github.javaparser.ast.type.Type type, CompilationUnit unit) {
            if (type.isArrayType()) {
                String element = typeName(type.asArrayType().getComponentType(), unit);
                return element == null ? null : element + "[]";
            }
            if (type.isPrimitiveType()) return type.asString();
            if (!type.isClassOrInterfaceType()) return null;
            var reference = type.asClassOrInterfaceType();
            String raw = reference.getNameWithScope();
            if (!raw.contains(".")) {
                String simple = raw;
                var imports = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(simple)).toList();
                if (imports.size() == 1) raw = imports.get(0).getNameAsString();
                else if (!imports.isEmpty()) return null;
                else if (Set.of("String", "Object", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character", "Class").contains(raw)) raw = "java.lang." + raw;
                else if (unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk())) return null;
                else raw = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + raw;
            }
            List<String> arguments = new ArrayList<>();
            for (var argument : reference.getTypeArguments().orElse(new NodeList<>())) {
                String value = typeName(argument, unit);
                if (value == null) return null;
                arguments.add(value);
            }
            return raw + (arguments.isEmpty() ? "" : "<" + String.join(",", arguments) + ">");
        }
        boolean activeMockito(ClassOrInterfaceDeclaration owner) {
            return owner.getAnnotations().stream().anyMatch(a -> bound(a.getNameAsString(), "org.junit.jupiter.api.extension.ExtendWith", test)
                    && a.findAll(ClassExpr.class).stream().anyMatch(c -> bound(c.getType().asString(), "org.mockito.junit.jupiter.MockitoExtension", test)));
        }
    }
    private static boolean fieldReference(Expression expression, VariableDeclarator field) {
        String name = field.getNameAsString();
        if (expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr())
            return expression.asFieldAccessExpr().getNameAsString().equals(name);
        if (!expression.isNameExpr() || !expression.toString().equals(name)) return false;
        var method = expression.findAncestor(MethodDeclaration.class).orElse(null);
        return method == null || method.getParameters().stream().noneMatch(p -> p.getNameAsString().equals(name))
                && method.findAll(VariableDeclarator.class).stream().noneMatch(v -> v.getNameAsString().equals(name));
    }
    private static boolean annotation(FieldDeclaration field, String type) {
        return field.getAnnotations().stream().anyMatch(a -> bound(a.getNameAsString(), type, field.findCompilationUnit().orElseThrow()));
    }
    private static boolean bound(String name, String type, CompilationUnit unit) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        if (unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> t.getNameAsString().equals(simple))) return false;
        if (name.equals(type)) return true;
        if (!name.equals(simple)) return false;
        var imports = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(simple)).toList();
        if (!imports.isEmpty()) return imports.stream().allMatch(i -> i.getNameAsString().equals(type));
        var stars = unit.getImports().stream().filter(i -> !i.isStatic() && i.isAsterisk()).toList();
        return stars.size() == 1 && stars.get(0).getNameAsString().equals(type.substring(0, type.lastIndexOf('.')));
    }
    private static boolean mockito(MethodCallExpr call, String name) {
        return call.getNameAsString().equals(name) && TestCodeNormalizationPipeline.boundTo(call, Set.of("org.mockito.Mockito"));
    }
    private static boolean testMethod(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> List.of("org.junit.jupiter.api.Test",
                "org.junit.jupiter.api.RepeatedTest", "org.junit.jupiter.api.TestFactory", "org.junit.jupiter.api.TestTemplate",
                "org.junit.jupiter.params.ParameterizedTest").stream().anyMatch(type ->
                bound(a.getNameAsString(), type, method.findCompilationUnit().orElseThrow())));
    }
    private static boolean beforeEach(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> bound(a.getNameAsString(), "org.junit.jupiter.api.BeforeEach", method.findCompilationUnit().orElseThrow()));
    }
}
