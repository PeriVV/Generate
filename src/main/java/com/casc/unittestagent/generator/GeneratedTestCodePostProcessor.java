package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对大模型返回的测试代码做轻量后处理。
 * 这里不做耗时编译，只清理明显无效代码并提示高风险模式，避免 UI 卡顿。
 */
public class GeneratedTestCodePostProcessor {
    private static final Pattern EMPTY_TEARDOWN = Pattern.compile(
            "(?s)\\n\\s*@AfterEach\\s*\\n\\s*void\\s+tearDown\\s*\\(\\s*\\)\\s*\\{\\s*(?://[^\\n\\r]*(?:\\R)?\\s*)*}\\s*");
    private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^import\\s+(static\\s+)?([^;]+);\\s*\\R?");
    private static final Pattern MOCK_CLASS_TYPE = Pattern.compile("\\bmock\\s*\\(\\s*([A-Z][\\w]*(?:\\.[A-Z][\\w]*)?)\\.class");
    private static final Pattern VARIABLE_TYPE = Pattern.compile("\\b([A-Z][\\w]*(?:\\.[A-Z][\\w]*)?)(?:<[^;=()]+>)?\\s+[a-z][\\w]*\\s*(?:=|;|,|\\))");
    private static final Pattern INVALID_DEEP_STUB_ANNOTATION = Pattern.compile("@Mock\\s*\\(\\s*answer\\s*=\\s*RETURNS_DEEP_STUBS\\s*\\)");
    private static final Pattern DEEP_STUB_ANNOTATION = Pattern.compile("@Mock\\s*\\(\\s*answer\\s*=\\s*Answers\\.RETURNS_DEEP_STUBS\\s*\\)");
    private static final Pattern LOCAL_DEEP_STUB = Pattern.compile("\\bmock\\s*\\(\\s*([A-Z][\\w]*(?:\\.[A-Z][\\w]*)?)\\.class\\s*,\\s*RETURNS_DEEP_STUBS\\s*\\)");
    private static final Pattern WHEN_THEN_RETURN = Pattern.compile("when\\s*\\((.+?)\\)\\s*\\.\\s*thenReturn\\s*\\((.+?)\\)\\s*;");
    private static final Pattern ASSERT_EQUALS_ONE = Pattern.compile("assertEquals\\s*\\(\\s*1\\s*,");
    private static final Pattern ASSERT_THAT_ONE = Pattern.compile("\\b(is|equalTo|sameInstance)\\s*\\(\\s*1\\s*\\)");

    /**
     * 清理并检查生成代码。
     *
     * @param code 大模型或规则生成的测试源码
     * @param methodModel 被测方法模型
     * @return 后处理后的源码和风险提示
     */
    public ProcessedCode process(String code, MethodModel methodModel) {
        String processed = code == null ? "" : code;
        List<String> warnings = new ArrayList<>();

        processed = removeEmptyTearDown(processed, warnings);
        processed = normalizeDeepStubAnnotation(processed, warnings);
        processed = ensureAnswersImport(processed);
        processed = normalizeAttachmentEmptyAssertions(processed, warnings);
        validateThenReturnTypes(processed, methodModel, warnings);
        processed = removeUnusedImports(processed);
        warnings.addAll(riskWarnings(processed, methodModel));

        return new ProcessedCode(processed, warnings);
    }

    private String removeEmptyTearDown(String code, List<String> warnings) {
        Matcher matcher = EMPTY_TEARDOWN.matcher(code);
        if (matcher.find()) {
            warnings.add("已删除空的 @AfterEach tearDown 方法；静态 mock 如果使用 try-with-resources，不需要额外 tearDown。");
            return matcher.replaceAll("\n");
        }
        return code;
    }

    private String normalizeDeepStubAnnotation(String code, List<String> warnings) {
        String processed = code;
        Matcher invalid = INVALID_DEEP_STUB_ANNOTATION.matcher(processed);
        if (invalid.find()) {
            processed = invalid.replaceAll("@Mock(answer = Answers.RETURNS_DEEP_STUBS)");
            warnings.add("已将 @Mock(answer = RETURNS_DEEP_STUBS) 修正为 @Mock(answer = Answers.RETURNS_DEEP_STUBS)。");
        }
        if (LOCAL_DEEP_STUB.matcher(processed).find()) {
            warnings.add("检测到局部 deep stubs mock；注解参数里不要使用 RETURNS_DEEP_STUBS，普通 mock(...) 中才允许使用。");
        }
        return processed;
    }

    private String normalizeAttachmentEmptyAssertions(String code, List<String> warnings) {
        if (code == null || code.isBlank()) {
            return code;
        }
        if (ASSERT_EQUALS_ONE.matcher(code).find() || ASSERT_THAT_ONE.matcher(code).find()) {
            warnings.add("检测到空附件/空路径场景使用 1 作为期望值；请改为 0。");
        }
        return code.replace("assertEquals(1,", "assertEquals(0,");
    }

    private void validateThenReturnTypes(String code, MethodModel methodModel, List<String> warnings) {
        if (code == null || code.isBlank() || methodModel == null) {
            return;
        }
        Matcher matcher = WHEN_THEN_RETURN.matcher(code);
        while (matcher.find()) {
            String stubExpression = matcher.group(1).trim();
            String returnExpression = matcher.group(2).trim();
            String returnType = inferStubReturnType(stubExpression, methodModel, code);
            String valueType = inferExpressionType(returnExpression, methodModel);
            if (!isAssignable(valueType, returnType)) {
                warnings.add("thenReturn 赋值类型不匹配：方法返回 " + returnType + "，但生成了 " + valueType + "。");
            }
        }
    }

    private String inferStubReturnType(String expression, MethodModel methodModel, String code) {
        if (expression == null) {
            return "";
        }
        String chain = expression.trim();
        if (chain.startsWith("when(")) {
            chain = chain.substring(5).trim();
        }
        if (chain.endsWith(")")) {
            chain = chain.substring(0, chain.length() - 1);
        }
        int lastDot = chain.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        String methodName = chain.substring(lastDot + 1);
        String qualifier = chain.substring(0, lastDot);
        String qualifierType = simpleTypeFromQualifier(qualifier, methodModel, code);
        if (qualifierType.isBlank()) {
            return "";
        }
        for (CallContextModel callContext : methodModel.getCallContexts()) {
            if (callContext.getExpression() != null && callContext.getExpression().contains(qualifier + "." + methodName + "(")) {
                String returnType = callContext.getReturnType();
                return returnType == null ? "" : returnType;
            }
        }
        return "";
    }

    private String inferExpressionType(String expression, MethodModel methodModel) {
        if (expression == null || methodModel == null) {
            return "";
        }
        String value = expression.trim();
        if (value.startsWith("mock(")) {
            int start = value.indexOf('(');
            int end = value.indexOf(".class");
            if (start >= 0 && end > start) {
                return value.substring(start + 1, end).trim();
            }
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (value.equals(dependency.getFieldName())) {
                return dependency.getFieldType();
            }
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (value.equals(parameter.getName())) {
                return parameter.getType();
            }
        }
        return simpleType(value);
    }

    private String simpleTypeFromQualifier(String qualifier, MethodModel methodModel, String code) {
        if (qualifier == null || methodModel == null) {
            return "";
        }
        String value = qualifier.trim();
        Matcher declarationMatcher = Pattern.compile("(?m)^\\s*([A-Z][\\w\\.<>, ?]+)\\s+" + Pattern.quote(value) + "\\s*=\\s*mock\\s*\\(").matcher(code);
        if (declarationMatcher.find()) {
            return declarationMatcher.group(1).trim();
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (value.equals(dependency.getFieldName())) {
                return simpleName(dependency.getFieldType());
            }
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (value.equals(parameter.getName())) {
                return simpleName(parameter.getType());
            }
        }
        return simpleName(value);
    }

    private boolean isAssignable(String valueType, String returnType) {
        if (valueType == null || valueType.isBlank() || returnType == null || returnType.isBlank()) {
            return true;
        }
        String normalizedValue = simpleName(valueType);
        String normalizedReturn = simpleName(returnType);
        if (normalizedReturn.equals(normalizedValue)) {
            return true;
        }
        if ("Object".equals(normalizedReturn)) {
            return true;
        }
        if (normalizedReturn.endsWith(normalizedValue)) {
            return true;
        }
        return false;
    }

    private String ensureAnswersImport(String code) {
        if (!code.contains("Answers.RETURNS_DEEP_STUBS") || code.contains("import org.mockito.Answers;")) {
            return code;
        }
        int importEnd = code.lastIndexOf("\nimport ");
        if (importEnd < 0) {
            int packageEnd = code.indexOf('\n');
            if (packageEnd < 0) {
                return "import org.mockito.Answers;\n" + code;
            }
            return code.substring(0, packageEnd + 1) + "import org.mockito.Answers;\n" + code.substring(packageEnd + 1);
        }
        int lineEnd = code.indexOf('\n', importEnd + 1);
        if (lineEnd < 0) {
            return code + "\nimport org.mockito.Answers;\n";
        }
        return code.substring(0, lineEnd + 1) + "import org.mockito.Answers;\n" + code.substring(lineEnd + 1);
    }

    private String removeUnusedImports(String code) {
        Matcher matcher = IMPORT_LINE.matcher(code);
        StringBuffer buffer = new StringBuffer();
        int importsEnd = 0;
        boolean hasImports = false;
        while (matcher.find()) {
            importsEnd = matcher.end();
            hasImports = true;
        }
        if (!hasImports) {
            return code;
        }

        String body = code.substring(importsEnd);
        matcher.reset();
        while (matcher.find()) {
            String importName = matcher.group(2).trim();
            boolean staticImport = matcher.group(1) != null;
            if (shouldKeepImport(importName, staticImport, body)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(buffer, "");
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean shouldKeepImport(String importName, boolean staticImport, String body) {
        if (importName.endsWith(".*")) {
            return true;
        }
        String simpleName = simpleName(importName);
        if (simpleName.isBlank()) {
            return true;
        }
        if ("Answers".equals(simpleName)) {
            return body.contains("Answers.RETURNS_DEEP_STUBS");
        }
        if (staticImport) {
            return body.contains(simpleName + "(")
                    || body.contains(simpleName + ".")
                    || body.contains(simpleName);
        }
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(body).find();
    }

    private List<String> riskWarnings(String code, MethodModel methodModel) {
        List<String> warnings = new ArrayList<>();
        if (methodModel == null || code == null || code.isBlank()) {
            return warnings;
        }

        Set<String> knownTypes = knownTypes(methodModel);
        knownTypes.addAll(importedTypes(code));
        for (String suspiciousType : suspiciousTypes(code)) {
            if (!knownTypes.contains(simpleName(suspiciousType))) {
                warnings.add("检测到疑似不存在的类型：" + suspiciousType + "。请确认源码中存在该类型；链式调用返回类型未知时建议使用 RETURNS_DEEP_STUBS。");
            }
        }

        if ("private".equals(methodModel.getAccessModifier())
                && directPrivateCall(code, methodModel)
                && !code.contains("getDeclaredMethod(\"" + methodModel.getMethodName() + "\"")) {
            warnings.add("当前被测方法是 private，不能直接调用。建议使用反射辅助方法或通过 public 方法间接测试。");
        }

        if (code.contains("mockStatic(Files.class)") || code.contains("MockedStatic<Files>")
                || code.contains("mockStatic(Paths.class)") || code.contains("MockedStatic<Paths>")) {
            warnings.add("检测到默认 mock Files/Paths。文件系统测试建议优先使用 @TempDir 和真实临时文件。");
        }
        if (Pattern.compile("\\bPath\\s+\\w+\\s*=\\s*mock\\s*\\(\\s*Path\\.class\\s*\\)").matcher(code).find()) {
            warnings.add("检测到 Path 使用 mock(Path.class)。路径安全校验建议使用 @TempDir 创建真实目录。");
        }
        if (INVALID_DEEP_STUB_ANNOTATION.matcher(code).find()) {
            warnings.add("检测到非法 deep stubs 注解写法：@Mock(answer = RETURNS_DEEP_STUBS)。请改用 @Mock(answer = Answers.RETURNS_DEEP_STUBS)。");
        }
        if (LOCAL_DEEP_STUB.matcher(code).find()) {
            warnings.add("检测到局部 deep stubs mock；注解参数中不要直接使用 RETURNS_DEEP_STUBS。");
        }
        if ((code.contains("Files.") || code.contains("Path ")) && !code.contains("@TempDir")) {
            warnings.add("检测到文件路径测试但未使用 @TempDir。建议使用真实临时目录构造源文件和目标目录。");
        }
        warnings.addAll(unstubbedChainWarnings(code, methodModel));
        warnings.addAll(exceptionPreconditionWarnings(code, methodModel));
        return warnings;
    }

    private List<String> unstubbedChainWarnings(String code, MethodModel methodModel) {
        List<String> warnings = new ArrayList<>();
        if (methodModel.getCallContexts() == null) {
            return warnings;
        }
        for (CallContextModel callContext : methodModel.getCallContexts()) {
            if (!"PARAMETER_CHAIN_CALL".equals(callContext.getKind()) && !"PARAMETER_OBJECT_CALL".equals(callContext.getKind())) {
                continue;
            }
            String expression = callContext.getExpression();
            if (expression == null || expression.isBlank()) {
                continue;
            }
            String owner = ownerName(callContext.getOwner());
            if (!code.contains(owner) || code.contains("when(" + expression) || code.contains("when(" + owner + ".")) {
                continue;
            }
            warnings.add("检测到参数对象调用可能未 stub：" + expression + "。如果使用 Mockito mock 参数对象，请补齐 getter/accessor/链式方法返回值；无法确定字段时应保留 TODO。");
        }
        return warnings;
    }

    private List<String> exceptionPreconditionWarnings(String code, MethodModel methodModel) {
        List<String> warnings = new ArrayList<>();
        for (BranchModel branch : methodModel.getBranches()) {
            if (!"throw".equals(branch.getAction()) || branch.getPreconditions() == null || branch.getPreconditions().isEmpty()) {
                continue;
            }
            String exceptionType = branch.getExceptionType() == null || branch.getExceptionType().isBlank()
                    ? "RuntimeException" : branch.getExceptionType();
            if (!code.contains("assertThrows(" + exceptionType + ".class")) {
                continue;
            }
            boolean mentionsPrecondition = false;
            for (String precondition : branch.getPreconditions()) {
                if (precondition != null && !precondition.isBlank() && code.contains(precondition)) {
                    mentionsPrecondition = true;
                    break;
                }
            }
            if (!mentionsPrecondition) {
                warnings.add("检测到目标异常 " + exceptionType + " 存在前置路径条件，但生成代码未明显体现这些条件，测试可能先触发更早的无关异常。");
            }
        }
        return warnings;
    }

    private String ownerName(String owner) {
        if (owner == null || owner.isBlank()) {
            return "";
        }
        int colon = owner.indexOf(':');
        return colon < 0 ? owner : owner.substring(0, colon);
    }

    private Set<String> suspiciousTypes(String code) {
        Set<String> types = new LinkedHashSet<>();
        collectTypeMatches(MOCK_CLASS_TYPE, code, types);
        collectTypeMatches(VARIABLE_TYPE, code, types);
        types.removeIf(type -> !type.endsWith("Config") && !type.contains("."));
        return types;
    }

    private void collectTypeMatches(Pattern pattern, String code, Set<String> types) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String type = matcher.group(1);
            if (type != null && !type.isBlank() && !isCommonType(type)) {
                types.add(type);
            }
        }
    }

    private boolean directPrivateCall(String code, MethodModel methodModel) {
        Pattern pattern = Pattern.compile("\\.\\s*" + Pattern.quote(methodModel.getMethodName()) + "\\s*\\(");
        return pattern.matcher(code).find();
    }

    private Set<String> knownTypes(MethodModel methodModel) {
        Set<String> types = new LinkedHashSet<>();
        addType(types, methodModel.getClassName());
        addType(types, methodModel.getReturnType());
        for (String importName : methodModel.getImports()) {
            if (!importName.endsWith(".*")) {
                addType(types, simpleName(importName));
            }
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            addType(types, parameter.getType());
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            addType(types, dependency.getFieldType());
        }
        types.addAll(commonTypes());
        return types;
    }

    private Set<String> importedTypes(String code) {
        Set<String> types = new LinkedHashSet<>();
        Matcher matcher = IMPORT_LINE.matcher(code);
        while (matcher.find()) {
            String importName = matcher.group(2).trim();
            if (!importName.endsWith(".*")) {
                addType(types, simpleName(importName));
            }
        }
        return types;
    }

    private void addType(Set<String> types, String type) {
        String simple = simpleName(type);
        if (!simple.isBlank()) {
            types.add(simple);
        }
    }

    private String simpleName(String type) {
        if (type == null) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        if (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private String simpleType(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private boolean isCommonType(String type) {
        return commonTypes().contains(simpleName(type));
    }

    private Set<String> commonTypes() {
        Set<String> types = new LinkedHashSet<>();
        types.add("String");
        types.add("Object");
        types.add("Class");
        types.add("Integer");
        types.add("Long");
        types.add("Boolean");
        types.add("Double");
        types.add("Float");
        types.add("Short");
        types.add("Byte");
        types.add("Character");
        types.add("List");
        types.add("ArrayList");
        types.add("Map");
        types.add("HashMap");
        types.add("Set");
        types.add("Optional");
        types.add("Path");
        types.add("Paths");
        types.add("Files");
        types.add("Method");
        types.add("Test");
        types.add("BeforeEach");
        types.add("AfterEach");
        types.add("TempDir");
        types.add("Mock");
        types.add("InjectMocks");
        types.add("MockedStatic");
        return types;
    }

    public static final class ProcessedCode {
        private final String code;
        private final List<String> warnings;

        private ProcessedCode(String code, List<String> warnings) {
            this.code = code;
            this.warnings = warnings;
        }

        public String getCode() {
            return code;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
