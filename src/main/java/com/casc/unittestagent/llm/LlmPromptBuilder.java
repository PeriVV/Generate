package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 构造发送给 OpenAI-compatible 接口的提示词。
 */
public class LlmPromptBuilder {
    public String systemPrompt() {
        return "你是一个资深 Java 单元测试工程师。你需要根据静态分析结果，为指定 Java 方法生成 JUnit5 + Mockito 单元测试代码。"
                + "参数约束以静态分析结果为准，不要编造不存在的参数约束。请判断合理的 Mock 策略，并生成可编辑、尽量可编译的测试代码。";
    }

    public String userPrompt(LlmGenerationRequest request) {
        JSONObject context = new JSONObject();
        context.put("packageName", nullToEmpty(request.getPackageName()));
        context.put("className", nullToEmpty(request.getClassName()));
        context.put("methodName", nullToEmpty(request.getMethodName()));
        context.put("methodSignature", nullToEmpty(request.getMethodSignature()));
        context.put("returnType", nullToEmpty(request.getReturnType()));
        context.put("imports", nullToEmpty(request.getImports()));
        context.put("classFields", nullToEmpty(request.getClassFields()));
        context.put("methodSourceCode", nullToEmpty(request.getMethodSourceCode()));
        context.put("parameters", parameters(request));
        context.put("constraints", constraints(request));
        context.put("branches", branches(request));
        context.put("dependencies", dependencies(request));
        context.put("usages", usages(request));
        context.put("scenarios", scenarios(request));
        context.put("existingTestCodeSummary", nullToEmpty(request.getExistingTestCodeSummary()));
        context.put("userInstruction", nullToEmpty(request.getUserInstruction()));
        context.put("testFramework", nullToEmpty(request.getTestFramework()));
        context.put("mockFramework", nullToEmpty(request.getMockFramework()));

        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出 JSON 格式必须为：{\"mockStrategies\":[{\"enabled\":true,\"dependencyType\":\"字段依赖\",\"target\":\"对象或类名\",\"method\":\"方法\",\"source\":\"源码线索\",\"mockitoStrategy\":\"mock/mockStatic/spy/构造测试对象/不处理\",\"note\":\"说明\"}],\"testCode\":\"完整 Java 测试类代码\",\"warnings\":[\"可选警告\"]}。\n"
                + "测试代码要求：\n"
                + "1. 使用 JUnit5。\n"
                + "2. 使用 Mockito。\n"
                + "3. 不要生成中文测试方法名。\n"
                + "4. 测试方法名使用英文。\n"
                + "5. DTO / Request / Entity 类型优先构造对象，不要默认 @Mock。\n"
                + "6. Repository / Mapper / Client / JdbcTemplate / RedisTemplate / RestTemplate / WebClient 可以 Mock。\n"
                + "7. 静态方法调用需要说明 mockStatic，如果不确定依赖是否存在，生成 TODO 注释。\n"
                + "8. 私有方法不要直接 stub。\n"
                + "9. 测试代码必须是完整 Java 测试类。\n"
                + "10. 不允许返回空代码。\n"
                + "11. 如果 userInstruction 非空，请在不违反静态分析约束的前提下优先满足用户补充要求。\n"
                + "12. 如果 existingTestCodeSummary 非空，请参考现有测试代码并整体重新输出完整测试类，不要只输出 diff。\n"
                + "13. usages 表示项目索引或当前源码文件中发现的被测方法调用点，可用于理解真实调用方式；LOW 置信度的调用点只作为参考，不要强行据此生成断言。\n"
                + "以下是 UnitMind 静态分析上下文，参数约束必须以其中 constraints 和 branches 为准：\n"
                + context.toString(2);
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
