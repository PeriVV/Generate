package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 用生产构造依赖、测试声明和真实 NPE 三方证据确认缺失 Fixture。 */
public final class MissingFixtureDependencyDetector {
    private static final Pattern NULL_MEMBER = Pattern.compile(
            "(?i)(?:because\\s+)?[\\\"']?(?:this\\.)?([A-Za-z_$][A-Za-z0-9_$]*)[\\\"']?\\s+is\\s+null");
    private static final Pattern FRAME_OWNER = Pattern.compile("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\.[\\w$<>]+\\(");

    private MissingFixtureDependencyDetector() { }

    /** Also prove explicit null constructor arguments in real collaborators on the NPE stack. */
    public static Result inspect(Project project, String productionSource, String testCode,
                                 List<RuntimeDiagnostic> diagnostics) {
        Result direct = inspect(productionSource, testCode, diagnostics);
        if (project == null || diagnostics == null || testCode == null || testCode.isBlank()) return direct;
        Set<String> missing = new LinkedHashSet<>(direct.getMissingDependencies());
        CompilationUnit test;
        try { test = StaticJavaParser.parse(testCode); }
        catch (RuntimeException ignored) { return direct; }
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            // A nested NPE inside initialization/context failures is not fixture proof.
            if (diagnostic == null || !diagnostic.getExceptionType().endsWith("NullPointerException")
                    || "FIXTURE_DEPENDENCY_MISSING".equals(diagnostic.getCategory())) continue;
            String member = nullMember(diagnostic);
            if (member.isBlank()) continue;
            List<ObjectCreationExpr> creations = activeFixtureCreations(test, diagnostic.getTestId());
            if (creations.isEmpty()) continue;
            List<String> frames = new ArrayList<>(diagnostic.getStackTrace());
            frames.add(diagnostic.getProductionFrame());
            frames.add(diagnostic.getRootCauseFrame());
            Set<String> owners = new LinkedHashSet<>();
            for (String frame : frames) {
                Matcher matcher = FRAME_OWNER.matcher(frame);
                if (matcher.find()) owners.add(matcher.group(1).replace('$', '.'));
                if (owners.size() >= 16) break;
            }
            for (String owner : owners) {
                String proof = ReadAction.compute(() -> nestedConstructorProof(project, test, creations, owner, member));
                if (proof == null) continue;
                missing.add(proof);
                diagnostic.setCategory("FIXTURE_DEPENDENCY_MISSING");
                diagnostic.setSuggestion("真实 NPE 栈帧、项目源码构造函数和测试中的 null 实参共同证明：" + proof
                        + "。在对应失败测试内补充 mock/真实依赖，并按真实构造函数重建协作对象；"
                        + "若该协作对象已注入被测对象，须同时重建被测对象或使用其真实注入入口。"
                        + "仅修复 Arrange/Fixture，保持原断言、verify 和业务输入不变。");
                break;
            }
        }
        return new Result(new ArrayList<>(missing));
    }

    private static List<ObjectCreationExpr> activeFixtureCreations(CompilationUnit test, String testId) {
        int hash = testId.indexOf('#');
        if (hash < 0) return List.of();
        String method = testId.substring(hash + 1).replaceFirst("\\(.*", "");
        String testClass = simpleType(testId.substring(0, hash).replace('$', '.'));
        var types = test.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(item -> item.getNameAsString().equals(testClass)).toList();
        if (types.size() != 1) return List.of();
        ClassOrInterfaceDeclaration type = types.get(0);
        Set<String> reachable = new LinkedHashSet<>(List.of(method));
        type.getMethods().stream().filter(item -> item.getAnnotations().stream().anyMatch(annotation ->
                List.of("BeforeEach", "BeforeAll").contains(annotation.getName().getIdentifier())))
                .forEach(item -> reachable.add(item.getNameAsString()));
        for (int round = 0; round < 10; round++) {
            int size = reachable.size();
            type.getMethods().stream().filter(item -> reachable.contains(item.getNameAsString()))
                    .flatMap(item -> item.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream())
                    .filter(call -> call.getScope().isEmpty() || call.getScope().get().isThisExpr())
                    .forEach(call -> reachable.add(call.getNameAsString()));
            if (size == reachable.size()) break;
        }
        return type.findAll(ObjectCreationExpr.class).stream().filter(creation -> {
            if (creation.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) != type) return false;
            var containing = creation.findAncestor(MethodDeclaration.class);
            return containing.map(item -> reachable.contains(item.getNameAsString())).orElseGet(() ->
                    creation.findAncestor(FieldDeclaration.class).isPresent());
        }).toList();
    }

    private static String nestedConstructorProof(Project project, CompilationUnit test, List<ObjectCreationExpr> creations,
                                                 String owner, String member) {
        PsiClass type = JavaPsiFacade.getInstance(project).findClass(owner, GlobalSearchScope.projectScope(project));
        if (type == null || type.getContainingFile() == null || type.getContainingFile().getVirtualFile() == null
                || !ProjectFileIndex.getInstance(project).isInSourceContent(type.getContainingFile().getVirtualFile())) return null;
        ClassOrInterfaceDeclaration declaration;
        try {
            var matches = StaticJavaParser.parse(type.getContainingFile().getText())
                    .findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(item -> item.getNameAsString().equals(type.getName())).toList();
            if (matches.size() != 1) return null;
            declaration = matches.get(0);
        } catch (RuntimeException ignored) { return null; }
        String fieldType = declaration.getFields().stream().filter(field -> !field.isStatic())
                .flatMap(field -> field.getVariables().stream()).filter(field -> field.getNameAsString().equals(member))
                .map(field -> field.getTypeAsString()).findFirst().orElse(null);
        if (fieldType == null) return null;
        var sourceField = type.findFieldByName(member, false);
        if (sourceField != null) fieldType = sourceField.getType().getCanonicalText();
        for (ObjectCreationExpr creation : creations) {
            if (!matchesOwner(test, creation.getType().getNameWithScope(), owner)) continue;
            int slot = constructorSlot(declaration, member, creation.getArguments().size());
            if (slot < 0 || !creation.getArgument(slot).isNullLiteralExpr()) continue;
            return owner + " 构造参数[" + (slot + 1) + "] " + fieldType + " " + member
                    + " 在测试中显式传入 null";
        }
        return null;
    }

    private static boolean matchesOwner(CompilationUnit test, String name, String owner) {
        if (name.equals(owner)) return true;
        if (!name.equals(simpleType(owner))) return false;
        if (test.getImports().stream().anyMatch(item -> !item.isStatic() && !item.isAsterisk()
                && item.getNameAsString().equals(owner))) return true;
        return test.getPackageDeclaration().map(item -> item.getNameAsString() + "." + name).orElse(name).equals(owner);
    }

    private static int constructorSlot(ClassOrInterfaceDeclaration declaration, String member, int arity) {
        var constructors = declaration.getConstructors().stream()
                .filter(item -> item.getParameters().size() == arity).toList();
        if (!declaration.getConstructors().isEmpty()) {
            if (constructors.size() != 1) return -1;
            var constructor = constructors.get(0);
            for (AssignExpr assignment : constructor.findAll(AssignExpr.class)) {
                if (assignment.getOperator() != AssignExpr.Operator.ASSIGN
                        || !assignment.getTarget().isFieldAccessExpr() || !assignment.getValue().isNameExpr()) continue;
                var target = assignment.getTarget().asFieldAccessExpr();
                if (!target.getScope().isThisExpr() || !target.getNameAsString().equals(member)) continue;
                String parameter = assignment.getValue().asNameExpr().getNameAsString();
                for (int i = 0; i < arity; i++)
                    if (constructor.getParameter(i).getNameAsString().equals(parameter)) return i;
            }
            return -1;
        }
        boolean all = declaration.getAnnotations().stream()
                .anyMatch(item -> item.getName().getIdentifier().equals("AllArgsConstructor"));
        boolean required = declaration.getAnnotations().stream()
                .anyMatch(item -> item.getName().getIdentifier().equals("RequiredArgsConstructor"));
        if (!all && !required) return -1;
        List<String> parameters = new ArrayList<>();
        for (FieldDeclaration field : declaration.getFields()) {
            if (field.isStatic()) continue;
            boolean nonNull = field.getAnnotations().stream().anyMatch(item -> item.getName().getIdentifier().equals("NonNull"));
            for (VariableDeclarator variable : field.getVariables()) {
                if (variable.getInitializer().isPresent() && (field.isFinal() || !all)) continue;
                if (all || field.isFinal() || nonNull) parameters.add(variable.getNameAsString());
            }
        }
        return parameters.size() == arity ? parameters.indexOf(member) : -1;
    }

    public static Result inspect(String productionSource,
                                 String testCode,
                                 List<RuntimeDiagnostic> diagnostics) {
        if (productionSource == null || productionSource.isBlank() || testCode == null
                || testCode.isBlank() || diagnostics == null || diagnostics.isEmpty()) {
            return new Result(List.of());
        }
        try {
            CompilationUnit production = StaticJavaParser.parse(productionSource);
            CompilationUnit test = StaticJavaParser.parse(testCode);
            Map<String, String> constructorDependencies = constructorDependencies(production);
            if (constructorDependencies.isEmpty()) return new Result(List.of());
            Set<String> testFixtures = declaredFixtureNamesAndTypes(test);
            Set<String> missing = new LinkedHashSet<>();
            for (RuntimeDiagnostic diagnostic : diagnostics) {
                if (!isNullPointerFailure(diagnostic)) continue;
                String member = nullMember(diagnostic);
                String type = constructorDependencies.get(member);
                if (type == null || hasFixture(testFixtures, member, type)) continue;
                missing.add(type + " " + member);
                diagnostic.setCategory("FIXTURE_DEPENDENCY_MISSING");
                diagnostic.setSuggestion("被测类的构造依赖 " + type + " " + member
                        + " 未在测试中提供，导致进入业务路径前发生 NullPointerException。"
                        + "补充 @Mock/局部 mock 并通过 @InjectMocks 或真实构造函数注入；"
                        + "仅修复 Arrange/Fixture，必须保持原断言和 verify 语义不变。");
            }
            return new Result(new ArrayList<>(missing));
        } catch (RuntimeException ignored) {
            return new Result(List.of());
        }
    }

    private static Map<String, String> constructorDependencies(CompilationUnit unit) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Boolean> finals = new LinkedHashMap<>();
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            if (field.isStatic()) continue;
            for (VariableDeclarator variable : field.getVariables()) {
                fields.put(variable.getNameAsString(), simpleType(variable.getTypeAsString()));
                finals.put(variable.getNameAsString(), field.isFinal());
            }
        }
        Set<String> constructorNames = new LinkedHashSet<>();
        Set<String> constructorTypes = new LinkedHashSet<>();
        for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class)) {
            constructor.getParameters().forEach(parameter -> {
                constructorNames.add(parameter.getNameAsString());
                constructorTypes.add(simpleType(parameter.getTypeAsString()));
            });
        }
        boolean lombokRequiredConstructor = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(type -> type.getAnnotations().stream())
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(name -> "RequiredArgsConstructor".equals(name)
                        || "AllArgsConstructor".equals(name));
        Map<String, String> result = new LinkedHashMap<>();
        fields.forEach((name, type) -> {
            if (constructorNames.contains(name) || constructorTypes.contains(type)
                    || lombokRequiredConstructor && finals.getOrDefault(name, false)) {
                result.put(name, type);
            }
        });
        return result;
    }

    private static Set<String> declaredFixtureNamesAndTypes(CompilationUnit test) {
        Set<String> values = new LinkedHashSet<>();
        for (VariableDeclarator variable : test.findAll(VariableDeclarator.class)) {
            values.add(variable.getNameAsString());
            values.add(simpleType(variable.getTypeAsString()));
        }
        return values;
    }

    private static boolean hasFixture(Set<String> fixtures, String name, String type) {
        return fixtures.contains(name) || fixtures.contains(simpleType(type));
    }

    private static boolean isNullPointerFailure(RuntimeDiagnostic diagnostic) {
        if (diagnostic == null) return false;
        // 只有顶层异常（外层或首个根因）是 NPE 才参与 fixture 升级：
        // ExceptionInInitializerError / 框架 IllegalStateException 仅 cause 链里带 NPE 时，
        // 根因是环境/上下文问题，不得误导成“补 @Mock 即可修”。
        String topEvidence = diagnostic.getExceptionType() + "\n" + diagnostic.getRootCauseType();
        return topEvidence.toLowerCase(java.util.Locale.ROOT).contains("nullpointerexception");
    }

    private static String nullMember(RuntimeDiagnostic diagnostic) {
        String evidence = diagnostic.getMessage() + "\n" + diagnostic.getFailureDetails();
        Matcher matcher = NULL_MEMBER.matcher(evidence);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String simpleType(String type) {
        String value = type == null ? "" : type.replaceAll("<.*>", "").replace("[]", "").strip();
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    public static final class Result {
        private final List<String> missingDependencies;

        private Result(List<String> missingDependencies) {
            this.missingDependencies = new ArrayList<>(missingDependencies);
        }

        public boolean isProven() { return !missingDependencies.isEmpty(); }
        public List<String> getMissingDependencies() {
            return new ArrayList<>(missingDependencies);
        }
    }
}
