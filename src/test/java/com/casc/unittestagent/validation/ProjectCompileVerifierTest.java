package com.casc.unittestagent.validation;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public class ProjectCompileVerifierTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor REAL_JDK = new LightProjectDescriptor() {
        @Override
        public Sdk getSdk() {
            return JavaSdk.getInstance().createJdk(
                    "UnitMindTestJdk",
                    System.getProperty("java.home"),
                    false);
        }
    };

    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        return REAL_JDK;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
    }

    public void testRejectsSyntaxErrorsBeforeEnvironmentChecks() {
        PsiFile source = myFixture.configureByText("Subject.java", "package sample; class Subject {}");
        GeneratedTestFile generated = generatedFile();

        ValidationResult result = new ProjectCompileVerifier().validate(
                getProject(), source, generated, "package sample; class SubjectTest { void broken( }");

        assertEquals(result.getDiagnostics().toString(), ValidationStatus.FAILED, result.getStatus());
        assertTrue(result.getDiagnostics().stream().anyMatch(item -> "JAVA_SYNTAX".equals(item.getCode())));
    }

    public void testReportsMissingTestFrameworkAsEnvironmentError() {
        PsiFile source = myFixture.configureByText("Subject.java", "package sample; class Subject {}");
        GeneratedTestFile generated = generatedFile();

        ValidationResult result = new ProjectCompileVerifier().validate(
                getProject(), source, generated, "package sample; class SubjectTest {}");

        assertEquals(ValidationStatus.ENVIRONMENT_ERROR, result.getStatus());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(item -> "JUNIT5_NOT_FOUND".equals(item.getCode()) || "MOCKITO_NOT_FOUND".equals(item.getCode())));
    }

    public void testCompilesValidCandidateAgainstProjectSourcePath() {
        addFrameworkStubs();
        PsiFile source = myFixture.configureByText(
                "Subject.java",
                "package sample; public class Subject { public String existing() { return \"ok\"; } }");
        String code = """
                package sample;
                class SubjectTest {
                    void shouldCallExistingMethod() {
                        String value = new Subject().existing();
                    }
                }
                """;

        ValidationResult result = new ProjectCompileVerifier().validate(getProject(), source, generatedFile(), code);

        assertEquals(result.getDiagnostics().toString(), ValidationStatus.PASSED, result.getStatus());
    }

    public void testRejectsHallucinatedProjectMethod() {
        addFrameworkStubs();
        PsiFile source = myFixture.configureByText(
                "Subject.java",
                "package sample; public class Subject { public String existing() { return \"ok\"; } }");
        String code = """
                package sample;
                class SubjectTest {
                    void shouldNotInventMethods() {
                        new Subject().missing();
                    }
                }
                """;

        ValidationResult result = new ProjectCompileVerifier().validate(getProject(), source, generatedFile(), code);

        assertEquals(result.getDiagnostics().toString(), ValidationStatus.FAILED, result.getStatus());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(item -> "CANNOT_FIND_SYMBOL".equals(item.getCode())));
    }

    public void testNormalizesCommonRepairableCompilerFailures() {
        addFrameworkStubs();
        PsiFile source = myFixture.configureByText(
                "Subject.java",
                """
                        package sample;
                        public class Subject {
                            public Subject(String value) {}
                            public String existing() { return "ok"; }
                            public String existing(String value) { return value; }
                            private void hidden() {}
                        }
                        class Mockito {
                            static <T> OngoingStubbing<T> when(T value) { return null; }
                        }
                        interface OngoingStubbing<T> {
                            OngoingStubbing<T> thenReturn(T value);
                        }
                        """);

        assertDiagnostic(source,
                "class SubjectTest { void test() { new Subject(); } }",
                "NO_SUITABLE_METHOD");
        assertDiagnostic(source,
                "class SubjectTest { void test() { new Subject(\"x\").existing(1); } }",
                "INCOMPATIBLE_TYPES");
        assertDiagnostic(source,
                "class SubjectTest { void test() { List<String> values = null; } }",
                "CANNOT_FIND_SYMBOL");
        assertDiagnostic(source,
                "class SubjectTest { void test() { int value = new Subject(\"x\").existing(); } }",
                "INCOMPATIBLE_TYPES");
        assertDiagnostic(source,
                "class SubjectTest { void test() { new Subject(\"x\").hidden(); } }",
                "INACCESSIBLE_MEMBER");
        assertDiagnostic(source,
                "class SubjectTest { void test() { new Subject(\"x\"); Mockito.when(\"x\").thenReturn(1); } }",
                "MOCKITO_WHEN_WITHOUT_INVOCATION");
    }

    public void testTargetSourceFailureIsNotReportedAsRepairableTestFailure() {
        addFrameworkStubs();
        PsiFile source = myFixture.configureByText(
                "Subject.java",
                "package sample; public class Subject { MissingProjectType value; }");

        ValidationResult result = new ProjectCompileVerifier().validate(
                getProject(), source, generatedFile(),
                "package sample; class SubjectTest { Subject value = new Subject(); }");

        assertEquals(result.getDiagnostics().toString(), ValidationStatus.ENVIRONMENT_ERROR, result.getStatus());
        CompileDiagnostic diagnostic = result.getDiagnostics().stream()
                .filter(item -> "TARGET_SOURCE".equals(item.getSourceOrigin()))
                .findFirst().orElse(null);
        assertNotNull(result.getDiagnostics().toString(), diagnostic);
        assertFalse(diagnostic.isRepairable());
        assertTrue(diagnostic.getSnippet().contains("MissingProjectType"));
    }

    private void assertDiagnostic(PsiFile source, String classBody, String expectedCode) {
        String code = "package sample;\n" + classBody;
        ValidationResult result = new ProjectCompileVerifier().validate(getProject(), source, generatedFile(), code);
        assertEquals(result.getDiagnostics().toString(), ValidationStatus.FAILED, result.getStatus());
        assertTrue(result.getDiagnostics().toString(), result.getDiagnostics().stream()
                .anyMatch(item -> expectedCode.equals(item.getCode())));
    }

    private void addFrameworkStubs() {
        myFixture.addClass("package org.junit.jupiter.api; public @interface Test {}");
        myFixture.addClass("""
                package org.mockito.stubbing;
                public interface OngoingStubbing<T> {
                    OngoingStubbing<T> thenReturn(T value);
                }
                """);
        myFixture.addClass("""
                package org.mockito;
                public final class Mockito {
                    public static <T> org.mockito.stubbing.OngoingStubbing<T> when(T value) {
                        return null;
                    }
                }
                """);
    }

    private GeneratedTestFile generatedFile() {
        GeneratedTestFile file = new GeneratedTestFile();
        file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java");
        file.setPackageName("sample");
        return file;
    }
}
