package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 封闭失败分类：确定性诊断码与 LLM 仲裁 rootCauseCategory 的唯一合法取值集合。
 * 成员名与既有诊断码字符串 1:1 相同，模型与诊断器两侧共享同一套取值；
 * 新增诊断码时必须同步新增枚举成员（FailureCategoryCoverageGuardTest 会拦截遗漏）。
 */
public enum FailureCategory {
    // --- 运行期类别：coverage/MockitoRuntimeDiagnosticEnricher ---
    MOCKITO_NO_INTERACTIONS_CONTRADICTION("无交互验证自相矛盾", RepairStrategy.DETERMINISTIC, "", false, true),
    ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN("期望异常未抛出", RepairStrategy.EVIDENCE_REQUIRED,
            "请确认该输入是否应抛出异常：给出异常类型、触发条件或阈值（引用需求或源码常量定义），"
                    + "并注明适用测试：{tests}。若确认不应抛出异常，请说明该场景的正确返回结果。", true, true),
    ASSERT_THROWS_WRAPPED_BY_REFLECTION("反射断言包装根因异常", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_INVOCATION_COUNT_MISMATCH("verify 调用次数不匹配", RepairStrategy.EVIDENCE_REQUIRED,
            "请补充该对外交互的期望调用次数及依据（接口契约、已有测试或需求），并注明适用测试：{tests}。", true, true),
    MOCKITO_VOID_STUB_MISUSE("doNothing 用于非 void 方法", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_ARGUMENT_MISMATCH("verify 参数不匹配", RepairStrategy.EVIDENCE_REQUIRED,
            "请补充该依赖应接收到的精确参数（或参数规则）及依据，并注明适用测试：{tests}。", true, true),
    MOCKITO_WANTED_BUT_NOT_INVOKED("目标交互未发生", RepairStrategy.DETERMINISTIC, "", true, true),
    MOCKITO_WRONG_RETURN_TYPE("stub 返回类型不匹配", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_INVALID_MATCHER_USAGE("matcher 用法非法", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_FIXTURE_WIRING_FAILURE("Mockito 依赖装配失效", RepairStrategy.DETERMINISTIC, "", true, true),
    TEST_INTERNAL_STATE_ORACLE("内部实现验证无效", RepairStrategy.EVIDENCE_REQUIRED,
            "请依据公开行为或已有契约重新设计验证；运行修复改变原 Oracle 仍需独立契约证据。", true, true),
    MOCKITO_FIXTURE_INITIALIZATION_FAILURE("Mockito Fixture 初始化失败", RepairStrategy.DETERMINISTIC, "", false, true),
    ASSERTION_VALUE_MISMATCH("断言期望值不一致", RepairStrategy.EVIDENCE_REQUIRED,
            "请补充该输入下的真实期望值及依据（需求文档片段、Issue 链接、已有测试或合同常量），"
                    + "并注明适用测试：{tests}。例：“订单满 100 元应打 95 折（来源：PRD-3.2）”。", true, true),
    ASSERTION_FAILURE("断言失败（无期望/实际值）", RepairStrategy.EVIDENCE_REQUIRED,
            "请补充该场景的业务期望结果及依据（需求、已有测试或接口契约），并注明适用测试：{tests}。", true, true),
    DOMAIN_PRECONDITION_FAILURE("业务前置条件不满足", RepairStrategy.DETERMINISTIC, "", false, true),
    NULL_FIXTURE_FAILURE("Fixture 空指针", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_MISSING_METHOD_INVOCATION("when 参数不是 mock 调用", RepairStrategy.DETERMINISTIC, "", false, true),
    // --- 运行期类别：coverage/MissingFixtureDependencyDetector / Slf4jMockVerificationGuard ---
    FIXTURE_DEPENDENCY_MISSING("构造依赖缺失", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_VERIFY_WRONG_INSTANCE("验证了错误的 Logger 实例", RepairStrategy.DETERMINISTIC, "", false, true),
    /** Mockito 运行时拒绝 mockStatic 该目标（final/不可 instrument 等），确定性测试侧错误。 */
    MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET("静态方法禁止 mock", RepairStrategy.DETERMINISTIC, "", false, true),
    // --- 运行期环境类（依赖繁多的真实项目高频；全部 runtimeArbitratable 以进入仲裁封闭列表） ---
    RUNTIME_DEPENDENCY_UNAVAILABLE("运行时依赖缺失或不可用", RepairStrategy.ENVIRONMENT,
            "测试运行时缺少依赖类或模块（NoClassDefFoundError/ClassNotFoundException/"
                    + "\"no suitable driver\"/缺少 JSR310 等可选模块）。"
                    + "请确认项目测试 classpath 包含该依赖及正确版本后重试；"
                    + "若被测代码必须依赖该环境才能运行，该用例不适合在当前环境自动生成。", false, true),
    RUNTIME_LINKAGE_VERSION_CONFLICT("依赖版本或链接冲突", RepairStrategy.ENVIRONMENT,
            "依赖版本冲突或链接失败（NoSuchMethodError/AbstractMethodError/IllegalAccessError/"
                    + "ServiceConfigurationError/Unsupported class file major version）。"
                    + "请检查测试 classpath 中同名依赖的版本一致性后重试。", false, true),
    RUNTIME_STATIC_INIT_FAILURE("静态初始化失败", RepairStrategy.ENVIRONMENT,
            "被测类或其依赖的静态初始化在测试环境失败（ExceptionInInitializerError/"
                    + "Could not initialize class）。请检查静态初始化依赖的系统属性、配置文件或"
                    + "外部资源在测试环境是否可用。", false, true),
    RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE("外部资源不可达", RepairStrategy.ENVIRONMENT,
            "测试执行期间访问了真实外部资源（数据库/网络/端口：ConnectException/"
                    + "SocketTimeoutException/SQLException 等）。请为相关协作者提供 mock/替身，"
                    + "或确认该用例需要在可达环境中执行。", false, true),
    SPRING_PROPERTY_BINDING_FAILURE("Spring 必需属性未绑定", RepairStrategy.DETERMINISTIC, "", false, true),
    FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE("框架上下文启动失败", RepairStrategy.ENVIRONMENT,
            "框架上下文（Spring/容器）启动失败（Failed to load ApplicationContext/"
                    + "BeanCreation/NoSuchBeanDefinition 等）。请检查配置、数据源与 Profile 是否"
                    + "适配测试环境，或为被测类改用非容器测试方式。", false, true),
    RUNTIME_RESOURCE_LIMIT_EXCEEDED("运行资源耗尽或超时", RepairStrategy.ENVIRONMENT,
            "运行资源耗尽或执行超时（OutOfMemoryError/StackOverflowError/TimeoutException）。"
                    + "请检查测试内存配置与潜在死循环后重试。", false, true),
    // --- 运行期确定性与数据绑定类 ---
    ORM_MAPPING_RUNTIME_FAILURE("ORM 映射或会话使用错误", RepairStrategy.DETERMINISTIC, "", false, true),
    DATA_BINDING_INPUT_INVALID("数据绑定输入无效", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_UNNECESSARY_STUBBING("存在多余 stub", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_STRICT_STUBBING_MISMATCH("严格 stub 参数不匹配", RepairStrategy.DETERMINISTIC, "", false, true),
    MOCKITO_MOCKING_UNAVAILABLE("目标不可 mock", RepairStrategy.DETERMINISTIC, "", false, true),
    TEST_RUNTIME_FAILURE("未分类运行失败", RepairStrategy.UNCLASSIFIED,
            "请补充失败测试的业务意图与期望结果（或真实业务异常与期望），以便重新归因（适用测试：{tests}）。",
            false, true),
    // --- 编译期：环境类（ProjectTestEnvironmentResolver / TestSandboxCompiler / 校验器） ---
    PROJECT_CONTEXT_MISSING("项目上下文缺失", RepairStrategy.ENVIRONMENT, "请在 IDE 中检查模块 SDK、JUnit 5 / Mockito 测试依赖与项目索引后重试。", false, false),
    INDEX_NOT_READY("项目索引未就绪", RepairStrategy.ENVIRONMENT, "请在 IDE 中检查模块 SDK、JUnit 5 / Mockito 测试依赖与项目索引后重试。", false, false),
    MODULE_NOT_FOUND("模块未找到", RepairStrategy.ENVIRONMENT, "请在 IDE 中检查模块 SDK、JUnit 5 / Mockito 测试依赖与项目索引后重试。", false, false),
    SDK_NOT_CONFIGURED("SDK 未配置", RepairStrategy.ENVIRONMENT, "请在 IDE 中检查模块 SDK、JUnit 5 / Mockito 测试依赖与项目索引后重试。", false, false),
    JAVA_EXECUTABLE_NOT_FOUND("Java 可执行文件缺失", RepairStrategy.ENVIRONMENT, "请在 IDE 中检查模块 SDK、JUnit 5 / Mockito 测试依赖与项目索引后重试。", false, false),
    JUNIT5_NOT_FOUND("JUnit 5 依赖缺失", RepairStrategy.ENVIRONMENT, "请在 IDE 中检查模块 SDK、JUnit 5 / Mockito 测试依赖与项目索引后重试。", false, false),
    BUNDLED_JUNIT_NOT_FOUND("插件内置 JUnit 缺失", RepairStrategy.ENVIRONMENT, "请重新安装完整插件包。", false, false),
    BUNDLED_MOCKITO_UNAVAILABLE("插件内置 Mockito 不可用", RepairStrategy.ENVIRONMENT, "请检查模块 JDK、插件安装包与 IDE 缓存目录权限。", false, false),
    MOCKITO_NOT_FOUND("Mockito 依赖缺失", RepairStrategy.ENVIRONMENT, "请在 IDE 中检查模块 SDK、JUnit 5 / Mockito 测试依赖与项目索引后重试。", false, false),
    RUNTIME_CONFIGURATION_UNAVAILABLE("项目测试运行配置不可用", RepairStrategy.ENVIRONMENT,
            "请检查项目 Maven/Gradle 配置导出；不能复用运行配置时不会按默认环境继续执行。", false, false),
    VERIFICATION_INPUT_CHANGED("验证期间执行环境已变化", RepairStrategy.ENVIRONMENT, "SDK、依赖或构建配置在验证期间发生变化。请重新分析并运行验证，不要修复测试断言来绕过。", false, false),
    TEMP_IO_FAILURE("沙箱临时目录 IO 失败", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    JDK_COMPILER_NOT_FOUND("模块 JDK 缺少 javac", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    SANDBOX_COMPILE_TIMEOUT("沙箱编译超时", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    TARGET_COMPILE_FAILED("被测源码编译失败", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    SANDBOX_BYTECODE_MISSING("沙箱字节码缺失", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    TARGET_COMPILE_INTERRUPTED("补编译被中断", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    TARGET_COMPILE_IO_FAILURE("补编译 IO 失败", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    VALIDATOR_FAILURE("校验器自身异常", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    MODULE_PATH_ERROR("模块路径错误", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    UNSUPPORTED_LANGUAGE_LEVEL("语言级别不受支持", RepairStrategy.ENVIRONMENT, "沙箱编译环境异常（JDK 编译器、临时目录或被测源编译失败）。请检查 JDK 配置与被测源可编译性后重试。", false, false),
    // --- 编译期：测试侧可修复（ProjectCompileVerifier / TestSandboxCompiler） ---
    EMPTY_SOURCE("测试源码为空", RepairStrategy.DETERMINISTIC, "", false, false),
    JAVA_SYNTAX("测试语法错误", RepairStrategy.DETERMINISTIC, "", false, false),
    TEST_PACKAGE_MISMATCH("测试包名不匹配", RepairStrategy.DETERMINISTIC, "", false, false),
    TEST_CLASS_NAME_MISMATCH("测试类名不匹配", RepairStrategy.DETERMINISTIC, "", false, false),
    MOCKITO_STATIC_MOCK_UNSUPPORTED("项目不支持静态 Mock", RepairStrategy.DETERMINISTIC, "", false, false),
    /** 候选代码引用 org.mockito 但项目 classpath 缺 Mockito——删 mock 改直构可修。 */
    MOCKITO_UNAVAILABLE("Mockito 依赖缺失", RepairStrategy.DETERMINISTIC, "", false, false),
    SANDBOX_JAVAC_FAILED("沙箱编译测试失败", RepairStrategy.DETERMINISTIC, "", false, false),
    CANNOT_FIND_SYMBOL("找不到符号", RepairStrategy.DETERMINISTIC, "", false, false),
    NO_SUITABLE_METHOD("无适用方法", RepairStrategy.DETERMINISTIC, "", false, false),
    INCOMPATIBLE_TYPES("类型不兼容", RepairStrategy.DETERMINISTIC, "", false, false),
    INACCESSIBLE_MEMBER("成员不可访问", RepairStrategy.DETERMINISTIC, "", false, false),
    AMBIGUOUS_REFERENCE("引用不明确", RepairStrategy.DETERMINISTIC, "", false, false),
    STATIC_CONTEXT("静态上下文错误", RepairStrategy.DETERMINISTIC, "", false, false),
    PACKAGE_NOT_FOUND("程序包不存在", RepairStrategy.DETERMINISTIC, "", false, false),
    JAVA_COMPILE("测试编译错误", RepairStrategy.DETERMINISTIC, "", false, false),
    FIXTURE_INITIALIZATION_CONFLICT("Fixture 初始化顺序冲突", RepairStrategy.DETERMINISTIC, "", false, false),
    // --- AST 预检：coverage/CoverageCandidatePreflight ---
    REFLECTION_ASSERT_THROWS_WRAPS_CAUSE("反射断言包装根因异常", RepairStrategy.DETERMINISTIC, "", false, false),
    MOCKITO_MATCHER_OUTSIDE_STUBBING("matcher 脱离 stubbing 上下文", RepairStrategy.DETERMINISTIC, "", false, false),
    MOCKITO_WHEN_WITHOUT_INVOCATION("when 缺少 mock 调用", RepairStrategy.DETERMINISTIC, "", false, false),
    MOCKITO_UNSTUBBABLE_OBJECT_METHOD("不可 stub 的 Object 方法", RepairStrategy.DETERMINISTIC, "", false, false),
    MOCKITO_WHEN_ON_REAL_OBJECT("when 作用于真实对象", RepairStrategy.DETERMINISTIC, "", false, false),
    MOCKITO_WHEN_ON_SPY("when 作用于 spy", RepairStrategy.DETERMINISTIC, "", false, false),
    REFLECTION_FIELD_ACCESS_UNSAFE("反射猜测私有字段", RepairStrategy.DETERMINISTIC, "", false, false),
    // --- 合成 rootCauseCategory（service 层） ---
    /** Kept for legacy report display; candidate rejection is not a runtime root cause. */
    UNSAFE_ORACLE_PATCH("补丁违反 Oracle 门禁", RepairStrategy.DETERMINISTIC, "", false, false),
    UNSUPPORTED_FIXTURE_CLASSIFICATION("Fixture 归类证据不足", RepairStrategy.UNCLASSIFIED,
            "模型未能证明 Fixture 缺失。请补充：真实异常栈中首个业务空指针位置、被测对象构造方式，"
                    + "或直接说明期望行为（适用测试：{tests}）。", false, true),
    // --- 兜底 ---
    UNKNOWN("未分类", RepairStrategy.UNCLASSIFIED,
            "请补充失败现象与业务期望，以便重新归因（适用测试：{tests}）。", false, false);

    private static final String TESTS_PLACEHOLDER = "{tests}";
    private static final String ALL_FAILING_TESTS = "本轮全部失败测试";

    private final String labelCn;
    private final RepairStrategy repairStrategy;
    private final String requiredUserInfo;
    /** 只镜像 UnitTestGenerationService.hasOracleSensitiveFailure 的语义，供展示与提示使用，不替代其判定。 */
    private final boolean oracleSensitive;
    /** 是否属于运行修复仲裁（rootCauseCategory）的合法取值子集。 */
    private final boolean runtimeArbitratable;

    FailureCategory(String labelCn,
                    RepairStrategy repairStrategy,
                    String requiredUserInfo,
                    boolean oracleSensitive,
                    boolean runtimeArbitratable) {
        this.labelCn = labelCn;
        this.repairStrategy = repairStrategy;
        this.requiredUserInfo = requiredUserInfo == null ? "" : requiredUserInfo;
        this.oracleSensitive = oracleSensitive;
        this.runtimeArbitratable = runtimeArbitratable;
    }

    /** 大小写/空白归一后解析；未知取值回退 UNKNOWN。 */
    public static FailureCategory fromCode(String code) {
        if (code == null) return UNKNOWN;
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return UNKNOWN;
        for (FailureCategory category : values()) {
            if (category.name().equals(normalized)) return category;
        }
        return UNKNOWN;
    }

    public static boolean isKnownCode(String code) {
        return code != null && fromCode(code) != UNKNOWN;
    }

    /** “中文标签(CODE)”，用于停止消息与诊断表展示。 */
    public String displayText() {
        return labelCn + "(" + name() + ")";
    }

    /** 用失败测试名单填充 requiredUserInfo 中的 {tests} 占位符；模板为空时返回空串。 */
    public String formatRequiredUserInfo(List<String> failingTests) {
        if (requiredUserInfo.isEmpty()) return "";
        String tests = failingTests == null || failingTests.isEmpty()
                ? ALL_FAILING_TESTS : String.join("、", failingTests);
        return requiredUserInfo.replace(TESTS_PLACEHOLDER, tests);
    }

    /** 运行修复仲裁（rootCauseCategory）的封闭取值子集，提示词与门禁共用，防止两处漂移。 */
    public static List<String> runtimeArbitrationCodes() {
        List<String> codes = new ArrayList<>();
        for (FailureCategory category : values()) {
            if (category.runtimeArbitratable) codes.add(category.name());
        }
        return Collections.unmodifiableList(codes);
    }

    public String getLabelCn() { return labelCn; }
    public RepairStrategy getRepairStrategy() { return repairStrategy; }
    public String getRequiredUserInfo() { return requiredUserInfo; }
    public boolean isOracleSensitive() { return oracleSensitive; }

    /** 是否属于运行修复仲裁（rootCauseCategory）的合法取值子集。 */
    public boolean isRuntimeArbitratable() { return runtimeArbitratable; }
}
