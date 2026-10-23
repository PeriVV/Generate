package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.List;

public class NestedFixtureDependencyTest extends LightJavaCodeInsightFixtureTestCase {
    private static final String TARGET = "class Subject { private AuditService audit; }";
    private static final String AUDIT = """
            package sample;
            class AuditService {
                private final Repository repository;
                AuditService(Repository storage) { this.repository = storage; }
                void record() { repository.save(); }
            }
            """;
    private static final String TEST = """
            package sample;
            class SubjectTest {
                @Mock Repository repository;
                AuditService audit;
                @BeforeEach void setUp() { audit = fixture(); }
                AuditService fixture() { return new AuditService(null); }
                @Test void records() { subject.run(); }
                @Test void unrelated() { }
            }
            """;

    public void testProvesExplicitNullInReachableHelperEvenWithUnusedMockDeclared() {
        myFixture.addFileToProject("sample/AuditService.java", AUDIT);
        RuntimeDiagnostic diagnostic = diagnostic();
        var result = MissingFixtureDependencyDetector.inspect(getProject(), TARGET, TEST, List.of(diagnostic));
        assertTrue(result.getMissingDependencies().toString(), result.isProven());
        assertEquals("FIXTURE_DEPENDENCY_MISSING", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion(), diagnostic.getSuggestion().contains("Repository repository"));
        assertTrue(diagnostic.getSuggestion().contains("构造参数[1]"));
        assertTrue(diagnostic.getSuggestion().contains("保持原断言"));
    }

    public void testLombokRequiredConstructorIgnoresStaticAndInitializedFields() {
        myFixture.addFileToProject("sample/AuditService.java", """
                package sample;
                @lombok.RequiredArgsConstructor class AuditService {
                    private static final String LABEL = "audit";
                    private final int initialized = 1;
                    private final Repository repository;
                    void record() { repository.save(); }
                }
                """);
        assertTrue(MissingFixtureDependencyDetector.inspect(getProject(), TARGET, TEST,
                List.of(diagnostic())).isProven());
    }

    public void testDoesNotUseNullConstructorInUnrelatedTest() {
        myFixture.addFileToProject("sample/AuditService.java", AUDIT);
        String unrelated = TEST.replace("return new AuditService(null)", "return new AuditService(repository)")
                .replace("void unrelated() { }", "void unrelated() { new AuditService(null); }");
        assertFalse(MissingFixtureDependencyDetector.inspect(getProject(), TARGET, unrelated,
                List.of(diagnostic())).isProven());
    }

    public void testDoesNotGuessWhenConstructorIsAmbiguous() {
        myFixture.addFileToProject("sample/AuditService.java", AUDIT.replace("void record()",
                "AuditService(String value) { this.repository = null; } void record()"));
        assertFalse(MissingFixtureDependencyDetector.inspect(getProject(), TARGET, TEST,
                List.of(diagnostic())).isProven());
    }

    public void testRequiresRealMatchingStackAndExplicitNullArgument() {
        myFixture.addFileToProject("sample/AuditService.java", AUDIT);
        assertFalse(MissingFixtureDependencyDetector.inspect(getProject(), TARGET,
                TEST.replace("new AuditService(null)", "new AuditService(repository)"), List.of(diagnostic())).isProven());
        RuntimeDiagnostic noStack = diagnostic(); noStack.setStackTrace(List.of());
        assertFalse(MissingFixtureDependencyDetector.inspect(getProject(), TARGET, TEST, List.of(noStack)).isProven());
    }

    public void testInitializationErrorWithNestedNpeRemainsEnvironmentFailure() {
        myFixture.addFileToProject("sample/AuditService.java", AUDIT);
        RuntimeDiagnostic wrapped = diagnostic();
        wrapped.setExceptionType("java.lang.ExceptionInInitializerError");
        wrapped.setRootCauseType("java.lang.NullPointerException");
        wrapped.setCategory("RUNTIME_STATIC_INIT_FAILURE");
        assertFalse(MissingFixtureDependencyDetector.inspect(getProject(), TARGET, TEST, List.of(wrapped)).isProven());
        assertEquals("RUNTIME_STATIC_INIT_FAILURE", wrapped.getCategory());
    }

    private RuntimeDiagnostic diagnostic() {
        RuntimeDiagnostic d = new RuntimeDiagnostic();
        d.setTestId("sample.SubjectTest#records()");
        d.setExceptionType("java.lang.NullPointerException");
        d.setCategory("NULL_FIXTURE_FAILURE");
        d.setMessage("Cannot invoke \"sample.Repository.save()\" because \"this.repository\" is null");
        d.setStackTrace(List.of("at sample.AuditService.record(AuditService.java:5)",
                "at sample.Subject.run(Subject.java:6)", "at sample.SubjectTest.records(SubjectTest.java:8)"));
        return d;
    }
}
