package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

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

    private MissingFixtureDependencyDetector() { }

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
