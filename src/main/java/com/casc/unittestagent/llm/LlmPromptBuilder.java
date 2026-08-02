package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.TypeSymbol;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 构造发送给 OpenAI-compatible 接口的提示词。
 */
public class LlmPromptBuilder {
    public String systemPrompt() {
        return "你是一个资深 Java 单元测试工程师。你需要根据静态分析结果，为指定 Java 方法生成 JUnit5 + Mockito 单元测试代码。"
                + "参数约束以静态分析结果为准，不要编造不存在的参数约束。请区分字段级 @Mock 和测试方法内部局部 mock，并生成可编辑、尽量可编译的测试代码。"
                + "禁止编造项目中不存在的类型名；如果链式调用中间返回类型未知，普通 mock(...) 方法里可以使用 RETURNS_DEEP_STUBS，但注解参数里不允许直接写 RETURNS_DEEP_STUBS。"
                + "如果确实要使用字段级深层 stub，必须写 @Mock(answer = Answers.RETURNS_DEEP_STUBS)。"
                + "链式调用必须逐层解析真实返回类型，禁止根据变量名猜测类型，例如 dbConfig 不能误写成 Path。"
                + "项目类型和方法只能使用 projectSymbolSnapshot 中列出的真实符号；快照未提供的项目方法不得创造，无法完成时保留 TODO。";
    }

    public String userPrompt(LlmGenerationRequest request) {
        JSONObject context = context(request);
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出 JSON 格式必须为：{\"mockStrategies\":[{\"enabled\":true,\"dependencyType\":\"字段依赖/方法参数数据对象/局部对象依赖/静态方法依赖\",\"target\":\"对象或类名\",\"method\":\"方法\",\"source\":\"源码线索\",\"mockitoStrategy\":\"mock/localMock/mockStatic/spy/构造测试对象/不处理\",\"note\":\"说明\"}],\"testCode\":\"完整 Java 测试类代码\",\"warnings\":[\"可选警告\"]}。\n"
                + "测试代码要求：\n"
                + "1. 使用 JUnit5。\n"
                + "2. 使用 Mockito。\n"
                + "3. 不要生成中文测试方法名。\n"
                + "4. 测试方法名使用英文。\n"
                + "5. DTO / Request / Entity / Profile / Path / File / Directory 等对象不要默认生成测试类字段级 @Mock；优先构造真实对象或使用 @TempDir。\n"
                + "6. DTO / Request / Entity / Profile 如果是 record、interface、final class、无可用构造函数、字段很多，或被测方法只关心少量 getter/accessor，可以在测试方法内部使用局部 Mockito.mock(...)，mockitoStrategy 写 localMock。\n"
                + "7. 局部 mock 示例：RequestObject request = mock(RequestObject.class); when(request.someAccessor()).thenReturn(value); 这类 mock 不要声明为测试类字段。\n"
                + "8. Repository / Mapper / Dao / Client / Service / JdbcTemplate / RedisTemplate / RestTemplate / WebClient / 外部系统接口 / 框架接口可以生成字段级 @Mock。\n"
                + "9. 静态方法调用需要说明 mockStatic，如果不确定依赖是否存在，生成 TODO 注释。\n"
                + "10. 私有方法不要直接 stub；如果需要覆盖 private 方法路径，应通过调用公开入口或反射调用被测方法。\n"
                + "11. 测试代码必须是完整 Java 测试类。\n"
                + "12. 不允许返回空代码。\n"
                + "13. 如果 userInstruction 非空，请在不违反静态分析约束的前提下优先满足用户补充要求。\n"
                + "14. 如果 existingTestCodeSummary 非空，请参考现有测试代码并整体重新输出完整测试类，不要只输出 diff。\n"
                + "15. usages 表示项目索引或当前源码文件中发现的被测方法调用点，可用于理解真实调用方式；LOW 置信度的调用点只作为参考，不要强行据此生成断言。\n"
                + "16. 禁止编造项目中不存在的类型名；只能使用 context.knownTypes、源码 import、java.lang、JUnit5、Mockito、JDK 常用类型，或测试代码中明确声明的类型。\n"
                + "17. 对链式调用，例如 request.config().resolveValue(...)：如果 context.chainedCallReturnTypes 提供了 config() 的真实返回类型，必须使用真实类型；如果没有提供真实返回类型，普通 mock(...) 可以使用 RETURNS_DEEP_STUBS，但不要把 RETURNS_DEEP_STUBS 放进注解参数。\n"
                + "18. 如果确实要字段级深层 stub，必须写 @Mock(answer = Answers.RETURNS_DEEP_STUBS)；禁止生成 @Mock(answer = RETURNS_DEEP_STUBS)。\n"
                + "19. 不要生成 Xxx.InnerConfig 这类内部类，除非源码或 knownTypes 明确存在这个内部类。\n"
                + "20. 如果 accessModifier=private，测试方法不能直接调用被测方法，必须生成 java.lang.reflect.Method/getDeclaredMethod 反射辅助方法，或者只通过 public 方法间接测试。\n"
                + "21. 文件复制、文件存在性、Path 安全校验默认使用 @TempDir 和真实 Path/Files 临时文件；不要默认 mock Files、Paths，也不要 mock Path 目录变量。\n"
                + "22. FileUtils.copy 优先真实临时文件复制；只有确认必须隔离副作用时才 mockStatic(FileUtils.class)，并添加 TODO 提醒项目未引入 mockito-inline 时改回真实文件方式。\n"
                + "23. 不要生成空的 @AfterEach tearDown；try-with-resources 已管理静态 mock 时不需要 tearDown。\n"
                + "24. 生成异常分支或边界分支测试时，必须先满足 branches.preconditions 和目标分支之前的 safeCondition，不能让测试提前失败在无关逻辑上。\n"
                + "25. 如果 callContexts 中显示参数对象 getter、record accessor、字段读取或链式方法被当前方法或内部调用链使用，必须补齐这些 mock 返回值或构造真实对象；无法确认字段时生成 TODO，不要输出看似完整但会 NPE 的代码。\n"
                + "26. callContexts.INTERNAL_METHOD_CALL 的 sourceCode 是当前方法直接调用的一层本类内部方法源码；nestedCalls 至少说明该内部方法继续调用的项目方法签名或静态调用摘要，生成测试时必须考虑这些真实执行路径。\n"
                + "27. 注解参数中不要使用 RETURNS_DEEP_STUBS；@Mock(answer = ...) 必须写 @Mock(answer = Answers.RETURNS_DEEP_STUBS)；普通 mock(...) 方法中才允许使用 RETURNS_DEEP_STUBS。\n"
                + "28. 链式调用必须逐层解析真实返回类型，禁止根据变量名猜测类型；例如 request.dbConfig() 的中间变量必须生成 DbConfig，而不是 Path。\n"
                + "29. 对空附件路径、空集合或空结果场景，期望值应按真实语义生成 0 或 empty，不要把 0 写成 1。\n"
                + "30. projectSymbolSnapshot 是 IntelliJ PSI 对当前项目的真实解析结果。项目类型的构造函数、方法和重载必须严格从该快照选择，禁止仅根据常见命名编造 getter、builder、factory 或 repository 方法。\n"
                + "31. projectSymbolSnapshot.unresolvedSymbols 中的表达式不得猜测返回类型或替代方法；无法生成可靠代码时添加 TODO，并采用不依赖该未知符号的最小测试结构。\n"
                + "以下是 UnitMind 静态分析上下文，参数约束必须以其中 constraints 和 branches 为准：\n"
                + context.toString(2);
    }

    public String repairSystemPrompt() {
        return "你是 Java 单元测试编译错误修复器。只能修复给定测试类，不能修改被测代码，"
                + "不能创造 projectSymbolSnapshot 中不存在的项目类型、方法、构造函数或重载。"
                + "必须根据 compilerDiagnostics 的行列和错误信息进行最小修改，并返回完整测试类。";
    }

    public String repairPrompt(LlmGenerationRequest request) {
        JSONObject repair = new JSONObject();
        repair.put("repairAttempt", request.getRepairAttempt());
        repair.put("compilerDiagnostics", compilerDiagnostics(request));
        repair.put("projectSymbolSnapshot", symbolSnapshot(request.getSymbolSnapshot()));
        repair.put("currentTestCode", nullToEmpty(request.getExistingTestCodeSummary()));
        repair.put("userInstruction", nullToEmpty(request.getUserInstruction()));
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出格式：{\"mockStrategies\":[],\"testCode\":\"修复后的完整 Java 测试类\",\"warnings\":[]}。\n"
                + "要求：只修复编译错误；保留原测试意图；不得删除测试来规避错误；不得调用符号快照之外的项目 API；"
                + "如果诊断与项目环境有关且无法通过修改测试解决，保持代码并在 warnings 中说明。\n"
                + repair.toString(2);
    }

    public String mockStrategySystemPrompt() {
        return "你是一个资深 Java 单元测试工程师。你只需要根据静态分析结果给出 Mockito Mock 策略候选，不要生成测试代码。"
                + "必须区分测试类字段级 @Mock 和测试方法内部局部 mock。";
    }

    public String mockStrategyPrompt(LlmGenerationRequest request) {
        JSONObject context = context(request);
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出 JSON 格式必须为：{\"mockStrategies\":[{\"enabled\":true,\"dependencyType\":\"字段依赖/方法参数数据对象/局部对象依赖/静态方法依赖\",\"target\":\"对象或类名\",\"method\":\"方法\",\"source\":\"源码线索\",\"mockitoStrategy\":\"mock/localMock/mockStatic/spy/构造测试对象/不处理\",\"note\":\"说明\"}],\"warnings\":[\"可选警告\"]}。\n"
                + "Mock 策略要求：\n"
                + "1. Repository / Mapper / Dao / Client / Service / JdbcTemplate / RedisTemplate / RestTemplate / WebClient / 外部系统接口 / 框架接口可以生成字段级 @Mock，mockitoStrategy 写 mock。\n"
                + "2. DTO / Request / Entity / Profile / Path / File / Directory 等对象不默认生成字段级 @Mock。\n"
                + "3. DTO / Request / Entity / Profile 如果构造复杂、是 record、interface、final class、无可用构造函数、字段很多，或被测方法只关心少量 getter/accessor，可以在测试方法内部局部 mock，mockitoStrategy 写 localMock。\n"
                + "4. 静态方法调用写 mockStatic；private 方法不要直接 stub。\n"
                + "5. 链式调用中间返回类型未知时，不要猜测 IntermediateType、Config、RequestConfig 或 Xxx.InnerConfig；普通 mock(...) 可以建议 RETURNS_DEEP_STUBS，但不要把 RETURNS_DEEP_STUBS 放进 @Mock(answer = ...) 里。\n"
                + "6. 如果确实要字段级深层 stub，必须建议 @Mock(answer = Answers.RETURNS_DEEP_STUBS)；禁止写 @Mock(answer = RETURNS_DEEP_STUBS)。\n"
                + "7. 文件系统相关逻辑默认建议 @TempDir + 真实 Path/Files，不要默认建议 mockStatic(Files.class)、mockStatic(Paths.class) 或 mock(Path.class)。\n"
                + "8. 不要根据变量名猜类型；链式调用必须逐层输出真实返回类型，禁止把 dbConfig 一类变量误判成 Path。\n"
                + "9. 每条策略必须说明依据，尤其是为什么建议字段级 mock、局部 mock、真实对象或 @TempDir。\n"
                + "以下是 UnitMind 静态分析上下文：\n"
                + context.toString(2);
    }

    private JSONObject context(LlmGenerationRequest request) {
        JSONObject context = new JSONObject();
        context.put("packageName", nullToEmpty(request.getPackageName()));
        context.put("className", nullToEmpty(request.getClassName()));
        context.put("methodName", nullToEmpty(request.getMethodName()));
        context.put("methodSignature", nullToEmpty(request.getMethodSignature()));
        context.put("returnType", nullToEmpty(request.getReturnType()));
        context.put("accessModifier", nullToEmpty(request.getAccessModifier()));
        context.put("imports", nullToEmpty(request.getImports()));
        context.put("classFields", nullToEmpty(request.getClassFields()));
        context.put("knownTypes", request.getKnownTypes());
        context.put("chainedCallReturnTypes", request.getChainedCallReturnTypes());
        context.put("filesystemTestingPolicy", "文件系统逻辑默认使用 @TempDir、真实 Path、真实 Files 临时文件；不要默认 mock Files、Paths 或 Path 目录变量。");
        context.put("methodSourceCode", nullToEmpty(request.getMethodSourceCode()));
        context.put("parameters", parameters(request));
        context.put("constraints", constraints(request));
        context.put("branches", branches(request));
        context.put("dependencies", dependencies(request));
        context.put("callContexts", callContexts(request));
        context.put("projectSymbolSnapshot", symbolSnapshot(request.getSymbolSnapshot()));
        context.put("usages", usages(request));
        context.put("scenarios", scenarios(request));
        context.put("existingTestCodeSummary", nullToEmpty(request.getExistingTestCodeSummary()));
        context.put("userInstruction", nullToEmpty(request.getUserInstruction()));
        context.put("testFramework", nullToEmpty(request.getTestFramework()));
        context.put("mockFramework", nullToEmpty(request.getMockFramework()));
        return context;
    }

    private JSONObject symbolSnapshot(ProjectSymbolSnapshot snapshot) {
        JSONObject json = new JSONObject();
        if (snapshot == null) {
            json.put("available", false);
            return json;
        }
        json.put("available", snapshot.isUsable());
        json.put("moduleName", nullToEmpty(snapshot.getModuleName()));
        json.put("languageLevel", nullToEmpty(snapshot.getLanguageLevel()));
        json.put("targetType", nullToEmpty(snapshot.getTargetType()));
        json.put("targetMethod", callable(snapshot.getTargetMethod()));
        json.put("unresolvedSymbols", snapshot.getUnresolvedSymbols());
        JSONArray types = new JSONArray();
        for (TypeSymbol type : snapshot.getTypes()) {
            JSONObject item = new JSONObject();
            item.put("qualifiedName", nullToEmpty(type.getQualifiedName()));
            item.put("kind", nullToEmpty(type.getKind()));
            item.put("final", type.isFinalType());
            JSONArray constructors = new JSONArray();
            for (CallableSymbol constructor : type.getConstructors()) {
                constructors.put(callable(constructor));
            }
            JSONArray methods = new JSONArray();
            for (CallableSymbol method : type.getMethods()) {
                methods.put(callable(method));
            }
            item.put("constructors", constructors);
            item.put("methods", methods);
            types.put(item);
        }
        json.put("types", types);
        return json;
    }

    private JSONObject callable(CallableSymbol symbol) {
        JSONObject json = new JSONObject();
        if (symbol == null) {
            return json;
        }
        json.put("id", nullToEmpty(symbol.getId()));
        json.put("owner", nullToEmpty(symbol.getOwnerQualifiedName()));
        json.put("name", nullToEmpty(symbol.getName()));
        json.put("returnType", nullToEmpty(symbol.getReturnType()));
        json.put("parameterTypes", symbol.getParameterTypes());
        json.put("visibility", nullToEmpty(symbol.getVisibility()));
        json.put("static", symbol.isStaticMethod());
        json.put("varArgs", symbol.isVarArgs());
        json.put("constructor", symbol.isConstructor());
        json.put("resolved", symbol.isResolved());
        return json;
    }

    private JSONArray compilerDiagnostics(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (CompileDiagnostic diagnostic : request.getCompilerDiagnostics()) {
            JSONObject item = new JSONObject();
            item.put("severity", nullToEmpty(diagnostic.getSeverity()));
            item.put("code", nullToEmpty(diagnostic.getCode()));
            item.put("message", nullToEmpty(diagnostic.getMessage()));
            item.put("line", diagnostic.getLine());
            item.put("column", diagnostic.getColumn());
            item.put("snippet", nullToEmpty(diagnostic.getSnippet()));
            item.put("repairable", diagnostic.isRepairable());
            array.put(item);
        }
        return array;
    }

    private JSONArray parameters(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (ParameterModel parameter : request.getParameters()) {
            JSONObject item = new JSONObject();
            item.put("name", nullToEmpty(parameter.getName()));
            item.put("type", nullToEmpty(parameter.getType()));
            item.put("nullable", parameter.isNullable());
            item.put("normalValues", parameter.getNormalValues());
            item.put("boundaryValues", parameter.getBoundaryValues());
            item.put("invalidValues", parameter.getInvalidValues());
            array.put(item);
        }
        return array;
    }

    private JSONArray constraints(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (ConstraintModel constraint : request.getConstraints()) {
            JSONObject item = new JSONObject();
            item.put("parameterName", nullToEmpty(constraint.getParameterName()));
            item.put("kind", nullToEmpty(constraint.getKind()));
            item.put("expression", nullToEmpty(constraint.getExpression()));
            item.put("safeExpression", nullToEmpty(constraint.getSafeExpression()));
            item.put("source", nullToEmpty(constraint.getSource()));
            item.put("confidence", constraint.getConfidence());
            array.put(item);
        }
        return array;
    }

    private JSONArray branches(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (BranchModel branch : request.getBranches()) {
            JSONObject item = new JSONObject();
            item.put("condition", nullToEmpty(branch.getCondition()));
            item.put("safeCondition", nullToEmpty(branch.getSafeCondition()));
            item.put("action", nullToEmpty(branch.getAction()));
            item.put("exceptionType", nullToEmpty(branch.getExceptionType()));
            item.put("preconditions", branch.getPreconditions());
            array.put(item);
        }
        return array;
    }

    private JSONArray dependencies(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (DependencyModel dependency : request.getDependencies()) {
            JSONObject item = new JSONObject();
            item.put("fieldName", nullToEmpty(dependency.getFieldName()));
            item.put("fieldType", nullToEmpty(dependency.getFieldType()));
            item.put("dependencyType", nullToEmpty(dependency.getDependencyType()));
            item.put("calledMethods", dependency.getCalledMethods());
            item.put("callExpressions", dependency.getCallExpressions());
            item.put("methodParameter", dependency.isMethodParameter());
            array.put(item);
        }
        return array;
    }

    private JSONArray usages(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (MethodUsageModel usage : request.getUsages()) {
            JSONObject item = new JSONObject();
            item.put("callerClassName", nullToEmpty(usage.getCallerClassName()));
            item.put("callerFilePath", nullToEmpty(usage.getCallerFilePath()));
            item.put("callerMethodName", nullToEmpty(usage.getCallerMethodName()));
            item.put("callerSignature", nullToEmpty(usage.getCallerSignature()));
            item.put("callExpression", nullToEmpty(usage.getCallExpression()));
            item.put("receiver", nullToEmpty(usage.getReceiver()));
            item.put("callStyle", nullToEmpty(usage.getCallStyle()));
            item.put("argumentExpressions", usage.getArgumentExpressions());
            item.put("lineNumber", usage.getLineNumber());
            item.put("confidence", nullToEmpty(usage.getConfidence()));
            item.put("sourceSnippet", nullToEmpty(usage.getSourceSnippet()));
            array.put(item);
        }
        return array;
    }

    private JSONArray callContexts(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (CallContextModel callContext : request.getCallContexts()) {
            JSONObject item = new JSONObject();
            item.put("kind", nullToEmpty(callContext.getKind()));
            item.put("owner", nullToEmpty(callContext.getOwner()));
            item.put("methodName", nullToEmpty(callContext.getMethodName()));
            item.put("expression", nullToEmpty(callContext.getExpression()));
            item.put("returnType", nullToEmpty(callContext.getReturnType()));
            item.put("signature", nullToEmpty(callContext.getSignature()));
            item.put("sourceCode", nullToEmpty(callContext.getSourceCode()));
            item.put("parameterUsageSummary", nullToEmpty(callContext.getParameterUsageSummary()));
            item.put("nestedCalls", callContext.getNestedCalls());
            array.put(item);
        }
        return array;
    }

    private JSONArray scenarios(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (TestScenario scenario : request.getScenarios()) {
            JSONObject item = new JSONObject();
            item.put("type", nullToEmpty(scenario.getType()));
            item.put("displayName", nullToEmpty(scenario.getDisplayNameCn()));
            item.put("methodName", nullToEmpty(scenario.getMethodNameEn()));
            item.put("description", nullToEmpty(scenario.getDescription()));
            item.put("inputExpressions", scenario.getInputExpressions());
            item.put("expectedAssertions", scenario.getExpectedAssertions());
            item.put("coveredBranches", scenario.getCoveredBranches());
            array.put(item);
        }
        return array;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
