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

        if (isOptionalReturn(methodModel) && !methodModel.getDependencies().isEmpty()) {
            scenarios.add(scenario("参数合法时，应返回数据", "shouldReturnConnectionWhenIdAndOwnerIdValid", "NORMAL",
                    "使用合法参数，验证返回 Optional 中存在数据。"));
            scenarios.add(scenario("仓储返回空时，应返回空结果", "shouldReturnEmptyWhenRepositoryReturnsEmpty", "BOUNDARY",
                    "仓储未找到数据时，验证返回 Optional.empty()。"));
            for (ParameterModel parameter : methodModel.getParameters()) {
                if (parameter.isNullable()) {
                    TestScenario nullScenario = scenario(parameter.getName() + " 为空时，应按源码逻辑处理",
                            "shouldHandleNull" + capitalize(parameter.getName()) + "AccordingToSourceLogic",
                            "BOUNDARY",
                            "覆盖 " + parameter.getName() + " 为 null 的边界输入。");
                    nullScenario.getCoveredBranches().add(parameter.getName() + " == null");
                    scenarios.add(nullScenario);
                    break;
                }
            }
            return completeScenarioSet(scenarios, methodModel);
        }

        scenarios.add(scenario("有效输入时，应正常返回", "shouldReturnNormallyWhenInputValid", "NORMAL",
                "有效输入应使被测方法按正常路径执行。"));

        for (BranchModel branch : methodModel.getBranches()) {
            if (!"throw".equals(branch.getAction())) {
                continue;
            }
            TestScenario exceptionScenario = scenario("条件命中时，应抛出异常",
                    "shouldThrowWhenConditionMatched",
                    "EXCEPTION",
                    "非法输入应触发 " + branch.getExceptionType() + "；进入该分支前必须先满足前置路径条件："
                            + preconditionText(branch.getPreconditions()) + "。");
            exceptionScenario.getCoveredBranches().add(branch.getCondition());
            exceptionScenario.getInputExpressions().addAll(branch.getPreconditions());
            scenarios.add(exceptionScenario);
        }

        if (!methodModel.getBranches().isEmpty() && hasBoundaryValues(methodModel)) {
            scenarios.add(scenario("边界值输入时，应正常处理",
                    "shouldHandleBoundaryValues",
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
            scenarios.add(scenario("有效输入时，应正常返回", "shouldReturnNormallyWhenInputValid", "NORMAL",
                    "有效输入应使被测方法按正常路径执行。"));
        }
        if (!hasScenarioType(scenarios, "EXCEPTION")) {
            scenarios.add(scenario("未生成异常场景", "exceptionScenarioNotGenerated", "EXCEPTION_REASON",
                    exceptionMissingReason(methodModel)));
        }
        if (!hasScenarioType(scenarios, "BOUNDARY")) {
            TestScenario boundary = boundaryScenario(methodModel);
            scenarios.add(boundary == null
                    ? scenario("未生成边界场景", "boundaryScenarioNotGenerated", "BOUNDARY_REASON", boundaryMissingReason(methodModel))
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
        for (ParameterModel parameter : methodModel.getParameters()) {
            if (parameter.isNullable()) {
                TestScenario scenario = scenario(parameter.getName() + " 为空时，应按源码逻辑处理",
                        "shouldHandleNull" + capitalize(parameter.getName()) + "AccordingToSourceLogic",
                        "BOUNDARY",
                        "覆盖 " + parameter.getName() + " 为 null 的边界输入。");
                scenario.getCoveredBranches().add(parameter.getName() + " == null");
                return scenario;
            }
            if (parameter.getBoundaryValues() != null && !parameter.getBoundaryValues().isEmpty()) {
                TestScenario scenario = scenario(parameter.getName() + " 使用边界值时，应按源码逻辑处理",
                        "shouldHandleBoundary" + capitalize(parameter.getName()) + "AccordingToSourceLogic",
                        "BOUNDARY",
                        "覆盖 " + parameter.getName() + " 的边界输入：" + String.join(", ", parameter.getBoundaryValues()) + "。");
                scenario.getCoveredBranches().add(parameter.getName() + " boundary value");
                return scenario;
            }
        }
        return null;
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
        return scenario;
    }

    /**
     * 判断被测方法是否返回 Optional。
     * Optional 返回值需要生成存在和空结果两个场景，不能只用 assertNotNull 弱断言。
     */
    private boolean isOptionalReturn(MethodModel methodModel) {
        return methodModel.getReturnType() != null && methodModel.getReturnType().startsWith("Optional<");
    }

    /**
     * 将参数名转换为英文方法名片段。
     * 空值场景需要把参数名拼入测试方法名，保持生成代码标识符全英文。
     */
    private String capitalize(String text) {
        if (text == null || text.isBlank()) {
            return "Parameter";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
