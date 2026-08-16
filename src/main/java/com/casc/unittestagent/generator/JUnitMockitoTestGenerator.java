package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.util.StringUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据方法模型、测试场景和 Mock 策略生成 JUnit5 与 Mockito 风格的测试源码。
 * 该生成器承担代码拼装职责，保持输出可预测，便于用户在预览窗口中继续调整。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class JUnitMockitoTestGenerator {
    /** 日志对象，用于记录测试源码生成器内部兜底原因。 */
    private static final Logger LOG = Logger.getInstance(JUnitMockitoTestGenerator.class);
    /** 测试数据生成器，负责按类型提供可写入源码的参数值。 */
    private final RandomTestDataGenerator dataGenerator = new RandomTestDataGenerator();

    /**
     * 执行单元测试源码生成。
     * 方法把分析模型和场景转换为一个可预览、可写入磁盘的测试文件模型。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param methodModel 源码分析得到的被测方法模型
     * @param scenarios 已规划的测试场景列表
     * @param mockStrategy 依赖调用对应的 Mock 策略
     * @return 生成流程结果或生成的测试文件模型
     */
    public GeneratedTestFile generate(MethodModel methodModel, List<TestScenario> scenarios, MockStrategy mockStrategy) {
        GeneratedTestFile file = new GeneratedTestFile();
        String testClassName = methodModel.getClassName() + "Test";
        file.setPackageName(methodModel.getPackageName());
        file.setClassName(testClassName);
        file.setFileName(testClassName + ".java");
        String content;
        try {
            content = buildContent(methodModel, scenarios == null ? new ArrayList<>() : scenarios, mockStrategy);
        } catch (Exception ex) {
            LOG.warn("UnitMind: generate test code failed", ex);
            content = fallbackContent(methodModel);
        }
        if (content == null || content.trim().isEmpty()) {
            content = fallbackContent(methodModel);
        }
        file.setContent(content);
        return file;
    }

    /**
     * 组装完整测试类源码。
     * 这里集中处理包名、导入、Mock 字段、被测对象和每个测试方法的输出顺序。
     */
    private String buildContent(MethodModel methodModel, List<TestScenario> scenarios, MockStrategy mockStrategy) {
        StringBuilder code = new StringBuilder();
        if (methodModel.getPackageName() != null && !methodModel.getPackageName().isBlank()) {
            code.append("package ").append(methodModel.getPackageName()).append(";\n\n");
        }

        for (String importName : importsFor(methodModel, scenarios)) {
            code.append("import ").append(importName).append(";\n");
        }
        code.append("\n");
        code.append("import static org.junit.jupiter.api.Assertions.*;\n");
        code.append("import static org.mockito.Mockito.*;\n\n");

        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");

        boolean hasFieldMocks = false;
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (shouldGenerateFieldMock(dependency, mockStrategy)) {
                hasFieldMocks = true;
                code.append("    @Mock\n");
                code.append("    private ").append(dependency.getFieldType()).append(" ").append(dependency.getFieldName()).append(";\n\n");
            }
        }

        String subjectName = StringUtils.lowerCamel(methodModel.getClassName());
        if (!methodModel.isStaticMethod()) {
            code.append("    @InjectMocks\n");
            code.append("    private ").append(methodModel.getClassName()).append(" ").append(subjectName).append(";\n\n");
        }
        if (!methodModel.isStaticMethod() || hasFieldMocks) {
            appendMockitoInitialization(code);
        }

        for (TestScenario scenario : scenarios) {
            code.append("\n");
            appendTestMethod(code, methodModel, scenario, subjectName, mockStrategy);
        }

        if (isPrivateMethod(methodModel)) {
            appendPrivateMethodInvoker(code, methodModel, subjectName);
        }

        code.append("}\n");
        return code.toString();
    }

    private boolean shouldGenerateFieldMock(DependencyModel dependency, MockStrategy mockStrategy) {
        if (dependency == null) {
            return false;
        }
        MockStrategyItem selected = strategyItem(dependency, mockStrategy);
        if (selected != null) {
            return selected.isGenerate() && isFieldMockStrategy(selected.getMockitoStrategy());
        }
        if (mockStrategy != null && mockStrategy.getMockFields() != null) {
            for (String field : mockStrategy.getMockFields()) {
                if (field == null) continue;
                String value = field.trim();
                if (value.equals(dependency.getFieldName())
                        || value.endsWith(" " + dependency.getFieldName())) {
                    return true;
                }
            }
        }
        return !looksLikeDtoType(dependency.getFieldType()) && !looksLikePathType(dependency.getFieldType());
    }

    private MockStrategyItem strategyItem(DependencyModel dependency, MockStrategy strategy) {
        if (dependency == null || strategy == null || strategy.getStrategyItems() == null) return null;
        String fieldName = dependency.getFieldName() == null ? "" : dependency.getFieldName();
        for (MockStrategyItem item : strategy.getStrategyItems()) {
            if (item == null) continue;
            if (fieldName.equals(item.getSource()) || fieldName.equals(item.getMethodName())
                    || fieldName.equals(item.getObjectName())
                    || startsWithReceiver(item.getSource(), fieldName)) {
                return item;
            }
        }
        return null;
    }

    private boolean startsWithReceiver(String expression, String receiver) {
        return expression != null && receiver != null && !receiver.isBlank()
                && (expression.startsWith(receiver + ".") || expression.startsWith(receiver + "("));
    }

    private boolean isFieldMockStrategy(String strategy) {
        if (strategy == null) return false;
        String value = strategy.replace(" ", "").toLowerCase();
        return "mock".equals(value) || "fieldmock".equals(value) || value.contains("字段级mock");
    }

    private boolean isLocalMockStrategy(String strategy) {
        if (strategy == null) return false;
        String value = strategy.replace(" ", "").toLowerCase();
        return value.contains("localmock") || value.contains("局部mock");
    }

    private boolean looksLikeDtoType(String type) {
        String simple = simpleType(type);
        return simple.endsWith("Request")
                || simple.endsWith("Response")
                || simple.endsWith("DTO")
                || simple.endsWith("VO")
                || simple.endsWith("BO")
                || simple.endsWith("PO")
                || simple.endsWith("Entity")
                || simple.endsWith("Model")
                || simple.endsWith("Command")
                || simple.endsWith("Query")
                || simple.endsWith("Param")
                || simple.endsWith("Profile")
                || simple.endsWith("Form")
                || simple.endsWith("Result");
    }

    private boolean looksLikePathType(String type) {
        String simple = simpleType(type);
        return "Path".equals(simple)
                || "File".equals(simple)
                || "Directory".equalsIgnoreCase(simple)
                || "PathMatcher".equals(simple)
                || "FileSystem".equals(simple);
    }

    /**
     * 生成最小可编辑测试类草稿。
     * 当复杂方法或依赖推断导致常规生成失败时，仍提供可编辑的完整测试类，避免测试代码页空白。
     */
    private String fallbackContent(MethodModel methodModel) {
        StringBuilder code = new StringBuilder();
        String subjectName = StringUtils.lowerCamel(methodModel.getClassName());
        if (methodModel.getPackageName() != null && !methodModel.getPackageName().isBlank()) {
            code.append("package ").append(methodModel.getPackageName()).append(";\n\n");
        }
        Set<String> imports = new LinkedHashSet<>();
        String returnImport = importForType(methodModel, methodModel.getReturnType());
        if (returnImport != null) {
            imports.add(returnImport);
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            String importName = importForType(methodModel, parameter.getType());
            if (importName != null) {
                imports.add(importName);
            }
        }
        imports.add("org.junit.jupiter.api.Test");
        if (!methodModel.isStaticMethod()) {
            imports.add("org.junit.jupiter.api.BeforeEach");
            imports.add("org.mockito.InjectMocks");
            imports.add("org.mockito.Mock");
            imports.add("org.mockito.MockitoAnnotations");
        }
        for (String importName : imports) {
            code.append("import ").append(importName).append(";\n");
        }
        code.append("\nimport static org.junit.jupiter.api.Assertions.*;\n");
        code.append("import static org.mockito.Mockito.*;\n\n");
        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");
        if (!methodModel.isStaticMethod()) {
            for (DependencyModel dependency : methodModel.getDependencies()) {
                if (dependency.isMethodParameter() || looksLikeDtoType(dependency.getFieldType())
                        || looksLikePathType(dependency.getFieldType())) continue;
                code.append("    @Mock\n");
                code.append("    private ").append(dependency.getFieldType()).append(" ")
                        .append(dependency.getFieldName()).append(";\n\n");
            }
            code.append("    @InjectMocks\n");
            code.append("    private ").append(methodModel.getClassName()).append(" ")
                    .append(subjectName).append(";\n\n");
            appendMockitoInitialization(code);
            code.append("\n");
        }
        code.append("    @Test\n");
        code.append("    void ").append(fallbackMethodName(methodModel)).append("()");
        if (isPrivateMethod(methodModel)) {
            code.append(" throws Exception");
        }
        code.append(" {\n");
        code.append("        // TODO: UnitMind 暂未能完整推断该方法的依赖和断言，请根据业务补充测试数据。\n");
        for (ParameterModel parameter : methodModel.getParameters()) {
            String value = compileSafeValueExpression(parameter.getType());
            if ("null".equals(value)) {
                code.append("        // TODO: ").append(parameter.getType()).append(" 无法自动构造，请根据业务补充测试数据。\n");
            }
            code.append("        ").append(parameter.getType()).append(" ").append(parameter.getName())
                    .append(" = ").append(value).append(";\n");
        }
        if (!methodModel.getParameters().isEmpty()) {
            code.append("\n");
        }
        String invocation = invocationExpression(methodModel, subjectName, fallbackArguments(methodModel));
        if ("void".equals(methodModel.getReturnType())) {
            code.append("        assertDoesNotThrow(() -> ").append(invocation).append(");\n");
        } else {
            code.append("        ").append(methodModel.getReturnType())
                    .append(" result = assertDoesNotThrow(() -> ").append(invocation).append(");\n");
        }
        code.append("    }\n");
        if (isPrivateMethod(methodModel)) {
            appendPrivateMethodInvoker(code, methodModel, subjectName);
        }
        code.append("}\n");
        return code.toString();
    }

    private String fallbackMethodName(MethodModel methodModel) {
        return "should" + capitalize(methodModel.getMethodName()) + "WhenInputValid";
    }

    private String fallbackArguments(MethodModel methodModel) {
        List<String> args = new ArrayList<>();
        for (ParameterModel parameter : methodModel.getParameters()) {
            args.add(parameter.getName());
        }
        return String.join(", ", args);
    }

    private String capitalize(String text) {
        if (text == null || text.isBlank()) {
            return "Execute";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * 为单个场景追加测试方法源码。
     * 异常场景生成 assertThrows，普通场景根据返回类型选择直接调用或结果断言。
     */
    private void appendTestMethod(StringBuilder code, MethodModel methodModel, TestScenario scenario, String subjectName, MockStrategy mockStrategy) {
        code.append("    @Test\n");
        code.append("    void ").append(scenario.getMethodNameEn()).append("()");
        if (requiresThrowsException(methodModel)) {
            code.append(" throws Exception");
        }
        code.append(" {\n");

        Map<String, String> values = valuesForScenario(methodModel, scenario);
        appendInputVariables(code, methodModel, values, mockStrategy);

        if (isOptionalReturn(methodModel) && !methodModel.getDependencies().isEmpty()) {
            appendOptionalTestBody(code, methodModel, scenario, subjectName, values, mockStrategy);
            code.append("    }\n");
            return;
        }

        List<String> args = argumentsFor(methodModel, values);
        String invocation = invocationExpression(methodModel, subjectName, String.join(", ", args));

        if ("EXCEPTION".equals(scenario.getType())) {
            String exceptionType = exceptionTypeFor(methodModel, scenario);
            code.append("        assertThrows(").append(exceptionType).append(".class, () -> {\n");
            code.append("            ").append(invocation).append(";\n");
            code.append("        });\n\n");
            code.append("    }\n");
            return;
        }

        if ("void".equals(methodModel.getReturnType())) {
            code.append("        assertDoesNotThrow(() -> ").append(invocation).append(");\n");
        } else {
            String resultType = methodModel.getReturnType();
            code.append("        ").append(resultType).append(" result = assertDoesNotThrow(() -> ")
                    .append(invocation).append(");\n");
        }
        code.append("    }\n");
    }

    /**
     * 计算某个场景下的方法调用参数表达式。
     * 先填充默认值，再根据分支条件覆盖关键参数，以保证生成用例能命中目标路径。
     */
    private Map<String, String> valuesForScenario(MethodModel methodModel, TestScenario scenario) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ParameterModel parameter : methodModel.getParameters()) {
            values.put(parameter.getName(), valueForScenario(methodModel, parameter, scenario));
        }

        if ("NORMAL".equals(scenario.getType())) {
            for (BranchModel branch : methodModel.getBranches()) {
                if (!"throw".equals(branch.getAction())) continue;
                applyCondition(values, methodModel, branch.getSafeCondition(), false);
            }
        } else if ("EXCEPTION".equals(scenario.getType()) && !scenario.getCoveredBranches().isEmpty()) {
            BranchModel targetBranch = branchForScenario(methodModel, scenario);
            if (targetBranch != null && targetBranch.getPreconditions() != null) {
                for (String precondition : targetBranch.getPreconditions()) {
                    applyCondition(values, methodModel, precondition, false);
                }
            }
            applyCondition(values, methodModel, scenario.getCoveredBranches().get(0), true);
        }

        return values;
    }

    private BranchModel branchForScenario(MethodModel methodModel, TestScenario scenario) {
        if (scenario.getCoveredBranches().isEmpty()) {
            return null;
        }
        String condition = scenario.getCoveredBranches().get(0);
        for (BranchModel branch : methodModel.getBranches()) {
            if (condition.equals(branch.getCondition())) {
                return branch;
            }
        }
        return null;
    }

    /**
     * 计算方法调用实参列表。
     * 非 Mock 参数使用已声明的局部变量名，Mock 参数直接使用字段名，保证 when、调用和 verify 一致。
     */
    private List<String> argumentsFor(MethodModel methodModel, Map<String, String> values) {
        List<String> args = new ArrayList<>();
        for (ParameterModel parameter : methodModel.getParameters()) {
            args.add(parameter.getName());
        }
        return args;
    }

    /**
     * 选择参数在指定场景下的默认表达式。
     * 边界场景优先取边界值，普通场景取普通值，缺失数据时退回随机生成器。
     */
    private String valueForScenario(MethodModel methodModel, ParameterModel parameter, TestScenario scenario) {
        if (scenario.getCoveredBranches().contains(parameter.getName() + " == null")) {
            return "null";
        }
        if (isOptionalReturn(methodModel)) {
            return stableOptionalInputValue(parameter);
        }
        if ("BOUNDARY".equals(scenario.getType()) && parameter.getBoundaryValues() != null && !parameter.getBoundaryValues().isEmpty()) {
            return parameter.getBoundaryValues().get(0);
        }
        if (parameter.getNormalValues() != null && !parameter.getNormalValues().isEmpty()) {
            return parameter.getNormalValues().get(0);
        }
        return dataGenerator.generateValueExpression(parameter.getType());
    }

    /**
     * 为 Optional 查询场景生成稳定输入值。
     * 稳定值能让 when、被测方法调用和 verify 使用同一组变量，避免随机字面量造成未定义变量。
     */
    private String stableOptionalInputValue(ParameterModel parameter) {
        String type = simpleType(parameter.getType());
        if ("long".equals(type) || "Long".equals(type)) {
            return "1L";
        }
        if ("int".equals(type) || "Integer".equals(type)) {
            return "1";
        }
        if ("String".equals(type)) {
            String name = parameter.getName() == null ? "" : parameter.getName().toLowerCase();
            return name.contains("owner") ? "\"owner_001\"" : "\"value_001\"";
        }
        return dataGenerator.generateValueExpression(parameter.getType());
    }

    /**
     * 追加输入变量声明。
     * 所有非 Mock 参数都先声明局部变量，后续 when、被测调用和 verify 共用这些变量名。
     */
    private void appendInputVariables(StringBuilder code,
                                      MethodModel methodModel,
                                      Map<String, String> values,
                                      MockStrategy mockStrategy) {
        boolean hasInputVariable = false;
        for (ParameterModel parameter : methodModel.getParameters()) {
            DependencyModel dependency = methodParameterDependency(parameter.getName(), methodModel);
            if (dependency != null && shouldGenerateFieldMock(dependency, mockStrategy)) {
                continue;
            }
            String expression = values.get(parameter.getName());
            MockStrategyItem selected = strategyItem(dependency, mockStrategy);
            if (dependency != null && selected != null && selected.isGenerate()
                    && isLocalMockStrategy(selected.getMockitoStrategy())) {
                expression = "mock(" + simpleType(parameter.getType()) + ".class)";
            } else if (dependency != null && "null".equals(expression)
                    && dependency.getCallExpressions() != null && !dependency.getCallExpressions().isEmpty()) {
                expression = "mock(" + simpleType(parameter.getType()) + ".class)";
            }
            code.append("        ").append(parameter.getType()).append(" ")
                    .append(parameter.getName()).append(" = ")
                    .append(expression).append(";\n");
            hasInputVariable = true;
        }
        if (hasInputVariable) {
            code.append("\n");
        }
    }

    /**
     * 追加 Optional 返回值方法的专用测试主体。
     * 正常场景使用 Optional.of，空结果场景使用 Optional.empty，避免 Mock 返回 null 和弱断言。
     */
    private void appendOptionalTestBody(StringBuilder code,
                                        MethodModel methodModel,
                                        TestScenario scenario,
                                        String subjectName,
                                        Map<String, String> values,
                                        MockStrategy mockStrategy) {
        DependencyModel dependency = firstDependencyWithCall(methodModel);
        if (dependency != null && !dependency.isMethodParameter()
                && !shouldGenerateFieldMock(dependency, mockStrategy)) {
            dependency = null;
        }
        String callExpression = dependency == null || dependency.getCallExpressions().isEmpty()
                ? null
                : dependency.getCallExpressions().get(0);

        if (scenario.getMethodNameEn().contains("Null")) {
            code.append("        // TODO: 根据源码逻辑补充期望行为，例如 assertThrows 或 assertTrue(result.isEmpty())\n");
            return;
        }

        String valueType = optionalValueType(methodModel);
        if ("NORMAL".equals(scenario.getType())) {
            code.append("        ").append(valueType).append(" connection = ")
                    .append(optionalValueExpression(valueType)).append(";\n\n");
            if (dependency != null && callExpression != null) {
                code.append("        when(").append(dependency.getFieldName()).append(".").append(callExpression)
                        .append(").thenReturn(Optional.of(connection));\n\n");
            }
        } else {
            if (dependency != null && callExpression != null) {
                code.append("        when(").append(dependency.getFieldName()).append(".").append(callExpression)
                        .append(").thenReturn(Optional.empty());\n\n");
            }
        }

        String invocation = invocationExpression(methodModel, subjectName, String.join(", ", argumentsFor(methodModel, values)));
        code.append("        ").append(methodModel.getReturnType()).append(" result = ").append(invocation).append(";\n\n");
        if ("NORMAL".equals(scenario.getType())) {
            code.append("        assertTrue(result.isPresent());\n");
            code.append("        assertSame(connection, result.get());\n");
        } else {
            code.append("        assertTrue(result.isEmpty());\n");
        }
    }

    private String optionalValueExpression(String valueType) {
        String generated = dataGenerator.generateValueExpression(valueType);
        if (generated == null || generated.isBlank() || "null".equals(generated)
                || generated.startsWith("new ")) {
            return "mock(" + simpleType(valueType) + ".class)";
        }
        return generated;
    }

    /**
     * 根据条件表达式修正参数取值。
     * 该方法只处理当前 MVP 支持的空值、非空、范围和参数关系，复杂表达式保持默认值。
     */
    private void applyCondition(Map<String, String> values, MethodModel methodModel, String condition, boolean exceptionInput) {
        if (condition == null || condition.isBlank()) {
            return;
        }
        String normalized = strip(condition);

        if (normalized.contains("== null")) {
            String parameter = normalized.substring(0, normalized.indexOf("== null")).trim();
            values.put(parameter, "null");
            return;
        }
        if (normalized.contains("!= null")) {
            String parameter = normalized.substring(0, normalized.indexOf("!= null")).trim();
            values.put(parameter, nonNullValue(methodModel, parameter));
            return;
        }

        for (String operator : new String[]{">=", ">", "<=", "<"}) {
            int index = normalized.indexOf(operator);
            if (index < 0) {
                continue;
            }
            String left = normalized.substring(0, index).trim();
            String right = normalized.substring(index + operator.length()).trim();
            if (isParameter(methodModel, left) && isParameter(methodModel, right)) {
                applyRelation(values, methodModel, left, right, operator);
            } else if (isParameter(methodModel, left)) {
                values.put(left, exceptionInput ? right : safeLiteralFor(left, right, operator, methodModel));
            }
            return;
        }
    }

    /**
     * 处理两个参数之间的大小关系。
     * 通过为左右参数分配高低值来满足或绕过关系条件。
     */
    private void applyRelation(Map<String, String> values, MethodModel methodModel, String left, String right, String operator) {
        ParameterModel leftParameter = parameter(methodModel, left);
        ParameterModel rightParameter = parameter(methodModel, right);
        if (leftParameter == null || rightParameter == null) {
            return;
        }
        String low = dataGenerator.relationLowValue(leftParameter.getType());
        String high = dataGenerator.relationHighValue(rightParameter.getType());
        if (">=".equals(operator) || ">".equals(operator)) {
            values.put(left, high);
            values.put(right, low);
        } else {
            values.put(left, low);
            values.put(right, high);
        }
    }

    /**
     * 为范围条件选择安全边界值。
     * 正常路径使用配置边界绕过异常条件，避免直接使用触发异常的字面量。
     */
    private String safeLiteralFor(String parameterName, String literal, String operator, MethodModel methodModel) {
        ParameterModel parameter = parameter(methodModel, parameterName);
        if (parameter == null) {
            return literal;
        }
        if (">=".equals(operator) || ">".equals(operator)) {
            return dataGenerator.minValueExpression(parameter.getType());
        }
        if ("<=".equals(operator) || "<".equals(operator)) {
            return dataGenerator.maxValueExpression(parameter.getType());
        }
        return literal;
    }

    /**
     * 为指定参数生成非空表达式。
     * 当无法找到参数模型时使用字符串兜底，保证非空条件仍能被满足。
     */
    private String nonNullValue(MethodModel methodModel, String parameterName) {
        ParameterModel parameter = parameter(methodModel, parameterName);
        if (parameter == null) {
            return "\"test\"";
        }
        return dataGenerator.generateValueExpression(parameter.getType());
    }

    /**
     * 判断名称是否为目标方法参数。
     * 条件解析只允许参数参与输入生成，避免误把字段或局部变量当作测试入参。
     */
    private boolean isParameter(MethodModel methodModel, String name) {
        return parameter(methodModel, name) != null;
    }

    /**
     * 按名称查找参数模型。
     * 多个生成步骤都需要从条件文本回到参数元数据，因此统一在这里查找。
     */
    private ParameterModel parameter(MethodModel methodModel, String name) {
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (parameter.getName().equals(name)) {
                return parameter;
            }
        }
        return null;
    }

    /**
     * 确定异常场景应断言的异常类型。
     * 无法从分支模型匹配时退回 RuntimeException，保证生成代码仍有明确断言目标。
     */
    private String exceptionTypeFor(MethodModel methodModel, TestScenario scenario) {
        for (BranchModel branch : methodModel.getBranches()) {
            if (!scenario.getCoveredBranches().isEmpty() && scenario.getCoveredBranches().get(0).equals(branch.getCondition())) {
                return branch.getExceptionType() == null || branch.getExceptionType().isBlank()
                        ? "RuntimeException"
                        : branch.getExceptionType();
            }
        }
        return "RuntimeException";
    }

    /**
     * 追加正常路径依赖调用验证。
     * 正常场景期望依赖被调用一次，便于测试观察被测方法的协作行为。
     */
    /**
     * 为 private 被测方法追加通用反射调用辅助方法。
     * 用户直接右键 private 方法时不能生成 subject.privateMethod(...)，否则测试代码无法编译。
     */
    private void appendPrivateMethodInvoker(StringBuilder code, MethodModel methodModel, String subjectName) {
        String helperName = privateInvokerName(methodModel);
        code.append("\n");
        code.append("    private ").append(methodModel.getReturnType()).append(" ").append(helperName).append("(")
                .append(parameterDeclarations(methodModel)).append(") throws Exception {\n");
        code.append("        Method method = ").append(methodModel.getClassName()).append(".class.getDeclaredMethod(\"")
                .append(methodModel.getMethodName()).append("\"");
        for (ParameterModel parameter : methodModel.getParameters()) {
            code.append(", ").append(classLiteralFor(parameter.getType()));
        }
        code.append(");\n");
        code.append("        method.setAccessible(true);\n");
        code.append("        try {\n");
        if ("void".equals(methodModel.getReturnType())) {
            code.append("            method.invoke(").append(subjectName).append(invocationArguments(methodModel)).append(");\n");
            code.append("            return;\n");
        } else {
            code.append("            return (").append(methodModel.getReturnType()).append(") method.invoke(")
                    .append(subjectName).append(invocationArguments(methodModel)).append(");\n");
        }
        code.append("        } catch (InvocationTargetException ex) {\n");
        code.append("            Throwable cause = ex.getCause();\n");
        code.append("            if (cause instanceof Exception) {\n");
        code.append("                throw (Exception) cause;\n");
        code.append("            }\n");
        code.append("            if (cause instanceof Error) {\n");
        code.append("                throw (Error) cause;\n");
        code.append("            }\n");
        code.append("            throw ex;\n");
        code.append("        }\n");
        code.append("    }\n");
    }

    private String invocationExpression(MethodModel methodModel, String subjectName, String arguments) {
        if (isPrivateMethod(methodModel)) {
            return privateInvokerName(methodModel) + "(" + arguments + ")";
        }
        if (methodModel != null && methodModel.isStaticMethod()) {
            return methodModel.getClassName() + "." + methodModel.getMethodName() + "(" + arguments + ")";
        }
        return subjectName + "." + methodModel.getMethodName() + "(" + arguments + ")";
    }

    private boolean isPrivateMethod(MethodModel methodModel) {
        return methodModel != null && "private".equals(methodModel.getAccessModifier());
    }

    private String privateInvokerName(MethodModel methodModel) {
        return "invoke" + capitalize(methodModel.getMethodName());
    }

    private String parameterDeclarations(MethodModel methodModel) {
        List<String> declarations = new ArrayList<>();
        for (ParameterModel parameter : methodModel.getParameters()) {
            declarations.add(parameter.getType() + " " + parameter.getName());
        }
        return String.join(", ", declarations);
    }

    private String invocationArguments(MethodModel methodModel) {
        if (methodModel.getParameters().isEmpty()) {
            return "";
        }
        List<String> arguments = new ArrayList<>();
        for (ParameterModel parameter : methodModel.getParameters()) {
            arguments.add(parameter.getName());
        }
        return ", " + String.join(", ", arguments);
    }

    private String classLiteralFor(String type) {
        String simple = simpleType(type);
        switch (simple) {
            case "byte":
                return "byte.class";
            case "short":
                return "short.class";
            case "int":
                return "int.class";
            case "long":
                return "long.class";
            case "float":
                return "float.class";
            case "double":
                return "double.class";
            case "boolean":
                return "boolean.class";
            case "char":
                return "char.class";
            default:
                return simple + ".class";
        }
    }

    /**
     * 汇总测试类需要的 import。
     * 只复用被测源码已经出现的 import，避免为某类框架或业务类型写死兜底包名。
     */
    private List<String> importsFor(MethodModel methodModel, List<TestScenario> scenarios) {
        Set<String> imports = new LinkedHashSet<>();
        String returnTypeImport = importForType(methodModel, methodModel.getReturnType());
        if (returnTypeImport != null) {
            imports.add(returnTypeImport);
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            String importName = importForType(methodModel, dependency.getFieldType());
            if (importName != null) {
                imports.add(importName);
            }
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            String importName = importForType(methodModel, parameter.getType());
            if (importName != null) {
                imports.add(importName);
            }
        }
        imports.add("org.junit.jupiter.api.Test");
        imports.add("org.junit.jupiter.api.BeforeEach");
        imports.add("org.mockito.Mock");
        imports.add("org.mockito.MockitoAnnotations");
        if (!methodModel.isStaticMethod()) {
            imports.add("org.mockito.InjectMocks");
        }
        if (isPrivateMethod(methodModel)) {
            imports.add("java.lang.reflect.InvocationTargetException");
            imports.add("java.lang.reflect.Method");
        }
        if (isOptionalReturn(methodModel)) {
            String valueTypeImport = importForType(methodModel, optionalValueType(methodModel));
            if (valueTypeImport != null) {
                imports.add(valueTypeImport);
            }
            imports.add("java.util.Optional");
        }
        if (usesList(methodModel)) {
            imports.add("java.util.List");
        }
        return new ArrayList<>(imports);
    }

    private void appendMockitoInitialization(StringBuilder code) {
        code.append("    @BeforeEach\n");
        code.append("    void initMockito() {\n");
        code.append("        MockitoAnnotations.initMocks(this);\n");
        code.append("    }\n");
    }

    /**
     * 根据简单类型查找源码中的 import。
     */
    private String importForType(MethodModel methodModel, String type) {
        String simpleType = simpleType(type);
        for (String importName : methodModel.getImports()) {
            if (importName.endsWith("." + simpleType)) {
                return importName;
            }
        }
        return null;
    }

    /**
     * 判断是否需要生成 throws Exception。
     * 被测方法声明异常或 private 反射调用需要让测试方法透传受检异常。
     */
    private boolean requiresThrowsException(MethodModel methodModel) {
        return !methodModel.getThrownExceptions().isEmpty()
                || isPrivateMethod(methodModel);
    }

    /**
     * 查找第一个包含实际调用表达式的依赖。
     * Optional 查询类方法通常只有一个仓储依赖，使用该依赖生成 when 与 verify。
     */
    private DependencyModel firstDependencyWithCall(MethodModel methodModel) {
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (!dependency.getCallExpressions().isEmpty()) {
                return dependency;
            }
        }
        return null;
    }

    /**
     * 判断方法返回值是否为 Optional。
     * Optional 返回值需要生成 Optional.of/empty 和 isPresent/isEmpty 断言。
     */
    private boolean isOptionalReturn(MethodModel methodModel) {
        return methodModel.getReturnType() != null && methodModel.getReturnType().startsWith("Optional<");
    }

    /**
     * 提取 Optional 中的值类型。
     * 正常场景需要创建该类型的对象并放入 Optional.of。
     */
    private String optionalValueType(MethodModel methodModel) {
        String returnType = methodModel.getReturnType();
        if (returnType == null || !returnType.startsWith("Optional<") || !returnType.endsWith(">")) {
            return "Object";
        }
        return returnType.substring("Optional<".length(), returnType.length() - 1).trim();
    }

    private DependencyModel methodParameterDependency(String parameterName, MethodModel methodModel) {
        if (methodModel == null) return null;
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (dependency.isMethodParameter() && dependency.getFieldName().equals(parameterName)) {
                return dependency;
            }
        }
        return null;
    }

    private String compileSafeValueExpression(String type) {
        String expression = dataGenerator.generateValueExpression(type);
        if (expression == null || expression.isBlank()) return "null";
        if (expression.startsWith("new ")) {
            return "mock(" + simpleType(type) + ".class)";
        }
        return expression;
    }

    /**
     * 提取 Java 类型的简单名称。
     * import 推断只需要简单类型名，泛型和包名前缀会在这里去掉。
     */
    private String simpleType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    /**
     * 判断生成代码是否需要导入 List。
     * 只有参数类型涉及集合时才追加导入，避免生成无用 import。
     */
    private boolean usesList(MethodModel methodModel) {
        for (ParameterModel parameter : methodModel.getParameters()) {
            String type = parameter.getType();
            if (type != null && (type.startsWith("List") || type.startsWith("Collection"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去除最外层括号。
     * 条件比较前先做轻量标准化，降低括号对简单解析规则的影响。
     */
    private String strip(String text) {
        String value = text.trim();
        if (value.startsWith("(") && value.endsWith(")")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
