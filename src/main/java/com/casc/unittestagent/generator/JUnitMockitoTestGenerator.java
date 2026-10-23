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

import java.math.BigDecimal;
import java.math.BigInteger;
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
    private ClasspathTypeProbe ownedTypeProbe;

    /** 注入真实 classpath 的可实例化探针，抑制 new 第三方接口/抽象类型。 */
    public void setTypeInstantiabilityProbe(java.util.function.Predicate<String> probe) {
        if (ownedTypeProbe != null && ownedTypeProbe != probe) ownedTypeProbe.close();
        ownedTypeProbe = probe instanceof ClasspathTypeProbe typed ? typed : null;
        dataGenerator.setTypeInstantiabilityProbe(probe);
    }

    /**
     * 项目测试 classpath 是否可用 Mockito。不可用时（joda-time/gson 等无 mockito 仓库）
     * 生成代码不得引用 org.mockito——编译期被插件 classpath 掩盖、执行期 NoClassDefFoundError
     * 整类崩溃。参数兜底退 null 字面量，零 mock 方法直构被测对象。
     */
    public void setMockitoAvailable(boolean available) {
        mockitoAvailable = available;
        dataGenerator.setMockitoAvailable(available);
    }

    private boolean mockitoAvailable = true;

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
        try {
            GeneratedTestFile file = new GeneratedTestFile();
            String testClassName = methodModel.getClassName() + "Test";
            file.setPackageName(methodModel.getPackageName());
            file.setClassName(testClassName);
            file.setFileName(testClassName + ".java");
            String content;
            try {
                content = buildContent(methodModel, scenarios == null ? new ArrayList<>() : scenarios, mockStrategy);
            } catch (Exception ex) {
                if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                LOG.warn("UnitMind: generate test code failed", ex);
                content = fallbackContent(methodModel);
            }
            if (content == null || content.trim().isEmpty()) {
                content = fallbackContent(methodModel);
            }
            file.setContent(content);
            return file;
        } finally {
            if (ownedTypeProbe != null) ownedTypeProbe.close();
        }
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

        // import 区在字段区之前：先算好是否需要 Mockito 样板，避免无 mockito 仓库
        // 引用 org.mockito（编译期被掩盖、执行期 NoClassDefFoundError 整类崩溃）。
        boolean hasFieldMocks = methodModel.getDependencies().stream()
                .anyMatch(dependency -> mockitoAvailable && shouldGenerateFieldMock(dependency, mockStrategy));
        boolean constructSubjectDirectly = !methodModel.isStaticMethod() && !hasFieldMocks
                && (!mockitoAvailable || dataGenerator.hasInstantiabilityProbe()
                        && dataGenerator.canInstantiate(methodModel.getClassName()));
        // 接口/抽象被测主体：@InjectMocks 必抛 "Cannot instantiate ... interface/abstract"
        // （包装成 Failed to release mocks 整类崩溃），直构也不可行。生成带说明的空测试类，
        // 走诚实的"0 测试被发现"失败路径；具体实现类/匿名实现交给 LLM 路径。
        if (!methodModel.isStaticMethod()
                && (methodModel.isOwnerInterface() || methodModel.isOwnerAbstract())) {
            return untestableSubjectContent(methodModel);
        }
        // @InjectMocks 的发出条件必须与 import 计算完全一致（此前有字段 mock 时也发
        // @InjectMocks 但 import 只按无字段分支算，jackson 实测找不到符号）。
        boolean usesInjectMocks = !methodModel.isStaticMethod() && !constructSubjectDirectly;
        for (String importName : importsFor(methodModel, scenarios, hasFieldMocks, usesInjectMocks)) {
            code.append("import ").append(importName).append(";\n");
        }
        code.append("\n");
        code.append("import static org.junit.jupiter.api.Assertions.*;\n");
        if (mockitoAvailable) {
            // 无 mockito 仓库（joda-time/gson 等）引用 org.mockito 是硬编译错误，
            // 该 import 必须与 mockitoAvailable 同门（静态导入未使用时 JVM 惰性解析，
            // 但沙箱 javac 对缺失包的 import 直接报错）。
            code.append("import static org.mockito.Mockito.*;\n");
        }
        code.append("\n");

        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");

        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (mockitoAvailable && shouldGenerateFieldMock(dependency, mockStrategy)) {
                code.append("    @Mock\n");
                code.append("    private ").append(dependency.getFieldType()).append(" ").append(dependency.getFieldName()).append(";\n\n");
            } else if (isUnmockableValueType(dependency.getFieldType())) {
                // String/包装类型/Class 等 Mockito 禁止 mock 的值依赖：@Mock 会让
                // initMocks 抛 "Cannot mock/spy class java.lang.String"（被包装成
                // "Failed to release mocks"）并崩溃整个测试类。改为普通字段+字面量，
                // @InjectMocks 注入 null 时仅该用例 NPE（可被闭环修复），类可运行。
                code.append("    private ").append(dependency.getFieldType()).append(" ")
                        .append(dependency.getFieldName()).append(" = ")
                        .append(dataGenerator.generateValidValueExpression(dependency.getFieldType()))
                        .append(";\n\n");
            }
        }

        String subjectName = StringUtils.lowerCamel(methodModel.getClassName());
        if (!methodModel.isStaticMethod()) {
            if (constructSubjectDirectly) {
                code.append("    private ").append(methodModel.getClassName()).append(" ").append(subjectName)
                        .append(dataGenerator.canInstantiate(methodModel.getClassName())
                                ? " = new " + methodModel.getClassName() + "()" : "")
                        .append(";\n\n");
            } else {
                code.append("    @InjectMocks\n");
                code.append("    private ").append(methodModel.getClassName()).append(" ").append(subjectName).append(";\n\n");
            }
        }
        if (hasFieldMocks || (!methodModel.isStaticMethod() && !constructSubjectDirectly)) {
            appendMockitoInitialization(code);
        }

        for (TestScenario scenario : scenarios) {
            // _REASON 结尾的占位场景（"未生成异常/边界场景"）只用于场景页说明根因，
            // 不应生成为真实测试——其占位输入是普通随机值，执行结果只会制造
            // 误导性的 boundaryScenarioNotGeneratedForXxx 失败噪音。
            if (scenario.getType() != null && scenario.getType().endsWith("_REASON")) {
                continue;
            }
            code.append("\n");
            appendTestMethod(code, methodModel, scenario, subjectName, mockStrategy);
        }

        if (isPrivateMethod(methodModel)) {
            appendPrivateMethodInvoker(code, methodModel, subjectName);
        }

        code.append("}\n");
        return code.toString();
    }

    /** 接口/抽象主体无法实例化：输出带原因说明的空测试类（编译通过、0 测试被发现）。 */
    private String untestableSubjectContent(MethodModel methodModel) {
        StringBuilder code = new StringBuilder();
        if (methodModel.getPackageName() != null && !methodModel.getPackageName().isBlank()) {
            code.append("package ").append(methodModel.getPackageName()).append(";\n\n");
        }
        code.append("import org.junit.jupiter.api.Test;\n\n");
        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");
        code.append("    // UnitMind：被测主体 ")
                .append(methodModel.isOwnerInterface() ? "是接口" : "是抽象类")
                .append("（").append(methodModel.getClassName()).append("），无法实例化——\n")
                .append("    // Mockito 的 InjectMocks 构造注入会抛 Cannot instantiate（整类崩溃），直构也不可行。\n")
                .append("    // 请选择具体实现类中的方法，或使用大模型路径生成匿名/替身实现。\n");
        code.append("}\n");
        return code.toString();
    }

    private boolean shouldGenerateFieldMock(DependencyModel dependency, MockStrategy mockStrategy) {
        if (dependency == null) {
            return false;
        }
        // Mockito 在任何 mock maker 下都禁止 mock String/包装类型/Class——即使策略
        // 选中也必须拦下，否则 initMocks 崩溃整个测试类（mybatis-3 实测）。
        // 数组类型同样不可 mock（mock(char[].class) 运行期抛 MockitoException）。
        if (isUnmockableValueType(dependency.getFieldType())
                || (dependency.getFieldType() != null && dependency.getFieldType().endsWith("[]"))) {
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

    /** Mockito 禁止 mock 的值类型：String/包装类型/Class/Object 与基本类型。 */
    static boolean isUnmockableValueType(String type) {
        String simple = type == null ? "" : type.replaceAll("<.*>", "").strip();
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        return switch (simple) {
            case "String", "CharSequence", "Integer", "Long", "Short", "Byte", "Character",
                    "Double", "Float", "Boolean", "Class", "Object",
                    "int", "long", "short", "byte", "char", "double", "float", "boolean" -> true;
            default -> false;
        };
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
            if (mockitoAvailable) {
                imports.add("org.mockito.InjectMocks");
                imports.add("org.mockito.Mock");
                imports.add("org.mockito.MockitoAnnotations");
            }
        }
        for (String importName : imports) {
            code.append("import ").append(importName).append(";\n");
        }
        code.append("\nimport static org.junit.jupiter.api.Assertions.*;\n");
        if (mockitoAvailable) {
            code.append("import static org.mockito.Mockito.*;\n");
        }
        code.append("\n");
        code.append("class ").append(methodModel.getClassName()).append("Test {\n\n");
        if (!methodModel.isStaticMethod() && mockitoAvailable) {
            for (DependencyModel dependency : methodModel.getDependencies()) {
                if (dependency.isMethodParameter() || looksLikeDtoType(dependency.getFieldType())
                        || looksLikePathType(dependency.getFieldType())
                        || isUnmockableValueType(dependency.getFieldType())) continue;
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
            code.append("        ").append(declarationTypeFor(methodModel.getReturnType(), "value"))
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

        if (isDirectOptionalDelegation(methodModel) && isEvidenceBackedOptionalScenario(scenario)) {
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
            code.append("        ").append(declarationTypeFor(resultType, "value"))
                    .append(" result = assertDoesNotThrow(() -> ")
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

        if ("NORMAL".equals(scenario.getType()) || "BOUNDARY".equals(scenario.getType())) {
            // Boundary values still have to enter the intended non-exception path. In particular,
            // values for related parameters cannot be chosen independently (start/end, min/max...).
            for (BranchModel branch : methodModel.getBranches()) {
                if (!"throw".equals(branch.getAction())) continue;
                applyCondition(values, methodModel, branch.getSafeCondition());
            }
            // A later relation (start < end) may choose the configured minimum for start and undo
            // an earlier scalar guard (start >= 0). Re-apply non-relational guards after relations.
            for (BranchModel branch : methodModel.getBranches()) {
                if (!"throw".equals(branch.getAction())
                        || hasDirectParameterRelation(methodModel, branch.getSafeCondition())) continue;
                applyCondition(values, methodModel, branch.getSafeCondition());
            }
        } else if ("EXCEPTION".equals(scenario.getType()) && !scenario.getCoveredBranches().isEmpty()) {
            BranchModel targetBranch = branchForScenario(methodModel, scenario);
            if (targetBranch != null && targetBranch.getPreconditions() != null) {
                for (String precondition : targetBranch.getPreconditions()) {
                    applyCondition(values, methodModel, precondition);
                }
            }
            applyCondition(values, methodModel, scenario.getCoveredBranches().get(0));
        }

        if (!"EXCEPTION".equals(scenario.getType())) {
            // 仅正常/边界场景对齐；EXCEPTION 场景的条件求解定向值（off=-1、start>end 等）
            // 不能被覆盖——上一轮修复建立的严格不等式异常路径契约依赖它。
            alignArrayOffsetLength(values, methodModel);
        }
        alignBooleanGuardDirection(values, methodModel, scenario);
        return values;
    }

    /**
     * 数组 + off/len 参数对齐：数组解析型方法（parseInt(ch, off, len)）的偏移与
     * 长度独立取值会越界。同方法存在 T[] 参数与名为 off/offset/index 或 len/length
     * 的 int 参数时，钳 off=0、len=4（与数组字面量长度一致）。
     */
    private void alignArrayOffsetLength(Map<String, String> values, MethodModel methodModel) {
        boolean hasArray = methodModel.getParameters().stream()
                .anyMatch(parameter -> parameter.getType() != null && parameter.getType().endsWith("[]"));
        if (!hasArray) return;
        for (ParameterModel parameter : methodModel.getParameters()) {
            String name = parameter.getName() == null ? "" : parameter.getName().toLowerCase();
            if (!"int".equals(simpleType(parameter.getType()))) continue;
            if (name.equals("off") || name.equals("offset") || name.equals("index")
                    || name.equals("start") || name.equals("from")) {
                values.put(parameter.getName(), "0");
            } else if (name.equals("len") || name.equals("length") || name.equals("count")
                    || name.equals("end") || name.equals("to")) {
                values.put(parameter.getName(), "4");
            }
        }
    }

    /**
     * 布尔守卫方向：applyCondition 文法不含裸布尔/取反条件，boolean 取值与场景
     * 目标方向脱钩（抛硬币）。场景覆盖分支明确写 param == true / param == false /
     * !param 时直接取该方向。
     */
    private void alignBooleanGuardDirection(Map<String, String> values,
                                            MethodModel methodModel,
                                            TestScenario scenario) {
        if (scenario == null || scenario.getCoveredBranches() == null) return;
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (!"boolean".equals(simpleType(parameter.getType()))) continue;
            for (String branch : scenario.getCoveredBranches()) {
                String compact = branch == null ? "" : branch.replaceAll("\\s+", "");
                if (compact.equals(parameter.getName() + "==true")) {
                    values.put(parameter.getName(), "true");
                    break;
                }
                if (compact.equals(parameter.getName() + "==false")
                        || compact.equals("!" + parameter.getName())) {
                    values.put(parameter.getName(), "false");
                    break;
                }
            }
        }
    }

    private boolean hasDirectParameterRelation(MethodModel methodModel, String condition) {
        if (condition == null || condition.isBlank()) return false;
        for (ParameterModel left : methodModel.getParameters()) {
            for (ParameterModel right : methodModel.getParameters()) {
                if (left == right) continue;
                String pattern = "(?s).*\\b" + java.util.regex.Pattern.quote(left.getName())
                        + "\\b\\s*(?:>=|<=|==|!=|>|<)\\s*\\b"
                        + java.util.regex.Pattern.quote(right.getName()) + "\\b.*";
                if (condition.matches(pattern)) return true;
            }
        }
        return false;
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
        // 数组参数优先取字面量：分析器预填充的 normalValues 对数组是 null，
        // 被测方法首语句解引用即 NPE（commons-codec/jackson 实测全场 0 通过）。
        if (parameter.getType() != null && parameter.getType().endsWith("[]")
                && dataGenerator.arrayLiteralAvailable(parameter.getType())) {
            return dataGenerator.generateValidValueExpression(parameter.getType());
        }
        if ("BOUNDARY".equals(scenario.getType()) && parameter.getBoundaryValues() != null && !parameter.getBoundaryValues().isEmpty()) {
            return parameter.getBoundaryValues().get(0);
        }
        if (parameter.getNormalValues() != null && !parameter.getNormalValues().isEmpty()) {
            return parameter.getNormalValues().get(0);
        }
        // 正常/边界场景兜底取值钳到非负区间（负值易随机命中 count<0 类守卫）；
        // 异常场景保留全域取值以触发守卫分支。
        return "EXCEPTION".equals(scenario.getType())
                ? dataGenerator.generateValueExpression(parameter.getType())
                : dataGenerator.generateValidValueExpression(parameter.getType());
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
            if (dependency != null && mockitoAvailable && shouldGenerateFieldMock(dependency, mockStrategy)) {
                continue;
            }
            String expression = values.get(parameter.getName());
            expression = safeValueExpression(methodModel, parameter, expression);
            MockStrategyItem selected = strategyItem(dependency, mockStrategy);
            boolean arrayTyped = parameter.getType() != null && parameter.getType().endsWith("[]");
            if (mockitoAvailable && !arrayTyped && dependency != null && selected != null && selected.isGenerate()
                    && isLocalMockStrategy(selected.getMockitoStrategy())) {
                expression = "mock(" + simpleType(parameter.getType()) + ".class)";
            } else if (mockitoAvailable && !arrayTyped && dependency != null && "null".equals(expression)
                    && dependency.getCallExpressions() != null && !dependency.getCallExpressions().isEmpty()) {
                expression = "mock(" + simpleType(parameter.getType()) + ".class)";
            }
            code.append("        ").append(declarationTypeFor(parameter.getType(), expression)).append(" ")
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
        DependencyModel dependency = directOptionalDependencyWithCall(methodModel);
        if (dependency != null && !dependency.isMethodParameter()
                && !shouldGenerateFieldMock(dependency, mockStrategy)) {
            dependency = null;
        }
        String callExpression = dependency == null || dependency.getCallExpressions().isEmpty()
                ? null
                : directOptionalCallExpression(methodModel, dependency);

        String valueType = optionalValueType(methodModel);
        if ("NORMAL".equals(scenario.getType())) {
            code.append("        ").append(declarationTypeFor(valueType, "value"))
                    .append(" expectedValue = ")
                    .append(optionalValueExpression(valueType)).append(";\n\n");
            if (mockitoAvailable && dependency != null && callExpression != null) {
                code.append("        when(").append(dependency.getFieldName()).append(".").append(callExpression)
                        .append(").thenReturn(Optional.of(expectedValue));\n\n");
            }
        } else {
            if (mockitoAvailable && dependency != null && callExpression != null) {
                code.append("        when(").append(dependency.getFieldName()).append(".").append(callExpression)
                        .append(").thenReturn(Optional.empty());\n\n");
            }
        }

        String invocation = invocationExpression(methodModel, subjectName, String.join(", ", argumentsFor(methodModel, values)));
        code.append("        ").append(declarationTypeFor(methodModel.getReturnType(), "value"))
                .append(" result = ").append(invocation).append(";\n\n");
        if ("NORMAL".equals(scenario.getType())) {
            code.append("        assertTrue(result.isPresent());\n");
            code.append("        assertSame(expectedValue, result.get());\n");
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
    private boolean applyCondition(Map<String, String> values, MethodModel methodModel, String condition) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        String normalized = strip(condition);

        List<String> conjunction = splitTopLevel(normalized, "&&");
        if (conjunction.size() > 1) {
            boolean applied = true;
            for (String part : conjunction) {
                applied &= applyCondition(values, methodModel, part);
            }
            return applied;
        }
        List<String> alternatives = splitTopLevel(normalized, "||");
        if (alternatives.size() > 1) {
            for (String alternative : alternatives) {
                Map<String, String> candidate = new LinkedHashMap<>(values);
                if (applyCondition(candidate, methodModel, alternative)) {
                    values.clear();
                    values.putAll(candidate);
                    return true;
                }
            }
            return false;
        }

        String parameter = nullComparedParameter(methodModel, normalized, "==");
        if (parameter != null) {
            values.put(parameter, "null");
            return true;
        }
        parameter = nullComparedParameter(methodModel, normalized, "!=");
        if (parameter != null) {
            values.put(parameter, nonNullValue(methodModel, parameter));
            return true;
        }

        for (String operator : new String[]{">=", "<=", "==", "!=", ">", "<"}) {
            int index = normalized.indexOf(operator);
            if (index < 0) {
                continue;
            }
            String left = normalized.substring(0, index).trim();
            String right = normalized.substring(index + operator.length()).trim();
            if (isParameter(methodModel, left) && isParameter(methodModel, right)) {
                applyRelation(values, methodModel, left, right, operator);
                return true;
            } else if (isParameter(methodModel, left)) {
                String satisfying = satisfyingLiteral(left, right, operator, methodModel);
                if (satisfying != null) {
                    values.put(left, satisfying);
                    return true;
                }
            } else if (isParameter(methodModel, right)) {
                String satisfying = satisfyingLiteral(right, left, reverse(operator), methodModel);
                if (satisfying != null) {
                    values.put(right, satisfying);
                    return true;
                }
            }
            return false;
        }
        return false;
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
        if (">=".equals(operator) || ">".equals(operator) || "!=".equals(operator)) {
            values.put(left, high);
            values.put(right, low);
        } else if ("<=".equals(operator) || "<".equals(operator)) {
            values.put(left, low);
            values.put(right, high);
        } else {
            values.put(left, low);
            values.put(right, low);
        }
    }

    /**
     * 根据比较表达式生成真正满足条件的字面量。
     * 严格不等式必须跨过边界，例如 `value < 0` 使用 -1，而不是错误地使用 0。
     */
    private String satisfyingLiteral(String parameterName, String literal, String operator, MethodModel methodModel) {
        ParameterModel parameter = parameter(methodModel, parameterName);
        if (parameter == null || literal == null || literal.isBlank()) {
            return null;
        }
        String type = simpleType(parameter.getType());
        if (isIntegralType(type)) {
            try {
                String raw = literal.replace("_", "").replaceAll("[lL]$", "");
                BigInteger value = new BigInteger(raw);
                if (">".equals(operator)) value = value.add(BigInteger.ONE);
                if ("<".equals(operator)) value = value.subtract(BigInteger.ONE);
                if ("!=".equals(operator)) value = value.add(BigInteger.ONE);
                return value + (("long".equals(type) || "Long".equals(type)) ? "L" : "");
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (isFloatingType(type)) {
            try {
                String raw = literal.replace("_", "").replaceAll("[fFdD]$", "");
                BigDecimal value = new BigDecimal(raw);
                if (">".equals(operator)) value = value.add(BigDecimal.ONE);
                if ("<".equals(operator)) value = value.subtract(BigDecimal.ONE);
                if ("!=".equals(operator)) value = value.add(BigDecimal.ONE);
                String suffix = ("float".equals(type) || "Float".equals(type)) ? "f" : "";
                return value.stripTrailingZeros().toPlainString() + suffix;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (("char".equals(type) || "Character".equals(type)) && literal.matches("'[^']'")) {
            char value = literal.charAt(1);
            if (">".equals(operator) || "!=".equals(operator)) value++;
            if ("<".equals(operator)) value--;
            return "'" + value + "'";
        }
        if ("boolean".equals(type) || "Boolean".equals(type)) {
            if ("==".equals(operator)) return literal;
            if ("!=".equals(operator)) return "true".equals(literal) ? "false" : "true";
        }
        if ("String".equals(type)) {
            if ("==".equals(operator)) return literal;
            if ("!=".equals(operator)) return "\"__different_value__\"";
        }
        return "==".equals(operator) ? literal : null;
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

    private String nullComparedParameter(MethodModel methodModel, String condition, String operator) {
        int index = condition.indexOf(operator);
        if (index < 0) return null;
        String left = strip(condition.substring(0, index));
        String right = strip(condition.substring(index + operator.length()));
        if ("null".equals(right) && isParameter(methodModel, left)) return left;
        if ("null".equals(left) && isParameter(methodModel, right)) return right;
        return null;
    }

    /** 按顶层逻辑运算符拆分条件，不破坏括号内的子表达式。 */
    private List<String> splitTopLevel(String condition, String operator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index <= condition.length() - operator.length(); index++) {
            char ch = condition.charAt(index);
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth == 0 && condition.startsWith(operator, index)) {
                parts.add(condition.substring(start, index).trim());
                start = index + operator.length();
                index += operator.length() - 1;
            }
        }
        if (start > 0) parts.add(condition.substring(start).trim());
        if (parts.isEmpty()) parts.add(condition);
        return parts;
    }

    private String reverse(String operator) {
        if (">".equals(operator)) return "<";
        if (">=".equals(operator)) return "<=";
        if ("<".equals(operator)) return ">";
        if ("<=".equals(operator)) return ">=";
        return operator;
    }

    private boolean isIntegralType(String type) {
        return "byte".equals(type) || "Byte".equals(type)
                || "short".equals(type) || "Short".equals(type)
                || "int".equals(type) || "Integer".equals(type)
                || "long".equals(type) || "Long".equals(type);
    }

    private boolean isFloatingType(String type) {
        return "float".equals(type) || "Float".equals(type)
                || "double".equals(type) || "Double".equals(type);
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
    private List<String> importsFor(MethodModel methodModel, List<TestScenario> scenarios,
                                    boolean hasFieldMocks, boolean usesInjectMocks) {
        Set<String> imports = new LinkedHashSet<>();
        addTypeImports(imports, methodModel, methodModel.getReturnType());
        for (DependencyModel dependency : methodModel.getDependencies()) {
            addTypeImports(imports, methodModel, dependency.getFieldType());
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            addTypeImports(imports, methodModel, parameter.getType());
        }
        imports.add("org.junit.jupiter.api.Test");
        imports.add("org.junit.jupiter.api.BeforeEach");
        if (hasFieldMocks || usesInjectMocks) {
            imports.add("org.mockito.MockitoAnnotations");
        }
        if (hasFieldMocks) {
            imports.add("org.mockito.Mock");
        }
        if (usesInjectMocks) {
            imports.add("org.mockito.InjectMocks");
        }
        // 异常场景使用 assertThrows(异常类型.class)，异常类型同样需要 import，
        // 否则生成代码引用分支里的业务异常（如 DecoderException）时无法编译。
        for (String exceptionType : methodModel.getThrownExceptions()) {
            addExceptionImport(imports, methodModel, exceptionType);
        }
        for (BranchModel branch : methodModel.getBranches()) {
            addExceptionImport(imports, methodModel, branch.getExceptionType());
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
     * 为异常类型补充 import。
     * 全限定名直接使用；简单名先匹配被测源码的 import，同包或 java.lang 类型无需 import。
     */
    private void addExceptionImport(Set<String> imports, MethodModel methodModel, String exceptionType) {
        if (exceptionType == null || exceptionType.isBlank()) {
            return;
        }
        String trimmed = exceptionType.trim();
        String simple = simpleType(trimmed);
        if ("Exception".equals(simple) || "RuntimeException".equals(simple)
                || "Error".equals(simple) || "Throwable".equals(simple)) {
            return; // java.lang 无需 import。
        }
        if (trimmed.contains(".")) {
            imports.add(trimmed);
            return;
        }
        String importName = importForType(methodModel, simple);
        if (importName != null) {
            imports.add(importName);
        }
    }

    /**
     * 根据简单类型查找源码中的 import。
     */
    private String importForType(MethodModel methodModel, String type) {
        for (String simple : simpleNamesIn(type)) {
            String resolved = resolveImport(methodModel, simple);
            if (resolved != null) return resolved;
        }
        return null;
    }

    /**
     * 把类型文本解析到的所有 import 加入集合。
     * 必须分解泛型实参与数组元素：List<JavaType> 需要 List 与 JavaType 两个 import，
     * 只按整段文本匹配会漏内层类型（jackson/HikariCP 实测生成代码编译失败）。
     */
    private void addTypeImports(Set<String> imports, MethodModel methodModel, String type) {
        for (String simple : simpleNamesIn(type)) {
            String resolved = resolveImport(methodModel, simple);
            if (resolved != null) imports.add(resolved);
        }
    }

    private String resolveImport(MethodModel methodModel, String simpleName) {
        if (simpleName == null || simpleName.isBlank() || isTypeVariableType(simpleName)) return null;
        for (String importName : methodModel.getImports()) {
            if (importName.endsWith("." + simpleName)) {
                return importName;
            }
        }
        // 通配 import（java.util.concurrent.* 等）无法用 endsWith 解析简单名：
        // 用插件 JVM 反射确认 JDK 包内确实存在该类型再展开为精确 import。
        for (String importName : methodModel.getImports()) {
            if (!importName.endsWith(".*")) continue;
            String candidate = importName.substring(0, importName.length() - 1) + simpleName;
            try {
                Class.forName(candidate, false, getClass().getClassLoader());
                return candidate;
            } catch (ClassNotFoundException notInThisPackage) {
                // 继续尝试下一个通配前缀。
            } catch (Throwable reflectionFailure) {
                if (reflectionFailure instanceof com.intellij.openapi.progress.ProcessCanceledException cancellation) throw cancellation;
                return null;
            }
        }
        return null;
    }

    /** 提取类型文本中的全部大写开头简单名（分解泛型实参、数组与限定名）。 */
    static List<String> simpleNamesIn(String type) {
        List<String> names = new ArrayList<>();
        if (type == null || type.isBlank()) return names;
        for (String token : type.split("[<>,\\s\\[\\]?]+")) {
            if (token.isBlank()) continue;
            int dot = token.lastIndexOf('.');
            String simple = dot >= 0 ? token.substring(dot + 1) : token;
            if (!simple.isEmpty() && Character.isUpperCase(simple.charAt(0))) {
                names.add(simple);
            }
        }
        return names;
    }

    /**
     * 泛型方法类型变量（T/E/K/V、A/B/C、T1/T2 等短全大写名）：不是可解析类型。
     * 未 import 的 1-2 位大写"类名"在测试类里声明/new 本就编译失败，当作类型变量
     * （值退字面量、声明退 var）严格优于按类名处理。
     */
    static boolean isTypeVariableType(String token) {
        return token != null && token.matches("[A-Z]{1,2}[0-9]?");
    }

    /** 类型文本中是否含类型变量（裸 T 或泛型位 Class<T>）——声明侧需要退 var。 */
    static boolean containsTypeVariable(String type) {
        if (type == null || type.isBlank()) return false;
        for (String token : type.split("[<>,\\s\\[\\]?]+")) {
            int dot = token.lastIndexOf('.');
            String simple = dot >= 0 ? token.substring(dot + 1) : token;
            if (isTypeVariableType(simple)) return true;
        }
        return false;
    }

    /** 局部变量声明类型：含类型变量时用 var（T obj = ... 在测试类中无 T 声明必失败）。 */
    static String declarationTypeFor(String type, String expression) {
        if (containsTypeVariable(type) && !"null".equals(expression)) return "var";
        return type;
    }

    /**
     * 判断是否需要生成 throws Exception。
     * 被测方法声明异常或 private 反射调用需要让测试方法透传受检异常。
     */
    private boolean requiresThrowsException(MethodModel methodModel) {
        return !methodModel.getThrownExceptions().isEmpty()
                || isPrivateMethod(methodModel);
    }

    /** 查找源码直接返回其调用结果的依赖。 */
    private DependencyModel directOptionalDependencyWithCall(MethodModel methodModel) {
        String body = compact(methodModel.getMethodBodyText());
        for (DependencyModel dependency : methodModel.getDependencies()) {
            for (String call : dependency.getCallExpressions()) {
                if (body.contains("return" + compact(dependency.getFieldName() + "." + call) + ";")) {
                    return dependency;
                }
            }
        }
        return null;
    }

    private String directOptionalCallExpression(MethodModel methodModel, DependencyModel dependency) {
        if (dependency == null) return null;
        String body = compact(methodModel.getMethodBodyText());
        for (String call : dependency.getCallExpressions()) {
            if (body.contains("return" + compact(dependency.getFieldName() + "." + call) + ";")) {
                return call;
            }
        }
        return null;
    }

    private boolean isDirectOptionalDelegation(MethodModel methodModel) {
        return isOptionalReturn(methodModel) && directOptionalDependencyWithCall(methodModel) != null;
    }

    private boolean isEvidenceBackedOptionalScenario(TestScenario scenario) {
        if (scenario == null || scenario.getExpectedAssertions() == null) return false;
        return scenario.getExpectedAssertions().contains("directly returned Optional is present")
                || scenario.getExpectedAssertions().contains("returned value equals Optional.empty()");
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
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
        // 数组字面量自包含且数组不可 mock，直接保留；其余 new 表达式按可解析性降级。
        if (expression.startsWith("new ")) {
            return simpleType(type).endsWith("[]")
                    ? expression
                    : (mockitoAvailable ? "mock(" + simpleType(type) + ".class)" : "null");
        }
        return expression;
    }

    /**
     * 主路径参数值安全化。
     * 数据生成器对引用类型给出 `new X()`，但 X 可能没有无参构造，也可能是源码 import 不到的
     * 嵌套类（如 jackson 的 TypeParser.MyTokenizer）或 package-private 类型——这类名字在
     * 生成的测试类里根本不可见，new 和 mock 都会编译失败。凡是被测源码 import 不到、又不在
     * java.lang 的类型统一退回 null（任何引用类型都可编译），断言质量交给后续 LLM/人工阶段。
     */
    private String safeValueExpression(MethodModel methodModel, ParameterModel parameter, String expression) {
        if (expression == null || !expression.startsWith("new ")) {
            return expression;
        }
        String simple = simpleType(parameter.getType());
        // 数组字面量自包含（无需 import/可解析类型），且数组类型 Mockito 不可 mock
        // （mock(char[].class) 运行期抛 MockitoException）——必须原样保留，
        // 否则数组参数退 null、被测方法首语句解引用即 NPE（commons-codec/jackson 实测）。
        if (simple.endsWith("[]")) {
            return expression;
        }
        if (isJavaLangType(simple) || importForType(methodModel, simple) != null) {
            return expression;
        }
        // 静态分析已把嵌套类型改写为“外部类.嵌套类”限定名，限定名在测试类中可见，可用 mock。
        String type = parameter.getType();
        if (mockitoAvailable && type != null && type.contains(".") && !type.contains("<")) {
            return "mock(" + type.trim() + ".class)";
        }
        return "null";
    }

    private boolean isJavaLangType(String simpleType) {
        switch (simpleType) {
            case "Object":
            case "String":
            case "StringBuilder":
            case "StringBuffer":
            case "Thread":
            case "Exception":
            case "RuntimeException":
            case "IllegalArgumentException":
            case "IllegalStateException":
            case "NullPointerException":
                return true;
            default:
                return false;
        }
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
        while (hasBalancedOuterParentheses(value)) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private boolean hasBalancedOuterParentheses(String value) {
        if (!value.startsWith("(") || !value.endsWith(")")) return false;
        int depth = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth == 0 && index < value.length() - 1) return false;
        }
        return depth == 0;
    }
}
