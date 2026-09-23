package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.RuntimeDiagnostic;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 Mockito 运行异常关联回候选测试源码，并给覆盖率修复模型提供可执行建议。 */
public final class MockitoRuntimeDiagnosticEnricher {
    private static final Pattern JAVA_LINE = Pattern.compile("\\.java:(\\d+)\\)");
    private static final Pattern WANTED_COUNT = Pattern.compile(
            "(?im)^Wanted\\s+(\\d+)\\s+times?\\s*:");
    private static final Pattern ACTUAL_COUNT = Pattern.compile(
            "(?im)^But was\\s+(\\d+)\\s+times?\\s*:");

    private MockitoRuntimeDiagnosticEnricher() { }

    public static void enrich(List<RuntimeDiagnostic> diagnostics, String testCode) {
        if (diagnostics == null || diagnostics.isEmpty()) return;
        List<CompileDiagnostic> preflight = CoverageCandidatePreflight.inspect(testCode);
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            enrichLocationAndCategory(diagnostic, testCode);
            if (!isMissingMethodInvocation(diagnostic)) continue;
            diagnostic.setCategory("MOCKITO_MISSING_METHOD_INVOCATION");
            int stackLine = diagnostic.getSourceLine() > 0
                    ? diagnostic.getSourceLine() : sourceLine(diagnostic.getStackTrace(), testSimpleName(diagnostic));
            CompileDiagnostic related = relatedPreflight(preflight, stackLine);
            int line = related != null && related.getLine() > 0 ? (int) related.getLine() : stackLine;
            diagnostic.setSourceLine(line);
            diagnostic.setSourceSnippet(related == null ? sourceLine(testCode, line) : related.getSnippet());
            diagnostic.setSuggestion(related == null
                    ? "定位并删除失败行的 when(...)。when 的参数必须是对 Mockito mock 的可覆盖方法调用；"
                    + "不要 stub @InjectMocks 被测对象、new 出来的 DTO/实体、静态方法、private/final/native/equals/hashCode。"
                    + "应改为构造输入状态，或 stub 被测方法真正调用的 @Mock 依赖；@Spy 优先使用 doReturn(...).when(spy)。"
                    : related.getMessage());
        }
    }

    private static void enrichLocationAndCategory(RuntimeDiagnostic diagnostic, String testCode) {
        if (diagnostic == null) return;
        enrichFrames(diagnostic);
        int line = sourceLine(diagnostic.getStackTrace(), testSimpleName(diagnostic));
        if (line <= 0) line = sourceLine(diagnostic.getStackTrace(), "");
        diagnostic.setSourceLine(line);
        diagnostic.setSourceSnippet(sourceLine(testCode, line));

        String rawEvidence = diagnostic.getExceptionType() + "\n" + diagnostic.getMessage()
                + "\n" + diagnostic.getFailureDetails();
        String evidence = rawEvidence
                .toLowerCase(Locale.ROOT);
        // 顶层证据只含异常类型（外层与首个根因）与消息，不含 Caused by 全文：
        // IAE/ISE/NPE 分支若看全文，会把“框架 IllegalStateException 上下文启动失败”
        // “静态初始化失败的 NPE cause”等环境问题主动误分类为业务/fixture 问题。
        String headEvidence = (diagnostic.getExceptionType() + "\n" + diagnostic.getRootCauseType()
                + "\n" + diagnostic.getMessage()).toLowerCase(Locale.ROOT);
        String actualType = diagnostic.getActualExceptionType() == null
                ? "" : diagnostic.getActualExceptionType().toLowerCase(Locale.ROOT);
        if ((evidence.contains("no interactions wanted")
                || evidence.contains("nointeractionswanted"))
                && MockitoNoInteractionsContradictionGuard.hasContradiction(
                testCode, testMethodName(diagnostic))) {
            diagnostic.setCategory("MOCKITO_NO_INTERACTIONS_CONTRADICTION");
            diagnostic.setSuggestion("同一测试已经 verify(mock).方法，随后又对同一 mock 调用 "
                    + "verifyNoInteractions(mock)，两个 Oracle 逻辑上自相矛盾。"
                    + "改用 verifyNoMoreInteractions(mock)，或精确验证不应发生的写操作 never()。");
        } else if (!diagnostic.getExpectedExceptionType().isBlank()
                && diagnostic.getActualExceptionType().isBlank()
                && evidence.contains("nothing was thrown")) {
            diagnostic.setCategory("ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN");
            diagnostic.setSuggestion("assertThrows 期望的异常路径没有被触发。必须结合完整生产类源码中的字段/常量定义、"
                    + "分支条件和当前 stub 实参核对边界：若输入未达到明确阈值，只能标记为测试路径设置不一致；"
                    + "若输入已达到阈值且真实依赖调用也已命中，则保留原异常 Oracle 并标记疑似被测代码缺陷。"
                    + "不得归类为 Fixture 缺失，除非另有真实 NullPointerException 和缺失构造依赖证据。");
        } else if ((actualType.contains("invocationtargetexception")
                || evidence.contains("but was: <java.lang.reflect.invocationtargetexception>"))
                && (!diagnostic.getExpectedExceptionType().isBlank()
                || evidence.contains("unexpected exception type thrown"))) {
            diagnostic.setCategory("ASSERT_THROWS_WRAPPED_BY_REFLECTION");
            diagnostic.setSuggestion("Method.invoke 会把被测方法抛出的异常包装为 InvocationTargetException。"
                    + "保持原目标异常断言，在 assertThrows 的 lambda 或反射 helper 中捕获包装异常并抛出 getCause()；"
                    + "不要把期望类型改成 InvocationTargetException。");
        } else if (isInvocationCountMismatch(diagnostic, rawEvidence)) {
            diagnostic.setCategory("MOCKITO_INVOCATION_COUNT_MISMATCH");
            diagnostic.setSuggestion("Mockito verify 的调用次数与实际路径不一致。"
                    + "对照生产源码、已有测试和场景契约仲裁：若生产路径明确证明实际次数正确，"
                    + "精确修改失败 verify 的 times(n)/once/atLeast 约束；若期望次数有权威依据，"
                    + "保留 verify 并标记疑似被测代码缺陷。不得删除 verify 或改为宽泛 anyTimes。");
        } else if (evidence.contains("only void methods can donothing")) {
            diagnostic.setCategory("MOCKITO_VOID_STUB_MISUSE");
            diagnostic.setSuggestion("doNothing() 只能用于 void 方法。根据 projectSymbolSnapshot 中的真实返回类型，"
                    + "对非 void 方法改用 when(...).thenReturn(...) / doReturn(...).when(...)，"
                    + "或删除无必要的 stub 并真实调用。");
        } else if (evidence.contains("argumentsaredifferent")
                || evidence.contains("argument(s) are different")) {
            diagnostic.setCategory("MOCKITO_ARGUMENT_MISMATCH");
            diagnostic.setSuggestion("verify 期望参数与生产代码实际调用不一致。"
                    + "使用 failureDetails 中的 Wanted/Actual 序列对照源码和已有测试；"
                    + "如果交互不是对外契约，优先改为返回值或状态断言。");
        } else if (evidence.contains("wantedbutnotinvoked") || evidence.contains("wanted but not invoked")) {
            diagnostic.setCategory("MOCKITO_WANTED_BUT_NOT_INVOKED");
            diagnostic.setSuggestion("目标交互没有发生。根据生产代码分支条件修正 Arrange/stub，"
                    + "不要删除 verify；确认输入确实能到达该调用。");
        } else if (evidence.contains("wrongtypeofreturnvalue")
                || evidence.contains("cannot be returned by") && evidence.contains("should return")) {
            diagnostic.setCategory("MOCKITO_WRONG_RETURN_TYPE");
            diagnostic.setSuggestion("Mockito stubbing 的返回值类型与被调用方法声明不一致。"
                    + "定位失败行并按真实方法签名修正 thenReturn/doReturn；如果接收者是 spy，"
                    + "不要使用会执行真实方法的 when(spy.method())，改用 doReturn(value).when(spy).method(...)。");
        } else if (evidence.contains("invaliduseofmatchersexception")
                || evidence.contains("misplaced or misused argument matcher")) {
            diagnostic.setCategory("MOCKITO_INVALID_MATCHER_USAGE");
            diagnostic.setSuggestion("Mockito matcher 只能出现在 stubbing 或 verify 的参数中；真实调用和构造函数请使用具体值。");
        } else if (evidence.contains("it is not possible to mock static")
                || evidence.contains("cannot mock static")
                || evidence.contains("cannot instrument")
                || evidence.contains("static mocking")) {
            diagnostic.setCategory("MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET");
            diagnostic.setSuggestion("Mockito 拒绝对该目标使用 mockStatic（final/原生/不可 instrument 或配置禁用）。"
                    + "删除 mockStatic/MockedStatic，改用真实调用、可注入依赖或测试专属配置；"
                    + "不得修改项目依赖或被测代码。");
        } else if (evidence.contains("unnecessarystubbingexception")
                || evidence.contains("unnecessary stubbings detected")) {
            diagnostic.setCategory("MOCKITO_UNNECESSARY_STUBBING");
            diagnostic.setSuggestion("严格 stub 检测到当前测试没有用到的 stub。删除失败测试内未走到的 when(...)，"
                    + "不要把整个类改成 lenient；如该 stub 服务于其他测试请移到对应测试内。");
        } else if (evidence.contains("potentialstubbingproblem")
                || evidence.contains("strict stubbing argument mismatch")) {
            diagnostic.setCategory("MOCKITO_STRICT_STUBBING_MISMATCH");
            diagnostic.setSuggestion("严格 stubbing 的参数与真实调用不一致。按生产代码实际实参修正 "
                    + "when(...) 的参数或 matcher；不要用 any() 放宽所有参数来绕过。");
        } else if (evidence.contains("cannot mock this class")
                || evidence.contains("cannot mock/spy")) {
            diagnostic.setCategory("MOCKITO_MOCKING_UNAVAILABLE");
            diagnostic.setSuggestion("Mockito 无法 mock 该目标（final 类/构造器受限/JDK 与 mock-maker 不兼容）。"
                    + "改用真实对象构造输入，或为被测代码使用可注入的接口依赖；"
                    + "不得修改项目依赖或被测代码。");
        } else if (evidence.contains("exceptionininitializererror")
                || evidence.contains("could not initialize class")) {
            diagnostic.setCategory("RUNTIME_STATIC_INIT_FAILURE");
            diagnostic.setSuggestion("被测类或其依赖的静态初始化在测试环境失败，这是环境问题而不是 Oracle 证据问题。"
                    + "先检查静态初始化依赖的系统属性/配置文件/外部资源；"
                    + "若被测路径可避开静态初始化，构造输入走非静态分支。");
        } else if (evidence.contains("applicationcontextfailureexception")
                || evidence.contains("failed to load applicationcontext")
                || evidence.contains("beancreationexception")
                || evidence.contains("nosuchbeandefinitionexception")
                || evidence.contains("unsatisfieddependencyexception")
                || evidence.contains("web server failed to start")) {
            diagnostic.setCategory("FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE");
            diagnostic.setSuggestion("框架上下文（Spring/容器）在测试环境启动失败，这是环境问题而不是业务前置条件或 Oracle 问题。"
                    + "为被测协作者提供 @Mock 注入并绕过容器启动路径；"
                    + "若被测方法必须依赖容器，该用例不适合当前自动生成。");
        } else if (evidence.contains("nosuchmethoderror")
                || evidence.contains("nosuchfielderror")
                || evidence.contains("abstractmethoderror")
                || evidence.contains("illegalaccesserror")
                || evidence.contains("incompatibleclasschangeerror")
                || evidence.contains("unsatisfiedlinkerror")
                || evidence.contains("serviceconfigurationerror")
                || evidence.contains("linkageerror")
                || evidence.contains("unsupported class file major version")) {
            diagnostic.setCategory("RUNTIME_LINKAGE_VERSION_CONFLICT");
            diagnostic.setSuggestion("依赖版本冲突或链接失败（同名依赖不同版本共存/模块未开放）。"
                    + "这是环境问题而不是 Oracle 证据问题：请检查测试 classpath 中同名依赖的版本一致性，"
                    + "或为反射路径补充 --add-opens 类启动参数。");
        } else if (evidence.contains("noclassdeffounderror")
                || evidence.contains("classnotfoundexception")
                || evidence.contains("typenotpresentexception")
                || evidence.contains("no suitable driver")
                || evidence.contains("not supported by default")) {
            diagnostic.setCategory("RUNTIME_DEPENDENCY_UNAVAILABLE");
            diagnostic.setSuggestion("测试运行时缺少依赖类、驱动或可选模块，这是环境问题而不是 Oracle 证据问题。"
                    + "请确认项目测试 classpath 包含该依赖及正确版本；"
                    + "若被测代码必须依赖该环境才能运行，该用例不适合在当前环境自动生成。");
        } else if (evidence.contains("bindingexception")
                || evidence.contains("lazyinitializationexception")
                || evidence.contains("persistenceexception")) {
            diagnostic.setCategory("ORM_MAPPING_RUNTIME_FAILURE");
            diagnostic.setSuggestion("ORM 映射或会话使用错误（MyBatis 绑定/Hibernate 懒加载等）。"
                    + "单测不应触达真实 Mapper/Session：为对应协作者提供 mock 并断言其交互，"
                    + "或构造已加载完毕的领域对象。");
        } else if (evidence.contains("connectexception")
                || evidence.contains("unknownhostexception")
                || evidence.contains("sockettimeoutexception")
                || evidence.contains("bindexception")
                || evidence.contains("sslexception")
                || evidence.contains("sqlexception")
                || evidence.contains("flywayexception")
                || evidence.contains("connection refused")
                || evidence.contains("address already in use")
                || evidence.contains("read timed out")
                || evidence.contains("connect timed out")
                || evidence.contains("unable to obtain connection")
                || evidence.contains("connection is not available")) {
            diagnostic.setCategory("RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE");
            diagnostic.setSuggestion("测试执行期间访问了真实外部资源（数据库/网络/端口），这是环境问题而不是 Oracle 证据问题。"
                    + "为相关协作者（HTTP 客户端/数据源）提供 mock 或替身；"
                    + "若被测方法必须访问真实资源，该用例不适合在当前环境自动生成。");
        } else if (evidence.contains("outofmemoryerror")
                || evidence.contains("stackoverflowerror")
                || evidence.contains("timeoutexception")) {
            diagnostic.setCategory("RUNTIME_RESOURCE_LIMIT_EXCEEDED");
            diagnostic.setSuggestion("运行资源耗尽或执行超时（内存/栈/超时），这是环境问题而不是 Oracle 证据问题。"
                    + "检查被测路径是否存在大数据量构造或潜在死循环，必要时缩小输入规模。");
        } else if (evidence.contains("invalidformatexception")
                || evidence.contains("unrecognizedpropertyexception")) {
            diagnostic.setCategory("DATA_BINDING_INPUT_INVALID");
            diagnostic.setSuggestion("序列化框架无法绑定当前输入（格式/字段不匹配）。"
                    + "按目标类型的真实模式修正输入字面量（日期格式、字段名、大小写），"
                    + "或改用框架认可的构造方式。");
        } else if (evidence.contains("assertionfailederror") || evidence.contains("assertionerror")) {
            boolean hasValues = diagnostic.getExpectedValue() != null && !diagnostic.getExpectedValue().isBlank()
                    || diagnostic.getActualValue() != null && !diagnostic.getActualValue().isBlank();
            diagnostic.setCategory(hasValues ? "ASSERTION_VALUE_MISMATCH" : "ASSERTION_FAILURE");
            diagnostic.setSuggestion(hasValues
                    ? "这是生成测试的断言值不一致。先用明确用户需求、已有测试或独立合同事实仲裁契约："
                    + "若它们证明原期望值正确，标记疑似被测代码缺陷；只有它们证明原期望值错误时才修改断言。"
                    + "生产源码当前实现和 actualValue 本身不是独立契约；证据不足时必须保留失败并等待人工确认。"
                    : "先根据被测源码、已有测试和明确需求确认真实契约，再修正 Arrange 或精确断言；不得删除或弱化断言。");
        } else if (headEvidence.contains("illegalargumentexception")
                || headEvidence.contains("illegalstateexception")
                || headEvidence.contains("numberformatexception")
                || headEvidence.contains("arithmeticexception")
                || headEvidence.contains("malformedurlexception")
                || headEvidence.contains("validationexception")
                || headEvidence.contains("selectorparseexception")) {
            diagnostic.setCategory("DOMAIN_PRECONDITION_FAILURE");
            diagnostic.setSuggestion("输入或 fixture 未满足被调用业务方法的前置条件；沿首个项目源码栈帧检查对象状态和关联实体。");
        } else if (headEvidence.contains("nullpointerexception")) {
            diagnostic.setCategory("NULL_FIXTURE_FAILURE");
            diagnostic.setSuggestion("定位首个项目源码栈帧，补齐该路径实际读取的 fixture 或 mock 返回值。");
        } else if (diagnostic.getCategory() == null || diagnostic.getCategory().isBlank()) {
            diagnostic.setCategory("TEST_RUNTIME_FAILURE");
            diagnostic.setSuggestion("根据失败测试源码行和首个项目源码栈帧修复最早的 Arrange/Act 根因。");
        }
    }

    private static boolean isInvocationCountMismatch(RuntimeDiagnostic diagnostic, String evidence) {
        if (diagnostic == null) return false;
        String raw = evidence == null ? "" : evidence;
        String lower = raw.toLowerCase(Locale.ROOT);
        Matcher wanted = WANTED_COUNT.matcher(raw);
        Matcher actual = ACTUAL_COUNT.matcher(raw);
        boolean parsed = wanted.find() && actual.find();
        if (parsed) {
            diagnostic.setExpectedInvocationCount(Integer.parseInt(wanted.group(1)));
            diagnostic.setActualInvocationCount(Integer.parseInt(actual.group(1)));
        }
        return lower.contains("toomanyactualinvocations")
                || lower.contains("too few actual invocations")
                || parsed && diagnostic.getExpectedInvocationCount() != diagnostic.getActualInvocationCount();
    }

    private static boolean isMissingMethodInvocation(RuntimeDiagnostic diagnostic) {
        if (diagnostic == null) return false;
        String evidence = (diagnostic.getExceptionType() + "\n" + diagnostic.getMessage()
                + "\n" + diagnostic.getFailureDetails())
                .toLowerCase(Locale.ROOT);
        return evidence.contains("missingmethodinvocationexception")
                || evidence.contains("when() requires an argument")
                || evidence.contains("has to be 'a method call on a mock'");
    }

    private static int sourceLine(List<String> stack, String preferredClass) {
        if (stack == null) return -1;
        if (preferredClass != null && !preferredClass.isBlank()) {
            for (String entry : stack) {
                String value = entry == null ? "" : entry;
                if (!value.contains(preferredClass + ".")) continue;
                Matcher matcher = JAVA_LINE.matcher(value);
                if (matcher.find()) return Integer.parseInt(matcher.group(1));
            }
        }
        for (String entry : stack) {
            Matcher matcher = JAVA_LINE.matcher(entry == null ? "" : entry);
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    private static String testSimpleName(RuntimeDiagnostic diagnostic) {
        if (diagnostic == null || diagnostic.getTestId() == null) return "";
        String id = diagnostic.getTestId();
        int hash = id.indexOf('#');
        if (hash >= 0) id = id.substring(0, hash);
        int dot = id.lastIndexOf('.');
        return dot < 0 ? id : id.substring(dot + 1);
    }

    private static String testMethodName(RuntimeDiagnostic diagnostic) {
        if (diagnostic == null || diagnostic.getTestId() == null) return "";
        String id = diagnostic.getTestId();
        int hash = id.indexOf('#');
        if (hash >= 0) id = id.substring(hash + 1);
        int parenthesis = id.indexOf('(');
        if (parenthesis >= 0) id = id.substring(0, parenthesis);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        return id.strip();
    }

    private static CompileDiagnostic relatedPreflight(List<CompileDiagnostic> diagnostics, int preferredLine) {
        CompileDiagnostic fallback = null;
        for (CompileDiagnostic diagnostic : diagnostics) {
            if (!diagnostic.getCode().startsWith("MOCKITO_WHEN_")
                    && !"MOCKITO_UNSTUBBABLE_OBJECT_METHOD".equals(diagnostic.getCode())) continue;
            if (diagnostic.getLine() == preferredLine) return diagnostic;
            if (fallback == null) fallback = diagnostic;
        }
        return fallback;
    }

    private static String sourceLine(String code, int line) {
        if (code == null || line <= 0) return "";
        String[] lines = code.split("\\R", -1);
        return line <= lines.length ? lines[line - 1].strip() : "";
    }

    private static void enrichFrames(RuntimeDiagnostic diagnostic) {
        List<String> stack = diagnostic.getStackTrace();
        if (stack == null || stack.isEmpty()) return;
        String testClass = testSimpleName(diagnostic);
        String testFrame = "";
        String productionFrame = "";
        String rootCauseFrame = "";
        for (String entry : stack) {
            String frame = entry == null ? "" : entry;
            if (testFrame.isBlank() && !testClass.isBlank() && frame.contains(testClass + ".")) {
                testFrame = frame;
            }
            if (!isFrameworkFrame(frame) && (testClass.isBlank() || !frame.contains(testClass + "."))) {
                if (productionFrame.isBlank()) productionFrame = frame;
                rootCauseFrame = frame;
            }
        }
        diagnostic.setTestFrame(testFrame);
        diagnostic.setProductionFrame(productionFrame);
        diagnostic.setRootCauseFrame(rootCauseFrame);
    }

    private static boolean isFrameworkFrame(String frame) {
        String value = frame == null ? "" : frame.toLowerCase(Locale.ROOT);
        return value.isBlank() || value.startsWith("caused by:") || value.contains(" org.junit.")
                || value.contains(" org.mockito.") || value.contains(" org.opentest4j.")
                || value.contains(" java.base/") || value.contains(" java.")
                || value.contains(" jdk.") || value.contains(" sun.");
    }
}
