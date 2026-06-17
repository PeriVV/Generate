package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
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
            content = looksLikeBackupMethod(methodModel)
                    ? backupDraftContent(methodModel)
                    : buildContent(methodModel, scenarios == null ? new ArrayList<>() : scenarios, mockStrategy);
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

        code.append("@ExtendWith(MockitoExtension.class)\n");
        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");

        for (DependencyModel dependency : methodModel.getDependencies()) {
            code.append("    @Mock\n");
            code.append("    private ").append(dependency.getFieldType()).append(" ").append(dependency.getFieldName()).append(";\n\n");
        }

        String subjectName = StringUtils.lowerCamel(methodModel.getClassName());
        code.append("    @InjectMocks\n");
        code.append("    private ").append(methodModel.getClassName()).append(" ").append(subjectName).append(";\n");

        for (TestScenario scenario : scenarios) {
            code.append("\n");
            appendTestMethod(code, methodModel, scenario, subjectName, mockStrategy);
        }

        if (looksLikeResolveTemplate(methodModel)) {
            appendResolveTemplateInvoker(code, methodModel, subjectName);
        }

        code.append("}\n");
        return code.toString();
    }

    /**
     * 为 BackupService#backup 生成可编辑草稿。
     * 该方法静态依赖和内部私有流程较多，MVP 阶段优先给出稳定草稿和 TODO，避免生成误导性的复杂 Mock 代码。
     */
    private String backupDraftContent(MethodModel methodModel) {
        StringBuilder code = new StringBuilder();
        if (methodModel.getPackageName() != null && !methodModel.getPackageName().isBlank()) {
            code.append("package ").append(methodModel.getPackageName()).append(";\n\n");
        }
        Set<String> imports = new LinkedHashSet<>();
        String requestImport = importForType(methodModel, "BackupRequest");
        String resultImport = importForType(methodModel, methodModel.getReturnType());
        String exceptionImport = importForType(methodModel, "DataSyncException");
        if (requestImport != null) {
            imports.add(requestImport);
        }
        if (resultImport != null) {
            imports.add(resultImport);
        }
        if (exceptionImport != null) {
            imports.add(exceptionImport);
        }
        imports.add("org.junit.jupiter.api.Test");
        for (String importName : imports) {
            code.append("import ").append(importName).append(";\n");
        }
        code.append("\nimport static org.junit.jupiter.api.Assertions.*;\n\n");
        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");
        code.append("    @Test\n");
        code.append("    void shouldBackupWhenRequestValid() {\n");
        code.append("        ").append(methodModel.getClassName()).append(" backupService = new ").append(methodModel.getClassName()).append("();\n\n");
        code.append("        // TODO: 根据业务补充 BackupRequest 的 dbConfig、backupMode、includeAttachments 等字段。\n");
        code.append("        // TODO: BackupRequest 无法确认存在无参构造，请根据构造函数补充。\n");
        code.append("        BackupRequest request = null;\n\n");
        code.append("        // TODO: JdbcHelper.connect 是静态方法，完整单测需要 mockStatic 或重构为可注入依赖。\n");
        code.append("        BackupResult result = backupService.backup(request);\n\n");
        code.append("        assertNotNull(result);\n");
        code.append("    }\n\n");
        code.append("    @Test\n");
        code.append("    void shouldThrowDataSyncExceptionWhenBackupFailed() {\n");
        code.append("        ").append(methodModel.getClassName()).append(" backupService = new ").append(methodModel.getClassName()).append("();\n\n");
        code.append("        // TODO: 构造会触发异常路径的 BackupRequest。\n");
        code.append("        // TODO: BackupRequest 无法确认存在无参构造，请根据构造函数补充。\n");
        code.append("        BackupRequest request = null;\n\n");
        code.append("        assertThrows(DataSyncException.class, () -> backupService.backup(request));\n");
        code.append("    }\n");
        code.append("}\n");
        return code.toString();
    }

    /**
     * 生成最小可编辑测试类草稿。
     * 当复杂方法或依赖推断导致常规生成失败时，仍提供可编辑的完整测试类，避免测试代码页空白。
     */
    private String fallbackContent(MethodModel methodModel) {
        StringBuilder code = new StringBuilder();
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
        for (String importName : imports) {
            code.append("import ").append(importName).append(";\n");
        }
        code.append("\nimport static org.junit.jupiter.api.Assertions.*;\n\n");
        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");
        code.append("    @Test\n");
        code.append("    void ").append(fallbackMethodName(methodModel)).append("() {\n");
        code.append("        // TODO: UnitMind 暂未能完整推断该方法的依赖和断言，请根据业务补充测试数据。\n");
        String subjectName = StringUtils.lowerCamel(methodModel.getClassName());
        code.append("        // TODO: ").append(methodModel.getClassName()).append(" 可能需要依赖注入，请根据构造函数或字段依赖补充初始化逻辑。\n");
        code.append("        ").append(methodModel.getClassName()).append(" ").append(subjectName)
                .append(" = new ").append(methodModel.getClassName()).append("();\n\n");
        for (ParameterModel parameter : methodModel.getParameters()) {
            String value = dataGenerator.generateValueExpression(parameter.getType());
            if ("null".equals(value)) {
                code.append("        // TODO: ").append(parameter.getType()).append(" 无法自动构造，请根据业务补充测试数据。\n");
            }
            code.append("        ").append(parameter.getType()).append(" ").append(parameter.getName())
                    .append(" = ").append(value).append(";\n");
        }
        if (!methodModel.getParameters().isEmpty()) {
            code.append("\n");
        }
        String invocation = subjectName + "." + methodModel.getMethodName() + "(" + fallbackArguments(methodModel) + ")";
        if ("void".equals(methodModel.getReturnType())) {
            code.append("        ").append(invocation).append(";\n");
        } else {
            code.append("        ").append(methodModel.getReturnType()).append(" result = ").append(invocation).append(";\n\n");
            code.append("        assertNotNull(result);\n");
        }
        code.append("    }\n");
        code.append("}\n");
        return code.toString();
    }

    /**
     * 生成兜底测试方法名。
     * 方法名保持英文，避免中文进入 Java 标识符。
     */
    private String fallbackMethodName(MethodModel methodModel) {
        return "should" + capitalize(methodModel.getMethodName()) + "WhenInputValid";
    }

    /**
     * 拼接兜底调用参数。
     */
    private String fallbackArguments(MethodModel methodModel) {
        List<String> args = new ArrayList<>();
        for (ParameterModel parameter : methodModel.getParameters()) {
            args.add(parameter.getName());
        }
        return String.join(", ", args);
    }

    /**
     * 首字母大写。
     */
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

        if (looksLikeServletFilter(methodModel)) {
            appendServletFilterTestMethod(code, methodModel, scenario, subjectName);
            code.append("    }\n");
            return;
        }

        if (looksLikeResolveTemplate(methodModel)) {
            appendResolveTemplateTestBody(code, methodModel, scenario);
            code.append("    }\n");
            return;
        }

        Map<String, String> values = valuesForScenario(methodModel, scenario);
        appendInputVariables(code, methodModel, values);

        if (isOptionalReturn(methodModel) && !methodModel.getDependencies().isEmpty()) {
            appendOptionalTestBody(code, methodModel, scenario, subjectName, values);
            code.append("    }\n");
            return;
        }

        List<String> args = argumentsFor(methodModel, values);
        String invocation = subjectName + "." + methodModel.getMethodName() + "(" + String.join(", ", args) + ")";

        if ("EXCEPTION".equals(scenario.getType())) {
            String exceptionType = exceptionTypeFor(methodModel, scenario);
            code.append("        assertThrows(").append(exceptionType).append(".class, () -> {\n");
            code.append("            ").append(invocation).append(";\n");
            code.append("        });\n\n");
            appendNeverVerifies(code, methodModel);
            code.append("    }\n");
            return;
        }

        if ("void".equals(methodModel.getReturnType())) {
            code.append("        ").append(invocation).append(";\n\n");
            appendNormalVerifies(code, methodModel);
        } else {
            String resultType = methodModel.getReturnType();
            code.append("        ").append(resultType).append(" result = ").append(invocation).append(";\n\n");
            code.append("        assertNotNull(result);\n");
            appendNormalVerifies(code, methodModel);
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
                applyCondition(values, methodModel, branch.getSafeCondition(), false);
            }
        } else if ("EXCEPTION".equals(scenario.getType()) && !scenario.getCoveredBranches().isEmpty()) {
            applyCondition(values, methodModel, scenario.getCoveredBranches().get(0), true);
        }

        return values;
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
        if (isMockParameter(parameter.getName(), methodModel)) {
            return parameter.getName();
        }
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
    private void appendInputVariables(StringBuilder code, MethodModel methodModel, Map<String, String> values) {
        boolean hasInputVariable = false;
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (isMockParameter(parameter.getName(), methodModel)) {
                continue;
            }
            code.append("        ").append(parameter.getType()).append(" ")
                    .append(parameter.getName()).append(" = ")
                    .append(values.get(parameter.getName())).append(";\n");
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
    private void appendOptionalTestBody(StringBuilder code, MethodModel methodModel, TestScenario scenario, String subjectName, Map<String, String> values) {
        DependencyModel dependency = firstDependencyWithCall(methodModel);
        String callExpression = dependency == null || dependency.getCallExpressions().isEmpty()
                ? null
                : dependency.getCallExpressions().get(0);

        if (scenario.getMethodNameEn().contains("Null")) {
            code.append("        // TODO: 根据源码逻辑补充期望行为，例如 assertThrows 或 assertTrue(result.isEmpty())\n");
            return;
        }

        String valueType = optionalValueType(methodModel);
        if ("NORMAL".equals(scenario.getType())) {
            code.append("        ").append(valueType).append(" connection = new ").append(valueType).append("();\n\n");
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

        String invocation = subjectName + "." + methodModel.getMethodName() + "(" + String.join(", ", argumentsFor(methodModel, values)) + ")";
        code.append("        ").append(methodModel.getReturnType()).append(" result = ").append(invocation).append(";\n\n");
        if ("NORMAL".equals(scenario.getType())) {
            code.append("        assertTrue(result.isPresent());\n");
            code.append("        assertSame(connection, result.get());\n");
        } else {
            code.append("        assertTrue(result.isEmpty());\n");
        }
        if (dependency != null && callExpression != null) {
            code.append("        verify(").append(dependency.getFieldName()).append(").").append(callExpression).append(";\n");
        }
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
    private void appendNormalVerifies(StringBuilder code, MethodModel methodModel) {
        for (DependencyModel dependency : methodModel.getDependencies()) {
            for (String callExpression : dependency.getCallExpressions()) {
                code.append("        verify(").append(dependency.getFieldName()).append(").")
                        .append(callExpression).append(";\n");
            }
        }
    }

    /**
     * 追加异常路径依赖未调用验证。
     * 保护分支抛出异常后不应继续调用外部依赖，因此使用 never 规则固定该行为。
     */
    private void appendNeverVerifies(StringBuilder code, MethodModel methodModel) {
        for (DependencyModel dependency : methodModel.getDependencies()) {
            for (String callExpression : dependency.getCallExpressions()) {
                code.append("        verify(").append(dependency.getFieldName()).append(", never()).")
                        .append(callExpression).append(";\n");
            }
        }
    }

    /**
     * 为 Servlet Filter 场景追加专用测试代码。
     * Header 读取和过滤链调用是该类方法的核心协作点，因此使用精确 when 和 verify。
     */
    private void appendServletFilterTestMethod(StringBuilder code, MethodModel methodModel, TestScenario scenario, String subjectName) {
        DependencyModel request = dependencyWithCall(methodModel, "getHeader(");
        DependencyModel filterChain = dependencyWithCall(methodModel, "doFilter(");
        String headerCall = firstCall(request, "getHeader(");
        String filterCall = firstCall(filterChain, "doFilter(");
        Map<String, String> values = valuesForScenario(methodModel, scenario);
        String invocation = subjectName + "." + methodModel.getMethodName() + "(" + String.join(", ", argumentsFor(methodModel, values)) + ")";

        if (headerCall != null) {
            String value = "BOUNDARY".equals(scenario.getType()) ? "null" : "\"demo-user\"";
            code.append("        when(").append(request.getFieldName()).append(".").append(headerCall)
                    .append(").thenReturn(").append(value).append(");\n");
        }

        if ("EXCEPTION".equals(scenario.getType()) && filterChain != null && filterCall != null) {
            code.append("        doThrow(new IOException(\"mock error\")).when(")
                    .append(filterChain.getFieldName()).append(").").append(filterCall).append(";\n\n");
            code.append("        assertThrows(IOException.class, () -> {\n");
            code.append("            ").append(invocation).append(";\n");
            code.append("        });\n\n");
        } else {
            code.append("\n");
            code.append("        ").append(invocation).append(";\n\n");
        }

        if (request != null && headerCall != null) {
            code.append("        verify(").append(request.getFieldName()).append(").").append(headerCall).append(";\n");
        }
        if (filterChain != null && filterCall != null) {
            code.append("        verify(").append(filterChain.getFieldName()).append(").").append(filterCall).append(";\n");
        }
    }

    /**
     * 为 DynamicSqlExecutor#resolveTemplate 追加专用测试主体。
     * 该方法的核心行为由 switch expression 和运行时数据源注册表共同决定，通用入参规则无法表达具体模板断言。
     */
    private void appendResolveTemplateTestBody(StringBuilder code, MethodModel methodModel, TestScenario scenario) {
        String methodName = scenario.getMethodNameEn();
        String expectedField = expectedTemplateField(methodModel, methodName);
        String kind = kindForResolveTemplateScenario(methodName);
        String sandbox = sandboxForResolveTemplateScenario(methodName);

        code.append("        String kind = ").append(kind).append(";\n");
        code.append("        boolean useSandbox = ").append(sandbox).append(";\n");

        if (methodName.contains("RuntimeDatasourceTemplateUnavailable")) {
            code.append("        when(runtimeRegistry.hasConfig(any(), any())).thenReturn(true);\n");
            code.append("        when(runtimeRegistry.getJdbcTemplate(any(), any())).thenReturn(null);\n\n");
            code.append("        assertThrows(IllegalStateException.class, () -> invokeResolveTemplate(kind, useSandbox));\n");
            return;
        }

        if (methodName.contains("RuntimeDatasourceNotConfigured")) {
            code.append("        when(runtimeRegistry.hasConfig(any(), any())).thenReturn(false);\n");
            code.append("        when(runtimeRegistry.getJdbcTemplate(any(), any())).thenReturn(null);\n\n");
            code.append("        assertThrows(IllegalStateException.class, () -> invokeResolveTemplate(kind, useSandbox));\n");
            return;
        }

        if (methodName.contains("KindUnsupported")) {
            code.append("\n");
            code.append("        assertThrows(IllegalArgumentException.class, () -> invokeResolveTemplate(kind, useSandbox));\n");
            return;
        }

        code.append("\n");
        code.append("        ").append(methodModel.getReturnType()).append(" result = invokeResolveTemplate(kind, useSandbox);\n\n");
        code.append("        assertSame(").append(expectedField).append(", result);\n");
    }

    /**
     * 追加 resolveTemplate 的反射调用辅助方法。
     * 目标项目中该方法通常是 private，测试草稿使用反射避免因为访问级别导致生成代码不可编译。
     */
    private void appendResolveTemplateInvoker(StringBuilder code, MethodModel methodModel, String subjectName) {
        code.append("\n");
        code.append("    private ").append(methodModel.getReturnType()).append(" invokeResolveTemplate(String kind, boolean useSandbox) throws Exception {\n");
        code.append("        java.lang.reflect.Method method = ").append(methodModel.getClassName())
                .append(".class.getDeclaredMethod(\"").append(methodModel.getMethodName())
                .append("\", String.class, boolean.class);\n");
        code.append("        method.setAccessible(true);\n");
        code.append("        try {\n");
        code.append("            return (").append(methodModel.getReturnType()).append(") method.invoke(")
                .append(subjectName).append(", kind, useSandbox);\n");
        code.append("        } catch (java.lang.reflect.InvocationTargetException ex) {\n");
        code.append("            Throwable cause = ex.getCause();\n");
        code.append("            if (cause instanceof RuntimeException) {\n");
        code.append("                throw (RuntimeException) cause;\n");
        code.append("            }\n");
        code.append("            if (cause instanceof Error) {\n");
        code.append("                throw (Error) cause;\n");
        code.append("            }\n");
        code.append("            throw ex;\n");
        code.append("        }\n");
        code.append("    }\n");
    }

    /**
     * 根据 resolveTemplate 场景选择期望返回的 JdbcTemplate 字段。
     * 优先使用模型中实际识别到的字段名，避免目标类字段命名略有差异时直接写死。
     */
    private String expectedTemplateField(MethodModel methodModel, String methodName) {
        if (methodName.contains("SandboxJdbcTemplateWhenMysql")) {
            return dependencyField(methodModel, "sandboxJdbcTemplate");
        }
        if (methodName.contains("DmJdbcTemplate")) {
            return dependencyField(methodModel, "dmJdbcTemplate");
        }
        if (methodName.contains("PostgresqlJdbcTemplate")) {
            return dependencyField(methodModel, "postgresJdbcTemplate");
        }
        if (methodName.contains("TdengineJdbcTemplate")) {
            return dependencyField(methodModel, "tdengineJdbcTemplate");
        }
        return dependencyField(methodModel, "mainJdbcTemplate");
    }

    /**
     * 为 resolveTemplate 场景选择 kind 输入。
     * 固定字面量能让预览代码直接对应 switch case，便于用户核对覆盖路径。
     */
    private String kindForResolveTemplateScenario(String methodName) {
        if (methodName.contains("DmJdbcTemplate")) {
            return "\"DM8\"";
        }
        if (methodName.contains("PostgresqlJdbcTemplate")) {
            return "\"POSTGRESQL\"";
        }
        if (methodName.contains("TdengineJdbcTemplate")) {
            return "\"TDENGINE\"";
        }
        if (methodName.contains("KindUnsupported")) {
            return "\"ORACLE\"";
        }
        if (methodName.contains("RuntimeDatasource")) {
            return "\"MYSQL:tenant_001\"";
        }
        return "\"MYSQL\"";
    }

    /**
     * 为 resolveTemplate 场景选择 useSandbox 输入。
     * 只有 MYSQL 沙箱场景需要 true，其余专用数据源场景默认验证主模板分支。
     */
    private String sandboxForResolveTemplateScenario(String methodName) {
        return methodName.contains("SandboxJdbcTemplateWhenMysql") ? "true" : "false";
    }

    /**
     * 查找依赖字段名。
     * 如果解析阶段没有识别到该字段，退回默认名称，使预览中仍能呈现用户需要补充的断言目标。
     */
    private String dependencyField(MethodModel methodModel, String defaultName) {
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (defaultName.equals(dependency.getFieldName())) {
                return dependency.getFieldName();
            }
        }
        return defaultName;
    }

    /**
     * 汇总测试类需要的 import。
     * Servlet 相关类型优先复用被测源码 import，无法找到时按 jakarta.servlet 作为默认兜底。
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
        imports.add("org.junit.jupiter.api.extension.ExtendWith");
        imports.add("org.mockito.InjectMocks");
        imports.add("org.mockito.Mock");
        imports.add("org.mockito.junit.jupiter.MockitoExtension");
        if (isOptionalReturn(methodModel)) {
            String valueTypeImport = importForType(methodModel, optionalValueType(methodModel));
            if (valueTypeImport != null) {
                imports.add(valueTypeImport);
            }
            imports.add("java.util.Optional");
        }
        if (requiresIOException(methodModel, scenarios)) {
            imports.add("java.io.IOException");
        }
        if (usesList(methodModel)) {
            imports.add("java.util.List");
        }
        return new ArrayList<>(imports);
    }

    /**
     * 根据简单类型查找源码中的 import。
     * 如果源码没有显式 import，但类型是常见 Servlet 类型，则使用 jakarta.servlet 默认包名。
     */
    private String importForType(MethodModel methodModel, String type) {
        String simpleType = simpleType(type);
        for (String importName : methodModel.getImports()) {
            if (importName.endsWith("." + simpleType)) {
                return importName;
            }
            if (importName.endsWith(".*") && servletWildcardMatches(importName, simpleType)) {
                return importName.substring(0, importName.length() - 2) + "." + simpleType;
            }
        }
        if ("HttpServletRequest".equals(simpleType) || "HttpServletResponse".equals(simpleType)) {
            return "jakarta.servlet.http." + simpleType;
        }
        if ("FilterChain".equals(simpleType) || "ServletRequest".equals(simpleType) || "ServletResponse".equals(simpleType)) {
            return "jakarta.servlet." + simpleType;
        }
        return null;
    }

    /**
     * 判断 Servlet 通配 import 是否覆盖目标类型。
     * 该逻辑用于沿用源码中的 javax 或 jakarta 选择，不强行切换 Servlet API 版本。
     */
    private boolean servletWildcardMatches(String importName, String simpleType) {
        if (("HttpServletRequest".equals(simpleType) || "HttpServletResponse".equals(simpleType))
                && (importName.equals("jakarta.servlet.http.*") || importName.equals("javax.servlet.http.*"))) {
            return true;
        }
        return ("FilterChain".equals(simpleType) || "ServletRequest".equals(simpleType) || "ServletResponse".equals(simpleType))
                && (importName.equals("jakarta.servlet.*") || importName.equals("javax.servlet.*"));
    }

    /**
     * 判断是否需要生成 throws Exception。
     * 被测方法声明异常或 Servlet Filter 专用场景都需要让测试方法透传受检异常。
     */
    private boolean requiresThrowsException(MethodModel methodModel) {
        return !methodModel.getThrownExceptions().isEmpty() || looksLikeServletFilter(methodModel) || looksLikeResolveTemplate(methodModel);
    }

    /**
     * 判断是否需要导入 IOException。
     * 过滤链异常场景会显式构造 IOException，因此必须补充 import。
     */
    private boolean requiresIOException(MethodModel methodModel, List<TestScenario> scenarios) {
        if (!looksLikeServletFilter(methodModel)) {
            return false;
        }
        for (TestScenario scenario : scenarios) {
            if ("EXCEPTION".equals(scenario.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前方法是否是 Servlet Filter 风格方法。
     * 只要同时读取 Header 并调用过滤链，就启用专用生成路径。
     */
    private boolean looksLikeServletFilter(MethodModel methodModel) {
        return dependencyWithCall(methodModel, "getHeader(") != null
                && dependencyWithCall(methodModel, "doFilter(") != null;
    }

    /**
     * 判断当前方法是否为动态数据源模板解析方法。
     * 该方法需要覆盖 switch expression 和运行时数据源分支，不能走通用弱断言路径。
     */
    private boolean looksLikeResolveTemplate(MethodModel methodModel) {
        return methodModel != null && "resolveTemplate".equals(methodModel.getMethodName());
    }

    /**
     * 判断当前方法是否为备份服务核心 backup 方法。
     * 该方法包含静态数据库连接和事务流程，优先生成可编辑草稿而不是误导性的复杂 Mock 代码。
     */
    private boolean looksLikeBackupMethod(MethodModel methodModel) {
        if (methodModel == null || !"backup".equals(methodModel.getMethodName())) {
            return false;
        }
        boolean hasBackupRequest = false;
        for (ParameterModel parameter : methodModel.getParameters()) {
            if ("BackupRequest".equals(simpleType(parameter.getType()))) {
                hasBackupRequest = true;
                break;
            }
        }
        String body = methodModel.getMethodBodyText() == null ? "" : methodModel.getMethodBodyText();
        return hasBackupRequest
                && body.contains("JdbcHelper.connect")
                && body.contains("TableProfileResolver.resolve")
                && body.contains("connection.commit")
                && body.contains("connection.rollback");
    }

    /**
     * 查找包含指定调用表达式前缀的依赖。
     * Servlet 场景需要分别定位 request 和 filterChain 变量。
     */
    private DependencyModel dependencyWithCall(MethodModel methodModel, String callPrefix) {
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (firstCall(dependency, callPrefix) != null) {
                return dependency;
            }
        }
        return null;
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

    /**
     * 查找依赖中的第一个匹配调用表达式。
     * 调用表达式保留实参，后续可以直接拼接到 when 或 verify。
     */
    private String firstCall(DependencyModel dependency, String callPrefix) {
        if (dependency == null) {
            return null;
        }
        for (String callExpression : dependency.getCallExpressions()) {
            if (callExpression.startsWith(callPrefix)) {
                return callExpression;
            }
        }
        return null;
    }

    /**
     * 判断指定参数是否来自 Mock 依赖。
     * Mock 参数在调用被测方法时直接传变量名，避免生成 new 接口或 null。
     */
    private boolean isMockParameter(String parameterName, MethodModel methodModel) {
        if (methodModel == null) {
            return false;
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (dependency.isMethodParameter() && dependency.getFieldName().equals(parameterName)) {
                return true;
            }
        }
        return false;
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
