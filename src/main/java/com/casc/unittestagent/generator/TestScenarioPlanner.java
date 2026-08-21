package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据方法分支、异常路径和参数边界规划测试场景。
 * 该类把“需要测什么”的判断从源码生成中拆出来，便于后续扩展更多场景类型。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestScenarioPlanner {
    /**
     * 规划测试场景或 Mock 策略。
     * 根据传入模型生成后续代码生成器可消费的结构化结果。
     *
      * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
      *
     * @param methodModel 源码分析得到的被测方法模型
     * @return 规划得到的测试场景或 Mock 策略
     */
    public List<TestScenario> plan(MethodModel methodModel) {
        List<TestScenario> scenarios = new ArrayList<>();
        if (methodModel == null) {
            return scenarios;
        }

        String methodPart = identifierPart(methodModel.getMethodName(), "Method");
        boolean directOptionalDelegation = isOptionalReturn(methodModel) && hasDirectOptionalDelegation(methodModel);
        if (directOptionalDelegation) {
            TestScenario present = scenario("依赖返回数据时，应原样返回 Optional 数据",
                    "shouldReturnPresentValueWhen" + methodPart + "DependencyFindsValue", "NORMAL",
                    "源码直接返回依赖调用结果；依赖返回 Optional.of(value) 时，验证被测方法返回同一结果。");
            present.setOracleKind("RETURN_VALUE");
            present.setOracleEvidence("SOURCE_DIRECT_RETURN");
            present.setOracleConfidence("HIGH");
            present.getExpectedAssertions().add("directly returned Optional is present");
            scenarios.add(present);
            TestScenario empty = scenario("依赖返回空 Optional 时，应原样返回空结果",
                    "shouldReturnEmptyWhen" + methodPart + "DependencyFindsNoValue", "BOUNDARY",
                    "源码直接返回依赖调用结果；依赖返回 Optional.empty() 时，验证被测方法返回空结果。");
            empty.setOracleKind("RETURN_VALUE");
            empty.setOracleEvidence("SOURCE_DIRECT_RETURN");
            empty.setOracleConfidence("HIGH");
            empty.getExpectedAssertions().add("returned value equals Optional.empty()");
            scenarios.add(empty);
        } else {
            scenarios.add(scenario("有效输入时，应正常返回", "shouldReturnNormallyWhen" + methodPart + "InputValid", "NORMAL",
                    "有效输入应使被测方法按正常路径执行。"));
        }

        int exceptionIndex = 0;
        for (BranchModel branch : methodModel.getBranches()) {
            if (!"throw".equals(branch.getAction())) {
                continue;
            }
            exceptionIndex++;
            String exceptionPart = identifierPart(branch.getExceptionType(), "Exception");
            TestScenario exceptionScenario = scenario("条件命中时，应抛出异常",
                    "shouldThrow" + exceptionPart + "When" + methodPart + "Condition" + exceptionIndex + "Matched",
                    "EXCEPTION",
                    "非法输入应触发 " + branch.getExceptionType() + "；进入该分支前必须先满足前置路径条件："
                            + preconditionText(branch.getPreconditions()) + "。");
            exceptionScenario.getCoveredBranches().add(branch.getCondition());
            exceptionScenario.getInputExpressions().addAll(branch.getPreconditions());
            scenarios.add(exceptionScenario);
        }

        if (!hasScenarioType(scenarios, "BOUNDARY")
                && !methodModel.getBranches().isEmpty() && hasBoundaryValues(methodModel)) {
            scenarios.add(scenario("边界值输入时，应正常处理",
                    "shouldHandle" + methodPart + "BoundaryValues",
                    "BOUNDARY",
                    "边界输入应被被测方法正确处理。"));
        }

        return completeScenarioSet(scenarios, methodModel);
    }

    /**
     * 保证场景页总是覆盖正常、异常、边界三个维度；无法静态生成时给出原因说明。
     */
    private List<TestScenario> completeScenarioSet(List<TestScenario> scenarios, MethodModel methodModel) {
        if (!hasScenarioType(scenarios, "NORMAL")) {
            scenarios.add(scenario("有效输入时，应正常返回",
                    "shouldReturnNormallyWhen" + identifierPart(methodModel.getMethodName(), "Method") + "InputValid", "NORMAL",
                    "有效输入应使被测方法按正常路径执行。"));
        }
        if (!hasScenarioType(scenarios, "EXCEPTION")) {
            scenarios.add(scenario("未生成异常场景",
                    "exceptionScenarioNotGeneratedFor" + identifierPart(methodModel.getMethodName(), "Method"), "EXCEPTION_REASON",
                    exceptionMissingReason(methodModel)));
        }
        if (!hasScenarioType(scenarios, "BOUNDARY")) {
            TestScenario boundary = boundaryScenario(methodModel);
            scenarios.add(boundary == null
                    ? scenario("未生成边界场景",
                    "boundaryScenarioNotGeneratedFor" + identifierPart(methodModel.getMethodName(), "Method"),
                    "BOUNDARY_REASON", boundaryMissingReason(methodModel))
                    : boundary);
        }
        return scenarios;
    }

    /**
     * 判断是否已有指定类型的可生成场景。
     */
    private boolean hasScenarioType(List<TestScenario> scenarios, String type) {
        for (TestScenario scenario : scenarios) {
            if (type.equals(scenario.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据参数候选边界值生成边界场景。
     */
    private TestScenario boundaryScenario(MethodModel methodModel) {
        for (BranchModel branch : methodModel.getBranches()) {
            if (!"throw".equals(branch.getAction()) || !referencesMultipleParameters(methodModel, branch.getCondition())) {
                continue;
            }
            TestScenario scenario = scenario("参数关系临界值时，应在有效区间内正常处理",
                    "shouldHandle" + identifierPart(methodModel.getMethodName(), "Method") + "ParameterRelationBoundary",
                    "BOUNDARY",
                    "覆盖参数关系 " + branch.getCondition()
                            + " 附近的有效边界；输入必须同时满足所有 throw 分支的 safeCondition。");
            for (BranchModel guard : methodModel.getBranches()) {
                if ("throw".equals(guard.getAction()) && guard.getSafeCondition() != null
                        && !guard.getSafeCondition().isBlank()) {
                    scenario.getInputExpressions().add(guard.getSafeCondition());
                }
            }
            scenario.getCoveredBranches().add(branch.getSafeCondition());
            return scenario;
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (parameter.isNullable()) {
                TestScenario scenario = scenario(parameter.getName() + " 为空时，应按源码逻辑处理",
                        "shouldHandleNull" + identifierPart(parameter.getName(), "Parameter") + "When"
                                + identifierPart(methodModel.getMethodName(), "Method") + "Called",
                        "BOUNDARY",
                        "覆盖 " + parameter.getName() + " 为 null 的边界输入。");
                scenario.getCoveredBranches().add(parameter.getName() + " == null");
                return scenario;
            }
            if (parameter.getBoundaryValues() != null && !parameter.getBoundaryValues().isEmpty()) {
                TestScenario scenario = scenario(parameter.getName() + " 使用边界值时，应按源码逻辑处理",
                        "shouldHandleBoundary" + identifierPart(parameter.getName(), "Parameter") + "When"
                                + identifierPart(methodModel.getMethodName(), "Method") + "Called",
                        "BOUNDARY",
                        "覆盖 " + parameter.getName() + " 的边界输入：" + String.join(", ", parameter.getBoundaryValues()) + "。");
                scenario.getCoveredBranches().add(parameter.getName() + " boundary value");
                return scenario;
            }
        }
        return null;
    }

    private boolean referencesMultipleParameters(MethodModel methodModel, String expression) {
        if (expression == null || expression.isBlank()) return false;
        int referenced = 0;
        for (ParameterModel parameter : methodModel.getParameters()) {
            String name = parameter.getName();
            if (name != null && !name.isBlank()
                    && expression.matches("(?s).*\\b" + java.util.regex.Pattern.quote(name) + "\\b.*")) {
                referenced++;
            }
        }
        return referenced >= 2;
    }

    /**
     * 解释为什么没有静态异常场景。
     */
    private String exceptionMissingReason(MethodModel methodModel) {
        if (methodModel.getBranches().isEmpty()) {
            return "静态分析未发现 throw 分支、显式异常保护条件或会包装异常的依赖调用，因此不生成异常场景。返回类型为 "
                    + nullToText(methodModel.getReturnType()) + " 本身不是判断异常场景的依据。";
        }
        return "静态分析发现了控制流分支，但未发现 action=throw 的异常分支；这些分支更适合作为正常或边界路径覆盖。";
    }

    /**
     * 解释为什么没有静态边界场景。
     */
    private String boundaryMissingReason(MethodModel methodModel) {
        if (methodModel.getParameters().isEmpty()) {
            return "当前方法没有入参，静态分析无法构造参数边界输入。";
        }
        if ("void".equals(methodModel.getReturnType())) {
            return "当前方法返回 void 且未识别到参数边界值、null 检查或集合空值分支，因此不生成边界场景。";
        }
        return "静态分析未识别到可用于构造边界输入的参数约束，例如 null、empty、min/max、集合为空或字符串空白。";
    }

    private String nullToText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * 创建测试场景模型。
     * 统一封装场景名称、类型和说明，保证特殊场景输出结构一致。
     */
    private TestScenario scenario(String displayNameCn, String methodNameEn, String type, String description) {
        TestScenario scenario = new TestScenario();
        scenario.setName(methodNameEn);
        scenario.setDisplayNameCn(displayNameCn);
        scenario.setMethodNameEn(methodNameEn);
        scenario.setType(type);
        scenario.setDescription(description);
        if ("EXCEPTION".equals(type)) {
            scenario.setOracleKind("EXCEPTION");
            scenario.setOracleEvidence("SOURCE");
            scenario.setOracleConfidence("HIGH");
        } else if ("BOUNDARY".equals(type)) {
            scenario.setOracleKind("BEHAVIOR");
            scenario.setOracleEvidence("STATIC_ANALYSIS");
            scenario.setOracleConfidence("MEDIUM");
        } else {
            // 通用正常场景没有精确返回值依据。明确标低可信度，防止生成器
            // 把方法名中的“正常”误当成可编造精确断言或 verify 次数的契约。
            scenario.setOracleKind("UNKNOWN");
            scenario.setOracleEvidence("STATIC_ANALYSIS");
            scenario.setOracleConfidence("LOW");
        }
        return scenario;
    }

    /** 判断被测方法是否返回 Optional。 */
    private boolean isOptionalReturn(MethodModel methodModel) {
        return methodModel.getReturnType() != null && methodModel.getReturnType().startsWith("Optional<");
    }

    /**
     * 只有源码直接 `return dependency.call(...)` 时，才把依赖的 present/empty 结果提升为被测方法契约。
     * 仅凭返回类型为 Optional 无法证明方法会透传依赖结果，可能还存在 filter、map 或异常转换。
     */
    private boolean hasDirectOptionalDelegation(MethodModel methodModel) {
        String body = compact(methodModel.getMethodBodyText());
        for (com.casc.unittestagent.model.DependencyModel dependency : methodModel.getDependencies()) {
            for (String call : dependency.getCallExpressions()) {
                String invocation = dependency.getFieldName() + "." + call;
                if (body.contains("return" + compact(invocation) + ";")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    /** 把源码名称转换为稳定、合法且可读的测试方法名片段。 */
    private String identifierPart(String text, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        String simple = text;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (int index = 0; index < simple.length(); index++) {
            char ch = simple.charAt(index);
            if (!Character.isJavaIdentifierPart(ch)) {
                capitalizeNext = true;
                continue;
            }
            result.append(capitalizeNext ? Character.toUpperCase(ch) : ch);
            capitalizeNext = false;
        }
        return result.length() == 0 ? fallback : result.toString();
    }

    private String preconditionText(List<String> preconditions) {
        return preconditions == null || preconditions.isEmpty() ? "无额外前置条件" : String.join("; ", preconditions);
    }

    /**
     * 判断方法参数中是否存在边界值。
     * 只有至少一个参数具备边界候选值时才生成边界场景，避免输出没有输入差异的空用例。
     */
    private boolean hasBoundaryValues(MethodModel methodModel) {
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (parameter.getBoundaryValues() != null && !parameter.getBoundaryValues().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
