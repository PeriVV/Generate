package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据被测方法中的外部依赖调用规划 Mockito 规则。
 * 该类不执行真实 Mock，只生成后续代码生成器和预览页可消费的策略描述。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class MockStrategyPlanner {
    /** 匹配 ClassName.staticMethod(...) 形式的静态方法调用。 */
    private static final Pattern STATIC_CALL = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\.([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");
    /** 匹配本类内部无作用域方法调用。 */
    private static final Pattern INTERNAL_CALL = Pattern.compile("(?<![\\.\\w])([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");
    /** 匹配由静态方法返回值创建的局部变量，例如 Resource resource = Factory.create(...). */
    private static final Pattern STATIC_LOCAL_ASSIGN = Pattern.compile("\\b([A-Z][A-Za-z0-9_<>?,\\s]*)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*=\\s*([A-Z][A-Za-z0-9_]*)\\.([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");
    /** 匹配局部对象的方法调用。 */
    private static final Pattern SCOPED_CALL = Pattern.compile("\\b([a-zA-Z_][A-Za-z0-9_]*)\\.([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");

    /**
     * 规划测试场景或 Mock 策略。
     * 根据传入模型生成后续代码生成器可消费的结构化结果。
     *
      * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
      *
     * @param methodModel 源码分析得到的被测方法模型
     * @param scenarios 已规划的测试场景列表
     * @return 规划得到的测试场景或 Mock 策略
     */
    public MockStrategy plan(MethodModel methodModel, List<TestScenario> scenarios) {
        MockStrategy strategy = new MockStrategy();
        if (methodModel == null) {
            return strategy;
        }

        List<String> mockFields = new ArrayList<>();
        List<String> verifyRules = new ArrayList<>();
        List<String> neverRules = new ArrayList<>();
        List<String> whenRules = new ArrayList<>();
        List<MockStrategyItem> strategyItems = new ArrayList<>();
        Set<String> itemKeys = new LinkedHashSet<>();

        for (DependencyModel dependency : methodModel.getDependencies()) {
            String fieldType = dependency.getFieldType();
            String fieldName = dependency.getFieldName();
            if (looksLikeDtoType(fieldType) || looksLikePathType(fieldType)) {
                boolean localMock = dependency.isMethodParameter()
                        && dependency.getCallExpressions() != null
                        && !dependency.getCallExpressions().isEmpty()
                        && !looksLikePathType(fieldType);
                addItem(strategyItems, itemKeys, new MockStrategyItem(localMock,
                        dependency.isMethodParameter() ? "方法参数数据对象" : "字段数据对象",
                        simpleType(fieldType),
                        "-",
                        fieldName,
                        localMock ? "localMock" : "构造测试对象/局部mock/@TempDir",
                        dtoRemark(fieldType)));
                continue;
            }
            mockFields.add(fieldType + " " + fieldName);
            addItem(strategyItems, itemKeys, new MockStrategyItem(true,
                    dependency.isMethodParameter() ? "方法参数依赖" : "字段依赖",
                    fieldType,
                    fieldName,
                    fieldName,
                    "mock",
                    dependency.isMethodParameter() ? "框架或接口型方法参数，需要作为 Mock 传入。" : "普通字段依赖，可生成 @Mock。"));
            for (String callExpression : dependency.getCallExpressions()) {
                // 发现调用只能证明协作者参与了实现，不能证明调用次数是业务契约，因此不自动生成 verify。
                String returnType = dependencyCallReturnType(methodModel, dependency, callExpression);
                if (looksLikeQuery(methodName(callExpression))
                        && isOptionalType(returnType) && hasEmptyOptionalScenario(scenarios)) {
                    whenRules.add("when(" + fieldName + "." + callExpression + ").thenReturn(Optional.empty())");
                }
            }
        }

        appendDtoParameterItems(methodModel, strategyItems, itemKeys);
        appendStaticItems(methodModel, strategyItems, itemKeys);
        appendInternalCallItems(methodModel, strategyItems, itemKeys);
        appendLocalObjectItems(methodModel, strategyItems, itemKeys);

        strategy.setMockFields(mockFields);
        strategy.setVerifyRules(verifyRules);
        strategy.setNeverRules(neverRules);
        strategy.setWhenRules(whenRules);
        strategy.setStrategyItems(strategyItems);
        return strategy;
    }

    /**
     * 将 DTO/Request/Result 等业务对象参数展示为“优先构造，必要时局部 mock”。
     * 这类参数不默认生成测试类字段级 @Mock，但构造复杂或 accessor 较多时允许在测试方法内部局部 mock。
     */
    private void appendDtoParameterItems(MethodModel methodModel, List<MockStrategyItem> items, Set<String> keys) {
        String body = methodModel.getMethodBodyText() == null ? "" : methodModel.getMethodBodyText();
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (!looksLikeDtoType(parameter.getType())) {
                continue;
            }
            boolean hasMethodCall = false;
            Matcher matcher = Pattern.compile("\\b" + Pattern.quote(parameter.getName()) + "\\.([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(").matcher(body);
            while (matcher.find()) {
                hasMethodCall = true;
                String method = matcher.group(1) + "()";
                addItem(items, keys, new MockStrategyItem(true,
                        "方法参数数据对象",
                        simpleType(parameter.getType()),
                        method,
                        parameter.getName() + "." + method,
                        "localMock",
                        dtoRemark(parameter.getType())));
            }
            if (!hasMethodCall) {
                addItem(items, keys, new MockStrategyItem(false,
                        "方法参数数据对象",
                        simpleType(parameter.getType()),
                        "-",
                        parameter.getName(),
                        "优先构造；必要时局部 mock",
                        dtoRemark(parameter.getType())));
            }
        }
    }

    /**
     * 识别 ClassName.staticMethod(...) 形式的静态方法调用。
     * 静态依赖通常不能用 @Mock 字段表达，需要 mockStatic 或人工 TODO。
     */
    private void appendStaticItems(MethodModel methodModel, List<MockStrategyItem> items, Set<String> keys) {
        Matcher matcher = STATIC_CALL.matcher(methodModel.getMethodBodyText() == null ? "" : methodModel.getMethodBodyText());
        while (matcher.find()) {
            String className = matcher.group(1);
            String methodName = matcher.group(2) + "(...)";
            if (isIgnoredStaticOwner(className)) {
                continue;
            }
            addItem(items, keys, new MockStrategyItem(false,
                    "静态方法依赖",
                    className,
                    methodName,
                    className + "." + methodName,
                    "默认真实调用；项目支持时可手动启用 mockStatic",
                    "静态 Mock 默认关闭。仅当静态调用存在不可控副作用，且项目已启用 Mockito inline MockMaker 时才使用。"));
        }
    }

    /**
     * 识别当前类内部方法调用。
     * private 方法无法直接 stub，展示出来是为了让用户理解复杂流程中哪些逻辑仍会真实执行。
     */
    private void appendInternalCallItems(MethodModel methodModel, List<MockStrategyItem> items, Set<String> keys) {
        Set<String> declaredMethods = new LinkedHashSet<>(methodModel.getDeclaredMethodNames());
        Matcher matcher = INTERNAL_CALL.matcher(methodModel.getMethodBodyText() == null ? "" : methodModel.getMethodBodyText());
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!declaredMethods.contains(name) || name.equals(methodModel.getMethodName())) {
                continue;
            }
            addItem(items, keys, new MockStrategyItem(false,
                    "本类内部调用",
                    methodModel.getClassName(),
                    name + "(...)",
                    "this." + name + "(...)",
                    "spy 或不处理",
                    "如果是 private 方法，Mockito 不能直接 stub；建议作为真实逻辑执行，或后续重构为可注入依赖。"));
        }
    }

    /**
     * 识别来自静态方法返回值的局部对象依赖及其调用。
     * 例如 Resource resource = Factory.create(...) 后续的方法调用需要在 Mock 策略中展示。
     */
    private void appendLocalObjectItems(MethodModel methodModel, List<MockStrategyItem> items, Set<String> keys) {
        String body = methodModel.getMethodBodyText() == null ? "" : methodModel.getMethodBodyText();
        Map<String, String> localTypes = new LinkedHashMap<>();
        Matcher assignMatcher = STATIC_LOCAL_ASSIGN.matcher(body);
        while (assignMatcher.find()) {
            localTypes.put(assignMatcher.group(2), simpleType(assignMatcher.group(1)));
        }
        Matcher callMatcher = SCOPED_CALL.matcher(body);
        while (callMatcher.find()) {
            String variable = callMatcher.group(1);
            if (!localTypes.containsKey(variable)) {
                continue;
            }
            String method = callMatcher.group(2) + "(...)";
            addItem(items, keys, new MockStrategyItem(true,
                    "局部对象依赖",
                    localTypes.get(variable),
                    method,
                    variable + "." + method,
                    localCallStrategy(callMatcher.group(2)),
                    variable + " 来自静态方法返回值，测试中通常需要配合静态方法 Mock 返回该对象。"));
        }
    }

    /**
     * 添加去重后的统一表格行。
     */
    private void addItem(List<MockStrategyItem> items, Set<String> keys, MockStrategyItem item) {
        String key = item.getDependencyType() + "|" + item.getObjectName() + "|" + item.getMethodName() + "|" + item.getSource();
        if (keys.add(key)) {
            items.add(item);
        }
    }

    /**
     * 判断依赖方法是否像查询方法。
     * 查询方法通常需要 when 返回值，因此按命名前缀生成可选桩规则。
     */
    private boolean looksLikeQuery(String methodName) {
        return methodName != null && (methodName.startsWith("select")
                || methodName.startsWith("find")
                || methodName.startsWith("get")
                || methodName.startsWith("query"));
    }

    /**
     * 判断类型是否为业务输入/输出对象。
     */
    private boolean looksLikeDtoType(String type) {
        String simple = simpleType(type);
        if ("HttpServletRequest".equals(simple) || "HttpServletResponse".equals(simple)) {
            return false;
        }
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
                || simple.endsWith("Result")
                || simple.endsWith("Request")
                || simple.endsWith("Entity")
                || simple.endsWith("DTO");
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
     * 根据 DTO 类型生成备注。
     */
    private String dtoRemark(String type) {
        String simple = simpleType(type);
        if (simple.endsWith("Request")) {
            return "Request DTO 不默认生成字段级 @Mock；无法构造或 accessor 较多时可在测试方法内部局部 mock。";
        }
        if (looksLikePathType(type)) {
            return "路径/文件对象不默认生成字段级 @Mock；优先使用 @TempDir 和真实临时目录。";
        }
        return "业务数据对象不默认生成字段级 @Mock；构造复杂时可在测试方法内部局部 mock。";
    }

    /**
     * 根据局部对象方法名推断策略。
     */
    private String localCallStrategy(String methodName) {
        if ("commit".equals(methodName) || "rollback".equals(methodName)) {
            return "verify";
        }
        if (methodName.startsWith("set")) {
            return "mock";
        }
        return "mock / verify";
    }

    /**
     * 排除明显不是业务静态依赖的类型拥有者。
     */
    private boolean isIgnoredStaticOwner(String className) {
        return "List".equals(className)
                || "Map".equals(className)
                || "Set".equals(className)
                || "Optional".equals(className)
                || "Objects".equals(className)
                || "String".equals(className)
                || "Math".equals(className)
                || className.toLowerCase(Locale.ROOT).endsWith("builder");
    }

    /**
     * 提取类型简单名。
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
        value = value.replace("final ", "").trim();
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    /**
     * 从完整调用表达式中提取方法名。
     * Mock 策略需要按方法名前缀判断是否应生成 when 规则。
     */
    private String methodName(String callExpression) {
        if (callExpression == null) {
            return "";
        }
        int index = callExpression.indexOf('(');
        return index < 0 ? callExpression : callExpression.substring(0, index);
    }

    /** 根据调用上下文解析依赖方法自身的返回类型，禁止借用被测方法返回类型猜测桩值。 */
    private String dependencyCallReturnType(MethodModel methodModel,
                                            DependencyModel dependency,
                                            String callExpression) {
        String expectedExpression = compact(dependency.getFieldName() + "." + callExpression);
        for (CallContextModel context : methodModel.getCallContexts()) {
            if (context == null || context.getReturnType() == null || context.getReturnType().isBlank()) {
                continue;
            }
            String expression = compact(context.getExpression());
            boolean sameExpression = expression.equals(expectedExpression);
            boolean sameOwnerAndMethod = context.getOwner() != null
                    && context.getOwner().startsWith(dependency.getFieldName() + ":")
                    && methodName(callExpression).equals(context.getMethodName());
            if (sameExpression || sameOwnerAndMethod) {
                return context.getReturnType();
            }
        }

        // 直接 return dependency.call(...) 时，Java 类型系统保证调用结果可赋给被测方法返回类型。
        String body = compact(methodModel.getMethodBodyText());
        if (body.contains("return" + expectedExpression + ";")) {
            return methodModel.getReturnType();
        }
        return "";
    }

    private boolean hasEmptyOptionalScenario(List<TestScenario> scenarios) {
        if (scenarios == null) return false;
        for (TestScenario scenario : scenarios) {
            if (scenario != null && scenario.getExpectedAssertions() != null
                    && scenario.getExpectedAssertions().contains("returned value equals Optional.empty()")) {
                return true;
            }
        }
        return false;
    }

    private boolean isOptionalType(String type) {
        return type != null && (type.startsWith("Optional<") || "Optional".equals(type));
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

}
