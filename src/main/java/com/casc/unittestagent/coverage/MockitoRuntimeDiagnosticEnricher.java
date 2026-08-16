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
        String actualType = diagnostic.getActualExceptionType() == null
                ? "" : diagnostic.getActualExceptionType().toLowerCase(Locale.ROOT);
        if ((actualType.contains("invocationtargetexception")
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
        } else if (evidence.contains("assertionfailederror") || evidence.contains("assertionerror")) {
            boolean hasValues = diagnostic.getExpectedValue() != null && !diagnostic.getExpectedValue().isBlank()
                    || diagnostic.getActualValue() != null && !diagnostic.getActualValue().isBlank();
            diagnostic.setCategory(hasValues ? "ASSERTION_VALUE_MISMATCH" : "ASSERTION_FAILURE");
            diagnostic.setSuggestion(hasValues
                    ? "这是生成测试的断言值不一致。先用被测源码、已有测试和明确需求仲裁契约："
                    + "若实际值与现有契约一致，精确修正生成断言；若期望值有权威依据，标记疑似被测代码缺陷；"
                    + "证据不足时不得在 Arrange 与 Assert 之间盲目往返。"
                    : "先根据被测源码、已有测试和明确需求确认真实契约，再修正 Arrange 或精确断言；不得删除或弱化断言。");
        } else if (evidence.contains("illegalargumentexception")
                || evidence.contains("illegalstateexception")) {
            diagnostic.setCategory("DOMAIN_PRECONDITION_FAILURE");
            diagnostic.setSuggestion("输入或 fixture 未满足被调用业务方法的前置条件；沿首个项目源码栈帧检查对象状态和关联实体。");
        } else if (evidence.contains("nullpointerexception")) {
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
