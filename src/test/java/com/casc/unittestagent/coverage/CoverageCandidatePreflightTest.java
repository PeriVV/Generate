package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CompileDiagnostic;
import junit.framework.TestCase;

import java.util.List;

public class CoverageCandidatePreflightTest extends TestCase {
    public void testQualifiedStaticCallsCannotHideInsideOrdinaryMockitoStubbing() {
        for (String stub : List.of("when", "org.mockito.Mockito.when", "org.mockito.BDDMockito.given")) {
            String code = "import static org.mockito.Mockito.*; class T { void test() { " + stub
                    + "(example.transport.RemoteClient.exec(eq(session), anyString(), any())).thenReturn(\"ok\"); } }";
            assertTrue(code, CoverageCandidatePreflight.inspect(code).stream().anyMatch(d ->
                    "MOCKITO_WHEN_ON_REAL_OBJECT".equals(d.getCode())
                            && d.getMessage().contains("example.transport.RemoteClient")));
        }
    }

    public void testQualifiedObjectAndScopedStaticMockAreNotPlainStaticStubbing() {
        for (String statement : List.of(
                "when(holder.Client.exec(anyString())).thenReturn(\"ok\");",
                "scoped.when(() -> example.transport.RemoteClient.exec(anyString())).thenReturn(\"ok\");")) {
            String code = "import static org.mockito.Mockito.*; class T { Holder holder; void test() { " + statement + " } }";
            assertTrue(CoverageCandidatePreflight.inspect(code).toString(), CoverageCandidatePreflight.inspect(code).isEmpty());
        }
    }

    public void testRejectsRealFieldAssignedThroughThis() {
        String code = """
                import static org.mockito.Mockito.when;
                class SubjectTest {
                    private Owner owner;
                    void setUp() { this.owner = new Owner(); }
                    void test() { when(owner.getName()).thenReturn("x"); }
                }
                """;

        assertTrue(CoverageCandidatePreflight.inspect(code).stream()
                .anyMatch(item -> "MOCKITO_WHEN_ON_REAL_OBJECT".equals(item.getCode())));
    }

    public void testRejectsReflectionFieldGuessing() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                class SubjectTest {
                    void test() throws Exception {
                        Subject.class.getDeclaredField("guessedPrivateState");
                    }
                }
                """);

        assertTrue(diagnostics.stream().anyMatch(item ->
                "REFLECTION_FIELD_ACCESS_UNSAFE".equals(item.getCode())));
    }

    public void testReflectionFieldMessagePointsToConstructorsAndMocks() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                class SubjectTest {
                    void test() throws Exception {
                        Subject.class.getDeclaredField("guessedPrivateState");
                    }
                }
                """);

        CompileDiagnostic diagnostic = diagnostics.stream()
                .filter(item -> "REFLECTION_FIELD_ACCESS_UNSAFE".equals(item.getCode()))
                .findFirst().orElseThrow();
        assertTrue(diagnostic.getMessage(), diagnostic.getMessage().contains("guessedPrivateState"));
        assertTrue(diagnostic.getMessage(), diagnostic.getMessage().contains("symbolSnapshot"));
        assertTrue(diagnostic.getMessage(), diagnostic.getMessage().contains("@Mock"));
    }

    public void testRejectsAssertThrowsThatDirectlyInvokesReflection() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import java.lang.reflect.Method;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class SubjectTest {
                    void test(Method method, Object subject) {
                        assertThrows(NullPointerException.class, () -> method.invoke(subject));
                    }
                }
                """);

        CompileDiagnostic diagnostic = diagnostics.stream()
                .filter(item -> "REFLECTION_ASSERT_THROWS_WRAPS_CAUSE".equals(item.getCode()))
                .findFirst().orElse(null);
        assertNotNull(diagnostics.toString(), diagnostic);
        assertTrue(diagnostic.getMessage().contains("getCause"));
    }

    public void testAllowsAssertThrowsThatUnwrapsReflectiveCause() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import java.lang.reflect.InvocationTargetException;
                import java.lang.reflect.Method;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class SubjectTest {
                    void test(Method method, Object subject) {
                        assertThrows(NullPointerException.class, () -> {
                            try { method.invoke(subject); }
                            catch (InvocationTargetException ex) { throw ex.getCause(); }
                        });
                    }
                }
                """);

        assertFalse(diagnostics.toString(), diagnostics.stream().anyMatch(item ->
                "REFLECTION_ASSERT_THROWS_WRAPS_CAUSE".equals(item.getCode())));
    }

    public void testRejectsMatcherUsedAsRealArgument() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import static org.mockito.ArgumentMatchers.any;
                class SubjectTest {
                    void test() { new Subject().call(any(String.class)); }
                }
                """);

        assertTrue(diagnostics.stream().anyMatch(item ->
                "MOCKITO_MATCHER_OUTSIDE_STUBBING".equals(item.getCode())));
    }

    public void testAllowsMatchersInsideStubbingAndVerification() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import static org.mockito.Mockito.*;
                class SubjectTest {
                    void test(Service service) {
                        when(service.call(any(String.class), eq(1))).thenReturn("ok");
                        verify(service).call(any(String.class), eq(1));
                    }
                }
                """);

        assertFalse(diagnostics.toString(), diagnostics.stream().anyMatch(item ->
                "MOCKITO_MATCHER_OUTSIDE_STUBBING".equals(item.getCode())));
    }

    public void testAllowsOrdinaryHelperNamedLikeMatcherWithoutMockitoImport() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                class SubjectTest {
                    boolean matches(String value) { return value != null; }
                    void test() { if (matches("ok")) new Subject().run(); }
                }
                """);

        assertFalse(diagnostics.toString(), diagnostics.stream().anyMatch(item ->
                "MOCKITO_MATCHER_OUTSIDE_STUBBING".equals(item.getCode())));
    }

    public void testRejectsWhenOnInjectMocksSubject() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import org.mockito.InjectMocks;
                import static org.mockito.Mockito.when;
                class SubjectTest {
                    @InjectMocks Subject subject;
                    void test() { when(subject.calculate()).thenReturn(1); }
                }
                """);

        CompileDiagnostic diagnostic = diagnostics.stream()
                .filter(item -> "MOCKITO_WHEN_ON_REAL_OBJECT".equals(item.getCode()))
                .findFirst().orElse(null);
        assertNotNull(diagnostics.toString(), diagnostic);
        assertEquals(5, diagnostic.getLine());
        assertTrue(diagnostic.getMessage().contains("subject"));
        assertTrue(diagnostic.getMessage().contains("@Mock 依赖"));
    }

    public void testRejectsWhenOnConstructedDtoAndStaticCall() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import static org.mockito.Mockito.when;
                class SubjectTest {
                    void test() {
                        Visit visit = new Visit();
                        when(visit.getDate()).thenReturn(null);
                        when(Visit.today()).thenReturn(null);
                    }
                }
                """);

        assertEquals(2, diagnostics.stream()
                .filter(item -> "MOCKITO_WHEN_ON_REAL_OBJECT".equals(item.getCode())).count());
    }

    public void testRejectsWhenArgumentThatIsNotMethodCall() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import static org.mockito.Mockito.when;
                class SubjectTest { void test() { when("value").thenReturn("x"); } }
                """);

        assertTrue(diagnostics.stream().anyMatch(item ->
                "MOCKITO_WHEN_WITHOUT_INVOCATION".equals(item.getCode())));
    }

    public void testAllowsWhenOnFieldAndLocalMocks() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import org.mockito.Mock;
                import static org.mockito.Mockito.*;
                class SubjectTest {
                    @Mock Service service;
                    void test() {
                        Request request = mock(Request.class);
                        when(service.call()).thenReturn("ok");
                        when(request.name()).thenReturn("pet");
                    }
                }
                """);

        assertFalse(diagnostics.toString(), diagnostics.stream().anyMatch(item ->
                item.getCode().startsWith("MOCKITO_WHEN_")));
    }

    public void testRejectsWhenOnAnnotatedAndLocalSpies() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import org.mockito.Spy;
                import static org.mockito.Mockito.*;
                class SubjectTest {
                    @Spy Service fieldSpy;
                    void test() {
                        Service localSpy = spy(new Service());
                        when(fieldSpy.call()).thenReturn(true);
                        when(localSpy.call()).thenReturn(true);
                    }
                }
                """);

        List<CompileDiagnostic> spyDiagnostics = diagnostics.stream()
                .filter(item -> "MOCKITO_WHEN_ON_SPY".equals(item.getCode()))
                .toList();
        assertEquals(diagnostics.toString(), 2, spyDiagnostics.size());
        assertTrue(spyDiagnostics.stream().allMatch(item -> item.getMessage().contains("doReturn")));
    }

    public void testAllowsDoReturnOnSpy() {
        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspect("""
                import org.mockito.Spy;
                import static org.mockito.Mockito.doReturn;
                class SubjectTest {
                    @Spy Service service;
                    void test() { doReturn(true).when(service).call(); }
                }
                """);

        assertFalse(diagnostics.toString(), diagnostics.stream().anyMatch(item ->
                "MOCKITO_WHEN_ON_SPY".equals(item.getCode())));
    }

    public void testGeneratedRegionsExemptLegacyCodeInMergedFile() {
        // 写入门禁：用户既有测试（region 之外）的合法遗留写法（@Spy + when）不得被候选
        // 引导规则误判为编译失败；生成区间内的违规（getDeclaredField）仍须拦截。
        String merged = """
                package sample;
                import org.mockito.Spy;
                import static org.mockito.Mockito.when;
                class SubjectTest {
                    @Spy OrderValidator validator;

                    @Test void legacyUsesWhenOnSpy() {
                        when(validator.validate("a")).thenReturn(true);
                    }

                    // region UnitMind generated tests for sample.Subject#calc()
                    @Test void generatedUsesReflection() {
                        Object field = new Subject().getClass().getDeclaredField("hidden");
                    }
                    // endregion
                }
                """;
        // 补充 import 使文本可解析。
        merged = merged.replace("import org.mockito.Spy;",
                "import org.junit.jupiter.api.Test;\nimport org.mockito.Spy;");

        List<CompileDiagnostic> full = CoverageCandidatePreflight.inspect(merged);
        assertTrue(full.stream().anyMatch(item -> "MOCKITO_WHEN_ON_SPY".equals(item.getCode())));
        assertTrue(full.stream().anyMatch(item -> "REFLECTION_FIELD_ACCESS_UNSAFE".equals(item.getCode())));

        List<CompileDiagnostic> regionFiltered = CoverageCandidatePreflight.inspectGeneratedRegions(merged);
        assertTrue(regionFiltered.toString(), regionFiltered.stream().noneMatch(item ->
                "MOCKITO_WHEN_ON_SPY".equals(item.getCode())));
        assertTrue(regionFiltered.stream().anyMatch(item ->
                "REFLECTION_FIELD_ACCESS_UNSAFE".equals(item.getCode())));
    }

    public void testGeneratedRegionsKeepFullCheckWithoutMarkers() {
        // 无 region 标记 = 纯生成文件（覆盖率闭环候选），保持全量检查。
        String generated = """
                package sample;
                import org.mockito.Spy;
                import static org.mockito.Mockito.when;
                class SubjectTest {
                    @Spy OrderValidator validator;
                    @Test void test() { when(validator.validate("a")).thenReturn(true); }
                }
                """.replace("import org.mockito.Spy;",
                        "import org.junit.jupiter.api.Test;\nimport org.mockito.Spy;");

        List<CompileDiagnostic> diagnostics = CoverageCandidatePreflight.inspectGeneratedRegions(generated);
        assertTrue(diagnostics.stream().anyMatch(item -> "MOCKITO_WHEN_ON_SPY".equals(item.getCode())));
    }
}
