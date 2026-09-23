package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import junit.framework.TestCase;

import java.util.List;

public class MockitoRuntimeDiagnosticEnricherTest extends TestCase {
    public void testEnrichesGenericDomainFailureWithTestSourceLocation() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#shouldWork()");
        diagnostic.setExceptionType("java.lang.IllegalArgumentException");
        diagnostic.setMessage("invalid aggregate");
        diagnostic.setStackTrace(List.of(
                "at sample.Subject.run(Subject.java:12)",
                "at sample.SubjectTest.shouldWork(SubjectTest.java:5)"));

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), """
                class SubjectTest {
                    void helper() {}
                    @Test
                    void shouldWork() {
                        new Subject().run();
                    }
                }
                """);

        assertEquals("DOMAIN_PRECONDITION_FAILURE", diagnostic.getCategory());
        assertEquals(5, diagnostic.getSourceLine());
        assertTrue(diagnostic.getSourceSnippet().contains("new Subject().run"));
        assertFalse(diagnostic.getSuggestion().isBlank());
    }

    public void testLinksMissingInvocationToIllegalWhenSource() {
        String code = """
                import org.mockito.InjectMocks;
                import static org.mockito.Mockito.when;
                class VisitControllerTest {
                    @InjectMocks VisitController controller;
                    void shouldThrow() { when(controller.load()).thenReturn(null); }
                }
                """;
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.VisitControllerTest#shouldThrow()");
        diagnostic.setExceptionType("org.mockito.exceptions.misusing.MissingMethodInvocationException");
        diagnostic.setMessage("when() requires an argument which has to be 'a method call on a mock'");
        diagnostic.setStackTrace(List.of(
                "at org.mockito.Mockito.when(Mockito.java:1)",
                "at sample.VisitControllerTest.shouldThrow(VisitControllerTest.java:5)"));

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), code);

        assertEquals("MOCKITO_MISSING_METHOD_INVOCATION", diagnostic.getCategory());
        assertEquals(5, diagnostic.getSourceLine());
        assertTrue(diagnostic.getSourceSnippet().contains("when(controller.load())"));
        assertTrue(diagnostic.getSuggestion().contains("@Mock 依赖"));
    }

    public void testProvidesGenericAdviceForUnstubabbleMethodOnKnownMock() {
        String code = """
                import org.mockito.Mock;
                import static org.mockito.Mockito.when;
                class VisitControllerTest {
                    @Mock Visit visit;
                    void shouldThrow() { when(visit.finalValue()).thenReturn(null); }
                }
                """;
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setExceptionType("org.mockito.exceptions.misusing.MissingMethodInvocationException");
        diagnostic.setStackTrace(List.of(
                "at sample.VisitControllerTest.shouldThrow(VisitControllerTest.java:5)"));

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), code);

        assertEquals(5, diagnostic.getSourceLine());
        assertTrue(diagnostic.getSuggestion().contains("private/final/native"));
    }

    public void testClassifiesWrongReturnTypeAndRecommendsSafeSpyStubbing() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#shouldWork()");
        diagnostic.setExceptionType("org.mockito.exceptions.misusing.WrongTypeOfReturnValue");
        diagnostic.setMessage("Boolean cannot be returned by cost(); cost() should return int");
        diagnostic.setStackTrace(List.of(
                "at sample.SubjectTest.shouldWork(SubjectTest.java:6)"));

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), """
                class SubjectTest {
                    void helper() {}
                    @Test
                    void shouldWork() {
                        when(subject.matches()).thenReturn(true);
                    }
                }
                """);

        assertEquals("MOCKITO_WRONG_RETURN_TYPE", diagnostic.getCategory());
        assertEquals(6, diagnostic.getSourceLine());
        assertTrue(diagnostic.getSuggestion().contains("doReturn"));
        assertTrue(diagnostic.getSuggestion().contains("真实方法签名"));
    }

    public void testClassifiesReflectiveAssertThrowsMismatch() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("org.jsoup.nodes.AttributeTest#shouldHandleBoundaryValues()");
        diagnostic.setExceptionType("org.opentest4j.AssertionFailedError");
        diagnostic.setMessage("Unexpected exception type thrown, expected: <java.lang.NullPointerException> "
                + "but was: <java.lang.reflect.InvocationTargetException>");
        diagnostic.setExpectedExceptionType("java.lang.NullPointerException");
        diagnostic.setActualExceptionType("java.lang.reflect.InvocationTargetException");
        diagnostic.setRootCauseType("java.lang.NullPointerException");
        diagnostic.setStackTrace(List.of(
                "at org.jsoup.nodes.AttributeTest.shouldHandleBoundaryValues(AttributeTest.java:12)",
                "Caused by: java.lang.reflect.InvocationTargetException",
                "Caused by: java.lang.NullPointerException"));

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), """
                class AttributeTest {
                    @Test
                    void shouldHandleBoundaryValues() {
                        assertThrows(NullPointerException.class, () -> method.invoke(attribute, (Object) null));
                    }
                }
                """);

        assertEquals("ASSERT_THROWS_WRAPPED_BY_REFLECTION", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("getCause"));
        assertTrue(diagnostic.getSuggestion().contains("不要把期望类型改成"));
    }

    public void testClassifiesExpectedExceptionNotThrownWithoutCallingItFixtureFailure() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.ProcessServiceTest#rejectsAtConcurrentLimit()");
        diagnostic.setExceptionType("org.opentest4j.AssertionFailedError");
        diagnostic.setMessage("Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.");
        diagnostic.setExpectedExceptionType("java.lang.IllegalStateException");

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), """
                class ProcessServiceTest {
                    @Test void rejectsAtConcurrentLimit() {
                        assertThrows(IllegalStateException.class, () -> service.start());
                    }
                }
                """);

        assertEquals("ASSERTION_EXPECTED_EXCEPTION_NOT_THROWN", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("完整生产类源码"));
        assertTrue(diagnostic.getSuggestion().contains("不得归类为 Fixture 缺失"));
    }

    public void testClassifiesContradictoryNoInteractionsOracle() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#returnsEarly()");
        diagnostic.setExceptionType("org.mockito.exceptions.verification.NoInteractionsWanted");
        diagnostic.setMessage("No interactions wanted here");
        diagnostic.setFailureDetails("But found this interaction on mock 'dao': dao.findById(\"a\")");

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), """
                class SubjectTest {
                    @Test void returnsEarly() {
                        verify(dao).findById("a");
                        verifyNoInteractions(dao);
                    }
                }
                """);

        assertEquals("MOCKITO_NO_INTERACTIONS_CONTRADICTION", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("verifyNoMoreInteractions"));
    }

    public void testClassifiesGeneratedAssertionValueMismatchForOracleAdjudication() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("org.jsoup.nodes.DocumentTypeTest#shouldHandleBoundaryValues()");
        diagnostic.setExceptionType("org.opentest4j.AssertionFailedError");
        diagnostic.setExpectedValue("<!DOCTYPE html>");
        diagnostic.setActualValue("<!DOCTYPE html PUBLIC>");
        diagnostic.setMessage("expected: <<!DOCTYPE html>> but was: <<!DOCTYPE html PUBLIC>>");

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), """
                class DocumentTypeTest {
                    @Test void shouldHandleBoundaryValues() {
                        assertEquals("<!DOCTYPE html>", type.outerHtml());
                    }
                }
                """);

        assertEquals("ASSERTION_VALUE_MISMATCH", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("仲裁契约"));
    }

    public void testClassifiesMockitoInvocationCountMismatchFromFullFailureDetails() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("org.jsoup.nodes.DocumentTypeTest#shouldHandleBoundaryValues()");
        diagnostic.setExceptionType("org.mockito.exceptions.verification.TooManyActualInvocations");
        diagnostic.setMessage("quietAppendable.append(\" \" );");
        diagnostic.setFailureDetails("""
                quietAppendable.append(" ");
                Wanted 1 time:
                -> at org.jsoup.nodes.DocumentTypeTest.shouldHandleBoundaryValues(DocumentTypeTest.java:57)
                But was 2 times:
                -> at org.jsoup.nodes.DocumentType.outerHtmlHead(DocumentType.java:98)
                -> at org.jsoup.nodes.DocumentType.outerHtmlHead(DocumentType.java:100)
                """);
        diagnostic.setStackTrace(java.util.List.of(
                "at org.jsoup.nodes.DocumentTypeTest.shouldHandleBoundaryValues(DocumentTypeTest.java:57)",
                "at org.jsoup.nodes.DocumentType.outerHtmlHead(DocumentType.java:98)"));

        MockitoRuntimeDiagnosticEnricher.enrich(java.util.List.of(diagnostic), """
                class DocumentTypeTest {
                    @Test void shouldHandleBoundaryValues() {
                        verify(quietAppendable, times(1)).append(" ");
                    }
                }
                """);

        assertEquals("MOCKITO_INVOCATION_COUNT_MISMATCH", diagnostic.getCategory());
        assertEquals(1, diagnostic.getExpectedInvocationCount());
        assertEquals(2, diagnostic.getActualInvocationCount());
        assertTrue(diagnostic.getSuggestion().contains("times(n)"));
        assertTrue(diagnostic.getSuggestion().contains("生产源码"));
        assertTrue(diagnostic.getSuggestion().contains("不得删除 verify"));
    }

    public void testClassifiesDoNothingOnNonVoidMethod() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#shouldLoad()");
        diagnostic.setExceptionType("org.mockito.exceptions.base.MockitoException");
        diagnostic.setFailureDetails("Only void methods can doNothing()!");
        diagnostic.setStackTrace(List.of(
                "at sample.SubjectTest.shouldLoad(SubjectTest.java:7)",
                "at sample.Subject.load(Subject.java:21)"));

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), """
                class SubjectTest {
                    @Test
                    void shouldLoad() {
                        doNothing().when(repository).load();
                    }
                }
                """);

        assertEquals("MOCKITO_VOID_STUB_MISUSE", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("非 void 方法"));
        assertTrue(diagnostic.getTestFrame().contains("SubjectTest.java:7"));
        assertTrue(diagnostic.getProductionFrame().contains("Subject.java:21"));
    }

    public void testClassifiesArgumentMismatchUsingFullMockitoEvidence() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#shouldWrite()");
        diagnostic.setExceptionType("org.mockito.exceptions.verification.junit.ArgumentsAreDifferent");
        diagnostic.setMessage("Argument(s) are different! Wanted: append(\"x\") Actual: append(\"y\")");
        diagnostic.setFailureDetails("Wanted append(\"x\")\nActual append(\"y\")");

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic),
                "class SubjectTest { @Test void shouldWrite() { verify(out).append(\"x\"); } }");

        assertEquals("MOCKITO_ARGUMENT_MISMATCH", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("Wanted/Actual"));
        assertTrue(diagnostic.getSuggestion().contains("返回值或状态断言"));
    }

    public void testClassifiesStaticMockRefusalAsForbiddenTarget() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("sample.SubjectTest#shouldMockSystem()");
        diagnostic.setExceptionType("org.mockito.exceptions.base.MockitoException");
        diagnostic.setMessage("It is not possible to mock static methods of class java.lang.System");
        diagnostic.setStackTrace(List.of(
                "at org.mockito.internal.MockitoCore.mockStatic(MockitoCore.java:1)",
                "at sample.SubjectTest.shouldMockSystem(SubjectTest.java:6)"));

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic),
                "class SubjectTest { @Test void shouldMockSystem() { mockStatic(System.class); } }");

        assertEquals("MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("删除 mockStatic"));
        assertEquals(6, diagnostic.getSourceLine());
    }

    public void testClassifiesCannotInstrumentRefusalAsForbiddenTarget() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setExceptionType("org.mockito.exceptions.base.MockitoException");
        diagnostic.setMessage("Cannot instrument class java.lang.Thread because it is a JDK class");

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), "class SubjectTest { @Test void t() {} }");

        assertEquals("MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET", diagnostic.getCategory());
    }

    public void testStaticMockRefusalDoesNotShadowAssertionFailures() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setExceptionType("org.opentest4j.AssertionFailedError");
        diagnostic.setMessage("expected: <2> but was: <1>");

        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic),
                "class SubjectTest { @Test void v() { assertEquals(2, run()); } }");

        // 无 expected/actual 值字段时落入 ASSERTION_FAILURE；关键是未被静态 mock 分支抢占。
        assertEquals("ASSERTION_FAILURE", diagnostic.getCategory());
    }
}
