package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.Modifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将多个“单方法完整测试类”候选按 AST 聚合为一个测试类。
 * 聚合过程不依赖正则截取 Java 成员，并对字段、测试方法和 helper 冲突做确定性处理。
 */
public class ClassTestAssembler {
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");
    private static final Set<String> LIFECYCLE_ANNOTATIONS = Set.of(
            "BeforeEach", "AfterEach", "BeforeAll", "AfterAll");

    private final JavaParser parser;

    public ClassTestAssembler() {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        parser = new JavaParser(configuration);
    }

    public AssemblyResult assemble(ClassModel classModel, List<MethodGenerationItem> items) {
        if (classModel == null) throw new IllegalArgumentException("缺少被测类模型。");
        CompilationUnit output = new CompilationUnit();
        if (classModel.getPackageName() != null && !classModel.getPackageName().isBlank()) {
            output.setPackageDeclaration(classModel.getPackageName());
        }
        ClassOrInterfaceDeclaration target = output.addClass(classModel.getClassName() + "Test");
        target.getModifiers().clear();

        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, MethodDeclaration> methods = new LinkedHashMap<>();
        Map<String, TypeDeclaration<?>> nestedTypes = new LinkedHashMap<>();
        Set<String> imports = new LinkedHashSet<>();
        Set<String> annotations = new LinkedHashSet<>();
        Map<String, MethodGenerationItem> testOwners = new HashMap<>();
        List<String> rejected = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean pureStatic = classModel.getMethods().stream().allMatch(method -> method.isStaticMethod());
        String subjectName = lowerCamel(classModel.getClassName());
        Map<String, String> realFields = classFields(classModel);

        if (!pureStatic) {
            output.addImport("org.mockito.InjectMocks");
            output.addImport("org.junit.jupiter.api.BeforeEach");
            output.addImport("org.mockito.MockitoAnnotations");
            imports.add("import org.mockito.InjectMocks;");
            imports.add("import org.junit.jupiter.api.BeforeEach;");
            imports.add("import org.mockito.MockitoAnnotations;");
            FieldDeclaration subject = target.addField(classModel.getClassName(), subjectName, Modifier.Keyword.PRIVATE);
            subject.addAnnotation("InjectMocks");
            fields.put(subjectName, classModel.getClassName());
            MethodDeclaration initMockito = target.addMethod("initMockito");
            initMockito.addAnnotation("BeforeEach");
            MethodCallExpr initCall = new MethodCallExpr(new NameExpr("MockitoAnnotations"), "initMocks");
            initCall.addArgument(new ThisExpr());
            initMockito.getBody().orElseThrow().addStatement(initCall);
            methods.put(methodKey(initMockito), initMockito);
        }

        for (MethodGenerationItem item : items == null ? List.<MethodGenerationItem>of() : items) {
            String code = item.getResult() == null || item.getResult().getGeneratedTestFile() == null
                    ? "" : item.getResult().getGeneratedTestFile().getContent();
            Candidate candidate;
            try {
                candidate = parseCandidate(code, classModel.getClassName() + "Test");
                if (normalizeBeforeEach(candidate)) {
                    warnings.add(item.getSignature()
                            + " 候选中的 @BeforeEach 已转换为仅由本方法测试调用的 setup helper。");
                }
                normalizeFixtureReferences(candidate, classModel.getClassName(), subjectName, realFields);
                validateCandidate(candidate, fields, nestedTypes, pureStatic,
                        classModel.getClassName(), subjectName, realFields);
            } catch (RuntimeException ex) {
                rejected.add(item.getMethodId());
                warnings.add(item.getSignature() + " 候选无法聚合：" + ex.getMessage());
                continue;
            }

            for (ImportDeclaration declaration : candidate.unit.getImports()) {
                String key = declaration.toString().trim();
                if (imports.add(key)) output.addImport(declaration.clone());
            }
            candidate.type.getAnnotations().forEach(annotation -> {
                String key = annotation.toString();
                if (annotations.add(key)) target.addAnnotation(annotation.clone());
            });

            for (FieldDeclaration field : candidate.fields) {
                if (isSubjectField(field, classModel.getClassName())) continue;
                String name = field.getVariable(0).getNameAsString();
                String type = field.getVariable(0).getType().asString();
                if (!fields.containsKey(name)) {
                    fields.put(name, type);
                    target.addMember(field.clone());
                }
            }

            List<MethodDeclaration> clones = new ArrayList<>();
            Map<String, String> helperRenames = helperRenames(candidate.methods, methods, item.getMethodId());
            for (MethodDeclaration original : candidate.methods) {
                MethodDeclaration identical = methods.get(methodKey(original));
                if (!isTestMethod(original) && identical != null
                        && normalize(identical.toString()).equals(normalize(original.toString()))) {
                    continue;
                }
                MethodDeclaration method = original.clone();
                rewriteHelperCalls(method, helperRenames);
                if (helperRenames.containsKey(method.getNameAsString()) && !isTestMethod(method)) {
                    method.setName(helperRenames.get(method.getNameAsString()));
                }
                if (isTestMethod(method)) {
                    String signature = methodKey(method);
                    if (methods.containsKey(signature)) {
                        method.setName(uniqueTestName(method.getNameAsString(), item.getMethodId(), methods));
                    }
                    method.setComment(new LineComment(" UnitMind method: " + item.getMethodId()));
                    testOwners.put(methodKey(method), item);
                }
                clones.add(method);
                methods.put(methodKey(method), method);
            }
            for (MethodDeclaration method : clones) target.addMember(method);

            for (TypeDeclaration<?> nested : candidate.nestedTypes) {
                String name = nested.getNameAsString();
                if (!nestedTypes.containsKey(name)) {
                    nestedTypes.put(name, nested);
                    target.addMember((BodyDeclaration<?>) nested.clone());
                }
            }
        }

        normalizeMockitoLifecycle(output, target, warnings);
        sortImports(output);
        String rendered = output.toString();
        updateLineRanges(rendered, testOwners);
        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName(classModel.getPackageName());
        file.setClassName(classModel.getClassName() + "Test");
        file.setFileName(classModel.getClassName() + "Test.java");
        file.setContent(rendered);
        return new AssemblyResult(file, rejected, warnings);
    }

    /**
     * MockitoExtension and MockitoAnnotations both initialize every @Mock/@InjectMocks field. Keeping
     * both in an assembled class replaces the mocks after the subject has been injected, so verifies
     * observe a different instance from the one used by the subject. Prefer the JUnit 5 extension
     * whenever any accepted candidate requested it.
     */
    private void normalizeMockitoLifecycle(CompilationUnit unit,
                                           ClassOrInterfaceDeclaration target,
                                           List<String> warnings) {
        boolean usesMockitoExtension = target.getAnnotations().stream()
                .anyMatch(annotation -> annotation.toString().contains("MockitoExtension"));
        if (!usesMockitoExtension) return;

        List<MethodDeclaration> duplicateInitializers = target.getMethods().stream()
                .filter(this::isSharedMockitoInitializer)
                .toList();
        duplicateInitializers.forEach(MethodDeclaration::remove);
        if (duplicateInitializers.isEmpty()) return;

        boolean stillUsesBeforeEach = target.getMethods().stream()
                .anyMatch(method -> annotationNames(method).contains("BeforeEach"));
        unit.getImports().removeIf(declaration ->
                declaration.getNameAsString().equals("org.mockito.MockitoAnnotations")
                        || !stillUsesBeforeEach
                        && declaration.getNameAsString().equals("org.junit.jupiter.api.BeforeEach"));
        warnings.add("检测到 MockitoExtension，已移除重复的 MockitoAnnotations 生命周期初始化，避免 @InjectMocks 持有失效 mock。");
    }

    private Candidate parseCandidate(String code, String expectedClassName) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("测试代码为空");
        CompilationUnit unit = parser.parse(code).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Java 语法解析失败"));
        ClassOrInterfaceDeclaration type = unit.getClassByName(expectedClassName)
                .orElseGet(() -> unit.findFirst(ClassOrInterfaceDeclaration.class)
                        .orElseThrow(() -> new IllegalArgumentException("未找到测试类声明")));
        Candidate candidate = new Candidate(unit, type);
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member.isFieldDeclaration()) candidate.fields.add(member.asFieldDeclaration());
            else if (member.isMethodDeclaration()) candidate.methods.add(member.asMethodDeclaration());
            else if (member.isTypeDeclaration()) candidate.nestedTypes.add((TypeDeclaration<?>) member);
        }
        if (candidate.methods.stream().noneMatch(this::isTestMethod)) {
            throw new IllegalArgumentException("未找到 JUnit 测试方法");
        }
        return candidate;
    }

    /**
     * 单方法候选经常习惯性生成 @BeforeEach。直接合并会让该 setup 作用到整类所有测试；
     * 将其降为普通 helper，并只在同一候选的测试入口调用，既保留初始化意图又隔离方法 fixture。
     */
    private boolean normalizeBeforeEach(Candidate candidate) {
        List<MethodDeclaration> allSetupMethods = candidate.methods.stream()
                .filter(method -> annotationNames(method).contains("BeforeEach"))
                .toList();
        List<MethodDeclaration> sharedMockitoInitializers = allSetupMethods.stream()
                .filter(this::isSharedMockitoInitializer)
                .toList();
        for (MethodDeclaration initializer : sharedMockitoInitializers) {
            candidate.methods.remove(initializer);
            candidate.type.getMembers().remove(initializer);
        }
        List<MethodDeclaration> setupMethods = allSetupMethods.stream()
                .filter(method -> !sharedMockitoInitializers.contains(method))
                .toList();
        if (setupMethods.isEmpty()) return false;
        for (MethodDeclaration setup : setupMethods) {
            if (!setup.getParameters().isEmpty() || setup.getBody().isEmpty()) {
                throw new IllegalArgumentException("@BeforeEach 必须是无参数且有方法体的 setup 方法");
            }
            setup.getAnnotations().removeIf(annotation ->
                    "BeforeEach".equals(annotation.getName().getIdentifier()));
        }
        for (MethodDeclaration test : candidate.methods) {
            if (!isTestMethod(test) || test.getBody().isEmpty()) continue;
            int insertionIndex = 0;
            for (MethodDeclaration setup : setupMethods) {
                String setupName = setup.getNameAsString();
                boolean alreadyCalled = test.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        setupName.equals(call.getNameAsString())
                                && (call.getScope().isEmpty() || call.getScope().get().isThisExpr()));
                if (!alreadyCalled) {
                    test.getBody().get().addStatement(insertionIndex++, new MethodCallExpr(setupName));
                }
            }
        }
        return true;
    }

    private boolean isSharedMockitoInitializer(MethodDeclaration method) {
        if (method == null || !method.getParameters().isEmpty() || method.getBody().isEmpty()
                || method.getBody().get().getStatements().size() != 1) {
            return false;
        }
        List<MethodCallExpr> calls = method.getBody().get().findAll(MethodCallExpr.class);
        if (calls.size() != 1) return false;
        MethodCallExpr call = calls.get(0);
        return ("initMocks".equals(call.getNameAsString()) || "openMocks".equals(call.getNameAsString()))
                && call.getScope().map(Object::toString).filter("MockitoAnnotations"::equals).isPresent()
                && call.getArguments().size() == 1
                && call.getArgument(0).isThisExpr();
    }

    private void validateCandidate(Candidate candidate,
                                   Map<String, String> fields,
                                   Map<String, TypeDeclaration<?>> nestedTypes,
                                   boolean pureStatic,
                                   String subjectType,
                                   String subjectName,
                                   Map<String, String> realFields) {
        for (MethodDeclaration method : candidate.methods) {
            if (annotationNames(method).stream().anyMatch(LIFECYCLE_ANNOTATIONS::contains)) {
                throw new IllegalArgumentException("类模式不允许候选生成生命周期方法");
            }
            if (method.findAll(ObjectCreationExpr.class).stream()
                    .anyMatch(creation -> sameType(subjectType, creation.getType().asString()))) {
                throw new IllegalArgumentException("类模式不允许候选自行 new 被测对象");
            }
            if (method.findAll(AssignExpr.class).stream().anyMatch(assign ->
                    assign.getTarget().isNameExpr()
                            && subjectName.equals(assign.getTarget().asNameExpr().getNameAsString()))) {
                throw new IllegalArgumentException("类模式不允许候选重新赋值统一被测对象");
            }
            if (pureStatic && method.findAll(NameExpr.class).stream()
                    .anyMatch(expression -> subjectName.equals(expression.getNameAsString()))) {
                throw new IllegalArgumentException("纯 static 类候选不得引用被测对象实例");
            }
        }
        for (FieldDeclaration field : candidate.fields) {
            if (field.getVariables().size() != 1) {
                throw new IllegalArgumentException("一条字段声明必须只包含一个变量");
            }
            if (isSubjectField(field, subjectType)) continue;
            String name = field.getVariable(0).getNameAsString();
            String type = field.getVariable(0).getType().asString();
            if (hasAnnotation(field, "Mock") && !sameType(type, realFields.get(name))) {
                throw new IllegalArgumentException("未知 @Mock 字段 " + type + " " + name);
            }
            if (!hasAnnotation(field, "Mock") && !hasAnnotation(field, "TempDir")
                    && !field.isStatic() && !realFields.containsKey(name)) {
                throw new IllegalArgumentException("未知候选字段 " + type + " " + name);
            }
            if (fields.containsKey(name) && !fields.get(name).equals(type)) {
                throw new IllegalArgumentException("字段 " + name + " 类型冲突：" + fields.get(name) + " / " + type);
            }
        }
        for (TypeDeclaration<?> nested : candidate.nestedTypes) {
            TypeDeclaration<?> existing = nestedTypes.get(nested.getNameAsString());
            if (existing != null && !normalize(existing.toString()).equals(normalize(nested.toString()))) {
                throw new IllegalArgumentException("嵌套辅助类 " + nested.getNameAsString() + " 冲突");
            }
        }
    }

    private void normalizeFixtureReferences(Candidate candidate,
                                            String subjectType,
                                            String subjectName,
                                            Map<String, String> realFields) {
        // Class-mode methods must use the one assembled fixture. LLM candidates often create a
        // semantically equivalent local subject despite that contract; route those references to
        // the shared @InjectMocks field before applying the strict isolation gate.
        for (MethodDeclaration method : candidate.methods) {
            for (VariableDeclarator variable : new ArrayList<>(method.findAll(VariableDeclarator.class))) {
                if (!sameType(subjectType, variable.getType().asString()) || variable.getInitializer().isEmpty()
                        || !variable.getInitializer().get().isObjectCreationExpr()
                        || !sameType(subjectType,
                        variable.getInitializer().get().asObjectCreationExpr().getType().asString())) continue;
                String localName = variable.getNameAsString();
                method.findAll(NameExpr.class).stream()
                        .filter(expression -> localName.equals(expression.getNameAsString()))
                        .forEach(expression -> expression.setName(subjectName));
                variable.findAncestor(ExpressionStmt.class).ifPresent(ExpressionStmt::remove);
            }
            for (AssignExpr assignment : new ArrayList<>(method.findAll(AssignExpr.class))) {
                if (!assignment.getTarget().isNameExpr()
                        || !subjectName.equals(assignment.getTarget().asNameExpr().getNameAsString())
                        || !assignment.getValue().isObjectCreationExpr()
                        || !sameType(subjectType,
                        assignment.getValue().asObjectCreationExpr().getType().asString())) continue;
                assignment.findAncestor(ExpressionStmt.class).ifPresent(ExpressionStmt::remove);
            }
            for (ObjectCreationExpr creation : new ArrayList<>(method.findAll(ObjectCreationExpr.class))) {
                if (sameType(subjectType, creation.getType().asString())) {
                    creation.replace(new NameExpr(subjectName));
                }
            }
        }
        for (FieldDeclaration field : candidate.fields) {
            if (field.getVariables().size() != 1) continue;
            String oldName = field.getVariable(0).getNameAsString();
            String type = field.getVariable(0).getType().asString();
            if (sameType(subjectType, type)) {
                renameReferences(candidate.type, oldName, subjectName);
                field.getVariable(0).setName(subjectName);
                field.getVariable(0).setType(subjectType);
                continue;
            }
            if (!hasAnnotation(field, "Mock")) continue;
            if (realFields.containsKey(oldName) && sameType(type, realFields.get(oldName))) {
                field.getVariable(0).setType(realFields.get(oldName));
                continue;
            }
            List<String> matches = realFields.entrySet().stream()
                    .filter(entry -> sameType(type, entry.getValue()))
                    .map(Map.Entry::getKey).toList();
            if (matches.size() == 1) {
                String canonicalName = matches.get(0);
                renameReferences(candidate.type, oldName, canonicalName);
                field.getVariable(0).setName(canonicalName);
                field.getVariable(0).setType(realFields.get(canonicalName));
            }
        }
    }

    private void renameReferences(ClassOrInterfaceDeclaration type, String oldName, String newName) {
        if (oldName.equals(newName)) return;
        type.findAll(NameExpr.class).stream()
                .filter(expression -> oldName.equals(expression.getNameAsString()))
                .forEach(expression -> expression.setName(newName));
    }

    private Map<String, String> classFields(ClassModel classModel) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String declaration : classModel.getClassFields()) {
            if (declaration == null || declaration.isBlank()) continue;
            String normalized = declaration.trim().replaceAll("\\s+", " ");
            int split = normalized.lastIndexOf(' ');
            if (split > 0 && split < normalized.length() - 1) {
                fields.put(normalized.substring(split + 1), normalized.substring(0, split));
            }
        }
        return fields;
    }

    private boolean isSubjectField(FieldDeclaration field, String subjectType) {
        return field.getVariables().stream()
                .anyMatch(variable -> sameType(subjectType, variable.getType().asString()));
    }

    private boolean hasAnnotation(FieldDeclaration field, String annotationName) {
        return field.getAnnotations().stream()
                .anyMatch(annotation -> annotationName.equals(annotation.getName().getIdentifier()));
    }

    private String lowerCamel(String value) {
        if (value == null || value.isBlank()) return "subject";
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private boolean sameType(String left, String right) {
        if (left == null || right == null) return false;
        return simpleType(left).equals(simpleType(right));
    }

    private String simpleType(String type) {
        String value = type == null ? "" : type.replaceAll("\\s+", "");
        int generic = value.indexOf('<');
        String raw = generic < 0 ? value : value.substring(0, generic);
        int dot = raw.lastIndexOf('.');
        String simpleRaw = dot < 0 ? raw : raw.substring(dot + 1);
        return generic < 0 ? simpleRaw : simpleRaw + value.substring(generic);
    }

    private Map<String, String> helperRenames(List<MethodDeclaration> candidates,
                                               Map<String, MethodDeclaration> existing,
                                               String methodId) {
        Map<String, String> renames = new HashMap<>();
        for (MethodDeclaration helper : candidates) {
            if (isTestMethod(helper)) continue;
            MethodDeclaration previous = existing.get(methodKey(helper));
            if (previous != null && !normalize(previous.toString()).equals(normalize(helper.toString()))) {
                renames.put(helper.getNameAsString(), helper.getNameAsString() + "_" + shortHash(methodId));
            }
        }
        return renames;
    }

    private void rewriteHelperCalls(MethodDeclaration method, Map<String, String> renames) {
        if (renames.isEmpty()) return;
        method.findAll(MethodCallExpr.class).forEach(call -> {
            String replacement = renames.get(call.getNameAsString());
            if (replacement != null && (call.getScope().isEmpty() || call.getScope().get().isThisExpr())) {
                call.setName(replacement);
            }
        });
    }

    private boolean isTestMethod(MethodDeclaration method) {
        return annotationNames(method).stream().anyMatch(TEST_ANNOTATIONS::contains);
    }

    private Set<String> annotationNames(MethodDeclaration method) {
        Set<String> names = new LinkedHashSet<>();
        method.getAnnotations().forEach(annotation -> names.add(annotation.getName().getIdentifier()));
        return names;
    }

    private String methodKey(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        method.getParameters().forEach(parameter -> types.add(parameter.getType().asString()));
        return method.getNameAsString() + "(" + String.join(",", types) + ")";
    }

    private String uniqueTestName(String base, String methodId, Map<String, MethodDeclaration> methods) {
        String candidate = base + "_" + shortHash(methodId);
        int suffix = 2;
        while (containsMethodName(methods, candidate)) {
            candidate = base + "_" + shortHash(methodId) + "_" + suffix++;
        }
        return candidate;
    }

    private boolean containsMethodName(Map<String, MethodDeclaration> methods, String name) {
        for (String key : methods.keySet()) if (key.startsWith(name + "(")) return true;
        return false;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "method" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < 4; index++) text.append(String.format(Locale.ROOT, "%02x", digest[index]));
            return text.toString();
        } catch (Exception ignored) {
            return "method";
        }
    }

    private String normalize(String source) {
        return source == null ? "" : source.replaceAll("\\s+", "").trim();
    }

    private void sortImports(CompilationUnit unit) {
        List<ImportDeclaration> sorted = new ArrayList<>(unit.getImports());
        sorted.sort(Comparator.comparing(ImportDeclaration::isStatic)
                .thenComparing(declaration -> declaration.getNameAsString()));
        unit.getImports().clear();
        sorted.forEach(unit::addImport);
    }

    private void updateLineRanges(String rendered, Map<String, MethodGenerationItem> owners) {
        parser.parse(rendered).getResult().ifPresent(unit -> unit.findAll(MethodDeclaration.class).forEach(method -> {
            MethodGenerationItem owner = owners.get(methodKey(method));
            if (owner == null || method.getRange().isEmpty()) return;
            int start = method.getRange().get().begin.line;
            int end = method.getRange().get().end.line;
            owner.setGeneratedStartLine(owner.getGeneratedStartLine() < 0
                    ? start : Math.min(owner.getGeneratedStartLine(), start));
            owner.setGeneratedEndLine(Math.max(owner.getGeneratedEndLine(), end));
        }));
    }

    private static class Candidate {
        private final CompilationUnit unit;
        private final ClassOrInterfaceDeclaration type;
        private final List<FieldDeclaration> fields = new ArrayList<>();
        private final List<MethodDeclaration> methods = new ArrayList<>();
        private final List<TypeDeclaration<?>> nestedTypes = new ArrayList<>();

        private Candidate(CompilationUnit unit, ClassOrInterfaceDeclaration type) {
            this.unit = unit;
            this.type = type;
        }
    }

    public static class AssemblyResult {
        private final GeneratedTestFile generatedTestFile;
        private final List<String> rejectedMethodIds;
        private final List<String> warnings;

        private AssemblyResult(GeneratedTestFile generatedTestFile,
                               List<String> rejectedMethodIds,
                               List<String> warnings) {
            this.generatedTestFile = generatedTestFile;
            this.rejectedMethodIds = rejectedMethodIds;
            this.warnings = warnings;
        }

        public GeneratedTestFile getGeneratedTestFile() { return generatedTestFile; }
        public List<String> getRejectedMethodIds() { return rejectedMethodIds; }
        public List<String> getWarnings() { return warnings; }
    }
}
