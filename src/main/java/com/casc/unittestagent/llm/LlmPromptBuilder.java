package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ConstraintModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.FailureCategory;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.TypeSymbol;
import com.casc.unittestagent.model.CoverageGap;
import com.casc.unittestagent.model.CoverageReport;
import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.GenerationScope;
import com.casc.unittestagent.model.OracleAssessment;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 构造发送给 OpenAI-compatible 接口的提示词。
 */
public class LlmPromptBuilder {
    public String systemPrompt() {
        return "你是资深 Java 单元测试工程师。根据结构化静态分析生成可运行的 JUnit 5 测试。"
                + "Mockito 不是必选项；必须遵守 context.mockUsagePolicy 和用户已选策略，禁止为了“使用 Mockito”而创建无必要的 mock。"
                + "断言依据优先级为：用户需求 > 已有测试 > 被测源码 > 高可信静态分析；"
                + "LOW 可信场景名不是精确契约，不得据此编造返回值、异常或 verify 次数。"
                + "交互验证只能在源码或已有测试明确证明调用本身是契约时使用；其余情况优先验证返回值、对外状态或精确异常。"
                + "生产类使用 @Slf4j/static logger 时，禁止创建 @Mock Logger 并 verify 日志调用；该 mock 与生产 logger 不是同一实例。"
                + "禁止创造 projectSymbolSnapshot 中不存在的项目符号；但已在被测源码、import、currentTestCode 或 existingTestExamples 中出现的符号可视为有效证据。"
                + testDoubleTypeRules() + mockWiringRules() + springFixtureRules();
    }

    private String testDoubleTypeRules() {
        return "手写测试替身必须遵守真实类型契约：implements 仅用于 interface，class 只能在非 final 且构造器可用时 extends；"
                + "实现接口时必须根据真实 API 实现全部继承的抽象方法，@Override 的返回类型、参数和 throws 必须与真实签名兼容。"
                + "符号快照中的方法列表可能不完整，不能据此猜写整个接口；API 不完整时使用已允许的 mock 或已知真实适配器。"
                + "编译诊断明确说明生产源码已独立通过时，只修复候选测试，禁止为级联错误给生产类补造字段或修改其类型。";
    }

    private String springFixtureRules() {
        return "\nSpring 属性与生命周期规则：context.springPropertyBindings 给出生产源码中真实 @Value 表达式、类型、属性键及无默认值的键；@ConfigurationProperties 列出简单字段的规范键，复杂嵌套/集合需结合源码，不能猜测。"
                + "严格使用真实属性键，不得依据类名/字段名猜前缀。@ConfigurationProperties 必须使用项目支持的注册/绑定方式（如 EnableConfigurationProperties），不能用无效的 @Value 空方法替代绑定。保留默认值及 SpEL 语义，Boolean/数值属性需可转换；"
                + "外部 application 配置可能已提供属性，不得把缺少本地声明直接当作环境缺失。"
                + "构造器或可见 API 可达时优先纯单元测试；必须处理私有 @Value 时可使用只注册必要 Bean 的最小 Spring context，"
                + "使用真实 PropertySource、属性解析/注入后处理器并显式关闭上下文。不要仅为绕过反射门禁启动整个 SpringBootTest。"
                + "容器测试必须通过 @Autowired/context.getBean 获取被测对象，不得在字段初始化、setUp 或测试中重新 new 覆盖它；"
                + "@Bean 工厂返回的对象可由容器继续初始化。禁止 @Value 空 setter 和未连接到被测对象的伪注入。"
                + "sharedContextFailure=true 表示共享初始化失败，后续 failure threshold 是关联症状，不是业务前置条件失败；"
                + "查看 primaryFailureTestId、rootCauseMessage 和 causeDetails，缺少首次根因时明确证据不足，不得编造原因或删除受影响用例。"
                + "共享初始化修复只允许修改可证明错误的装配/属性绑定，全部测试正文、业务输入和 Oracle 必须保留。\n";
    }

    private String mockWiringRules() {
        return "先检查 mock 对象图：每个 stub/verify 的 mock 必须能沿真实构造参数、可注入字段或方法参数接入被测对象。"
                + "@InjectMocks 初始化后不得重新 new 覆盖被测对象并丢失注入；若选择手动构造，必须显式传入全部依赖。"
                + "生产类自行初始化的 private final/static 容器属于内部状态，不能声明同名 @Mock 就假定替换成功；"
                + "不要反射替换它们，应通过公开入口建立状态并验证返回值、对外状态或异常。"
                + "禁止对真实对象及其真实返回值使用 verify；不要把 getter 调用次数、私有容器操作顺序当作业务契约，除非有独立契约证据。"
                + "运行诊断的 mockWiringEvidence 是绑定当前源码的事实；零交互本身不能证明业务缺陷、空参数或另一实例被使用。"
                + "MOCKITO_FIXTURE_WIRING_FAILURE 仅允许删除证据指定的覆盖赋值，保持所有测试正文、输入、stub 和 Oracle；"
                + "TEST_INTERNAL_STATE_ORACLE 需要重新设计验证，禁止以 KEEP 偷换 Oracle，改变已有运行失败用例的 Oracle 仍须独立权威授权。";
    }

    public String userPrompt(LlmGenerationRequest request) {
        JSONObject context = context(request);
        String classModeRules = request.getGenerationScope() == GenerationScope.CLASS
                ? "33. 当 generationScope=CLASS 时，只为 targetMethodId 生成测试；测试方法名必须包含被测方法名。\n"
                + "34. 类模式候选中不得生成 @BeforeEach、@AfterEach、@BeforeAll 或 @AfterAll；方法专属初始化必须放在测试方法内。\n"
                + "35. 统一被测对象字段名为 sharedFixtureSummary 中给出的名称；方法参数依赖仅在已选策略允许时才可生成局部 mock，否则构造真实对象。\n"
                + "36. staticMethod=true 时必须使用 ClassName.method(...) 静态调用，不得为该方法生成被测对象字段。\n"
                + "37. 类模式的实例方法候选只能使用 sharedFixtureSummary 中的统一 @InjectMocks 字段；不得在字段初始化器、测试方法或 helper 中 new 被测类，不得重新赋值该字段。\n"
                + "38. @Mock 字段必须对应 context.classFields 中的真实字段依赖，不得改名或额外创造未知字段。\n"
                : "";
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出 JSON 格式必须为：{\"testCode\":\"完整 Java 测试类代码\",\"warnings\":[\"可选警告\"]}。Mock 策略已由用户确定，不得重新选择或返回新策略。\n"
                + "测试代码要求：\n"
                + "1. 使用 JUnit5。\n"
                + "2. 严格遵守 context.mockUsagePolicy：FORBIDDEN 时禁止新建 mock/spy/verify；SELECTED_ONLY 时只能使用 userInstruction 中 enabled=true 的已选策略。\n"
                + "3. 不要生成中文测试方法名。\n"
                + "4. 测试方法名使用英文。\n"
                + "5. DTO / Request / Entity / Profile / Path / File / Directory 等对象不要默认生成测试类字段级 @Mock；优先构造真实对象或使用 @TempDir。\n"
                + "6. 仅当 mockUsagePolicy=SELECTED_ONLY 且已选策略明确允许时，DTO / Request / Entity / Profile 构造困难才可在测试方法内使用局部 Mockito.mock(...)。\n"
                + "7. 局部 mock 只能来自已选 localMock 策略，且不得提升为新的测试类字段。\n"
                + "8. Repository / Mapper / Dao / Client / Service / JdbcTemplate / RedisTemplate / RestTemplate / WebClient / 外部系统接口 / 框架接口只有在已选策略明确指定时才可生成字段级 @Mock。\n"
                + "9. 只有已选策略明确允许且 context.mockitoCapabilities.staticMockingSupported=true 时才允许使用 mockStatic/MockedStatic；否则必须真实执行无副作用静态方法，或改用可注入依赖，不得建议修改 pom/Gradle。\n"
                + "10. 私有方法不要直接 stub；如果需要覆盖 private 方法路径，应通过调用公开入口或反射调用被测方法。\n"
                + "11. 测试代码必须是完整 Java 测试类。\n"
                + "11a. 使用 @Mock/@Spy/@Captor/@InjectMocks 时必须导入对应 org.mockito 注解。调用声明了受检异常的 API 时，"
                + "Mockito 的 when/verify/doReturn 等也受 Java throws 规则约束；在所属测试或生命周期方法声明真实异常，必要时 throws java.lang.Exception，禁止吞异常或删除验证。\n"
                + "11b. Mockito 同一次 stub/verify 调用使用了 matcher 时，其他参数也必须用保留原值的 eq(...) 等匹配器；不要把明确值替换成 any()。\n"
                + "12. 不允许返回空代码。\n"
                + "13. 如果 userInstruction 非空，请在不违反静态分析约束的前提下优先满足用户补充要求。\n"
                + "14. 如果 existingTestCodeSummary 非空，请参考现有测试代码并整体重新输出完整测试类，不要只输出 diff。\n"
                + "15. usages 表示项目索引或当前源码文件中发现的被测方法调用点，可用于理解真实调用方式；LOW 置信度的调用点只作为参考，不要强行据此生成断言。\n"
                + "16. 禁止编造项目中不存在的类型名；只能使用 context.knownTypes、源码 import、java.lang、JUnit5、Mockito、JDK 常用类型，或测试代码中明确声明的类型。\n"
                + "17. 对链式调用，例如 request.config().resolveValue(...)：如果 context.chainedCallReturnTypes 提供了 config() 的真实返回类型，必须使用真实类型；只有已选策略允许 mock 时才可使用 RETURNS_DEEP_STUBS，且不得猜测未知返回类型。\n"
                + "18. 已选策略明确要求字段级深层 stub 时，必须写 @Mock(answer = Answers.RETURNS_DEEP_STUBS)；禁止生成 @Mock(answer = RETURNS_DEEP_STUBS)。\n"
                + "19. 不要生成 Xxx.InnerConfig 这类内部类，除非源码或 knownTypes 明确存在这个内部类。\n"
                + "20. 如果 accessModifier=private，测试方法不能直接调用被测方法，必须生成 java.lang.reflect.Method/getDeclaredMethod 反射辅助方法，或者只通过 public 方法间接测试。\n"
                + "21. 文件复制、文件存在性、Path 安全校验默认使用 @TempDir 和真实 Path/Files 临时文件；不要默认 mock Files、Paths，也不要 mock Path 目录变量。\n"
                + "22. FileUtils.copy 优先真实临时文件复制；仅当已选策略要求且静态 Mock 能力已证明可用时才 mockStatic(FileUtils.class)。\n"
                + "23. 不要生成空的 @AfterEach tearDown；try-with-resources 已管理静态 mock 时不需要 tearDown。\n"
                + "24. 生成异常分支或边界分支测试时，必须先满足 branches.preconditions 和目标分支之前的 safeCondition，不能让测试提前失败在无关逻辑上。\n"
                + "25. 如果 callContexts 中显示参数对象 getter、record accessor、字段读取或链式方法被当前方法或内部调用链使用，必须补齐这些 mock 返回值或构造真实对象；无法确认字段时生成 TODO，不要输出看似完整但会 NPE 的代码。\n"
                + "25a. 被测类构造函数或 Lombok required-args 构造器中的依赖必须全部提供。使用 @InjectMocks 时，"
                + "每个实际执行路径会访问的构造依赖都必须有类型匹配的 @Mock/真实对象；不得让 Mockito 以 null 填充缺失依赖。\n"
                + "25b. 上述要求也适用于真实协作对象：沿 callContexts/nestedCalls 检查 Service/Repository 等内部调用链，"
                + "不得以 new Collaborator(null) 占位已知会被访问的构造依赖；按已选策略提供完整真实依赖或允许的 mock。\n"
                + "25c. 初始化顺序必须一致：MockitoExtension 先于 @BeforeEach 处理 @Spy。仅有有参构造器的类型不得声明未初始化的 @Spy 字段再期望 @BeforeEach 补救；"
                + "应在依赖已初始化后手动构造并按策略显式 spy，保持原测试意图；字段初始化器中不要提前读取尚未初始化的 @Mock。\n"
                + "26. callContexts.INTERNAL_METHOD_CALL 的 sourceCode 是当前方法直接调用的一层本类内部方法源码；nestedCalls 至少说明该内部方法继续调用的项目方法签名或静态调用摘要，生成测试时必须考虑这些真实执行路径。\n"
                + "27. scenarios.oracle.confidence=LOW 或 expectedAssertions 为空时，场景名只表示覆盖目标，不是精确 Oracle。必须先从 existingTestExamples 或源码确定可观测契约；证据不足时保留 TODO/warnings，不得编造精确值或交互次数。\n"
                + "28. 优先使用真实对象和 public/package-visible 可观测行为；verify/times(n) 仅在源码或已有测试明确支持该交互契约时使用。\n"
                + "29. 对空附件路径、空集合或空结果场景，期望值应按真实语义生成 0 或 empty，不要把 0 写成 1。\n"
                + "30. projectSymbolSnapshot 是 IntelliJ PSI 对当前项目的真实解析结果。项目类型的构造函数、方法和重载必须严格从该快照选择，禁止仅根据常见命名编造 getter、builder、factory 或 repository 方法。\n"
                + "30a. projectSymbolSnapshot.types 中 abstract=true 或 kind=interface 的类型不得直接 new/匿名实现；优先从 concreteSubtypes 选择可构造子类。record 必须使用 constructors 中给出的完整 canonical constructor 参数。\n"
                + "31. projectSymbolSnapshot.unresolvedSymbols 中的表达式不得猜测返回类型或替代方法；无法生成可靠代码时添加 TODO，并采用不依赖该未知符号的最小测试结构。\n"
                + "32. Mockito.when(...) 只能包裹对 @Mock/@Spy/Mockito.mock 创建对象的方法调用；"
                + "不得 stub @InjectMocks 被测对象、new 出来的真实对象、静态方法、private/final/native/equals/hashCode。\n"
                + "32a. 若生产源码使用 @Slf4j 或 static Logger，禁止生成 @Mock Logger、mock(Logger.class) 或 verify(logger).info/error/warn/debug/trace；"
                + "日志不是默认业务 Oracle，应验证返回值、状态或真正可注入的业务协作者。只有用户需求明确把日志定义为契约时，才可使用真实日志捕获器验证。\n"
                + classModeRules
                + "以下是 UnitMind 静态分析上下文，参数约束必须以其中 constraints 和 branches 为准：\n"
                + context.toString(2);
    }

    public String repairSystemPrompt() {
        return "你是 Java 单元测试编译错误修复器。只能修复给定测试类，不能修改被测代码，"
                + "不能创造无任何证据的项目类型、方法、构造函数或重载；"
                + "项目符号快照、被测源码、import、currentTestCode 和 existingTestExamples 都可作为真实符号证据。"
                + "必须根据 compilerDiagnostics 的行列和错误信息进行最小修改，并返回完整测试类。"
                + "整类测试修复时必须保留 // UnitMind method: <methodId> 标记和其他方法已通过的测试。"
                + "当 MOCKITO_STATIC_MOCK_UNSUPPORTED 出现时，必须删除 mockStatic/MockedStatic 并改为真实调用或普通 mock，不得修改项目依赖。"
                + "UNREPORTED_EXCEPTION 表示未声明的受检异常；即使调用位于 Mockito when/verify 中也必须处理。"
                + "优先为所属测试/生命周期方法添加 throws 声明，保留已有 throws、原断言和验证，不得吞异常、改成 assertThrows 或删除调用。"
                + "FIXTURE_CONSTRUCTOR_DEPENDENCY 是生成阶段的构造依赖检查：按真实类型、作用域和初始化顺序补齐非空依赖，"
                + "保持原断言及 Mock 策略；不要用 null 占位、反射注入或把失败改成期望异常。"
                + "FIXTURE_INITIALIZATION_CONFLICT 表示 Mockito 与手动初始化的顺序冲突，修复字段注解及生命周期，保持测试方法正文不变；不要修改生产构造函数。"
                + testDoubleTypeRules() + mockWiringRules() + springFixtureRules();
    }

    public String repairPrompt(LlmGenerationRequest request) {
        JSONObject repair = new JSONObject();
        JSONObject repairContext = context(request);
        repairContext.remove("existingTestCodeSummary");
        repair.put("repairAttempt", request.getRepairAttempt());
        repair.put("compilerDiagnostics", compilerDiagnostics(request));
        repair.put("context", repairContext);
        repair.put("currentTestCode", boundedText(request.getExistingTestCodeSummary(), MAX_TEST_CODE_CHARS));
        repair.put("userInstruction", nullToEmpty(request.getUserInstruction()));
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出格式：{\"diagnosis\":{\"diagnosticCode\":\"\",\"evidence\":[]},"
                + "\"edits\":[{\"target\":\"\",\"reason\":\"\"}],\"testCode\":\"修复后的完整 Java 测试类\",\"warnings\":[]}。\n"
                + "要求：只修复编译错误；保留原测试意图；不得删除测试来规避错误；"
                + "严格遵守 context.mockUsagePolicy；项目 API 必须有 context 中的符号快照、源码、import、当前测试或已有测试样例证据；"
                + "如果诊断与项目环境有关且无法通过修改测试解决，保持代码并在 warnings 中说明。\n"
                + repair.toString(2);
    }

    public String coverageSystemPrompt() {
        return "你是 Java 单元测试候选转换器。一次请求只执行 payload.taskMode 指定的一种任务。"
                + "只能修改给定测试类，禁止修改被测代码，禁止删除测试、@Disabled、空断言、捕获并忽略异常。"
                + "不得编造项目 API。已在项目符号快照、被测源码、import、currentTestCode 或 existingTestExamples 中出现的符号可作为有效证据。"
                + "运行失败只是事实证据，不自动成为新的期望行为。" + testDoubleTypeRules() + mockWiringRules() + springFixtureRules();
    }

    public String coveragePrompt(LlmGenerationRequest request) {
        JSONObject payload = new JSONObject();
        String taskMode = coverageTaskMode(request);
        JSONObject focusedContext = context(request);
        // currentTestCode is already a top-level field below. Keeping another full copy inside
        // context doubles the largest part of every repair request and dilutes coverage feedback.
        focusedContext.remove("existingTestCodeSummary");
        focusedContext.put("methodSourceCode", coverageFocusSource(request));
        if ("RUNTIME_REPAIR".equals(taskMode)) {
            // Method-only source omits class constants and field initializers that decide whether a
            // guard is reachable. Runtime adjudication needs the complete production class so the
            // model cannot guess values such as MAX_CONCURRENT_PROCESSES.
            focusedContext.put("classSourceCode", boundedText(request.getClassSourceCode(), MAX_CLASS_SOURCE_CHARS));
        }
        // ProjectSymbolSnapshotBuilder 已将快照限制为最多 40 个类型/每类型 40 个成员。
        // 修复阶段再截断会与“禁止使用快照外 API”形成无解冲突，因此发送已有快照全量。
        focusedContext.put("projectSymbolSnapshot", symbolSnapshot(request.getSymbolSnapshot()));
        payload.put("context", focusedContext);
        payload.put("currentTestCode", boundedText(request.getExistingTestCodeSummary(), MAX_TEST_CODE_CHARS));
        payload.put("coverage", coverage(request.getCoverageReport()));
        // A candidate that does not compile has no current runtime result. Never ask the model to
        // solve stale compile and runtime failures in the same response.
        payload.put("runtimeDiagnostics", "RUNTIME_REPAIR".equals(taskMode)
                ? runtimeDiagnostics(request) : new JSONArray());
        payload.put("compilerDiagnostics", "COMPILE_REPAIR".equals(taskMode)
                ? compilerDiagnostics(request) : new JSONArray());
        payload.put("iteration", request.getRepairAttempt());
        payload.put("taskMode", taskMode);
        payload.put("repairScope", new JSONObject()
                .put("focusTestIds", request.getFocusTestIds())
                .put("allFailingTestIds", request.getFailingTestIds())
                .put("passedTestIds", request.getPassedTestIds()));
        payload.put("oracleEvidence", oracleEvidence(request));
        payload.put("previousAttempt", nullToEmpty(request.getPreviousAttemptSummary()));
        String outputSchema = "RUNTIME_REPAIR".equals(taskMode)
                ? "输出格式：{\"diagnosis\":{\"rootCauseCategory\":\"必须取自规则 R3c 的封闭分类列表\",\"evidence\":[\"可定位的独立证据\"],"
                + "\"oracleDecision\":\"KEEP|CORRECT\",\"oracleAuthority\":\"USER_REQUIREMENT|EXISTING_TEST|CONTRACT_FACT|PRODUCTION_SOURCE|SCENARIO|RUNTIME_ACTUAL|NONE\","
                + "\"oracleAuthorityRef\":\"oracleEvidence.authorityItems 中的精确 ref；无权威证据时为 NONE\","
                + "\"repairDisposition\":\"REPAIR_TEST|SUSPECTED_SUT_BUG|INCONCLUSIVE\"},"
                + "\"edits\":[{\"targetMethod\":\"失败测试方法名\",\"reason\":\"\",\"expectedEffect\":\"\"}],"
                + "\"methodPatches\":[{\"methodName\":\"失败测试方法名\",\"methodCode\":\"带@Test的完整方法声明\"}],\"warnings\":[]}。\n"
                : "输出格式：{\"diagnosis\":{\"rootCauseCategory\":\"\",\"evidence\":[],\"oracleDecision\":\"NOT_APPLICABLE\"},"
                + "\"edits\":[{\"targetMethod\":\"\",\"reason\":\"\",\"expectedEffect\":\"\"}],"
                + "\"testCode\":\"改进后的完整 Java 测试类\",\"warnings\":[]}。\n";
        return "请只返回 JSON，不要返回 Markdown。\n"
                + outputSchema
                + coverageCommonRules(taskMode)
                + coverageModeRules(request)
                + "\n以下 JSON 是本轮唯一有效事实上下文：\n"
                + payload.toString(2);
    }

    public String classRepairSystemPrompt() {
        return systemPrompt()
                + "你正在修复一个已成功编译但完整 JUnit 回归失败的最终测试类。"
                + "必须保护已通过测试。生产源码和运行诊断只能说明当前实现与路径；"
                + "只有可验证的用户需求、已有测试或独立合同事实可以授权修改 Oracle。";
    }

    public String classRepairPrompt(LlmGenerationRequest request) {
        JSONObject payload = new JSONObject();
        JSONObject focusedContext = context(request);
        focusedContext.remove("existingTestCodeSummary");
        focusedContext.put("classSourceCode", boundedText(request.getClassSourceCode(), MAX_CLASS_SOURCE_CHARS));
        payload.put("context", focusedContext);
        payload.put("currentTestCode", boundedText(request.getExistingTestCodeSummary(), MAX_TEST_CODE_CHARS));
        payload.put("runtimeDiagnostics", runtimeDiagnostics(request));
        payload.put("iteration", request.getRepairAttempt());
        payload.put("focusTestIds", request.getFocusTestIds());
        payload.put("allFailingTestIds", request.getFailingTestIds());
        payload.put("passedTestIds", request.getPassedTestIds());
        payload.put("oracleEvidence", oracleEvidence(request));
        payload.put("previousAttempt", nullToEmpty(request.getPreviousAttemptSummary()));
        payload.put("userInstruction", nullToEmpty(request.getUserInstruction()));
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出格式：{\"repairScope\":\"METHOD_ONLY|SHARED_FIXTURE\","
                + "\"diagnosis\":{\"rootCauseCategory\":\"必须取自规则 13 的封闭分类列表\",\"evidence\":[\"可定位的独立证据\"],\"oracleDecision\":\"KEEP|CORRECT\","
                + "\"oracleAuthority\":\"USER_REQUIREMENT|EXISTING_TEST|CONTRACT_FACT|PRODUCTION_SOURCE|SCENARIO|RUNTIME_ACTUAL|NONE\","
                + "\"oracleAuthorityRef\":\"oracleEvidence.authorityItems 中的精确 ref；无权威证据时为 NONE\","
                + "\"repairDisposition\":\"REPAIR_TEST|SUSPECTED_SUT_BUG|INCONCLUSIVE\"},"
                + "\"edits\":[{\"targetMethod\":\"\",\"reason\":\"\",\"expectedEffect\":\"\"}],"
                + "\"testCode\":\"修复后的完整 Java 测试类\",\"warnings\":[]}。\n"
                + "规则：\n"
                + "1. repairDisposition=REPAIR_TEST 时必须返回完整测试类，类名、package 和现有测试方法集合保持不变。\n"
                + "2. passedTestIds 对应的测试已经真实通过，严禁修改、删除、改名或禁用。\n"
                + "3. 局部输入、断言、stub 或 verify 错误选择 METHOD_ONLY；共享字段、@BeforeEach、@InjectMocks/@Mock 或 helper 错误选择 SHARED_FIXTURE。\n"
                + "3s. 分类 SPRING_PROPERTY_BINDING_FAILURE 且 sharedContextFailure=true、lifecycleEvidence 有源码属性声明时，使用 SHARED_FIXTURE + REPAIR_TEST + KEEP + NONE；只允许向 TestPropertySource(properties=...) 追加被证明缺失的真实键，保留已有属性、所有测试正文和配置。其他共享上下文失败缺少可证明的修复范围时必须 INCONCLUSIVE。\n"
                + "3b. runtimeDiagnostics 中 failurePhase=TEST_SETUP 且分类 MOCKITO_FIXTURE_INITIALIZATION_FAILURE，"
                + "已由真实异常和当前测试字段声明证明初始化失败，必须选择 SHARED_FIXTURE + REPAIR_TEST + KEEP + NONE。"
                + "测试方法尚未执行，只能调整字段/生命周期/构造顺序；所有测试方法正文必须保持不变，不需要业务 Oracle 证据或 NullPointerException。"
                + "保留原本需要的 Spy 语义：依赖初始化后显式 Mockito.spy(new Type(...))；不得修改生产源码或为其增加无参构造器。\n"
                + "3a. 本请求一次只仲裁 focusTestIds 中的一个失败测试。runtimeDiagnostics 只包含该测试的证据；"
                + "allFailingTestIds 中其他失败不得修改、不得混入 diagnosis，它们会在后续请求中独立处理。\n"
                + "4. 生产源码可用于路径定位，但不得单独用于修正生成断言；"
                + "不得把正常场景的意外异常改成 assertThrows，不得删除或弱化测试以伪造成功。\n"
                + "5. 不得添加 @Disabled、无条件 assumption、吞异常的 try/catch 或空测试；不得修改生产源码。\n"
                + "6. Mockito stubbing 必须与真实调用参数一致；UnnecessaryStubbingException 应删除对应测试内无用 stub，不要全局改成 lenient。\n"
                + "7. SHARED_FIXTURE 修改后仍必须保证所有 passedTestIds 全量回归通过。\n"
                + "8. repairDisposition=SUSPECTED_SUT_BUG 或 INCONCLUSIVE 时，必须省略 testCode 且 edits 为空，保留失败测试等待用户处理。\n"
                + "9. repairDisposition=REPAIR_TEST 时必须返回 testCode。oracleDecision=KEEP 不得修改断言/verify/expected exception 语义。"
                + "oracleDecision=CORRECT 只能在 oracleAuthority 为 USER_REQUIREMENT、EXISTING_TEST 或 CONTRACT_FACT 时修改 Oracle；"
                + "PRODUCTION_SOURCE 和 RUNTIME_ACTUAL 都不是独立契约，不得据此把当前错误实现写成新期望。\n"
                + "10. oracleAuthorityRef 必须精确引用 oracleEvidence.authorityItems 中同类型 ref。不得自己编造 ref 或将修复指令冒充用户业务需求。"
                + "authorityItems 中 type=USER_REQUIREMENT 且 content 标注“适用于失败测试：…”的证据，只对列出的失败测试有效，"
                + "不得据此修改其他测试的 Oracle。\n"
                + "11. MOCKITO_VERIFY_WRONG_INSTANCE 表示测试验证了不可注入的 @Mock Logger，而生产代码使用 @Slf4j/static logger。"
                + "不得尝试通过 matcher、times 或 Arrange 修复；若还有业务断言，删除该无效日志 verify；若它是唯一 Oracle，保留失败并说明应改用业务断言或真实日志捕获器。\n"
                + "12. FIXTURE_DEPENDENCY_MISSING 只有在 runtimeDiagnostics 已明确给出该分类时才可使用。"
                + "选择 SHARED_FIXTURE，补充缺失的构造依赖 @Mock/真实对象并确保 @InjectMocks 或显式构造实际注入；"
                + "只修复 fixture，必须返回 oracleDecision=KEEP、oracleAuthority=NONE、oracleAuthorityRef=NONE，严禁修改任何原断言或 verify。\n"
                + "12a. 运行期环境类失败（RUNTIME_DEPENDENCY_UNAVAILABLE / RUNTIME_LINKAGE_VERSION_CONFLICT / "
                + "RUNTIME_STATIC_INIT_FAILURE / RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE / FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE / "
                + "RUNTIME_RESOURCE_LIMIT_EXCEEDED）：根因在测试代码之外，不是 Oracle 证据问题。"
                + "rootCauseCategory 原样保留环境分类；能为外部协作者补 mock/替身时返回 REPAIR_TEST+KEEP 修复 Arrange；"
                + "无法补救时返回 INCONCLUSIVE 并保留环境分类，不得当作证据冲突。\n"
                + "13. rootCauseCategory 只能从以下封闭分类中选取："
                + String.join("、", FailureCategory.runtimeArbitrationCodes())
                + "；此外可原样引用 runtimeDiagnostics.category 中已出现的值。不得发明新分类。\n"
                + "以下 JSON 是本轮唯一有效事实上下文：\n" + payload.toString(2);
    }

    /** 疑似生产代码缺陷终态后的只读归因协议。 */
    public String sourceBugAnalysisSystemPrompt() {
        return "你是 Java 失败路径与生产代码缺陷归因分析器。严格门禁已经决定保留失败测试；"
                + "你的任务是在给定源码、契约证据和真实运行诊断中，"
                + "比较测试路径未命中（常量边界、stub 值、调用链）与生产代码缺陷两类可能原因。"
                + "不得修改、重写或建议弱化测试，不得输出补丁、代码块、diff、testCode、methodPatches 或 edits。"
                + "结论必须保持为可核查的假设，不能把模型判断描述成已确认事实。";
    }

    public String sourceBugAnalysisPrompt(LlmGenerationRequest request, OracleAssessment assessment) {
        JSONObject payload = new JSONObject();
        JSONObject focusedContext = context(request);
        focusedContext.remove("existingTestCodeSummary");
        payload.put("context", focusedContext);
        payload.put("sourceCode", !nullToEmpty(request.getClassSourceCode()).isBlank()
                ? request.getClassSourceCode() : nullToEmpty(request.getMethodSourceCode()));
        payload.put("currentFailingTestCode", nullToEmpty(request.getExistingTestCodeSummary()));
        payload.put("runtimeDiagnostics", runtimeDiagnostics(request));
        payload.put("oracleEvidence", oracleEvidence(request));
        JSONObject oracle = new JSONObject();
        if (assessment != null) {
            oracle.put("disposition", assessment.getDisposition().name());
            oracle.put("decision", assessment.getDecision().name());
            oracle.put("authority", assessment.getAuthority().name());
            oracle.put("authorityRef", assessment.getAuthorityRef());
            oracle.put("rootCauseCategory", assessment.getRootCauseCategory());
            oracle.put("evidence", assessment.getEvidence());
        }
        payload.put("oracleAssessment", oracle);
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出格式：{\"summary\":\"一句话说明最可能原因，并标明更偏向测试路径设置还是生产源码\","
                + "\"confidence\":\"HIGH|MEDIUM|LOW\","
                + "\"suspectedLocations\":[{\"file\":\"源码文件或类名\",\"line\":1,"
                + "\"symbol\":\"方法或符号\",\"reason\":\"为什么这里可疑\"}],"
                + "\"hypotheses\":[\"按可能性排序的根因假设\"],"
                + "\"evidence\":[\"逐条引用输入中的契约、诊断、期望/实际值或栈帧\"],"
                + "\"recommendedChecks\":[\"无需修改测试即可执行的人工核查步骤\"]}。\n"
                + "规则：\n"
                + "1. 这是门禁之后的只读解释步骤，不得重新裁决或改变 oracleAssessment 终态。\n"
                + "2. 只能依据 payload 中可见事实；行号不确定时用 -1，禁止虚构文件、行号或调用链。\n"
                + "3. 明确区分独立契约期望、运行实际值和推断；至少给出一条证据及一条可执行核查建议。\n"
                + "3a. 对 ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN，必须显式引用 sourceCode 中定义阈值的常量声明、"
                + "完整分支谓词和 currentFailingTestCode 中的实际 stub/输入值；任一缺失时必须降低置信度。\n"
                + "4. 不得建议删除、禁用、弱化或改写当前失败测试，不得返回任何可应用的代码修改字段。\n"
                + "5. suspectedLocations、hypotheses 各最多 5 项，evidence、recommendedChecks 各最多 8 项。\n"
                + "以下 JSON 是唯一有效上下文：\n" + payload.toString(2);
    }

    private String coverageCommonRules(String taskMode) {
        String codeContract = "RUNTIME_REPAIR".equals(taskMode)
                ? "C1. REPAIR_TEST 只返回非空 methodPatches，不得返回 testCode。每个补丁只是完整的失败 @Test 方法，不得包含类/import/fixture/helper。"
                + "SUSPECTED_SUT_BUG/INCONCLUSIVE 时 methodPatches 必须为空。\n"
                : "C1. 测试类必须命名为 context.expectedTestClassName，package 必须等于 context.packageName，必须返回完整测试类而不是 diff。\n";
        return "公共契约：\n"
                + codeContract
                + "C2. compilerDiagnostics/runtimeDiagnostics 只是当前活动错误；previousAttempt 只用于避免重复已被拒绝的修改。\n"
                + "C2a. runtimeDiagnostics.testId 是 JUnit 实际报错测试；originLocations 是框架报告并与候选源码核对的位置，"
                + "可能属于前一个测试。不得混为同一测试，不得仅凭 matcher 遗留推断整个类的 beforeEach 失败；"
                + "发生阶段以 failurePhase 和真实栈帧为准。修复必须遵守 repairScope，不能改无关测试来消除连带报错。\n"
                + "C3. 只有 repairScope.passedTestIds 是已证明通过的测试，必须保持不变；allFailingTestIds 中的测试不得误当为已通过测试。\n"
                + "C4. 严格遵守 context.mockUsagePolicy；FORBIDDEN 时不得新建 mock/spy/verify；"
                + "PRESERVE_EXISTING 时只可调整已有 mock/spy 对象的 stub/verify，不得新建被 mock 类型、对象或字段。\n"
                + "C5. 生产源码使用 @Slf4j/static Logger 时，@Mock Logger 与生产 logger 不是同一实例；"
                + "禁止新增或保留 verify(mockLogger).info/error/warn/debug/trace。日志不是默认业务 Oracle，"
                + "应验证返回值、状态或真正可注入的协作者。\n";
    }

    private String coverageModeRules(LlmGenerationRequest request) {
        String mode = coverageTaskMode(request);
        if ("COMPILE_REPAIR".equals(mode)) return compileRepairRules();
        if ("RUNTIME_REPAIR".equals(mode)) return runtimeRepairRules();
        return coverageExplorationRules();
    }

    private String compileRepairRules() {
        return "当前模式：COMPILE_REPAIR。\n"
                + "P1. 只修复 compilerDiagnostics 指向的当前编译错误，按行列/snippet 做最小修改，不得改写测试行为。\n"
                + "P2. 修复后自检不得引入新项目 API、新语法错误或删除现有测试。\n"
                + "P3. MOCKITO_STATIC_MOCK_UNSUPPORTED 时删除 mockStatic/MockedStatic，改用真实调用或已有可注入依赖，不得修改项目依赖。\n";
    }

    private String runtimeRepairRules() {
        return "当前模式：RUNTIME_REPAIR。执行通用运行修复协议：\n"
                + "R1. 意图仲裁：方法名和 LOW 可信 scenario 只是线索，不是权威契约。"
                + "只有 oracleEvidence.authorityItems 中的 USER_REQUIREMENT > EXISTING_TEST > CONTRACT_FACT 可以授权修改 Oracle。"
                + "methodSourceCode、HIGH/MEDIUM scenario 和 runtimeDiagnostics 只用于解释当前路径，不得把意外失败直接改写成新期望。\n"
                + "R2. 路径定位：优先使用 testFrame/productionFrame/rootCauseFrame/failureDetails，再结合完整 stackTrace、branches、constraints、callContexts 还原 Arrange 到失败点的路径，不得只猜异常消息。\n"
                + "R3. 根因分类：选择最早能解释失败的输入/fixture/stub、Act 调用、低可信 Oracle 错误或不可修环境错误。\n"
                + "R3a. runtimeDiagnostics.category=FIXTURE_DEPENDENCY_MISSING 时，源码构造依赖、测试声明和真实 NPE 已共同证明 fixture 缺失。"
                + "返回 REPAIR_TEST + KEEP + NONE；方法级补丁必须在失败测试内创建缺失 mock/真实依赖、按真实构造函数重建被测对象并补充必要 stub，"
                + "不得修改断言、verify、期望异常或换掉触发失败的业务输入。\n"
                + "R3b. 运行期环境类失败（RUNTIME_DEPENDENCY_UNAVAILABLE / RUNTIME_LINKAGE_VERSION_CONFLICT / "
                + "RUNTIME_STATIC_INIT_FAILURE / RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE / FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE / "
                + "RUNTIME_RESOURCE_LIMIT_EXCEEDED，或 exceptionType/causeChain 为 NoClassDefFoundError、NoSuchMethodError、"
                + "ExceptionInInitializerError、ConnectException、SQLException 等）根因在测试代码之外，不是 Oracle 证据问题："
                + "rootCauseCategory 原样保留该环境分类；能在测试内补救（为外部协作者补 mock/替身、@TempDir 写临时配置、"
                + "缩小大数据量输入）时返回 REPAIR_TEST + KEEP + NONE 修复 Arrange；确认无法在测试内补救时返回 INCONCLUSIVE "
                + "并保留该环境分类，不得改成 TEST_RUNTIME_FAILURE，也不得当作证据冲突。\n"
                + "R3c. rootCauseCategory 只能从以下封闭分类中选取："
                + String.join("、", FailureCategory.runtimeArbitrationCodes())
                + "；此外可原样引用 runtimeDiagnostics.category 中已出现的值。不得发明新分类。\n"
                + "R4. 按证据修复：成功/正常/有效输入场景意外抛异常时，修复 Arrange、输入、fixture、前置条件或依赖 stub，禁止改成 assertThrows、捕获忽略异常、删除或弱化断言。"
                + "但若 methodSourceCode/branches 明确证明当前输入本就命中 action=throw 的异常契约，则该场景被错分为正常路径："
                + "应优先把输入改为满足所有 safeCondition 的有效输入；只有独立契约证据证明该场景本就是异常路径时才允许改为精确 assertThrows。"
                + "确保测试输入、被测调用实参及 Mockito stub 的参数和值一致。\n"
                + "R5. 修改范围：仅当 repairDisposition=REPAIR_TEST 时，必须为每个 focusTestIds 中需要修复的失败测试返回同名 methodPatch；"
                + "严禁修改 passedTestIds，严禁新增 import/fixture/helper。\n"
                + "R6. 返回前自检：methodCode 是可解析、带测试注解的完整方法；diagnosis/edits/methodPatches 目标逐项对应；Mockito 调用合法。\n"
                + "R7. 先决定 repairDisposition：有独立契约支持原测试但 SUT 违反时返回 SUSPECTED_SUT_BUG；"
                + "只有确认是测试输入/fixture/stub 或生成 Oracle 错误时才返回 REPAIR_TEST。"
                + "INCONCLUSIVE 仅限 Oracle 语义类失败：断言期望值、期望异常、verify 次数/参数等需要独立契约仲裁、"
                + "而 authorityItems 为空或相互冲突时才允许返回；"
                + "失败可由 Arrange/依赖准备/运行环境解释时（环境类失败、fixture、stub、输入问题）不得返回 INCONCLUSIVE，"
                + "按 R3a/R3b/R4 处置。SUSPECTED_SUT_BUG/INCONCLUSIVE 时 methodPatches 和 edits 必须为空。\n"
                + "R8. oracleDecision=KEEP 时代码门禁会比对断言、verify 和期望异常指纹，只允许修改 Arrange/Act。"
                + "oracleDecision=CORRECT 只能使用 USER_REQUIREMENT、EXISTING_TEST 或 CONTRACT_FACT 作为 oracleAuthority；"
                + "当前生产实现、actualValue、失败消息和模型自身猜测不能授权改 Oracle。\n"
                + "R9. oracleAuthorityRef 必须精确来自 oracleEvidence.authorityItems；当没有对应条目时必须用 NONE，不得虚构。\n"
                + "R10. runtimeDiagnostics 已是 ASSERTION_VALUE_MISMATCH / ASSERTION_FAILURE / Mockito verify 语义不匹配时，"
                + "不得返回 oracleDecision=KEEP 的 REPAIR_TEST 后通过改 Arrange 换掉触发 Bug 的输入。"
                + "唯一装配例外是 runner 提供完整 mockWiringEvidence.removableAssignment：仅移除指定覆盖赋值，SHARED_FIXTURE + REPAIR_TEST + KEEP + NONE，保持全部测试正文。"
                + "其他情况没有独立契约授权 CORRECT 时必须返回 INCONCLUSIVE 或 SUSPECTED_SUT_BUG。\n"
                + "R11. ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN 表示 assertThrows 的目标异常未发生，不是 Fixture 缺失。"
                + "必须使用 context.classSourceCode 中的真实常量/字段定义、分支谓词和 currentTestCode 中的 stub 值核对路径；"
                + "常量缺失或边界无法确定时返回 INCONCLUSIVE，严禁改期望异常类型。\n"
                + "专项规则：MOCKITO_MISSING_METHOD_INVOCATION 删除或替换非法 when，禁止继续 when(@InjectMocks/真实对象/静态/不可覆盖方法)；"
                + "MOCKITO_VOID_STUB_MISUSE 必须删除对非 void 方法的 doNothing，按真实返回类型使用 when/thenReturn 或直接真实调用；"
                + "MOCKITO_ARGUMENT_MISMATCH 对照源码定位真实调用序列；没有独立契约授权时不得修改 verify 参数或换掉该交互 Oracle；"
                + "ASSERT_THROWS_WRAPPED_BY_REFLECTION 保留 expectedExceptionType，解包 InvocationTargetException.getCause()；"
                + "MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET 表示 Mockito 拒绝 mockStatic 该目标（final/不可 instrument）："
                + "删除 mockStatic/MockedStatic 改真实调用或可注入依赖，oracleDecision=KEEP 授权该删除；"
                + "MOCKITO_UNNECESSARY_STUBBING 删除失败测试内未走到的 when(...)，不要全局 lenient；"
                + "MOCKITO_STRICT_STUBBING_MISMATCH 按生产实参精确修正 when(...) 参数，不得用 any() 放宽绕过；"
                + "MOCKITO_MOCKING_UNAVAILABLE 改用真实对象构造输入或可注入接口，不得修改项目依赖；"
                + "ORM_MAPPING_RUNTIME_FAILURE 为对应 Mapper/Session 协作者提供 mock 并断言交互，不触达真实数据库会话；"
                + "DATA_BINDING_INPUT_INVALID 按目标类型真实模式修正输入字面量（日期格式、字段名）；"
                + "ASSERTION_VALUE_MISMATCH 的生成断言是待验证假设；只有 authorityItems 中的独立契约证明它错误时才能精确修正，"
                + "生产源码当前实现或 actualValue 一致不能单独授权改期望值；"
                + "若原期望值有独立契约证据则标记 POTENTIAL_SUT_BUG，证据不足则 INCONCLUSIVE；"
                + "MOCKITO_INVOCATION_COUNT_MISMATCH 中 expectedInvocationCount/actualInvocationCount 是 verify 次数；"
                + "只有独立契约授权时可精确修改 times(n)/once/atLeast，否则必须保留失败；不得用 atLeast(0)/anyTimes 伪造成功；"
                + "authorityItems 中 type=USER_REQUIREMENT 且 content 标注“适用于失败测试：…”的证据，"
                + "只对列出的失败测试有效，不得据此修改其他测试的 Oracle。\n";
    }

    private String coverageExplorationRules() {
        return "当前模式：COVERAGE_EXPLORATION。\n"
                + "E1. coverage.gaps[0] 是唯一主目标，只追加命中该目标所需的最少测试，不得修改现有测试方法。\n"
                + "E2. BRANCH missingDirection=UNKNOWN 时不得猜 JaCoCo 已覆盖方向；必须对照 currentTestCode 的已有输入与 predicate/pathConstraints，选择不同的相反或边界输入。\n"
                + "E3. 使用 context.methodSourceCode 的方法入口到 gap 路径，满足所有前置条件；不得只围绕 gap 单行代码猜测。\n"
                + "E3a. predicate 中出现 contains/startsWith/endsWith/正则字面量时，新输入必须显式包含或排除该精确字面量，不得只换一个无关普通值。\n"
                + "E3b. 若参数类型 sealed=true、permittedSubtypes 已列全且都无其他可实例化子类，"
                + "不得为命中穷尽 instanceof 之后的 fallback 伪造匿名实现、mock 或非 permits 子类；"
                + "应改选其他可达 gap，没有时保持代码不变并在 warnings 说明静态不可达证据。\n"
                + "E3c. gap 位于 lambda 或延迟回调中时，仅构造/传递回调不算执行；若接收方被 mock，"
                + "须通过捕获参数或 Answer，在满足真实入参约束的情况下执行回调并验证结果。不得用修改生产代码、反射调用合成方法或绕过断言制造覆盖。\n"
                + "E4. 新测试在方法内独立 Arrange，不改写字段或生命周期方法。优先验证返回值/对外状态/精确异常，禁止在没有源码证据时编造 verify/times(n)。\n";
    }

    private JSONObject coverage(CoverageReport report) {
        JSONObject json = new JSONObject();
        if (report == null) return json;
        json.put("status", report.getStatus().name());
        json.put("round", report.getRounds());
        json.put("generated", snapshot(report.getGenerated()));
        JSONArray gaps = new JSONArray();
        List<CoverageGap> focusedGaps = focusedGaps(report, 5);
        for (int index = 0; index < focusedGaps.size(); index++) {
            CoverageGap gap = focusedGaps.get(index);
            gaps.put(new JSONObject()
                    .put("primary", index == 0)
                    .put("kind", gap.getKind().name())
                    .put("line", gap.getLineNumber())
                    .put("source", gap.getSource())
                    .put("context", gap.getContext())
                    .put("missedCount", gap.getMissedCount())
                    .put("totalCount", gap.getTotalCount())
                    .put("branchId", gap.getBranchId())
                    .put("predicate", gap.getPredicate())
                    .put("missingDirection", gap.getMissingDirection())
                    .put("pathConstraints", gap.getPathConstraints()));
        }
        json.put("gaps", gaps);
        json.put("allGapCount", report.getGaps().size());
        json.put("omittedGapCount", Math.max(0, report.getGaps().size() - focusedGaps.size()));
        json.put("explorationRound", report.getExplorationRounds() + 1);
        return json;
    }

    /**
     * 每轮只把一个缺口设为主目标，并在无增益时轮换。分支缺口优先于普通未覆盖行，
     * 避免一次让模型同时解决二十个目标而生成过大的脆弱候选。
     */
    private List<CoverageGap> focusedGaps(CoverageReport report, int limit) {
        if (report == null || report.getGaps().isEmpty() || limit <= 0) return List.of();
        List<CoverageGap> ordered = new java.util.ArrayList<>(report.getGaps());
        ordered.sort(java.util.Comparator
                .comparing((CoverageGap gap) -> gap.getKind() == CoverageGap.Kind.BRANCH ? 0 : 1)
                .thenComparing(java.util.Comparator.comparingInt(CoverageGap::getMissedCount).reversed())
                .thenComparingInt(CoverageGap::getLineNumber));
        int start = Math.floorMod(report.getNoProgressCandidates(), ordered.size());
        List<CoverageGap> selected = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int offset = 0; offset < ordered.size() && selected.size() < limit; offset++) {
            CoverageGap gap = ordered.get((start + offset) % ordered.size());
            String key = gap.getKind() + ":" + gap.getLineNumber();
            if (seen.add(key)) selected.add(gap);
        }
        return selected;
    }

    private JSONObject snapshot(CoverageSnapshot snapshot) {
        if (snapshot == null) return new JSONObject();
        return new JSONObject()
                .put("lineCovered", snapshot.getLine().getCovered())
                .put("lineTotal", snapshot.getLine().getTotal())
                .put("lineRate", snapshot.getLine().getRate())
                .put("branchCovered", snapshot.getBranch().getCovered())
                .put("branchTotal", snapshot.getBranch().getTotal())
                .put("branchRate", snapshot.getBranch().getRate())
                .put("testsFound", snapshot.getExecution().getFound())
                .put("testsFailed", snapshot.getExecution().getFailed());
    }

    private JSONArray runtimeDiagnostics(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (RuntimeDiagnostic diagnostic : request.getRuntimeDiagnostics()) {
            array.put(new JSONObject()
                    .put("testId", nullToEmpty(diagnostic.getTestId()))
                    .put("exceptionType", nullToEmpty(diagnostic.getExceptionType()))
                    .put("message", nullToEmpty(diagnostic.getMessage()))
                    .put("category", nullToEmpty(diagnostic.getCategory()))
                    .put("failurePhase", diagnostic.getFailurePhase().name())
                    .put("fixtureField", diagnostic.getFixtureField())
                    .put("lifecycleEvidence", diagnostic.getLifecycleEvidence())
                    .put("mockWiringEvidence", new JSONArray(diagnostic.getMockWiringEvidence().stream().map(e -> new JSONObject()
                            .put("category", e.category()).put("subject", e.subject()).put("receiver", e.receiver())
                            .put("line", e.line()).put("fact", e.fact()).put("testHash", e.testHash())
                            .put("productionHash", e.productionHash()).put("removableAssignment", e.removableAssignment())).toList()))
                    .put("originLocations", new JSONArray(diagnostic.getOriginLocations().stream().map(origin ->
                            new JSONObject().put("testId", origin.testId()).put("sourceLine", origin.sourceLine())
                                    .put("sourceSnippet", origin.sourceSnippet()).put("frame", origin.frame())).toList()))
                    .put("sourceLine", diagnostic.getSourceLine())
                    .put("sourceSnippet", nullToEmpty(diagnostic.getSourceSnippet()))
                    .put("suggestion", nullToEmpty(diagnostic.getSuggestion()))
                    .put("expectedValue", nullToEmpty(diagnostic.getExpectedValue()))
                    .put("actualValue", nullToEmpty(diagnostic.getActualValue()))
                    .put("expectedExceptionType", nullToEmpty(diagnostic.getExpectedExceptionType()))
                    .put("actualExceptionType", nullToEmpty(diagnostic.getActualExceptionType()))
                    .put("rootCauseType", nullToEmpty(diagnostic.getRootCauseType()))
                    .put("rootCauseMessage", nullToEmpty(diagnostic.getRootCauseMessage()))
                    .put("causeDetails", diagnostic.getCauseDetails())
                    .put("sharedContextFailure", diagnostic.isSharedContextFailure())
                    .put("contextFailureSecondary", diagnostic.isContextFailureSecondary())
                    .put("contextKey", diagnostic.getContextKey())
                    .put("primaryFailureTestId", diagnostic.getPrimaryFailureTestId())
                    .put("expectedInvocationCount", diagnostic.getExpectedInvocationCount())
                    .put("actualInvocationCount", diagnostic.getActualInvocationCount())
                    .put("causeChain", diagnostic.getCauseChain())
                    .put("testFrame", nullToEmpty(diagnostic.getTestFrame()))
                    .put("productionFrame", nullToEmpty(diagnostic.getProductionFrame()))
                    .put("rootCauseFrame", nullToEmpty(diagnostic.getRootCauseFrame()))
                    .put("failureDetails", limitText(diagnostic.getFailureDetails(), 6000))
                    .put("stackTrace", boundedStackTrace(diagnostic.getStackTrace())));
        }
        return array;
    }

    public String mockStrategySystemPrompt() {
        return "你是一个资深 Java 单元测试工程师。你只需要根据静态分析结果给出 Mockito Mock 策略候选，不要生成测试代码。"
                + "必须区分测试类字段级 @Mock 和测试方法内部局部 mock。"
                + "context.mockitoCapabilities.staticMockingSupported=false 时禁止建议 mockStatic。";
    }

    public String mockStrategyPrompt(LlmGenerationRequest request) {
        JSONObject context = context(request);
        return "请只返回 JSON，不要返回 Markdown。\n"
                + "输出 JSON 格式必须为：{\"mockStrategies\":[{\"enabled\":true,\"dependencyType\":\"字段依赖/方法参数数据对象/局部对象依赖/静态方法依赖\",\"target\":\"对象或类名\",\"method\":\"方法\",\"source\":\"源码线索\",\"mockitoStrategy\":\"mock/localMock/mockStatic/spy/构造测试对象/不处理\",\"note\":\"说明\"}],\"warnings\":[\"可选警告\"]}。\n"
                + "Mock 策略要求：\n"
                + "1. Repository / Mapper / Dao / Client / Service / JdbcTemplate / RedisTemplate / RestTemplate / WebClient / 外部系统接口 / 框架接口可以生成字段级 @Mock，mockitoStrategy 写 mock。\n"
                + "2. DTO / Request / Entity / Profile / Path / File / Directory 等对象不默认生成字段级 @Mock。\n"
                + "3. DTO / Request / Entity / Profile 如果构造复杂、是 record、interface、final class、无可用构造函数、字段很多，或被测方法只关心少量 getter/accessor，可以在测试方法内部局部 mock，mockitoStrategy 写 localMock。\n"
                + "4. 静态方法默认真实调用。仅当 context.mockitoCapabilities.staticMockingSupported=true 且调用存在不可控副作用时才建议 mockStatic；private 方法不要直接 stub。\n"
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
        context.put("generationScope", request.getGenerationScope().name());
        context.put("targetMethodId", nullToEmpty(request.getMethodId()));
        context.put("classMethodSignatures", request.getClassMethodSignatures());
        context.put("sharedFixtureSummary", nullToEmpty(request.getSharedFixtureSummary()));
        context.put("packageName", nullToEmpty(request.getPackageName()));
        context.put("className", nullToEmpty(request.getClassName()));
        context.put("expectedTestClassName", nullToEmpty(request.getClassName()).isBlank()
                ? "GeneratedTest" : request.getClassName() + "Test");
        context.put("methodName", nullToEmpty(request.getMethodName()));
        context.put("methodSignature", nullToEmpty(request.getMethodSignature()));
        context.put("returnType", nullToEmpty(request.getReturnType()));
        context.put("accessModifier", nullToEmpty(request.getAccessModifier()));
        context.put("staticMethod", request.isStaticMethod());
        context.put("imports", nullToEmpty(request.getImports()));
        context.put("classFields", nullToEmpty(request.getClassFields()));
        var properties = request.getSpringPropertyBindings().isEmpty()
                ? com.casc.unittestagent.coverage.SpringFixtureAnalyzer.properties(java.util.List.of(nullToEmpty(request.getClassSourceCode())))
                : request.getSpringPropertyBindings();
        JSONArray bindings = new JSONArray();
        properties.forEach(p -> bindings.put(new JSONObject().put("owner", p.owner()).put("member", p.member())
                .put("type", p.type()).put("injectionSite", p.injectionSite()).put("expression", p.expression()).put("keys", p.keys())
                .put("requiredKeys", p.requiredKeys()).put("spel", p.spel())));
        context.put("springPropertyBindings", bindings);
        context.put("knownTypes", request.getKnownTypes());
        context.put("chainedCallReturnTypes", request.getChainedCallReturnTypes());
        context.put("filesystemTestingPolicy", "文件系统逻辑默认使用 @TempDir、真实 Path、真实 Files 临时文件；不要默认 mock Files、Paths 或 Path 目录变量。");
        context.put("methodSourceCode", boundedText(request.getMethodSourceCode(), MAX_METHOD_SOURCE_CHARS));
        context.put("parameters", parameters(request));
        context.put("constraints", constraints(request));
        context.put("branches", branches(request));
        context.put("dependencies", dependencies(request));
        context.put("callContexts", callContexts(request));
        context.put("projectSymbolSnapshot", symbolSnapshot(request.getSymbolSnapshot()));
        context.put("usages", usages(request));
        context.put("scenarios", scenarios(request));
        context.put("existingTestCodeSummary", boundedText(request.getExistingTestCodeSummary(), MAX_TEST_CODE_CHARS));
        context.put("userInstruction", nullToEmpty(request.getUserInstruction()));
        context.put("testFramework", nullToEmpty(request.getTestFramework()));
        context.put("mockFramework", nullToEmpty(request.getMockFramework()));
        context.put("mockUsagePolicy", nullToEmpty(request.getMockUsagePolicy()));
        context.put("mockitoCapabilities", new JSONObject()
                .put("staticMockingSupported", request.isMockitoStaticMockingSupported())
                .put("evidence", nullToEmpty(request.getMockitoStaticMockingSupport())));
        return context;
    }

    /** 快照全量序列化：ProjectSymbolSnapshotBuilder 已在构建期限制类型与成员数量。 */
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
        json.put("existingTestExamples", snapshot.getTestExamples());
        json.put("contractFacts", snapshot.getContractFacts());
        JSONArray types = new JSONArray();
        int typeCount = snapshot.getTypes().size();
        for (int typeIndex = 0; typeIndex < typeCount; typeIndex++) {
            TypeSymbol type = snapshot.getTypes().get(typeIndex);
            JSONObject item = new JSONObject();
            item.put("qualifiedName", nullToEmpty(type.getQualifiedName()));
            item.put("kind", nullToEmpty(type.getKind()));
            item.put("final", type.isFinalType());
            item.put("abstract", type.isAbstractType());
            item.put("sealed", type.isSealedType());
            item.put("permittedSubtypes", type.getPermittedSubtypes());
            item.put("concreteSubtypes", type.getConcreteSubtypes());
            JSONArray constructors = new JSONArray();
            int constructorCount = type.getConstructors().size();
            for (int index = 0; index < constructorCount; index++) {
                constructors.put(callable(type.getConstructors().get(index)));
            }
            JSONArray methods = new JSONArray();
            int methodCount = type.getMethods().size();
            for (int index = 0; index < methodCount; index++) {
                methods.put(callable(type.getMethods().get(index)));
            }
            item.put("constructors", constructors);
            item.put("methods", methods);
            item.put("omittedConstructorCount", Math.max(0, type.getConstructors().size() - constructorCount));
            item.put("omittedMethodCount", Math.max(0, type.getMethods().size() - methodCount));
            types.put(item);
        }
        json.put("types", types);
        json.put("omittedTypeCount", Math.max(0, snapshot.getTypes().size() - typeCount));
        return json;
    }

    private JSONObject oracleEvidence(LlmGenerationRequest request) {
        JSONObject evidence = new JSONObject();
        ProjectSymbolSnapshot snapshot = request.getSymbolSnapshot();
        evidence.put("authorityOrder", List.of("USER_REQUIREMENT", "EXISTING_TEST", "CONTRACT_FACT",
                "PRODUCTION_SOURCE", "SCENARIO", "RUNTIME_ACTUAL"));
        evidence.put("contractFacts", snapshot == null ? List.of() : snapshot.getContractFacts());
        JSONArray authorityItems = new JSONArray();
        if (request.getScenarios() != null) {
            for (int index = 0; index < request.getScenarios().size(); index++) {
                TestScenario scenario = request.getScenarios().get(index);
                if (scenario == null || !"USER_REQUIREMENT".equalsIgnoreCase(scenario.getOracleEvidence())
                        || scenario.getExpectedAssertions() == null
                        || scenario.getExpectedAssertions().isEmpty()) continue;
                authorityItems.put(new JSONObject()
                        .put("ref", "USER_REQUIREMENT:" + index)
                        .put("type", "USER_REQUIREMENT")
                        .put("content", scenario.getExpectedAssertions()));
            }
        }
        if (snapshot != null) {
            for (int index = 0; index < snapshot.getTestExamples().size(); index++) {
                authorityItems.put(new JSONObject()
                        .put("ref", "EXISTING_TEST:" + index)
                        .put("type", "EXISTING_TEST")
                        .put("content", snapshot.getTestExamples().get(index)));
            }
            for (int index = 0; index < snapshot.getContractFacts().size(); index++) {
                if (!isIndependentContractFact(snapshot.getContractFacts().get(index))) continue;
                authorityItems.put(new JSONObject()
                        .put("ref", "CONTRACT_FACT:" + index)
                        .put("type", "CONTRACT_FACT")
                        .put("content", snapshot.getContractFacts().get(index)));
            }
        }
        evidence.put("authorityItems", authorityItems);
        JSONArray sourceBranches = new JSONArray();
        if (request.getBranches() != null) {
            for (BranchModel branch : request.getBranches()) {
                if (branch == null) continue;
                sourceBranches.put(new JSONObject()
                        .put("condition", nullToEmpty(branch.getCondition()))
                        .put("safeCondition", nullToEmpty(branch.getSafeCondition()))
                        .put("action", nullToEmpty(branch.getAction()))
                        .put("exceptionType", nullToEmpty(branch.getExceptionType()))
                        .put("preconditions", branch.getPreconditions()));
            }
        }
        evidence.put("sourceBranches", sourceBranches);
        evidence.put("rule", "actualValue 和当前生产实现只是失败事实，不是改 Oracle 的独立契约。"
                + "自动修改 Oracle 必须引用 authorityItems 中的 USER_REQUIREMENT、EXISTING_TEST 或 CONTRACT_FACT。");
        return evidence;
    }

    private boolean isIndependentContractFact(String value) {
        String fact = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
        return fact.startsWith("CONTRACT:") || fact.startsWith("REQUIREMENT:")
                || fact.startsWith("EXISTING_TEST_ORACLE:");
    }

    private String coverageFocusSource(LlmGenerationRequest request) {
        String source = nullToEmpty(request.getMethodSourceCode());
        // Runtime repair needs the complete control flow. A coverage-focused excerpt can omit the
        // guard or dependency call that actually threw, forcing the model to guess from the message.
        if ("RUNTIME_REPAIR".equals(coverageTaskMode(request))) {
            return boundedText(source, MAX_METHOD_SOURCE_CHARS);
        }
        if (source.length() <= 12_000 || request.getCoverageReport() == null
                || request.getCoverageReport().getGaps().isEmpty()) {
            return source;
        }
        java.util.LinkedHashSet<String> contexts = new java.util.LinkedHashSet<>();
        for (CoverageGap gap : focusedGaps(request.getCoverageReport(), 5)) {
            String context = gap.getContext();
            if (context != null && !context.isBlank()) contexts.add(context);
        }
        String prefix = source.substring(0, Math.min(5000, source.length()));
        String suffix = source.substring(Math.max(prefix.length(), source.length() - 2500));
        return "// 超长方法的路径切片：保留方法入口、本轮 gap 附近代码和方法尾部。\n"
                + prefix + "\n// --- focused coverage gaps ---\n"
                + String.join("\n---\n", contexts)
                + "\n// --- method tail ---\n" + suffix;
    }

    private String coverageTaskMode(LlmGenerationRequest request) {
        boolean compileRepair = request.getCompilerDiagnostics() != null
                && !request.getCompilerDiagnostics().isEmpty();
        boolean runtimeRepair = request.getRuntimeDiagnostics() != null
                && !request.getRuntimeDiagnostics().isEmpty();
        // Compilation is the first executable gate. Any runtime diagnostic supplied alongside a
        // compile failure necessarily belongs to an older candidate and must not be made active.
        if (compileRepair) return "COMPILE_REPAIR";
        if (runtimeRepair) return "RUNTIME_REPAIR";
        return "COVERAGE_EXPLORATION";
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
        json.put("fixtureMethod", symbol.isFixtureMethod());
        json.put("sourceCode", nullToEmpty(symbol.getSourceCode()));
        return json;
    }

    private JSONArray compilerDiagnostics(LlmGenerationRequest request) {
        JSONArray array = new JSONArray();
        for (CompileDiagnostic diagnostic : request.getCompilerDiagnostics()) {
            JSONObject item = new JSONObject();
            item.put("severity", nullToEmpty(diagnostic.getSeverity()));
            item.put("code", nullToEmpty(diagnostic.getCode()));
            item.put("validationStage", diagnostic.getValidationStage().name());
            item.put("message", nullToEmpty(diagnostic.getMessage()));
            item.put("line", diagnostic.getLine());
            item.put("column", diagnostic.getColumn());
            item.put("snippet", nullToEmpty(diagnostic.getSnippet()));
            item.put("repairable", diagnostic.isRepairable());
            item.put("sourceOrigin", nullToEmpty(diagnostic.getSourceOrigin()));
            item.put("sourcePath", nullToEmpty(diagnostic.getSourcePath()));
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
            item.put("branchId", nullToEmpty(branch.getBranchId()));
            item.put("sourceStartLine", branch.getSourceStartLine());
            item.put("sourceEndLine", branch.getSourceEndLine());
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
            item.put("oracle", new JSONObject()
                    .put("kind", nullToEmpty(scenario.getOracleKind()))
                    .put("evidence", nullToEmpty(scenario.getOracleEvidence()))
                    .put("confidence", nullToEmpty(scenario.getOracleConfidence())));
            array.put(item);
        }
        return array;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String limitText(String value, int maxLength) {
        String text = nullToEmpty(value);
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "\n...";
    }

    /**
     * 超长文本的保底截断：保留头尾并标注省略规模。
     * 常规场景远达不到这些上限；命中的都是原本就会超出供应商上下文窗口、
     * 直接得到 400 的请求，标记式截断至少让请求可解析并让模型知道有省略。
     */
    private static final int MAX_METHOD_SOURCE_CHARS = 40_000;
    private static final int MAX_CLASS_SOURCE_CHARS = 150_000;
    private static final int MAX_TEST_CODE_CHARS = 150_000;
    private static final int MAX_STACK_TRACE_CHARS = 6_000;

    private String boundedText(String value, int maxLength) {
        String text = nullToEmpty(value);
        if (text.length() <= maxLength) {
            return text;
        }
        int headLength = Math.max(1, maxLength * 4 / 5);
        int tailLength = Math.max(1, maxLength - headLength);
        return text.substring(0, headLength)
                + "\n// UnitMind: 内容过长，已省略中间 "
                + (text.length() - headLength - tailLength) + " 字符。\n"
                + text.substring(text.length() - tailLength);
    }

    /** 堆栈按总字符数封顶，保留抛出点附近的头部帧，并标注省略的行数。 */
    private java.util.List<String> boundedStackTrace(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> bounded = new java.util.ArrayList<>();
        int total = 0;
        for (String line : lines) {
            int length = line == null ? 0 : line.length();
            if (total + length > MAX_STACK_TRACE_CHARS) {
                bounded.add("...（堆栈过长，已省略剩余 "
                        + (lines.size() - bounded.size()) + " 行）");
                break;
            }
            bounded.add(nullToEmpty(line));
            total += length;
        }
        return bounded;
    }
}
