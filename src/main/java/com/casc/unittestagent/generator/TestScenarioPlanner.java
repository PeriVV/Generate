package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.DependencyModel;
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

        if (looksLikeServletFilter(methodModel)) {
            scenarios.add(scenario("请求头存在时应继续执行过滤链", "shouldContinueFilterChainWhenHeaderExists", "NORMAL",
                    "请求头存在时应继续执行过滤链。"));
            scenarios.add(scenario("请求头为空时也应继续执行过滤链", "shouldContinueFilterChainWhenHeaderMissing", "BOUNDARY",
                    "请求头为空时也应继续执行过滤链。"));
            scenarios.add(scenario("过滤链抛出异常时应继续向外抛出异常", "shouldPropagateExceptionWhenFilterChainThrows", "EXCEPTION",
                    "过滤链抛出异常时应继续向外抛出异常。"));
            return scenarios;
        }

        if (looksLikeResolveTemplate(methodModel)) {
            scenarios.add(scenario("kind 为 MYSQL 且 useSandbox=false 时，应返回 mainJdbcTemplate",
                    "shouldReturnMainJdbcTemplateWhenMysqlAndSandboxDisabled", "NORMAL",
                    "覆盖 MYSQL 主库路径，验证不会误用沙箱模板。"));
            scenarios.add(scenario("kind 为 MYSQL 且 useSandbox=true 时，应返回 sandboxJdbcTemplate",
                    "shouldReturnSandboxJdbcTemplateWhenMysqlAndSandboxEnabled", "NORMAL",
                    "覆盖 MYSQL 沙箱路径，验证 useSandbox 会切换到沙箱模板。"));
            scenarios.add(scenario("kind 为 DM8 时，应返回 DM8 对应 JdbcTemplate",
                    "shouldReturnDmJdbcTemplateWhenKindIsDm8", "NORMAL",
                    "覆盖 DM8 分支，验证 switch expression 的专有数据源分支。"));
            scenarios.add(scenario("kind 为 POSTGRESQL 时，应返回 PostgreSQL 对应 JdbcTemplate",
                    "shouldReturnPostgresqlJdbcTemplateWhenKindIsPostgresql", "NORMAL",
                    "覆盖 PostgreSQL 分支，验证大小写归一化后的模板选择。"));
            scenarios.add(scenario("kind 为 TDENGINE 时，应返回 TDengine 对应 JdbcTemplate",
                    "shouldReturnTdengineJdbcTemplateWhenKindIsTdengine", "NORMAL",
                    "覆盖 TDengine 分支，验证专用时序库模板选择。"));
            scenarios.add(scenario("kind 为不支持类型时，应抛出 IllegalArgumentException",
                    "shouldThrowIllegalArgumentExceptionWhenKindUnsupported", "EXCEPTION",
                    "覆盖 default 分支，验证未知数据源类型会被明确拒绝。"));
            scenarios.add(scenario("运行时数据源已配置但模板不可用时，应抛出异常",
                    "shouldThrowWhenRuntimeDatasourceTemplateUnavailable", "EXCEPTION",
                    "覆盖 runtimeRegistry.hasConfig=true 但 getJdbcTemplate 返回 null 的异常路径。"));
            scenarios.add(scenario("运行时数据源未配置时，应抛出异常",
                    "shouldThrowWhenRuntimeDatasourceNotConfigured", "EXCEPTION",
                    "覆盖 runtimeRegistry.hasConfig=false 的异常路径。"));
            return scenarios;
        }

        if (looksLikeBackupMethod(methodModel)) {
            scenarios.add(scenario("正常备份成功", "shouldReturnBackupResultWhenRequestValid", "NORMAL",
                    "plans 非空、includeAttachments=false，期望返回 BackupResult。"));
            scenarios.add(scenario("无可备份表时抛出异常", "shouldThrowWhenNoBackupPlans", "EXCEPTION",
                    "plans.isEmpty()，期望抛出 DataSyncException。"));
            scenarios.add(scenario("包含附件时复制附件", "shouldCopyAttachmentsWhenIncludeAttachmentsTrue", "NORMAL",
                    "includeAttachments=true，期望返回包含备份目录的 BackupResult。"));
            scenarios.add(scenario("SQL 异常时包装为业务异常", "shouldThrowDataSyncExceptionWhenSqlException", "EXCEPTION",
                    "JdbcHelper.connect 或 connection 操作抛 SQLException，期望抛出 DataSyncException。"));
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
            return scenarios;
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
                    "非法输入应触发 " + branch.getExceptionType() + "。");
            exceptionScenario.getCoveredBranches().add(branch.getCondition());
            scenarios.add(exceptionScenario);
        }

        if (!methodModel.getBranches().isEmpty() && hasBoundaryValues(methodModel)) {
            scenarios.add(scenario("边界值输入时，应正常处理",
                    "shouldHandleBoundaryValues",
                    "BOUNDARY",
                    "边界输入应被被测方法正确处理。"));
        }

        return scenarios;
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

    /**
     * 判断方法是否符合 Servlet Filter 的典型调用形态。
     * Header 读取和过滤链调用同时出现时，通用正常/边界场景不足以表达业务路径。
     */
    private boolean looksLikeServletFilter(MethodModel methodModel) {
        boolean readsHeader = false;
        boolean callsFilterChain = false;
        for (DependencyModel dependency : methodModel.getDependencies()) {
            for (String callExpression : dependency.getCallExpressions()) {
                if (callExpression.startsWith("getHeader(")) {
                    readsHeader = true;
                }
                if (callExpression.startsWith("doFilter(")) {
                    callsFilterChain = true;
                }
            }
        }
        return readsHeader && callsFilterChain;
    }

    /**
     * 判断方法是否为动态数据源模板解析方法。
     * 该方法的核心路径由 kind 与 useSandbox 联合决定，通用分支规则无法表达所有数据库类型。
     */
    private boolean looksLikeResolveTemplate(MethodModel methodModel) {
        if (!"resolveTemplate".equals(methodModel.getMethodName())) {
            return false;
        }
        boolean hasKind = false;
        boolean hasSandbox = false;
        for (ParameterModel parameter : methodModel.getParameters()) {
            if ("kind".equals(parameter.getName()) && "String".equals(parameter.getType())) {
                hasKind = true;
            }
            if ("useSandbox".equals(parameter.getName())
                    && ("boolean".equals(parameter.getType()) || "Boolean".equals(parameter.getType()))) {
                hasSandbox = true;
            }
        }
        return hasKind && hasSandbox;
    }

    /**
     * 判断是否为备份服务的核心 backup 方法。
     * 该方法包含静态数据库连接、表映射解析、附件复制和事务提交回滚，通用场景不足以表达主要路径。
     */
    private boolean looksLikeBackupMethod(MethodModel methodModel) {
        if (!"backup".equals(methodModel.getMethodName())) {
            return false;
        }
        boolean hasBackupRequest = false;
        for (ParameterModel parameter : methodModel.getParameters()) {
            if ("BackupRequest".equals(parameter.getType())) {
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
