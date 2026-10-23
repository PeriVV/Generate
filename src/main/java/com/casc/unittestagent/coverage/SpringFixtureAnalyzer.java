package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ValidationStage;
import com.casc.unittestagent.model.SpringPropertyBinding;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;
import java.util.regex.Pattern;

/** Source-backed property contracts and container ownership, without loading Spring in the IDE. */
public final class SpringFixtureAnalyzer {
    private SpringFixtureAnalyzer() { }

    public static List<SpringPropertyBinding> properties(List<String> sources) {
        List<SpringPropertyBinding> result = new ArrayList<>();
        for (String source : sources) try {
            var unit = StaticJavaParser.parse(source);
            for (AnnotationExpr annotation : unit.findAll(AnnotationExpr.class)) {
                if (!annotationIs(annotation, unit, "org.springframework.beans.factory.annotation.Value")) continue;
                Expression value = annotation.isSingleMemberAnnotationExpr() ? annotation.asSingleMemberAnnotationExpr().getMemberValue()
                        : annotation.isNormalAnnotationExpr() ? annotation.asNormalAnnotationExpr().getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("value")).map(MemberValuePair::getValue).findFirst().orElse(null) : null;
                if (value == null || !value.isStringLiteralExpr()) continue; // constants/metadata we cannot resolve stay unknown
                String expression = value.asStringLiteralExpr().asString();
                var keys = new ArrayList<String>(); var required = new ArrayList<String>();
                placeholders(expression, keys, required);
                Node member = annotation.getParentNode().orElseThrow();
                String name = member instanceof FieldDeclaration f ? f.getVariables().stream().map(VariableDeclarator::getNameAsString).reduce((a,b) -> a + "," + b).orElse("")
                        : member instanceof MethodDeclaration m ? m.getNameAsString() : member instanceof Parameter p ? p.getNameAsString() : "";
                String type = member instanceof FieldDeclaration f ? f.getElementType().asString()
                        : member instanceof Parameter p ? p.getTypeAsString() : member instanceof MethodDeclaration m ? m.getParameters().toString() : "";
                String owner = member.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> c.getFullyQualifiedName().orElse(c.getNameAsString())).orElse("");
                result.add(new SpringPropertyBinding(owner, name, type, member instanceof Parameter ? "PARAMETER" : member instanceof FieldDeclaration ? "FIELD" : "METHOD", expression, List.copyOf(keys), List.copyOf(required), expression.contains("#{")));
            }
            for (var type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                var binding = type.getAnnotations().stream().filter(a -> annotationIs(a, unit, "org.springframework.boot.context.properties.ConfigurationProperties")).findFirst();
                if (binding.isEmpty()) continue;
                AnnotationExpr annotation = binding.get();
                Expression prefix = annotation.isSingleMemberAnnotationExpr() ? annotation.asSingleMemberAnnotationExpr().getMemberValue()
                        : annotation.isNormalAnnotationExpr() ? annotation.asNormalAnnotationExpr().getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("prefix") || p.getNameAsString().equals("value"))
                        .map(MemberValuePair::getValue).findFirst().orElse(new StringLiteralExpr("")) : new StringLiteralExpr("");
                if (!prefix.isStringLiteralExpr()) continue;
                for (var field : type.getFields()) if (!field.isStatic()) for (var variable : field.getVariables()) {
                    if (!Set.of("String", "boolean", "byte", "short", "int", "long", "float", "double", "char", "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character").contains(variable.getTypeAsString().replace("java.lang.", ""))) continue;
                    String name = variable.getNameAsString().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
                    String key = prefix.asStringLiteralExpr().asString(); if (!key.isBlank()) key += "."; key += name;
                    result.add(new SpringPropertyBinding(type.getFullyQualifiedName().orElse(type.getNameAsString()), variable.getNameAsString(), variable.getTypeAsString(), "CONFIGURATION_PROPERTIES_FIELD",
                            "@ConfigurationProperties(prefix=" + prefix + ")", List.of(key), List.of(), false));
                }
            }
        } catch (RuntimeException ignored) { /* javac owns syntax errors */ }
        return List.copyOf(result);
    }

    private static void placeholders(String expression, List<String> keys, List<String> required) {
        for (int offset = 0; offset < expression.length(); offset++) {
            if (!expression.startsWith("${", offset)) continue;
            int depth = 1, end = offset + 2, colon = -1;
            for (; end < expression.length() && depth > 0; end++) {
                char c = expression.charAt(end);
                if (c == '{') depth++;
                if (c == '}') depth--;
                if (c == ':' && depth == 1 && colon < 0) colon = end;
            }
            if (depth != 0) break;
            String key = expression.substring(offset + 2, colon < 0 ? end - 1 : colon);
            if (!key.contains("${")) { keys.add(key); if (colon < 0) required.add(key); }
            placeholders(expression.substring(offset + 2, end - 1), keys, required);
            offset = end - 1;
        }
    }

    public static List<CompileDiagnostic> preflight(List<String> sources, String code) {
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        try {
            var unit = StaticJavaParser.parse(code);
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                if (method.getAnnotations().stream().anyMatch(a -> annotationIs(a, unit, "org.springframework.beans.factory.annotation.Value"))
                        && method.getBody().isPresent() && method.getBody().get().getStatements().isEmpty()) {
                    diagnostics.add(error("SPRING_VALUE_NOOP", method, "@Value 方法 " + method.getNameAsString()
                            + " 的方法体为空，属性注入不会改变任何对象状态；使用真实属性键和容器管理的对象，或源码可见的构造器/API。"));
                }
            }
            boolean container = unit.findAll(AnnotationExpr.class).stream().anyMatch(a -> annotationIs(a, unit, "org.springframework.boot.test.context.SpringBootTest")
                    || annotationIs(a, unit, "org.springframework.test.context.ContextConfiguration")
                    || annotationIs(a, unit, "org.springframework.test.context.junit.jupiter.SpringJUnitConfig"));
            if (!container) return diagnostics;
            Set<String> boundTypes = new HashSet<>();
            properties(sources).stream().filter(p -> p.injectionSite().equals("FIELD") || p.injectionSite().equals("METHOD"))
                    .forEach(p -> boundTypes.add(p.owner()));
            // Source-backed inherited @Value state belongs to subclasses as well.
            boolean inherited;
            do {
                inherited = false;
                for (String source : sources) try {
                    var production = StaticJavaParser.parse(source);
                    for (var declaration : production.findAll(ClassOrInterfaceDeclaration.class)) {
                        String name = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
                        if (boundTypes.contains(name)) continue;
                        if (declaration.getExtendedTypes().stream().anyMatch(t -> boundTypes.contains(resolvedType(t.getNameWithScope(), production))))
                            inherited |= boundTypes.add(name);
                    }
                } catch (RuntimeException ignored) { /* unresolved source does not erase independent test-side facts */ }
            } while (inherited);
            Set<String> localBound = new HashSet<>();
            boolean changed;
            do {
                changed = false;
                for (var declaration : unit.findAll(ClassOrInterfaceDeclaration.class))
                    if (unit.findAll(ClassOrInterfaceDeclaration.class).stream().filter(t -> t.getNameAsString().equals(declaration.getNameAsString())).count() == 1
                            && !localBound.contains(declaration.getNameAsString()) && declaration.getExtendedTypes().stream().anyMatch(t ->
                            boundTypes.contains(resolvedType(t.getNameWithScope(), unit)) || localBound.contains(t.getNameWithScope())))
                        changed |= localBound.add(declaration.getNameAsString());
            } while (changed);
            for (ObjectCreationExpr creation : unit.findAll(ObjectCreationExpr.class)) {
                // @Bean factory return values are subsequently initialized by Spring.
                if (creation.findAncestor(MethodDeclaration.class).stream().anyMatch(m -> m.getAnnotations().stream()
                        .anyMatch(a -> annotationIs(a, unit, "org.springframework.context.annotation.Bean")))) continue;
                String type = resolvedType(creation.getType().getNameWithScope(), unit);
                if (!boundTypes.contains(type) && !localBound.contains(creation.getType().getNameWithScope())) continue;
                if (!creation.getArguments().isEmpty()) continue; // explicit constructor state may be valid without injection
                diagnostics.add(error("SPRING_CONTAINER_OWNERSHIP", creation, "容器测试中直接构造 " + (type.isBlank() ? creation.getType().asString() : type)
                        + "，该实例的 @Value 属性不会由 Spring 注入；@TestPropertySource 只作用于容器管理的 Bean。"
                        + "通过 @Autowired 或 context.getBean 获取被测实例，禁止在 setUp/测试中 new 覆盖注入实例；不要为绕过反射门禁直接升级为全量 SpringBootTest。"));
            }
            // Independent of @Value: replacing an injected object loses post-processors/proxies and other injection.
            for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
                if (field.getAnnotations().stream().noneMatch(a -> annotationIs(a, unit, "org.springframework.beans.factory.annotation.Autowired")
                        || annotationIs(a, unit, "jakarta.annotation.Resource") || annotationIs(a, unit, "javax.annotation.Resource"))) continue;
                var owner = field.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
                for (var variable : field.getVariables()) for (AssignExpr assignment : owner.findAll(AssignExpr.class)) {
                    if (assignment.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) != owner) continue;
                    Expression target = assignment.getTarget();
                    boolean same = target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()
                            && target.asFieldAccessExpr().getNameAsString().equals(variable.getNameAsString());
                    if (target.isNameExpr() && target.asNameExpr().getNameAsString().equals(variable.getNameAsString())) {
                        // A local/parameter shadow is not proof of writing this field.
                        var method = assignment.findAncestor(MethodDeclaration.class);
                        same = method.isPresent() && method.get().getParameters().stream().noneMatch(p -> p.getNameAsString().equals(variable.getNameAsString()))
                                && method.get().findAll(VariableDeclarator.class).stream().noneMatch(v -> v.getNameAsString().equals(variable.getNameAsString()));
                    }
                    if (same && assignment.getValue().isObjectCreationExpr() && diagnostics.stream().noneMatch(d -> d.getLine() == assignment.getBegin().map(p -> p.line).orElse(-1)))
                        diagnostics.add(error("SPRING_CONTAINER_OWNERSHIP", assignment, "被注入字段 " + variable.getNameAsString()
                                + " 被 new 实例覆盖，容器生命周期、代理和注入状态将丢失；保留容器管理的实例，使用真实属性绑定配置。"));
                }
            }
            return List.copyOf(diagnostics);
        } catch (RuntimeException ignored) { return List.copyOf(diagnostics); }
    }

    public static final String BINDING_FAILURE = "SPRING_PROPERTY_BINDING_FAILURE";
    private static final Pattern MISSING_PROPERTY = Pattern.compile("Could not resolve placeholder ['\"]([^'\"]+)['\"]");
    /** A loader failure plus a source-declared required property is fixture evidence, not a guessed business failure. */
    public static void enrich(List<String> sources, List<com.casc.unittestagent.model.RuntimeDiagnostic> diagnostics) {
        var contracts = properties(sources);
        for (var diagnostic : diagnostics) {
            if (!diagnostic.isSharedContextFailure() || diagnostic.isContextFailureSecondary()
                    || diagnostic.getContextKey().startsWith("fixture:")) continue;
            var missing = MISSING_PROPERTY.matcher(diagnostic.getRootCauseMessage());
            if (!missing.find()) continue;
            String key = missing.group(1);
            var matches = contracts.stream().filter(p -> p.requiredKeys().contains(key)).toList();
            if (matches.isEmpty()) continue;
            diagnostic.setCategory(BINDING_FAILURE);
            diagnostic.setFixtureField(key);
            diagnostic.setLifecycleEvidence(matches.stream().map(p -> p.owner() + "#" + p.member() + ": " + p.expression()).toList());
            diagnostic.setSuggestion("真实 Spring loader 和源码 @Value 共同证明必需属性 " + key
                    + " 未解析。仅补充该真实属性键及可转换的测试配置，保留全部已有属性、Bean 配置、测试正文和 Oracle；不得猜测业务期望。" );
        }
    }
    public static boolean allowsPropertyRepair(List<com.casc.unittestagent.model.RuntimeDiagnostic> diagnostics) {
        return diagnostics != null && diagnostics.stream().anyMatch(d -> d != null && d.isSharedContextFailure()
                && !d.isContextFailureSecondary() && BINDING_FAILURE.equals(d.getCategory()) && !d.getFixtureField().isBlank()
                && !d.getLifecycleEvidence().isEmpty());
    }
    /** Runtime authorization is additive property configuration only, never a new object/context or edited input. */
    public static void validatePropertyRepair(String before, String after, List<com.casc.unittestagent.model.RuntimeDiagnostic> diagnostics) {
        if (!allowsPropertyRepair(diagnostics)) return;
        var left = StaticJavaParser.parse(before); var right = StaticJavaParser.parse(after);
        var allowed = diagnostics.stream().filter(d -> BINDING_FAILURE.equals(d.getCategory())).map(d -> d.getFixtureField()).collect(java.util.stream.Collectors.toSet());
        var prior = localProperties(left); var candidate = localProperties(right);
        boolean added = candidate.entrySet().stream().anyMatch(e -> !prior.containsKey(e.getKey()) && allowed.contains(e.getKey()));
        if (!added || !candidate.entrySet().containsAll(prior.entrySet()) || candidate.keySet().stream().anyMatch(k -> !prior.containsKey(k) && !allowed.contains(k)))
            reject("Spring 初始化修复只能补充源码证明缺失的属性，已有属性和业务输入必须保留。");
        stripPropertyAnnotations(left); stripPropertyAnnotations(right);
        left.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove);
        right.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove);
        if (!left.toString().equals(right.toString())) reject("Spring 属性修复不得更改 Bean 配置、生命周期、测试正文或 Oracle。");
    }
    private static Map<String,String> localProperties(CompilationUnit unit) {
        Map<String,String> values = new LinkedHashMap<>();
        for (var a : unit.findAll(AnnotationExpr.class)) if (annotationIs(a, unit, "org.springframework.test.context.TestPropertySource")) {
            if (!a.isNormalAnnotationExpr()) reject("无法证明 TestPropertySource 的属性补丁范围。");
            for (var pair : a.asNormalAnnotationExpr().getPairs()) {
                if (!pair.getNameAsString().equals("properties")) continue;
                List<Expression> entries = pair.getValue().isArrayInitializerExpr() ? new ArrayList<>(pair.getValue().asArrayInitializerExpr().getValues()) : List.of(pair.getValue());
                for (var entry : entries) {
                    if (!entry.isStringLiteralExpr()) reject("运行修复要求可核对的字面量属性配置。");
                    String text = entry.asStringLiteralExpr().asString(); int separator = text.indexOf('=');
                    if (separator < 1 || values.putIfAbsent(text.substring(0, separator).strip(), text.substring(separator + 1)) != null)
                        reject("属性语法不明确或存在重复键，无法自动授权。");
                }
            }
        }
        return values;
    }
    private static void stripPropertyAnnotations(CompilationUnit unit) {
        for (var a : new ArrayList<>(unit.findAll(AnnotationExpr.class))) if (annotationIs(a, unit, "org.springframework.test.context.TestPropertySource")) {
            // Retain every non-properties setting (locations, inheritance, encoding, etc.).
            var normal = a.asNormalAnnotationExpr(); normal.getPairs().removeIf(p -> p.getNameAsString().equals("properties"));
            if (normal.getPairs().isEmpty()) a.remove();
        }
        unit.getImports().removeIf(i -> i.getNameAsString().equals("org.springframework.test.context.TestPropertySource"));
    }
    private static void reject(String message) {
        throw new com.casc.unittestagent.generator.RepairCandidateRejectedException(
                com.casc.unittestagent.generator.RepairCandidateRejectedException.Stage.REPAIR_SCOPE, message);
    }

    private static CompileDiagnostic error(String code, Node node, String message) {
        var result = new CompileDiagnostic("ERROR", code, message, node.getBegin().map(p -> p.line).orElse(-1), -1, node.toString(), true);
        result.setValidationStage(ValidationStage.SOURCE_PREFLIGHT); return result;
    }
    private static String resolvedType(String name, CompilationUnit unit) {
        if (name.contains(".")) return name;
        var local = unit.findAll(ClassOrInterfaceDeclaration.class).stream().filter(t -> t.getNameAsString().equals(name)).toList();
        if (local.size() == 1) return local.get(0).getFullyQualifiedName().orElse("");
        if (!local.isEmpty()) return "";
        var imports = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name)).toList();
        if (imports.size() == 1) return imports.get(0).getNameAsString();
        if (!imports.isEmpty()) return "";
        // Same-package binding only; wildcard imports are not sufficient proof.
        return unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + name;
    }
    static boolean annotationIs(AnnotationExpr annotation, CompilationUnit unit, String type) {
        if (annotation.getNameAsString().equals(type)) return true;
        String simple = type.substring(type.lastIndexOf('.') + 1);
        if (!annotation.getNameAsString().equals(simple) || unit.findAll(TypeDeclaration.class).stream().anyMatch(t -> t.getNameAsString().equals(simple))) return false;
        var imports = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(simple)).toList();
        if (!imports.isEmpty()) return imports.stream().allMatch(i -> i.getNameAsString().equals(type));
        return unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk() && i.getNameAsString().equals(type.substring(0, type.lastIndexOf('.'))));
    }
}
