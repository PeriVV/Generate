package com.casc.unittestagent.generator;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public class ConstructorFixturePreflightTest extends LightJavaCodeInsightFixtureTestCase {
    private PsiFile source;
    private static final String AUDIT = """
            package sample;
            public class AuditService {
                private final Repository repository;
                public AuditService(Repository repository) { this.repository = repository; }
                public void record() { repository.save(); }
            }
            """;
    private static final String TEST = """
            package sample;
            class SubjectTest {
                AuditService audit;
                // preserve this fixture comment
                @BeforeEach void setUp() { audit = new AuditService(null); }
                @Test void records() { assertEquals(7, subject.run()); }
            }
            """;
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("sample/Repository.java", "package sample; public interface Repository { void save(); }");
        source = myFixture.addFileToProject("sample/Subject.java", "package sample; public class Subject { }");
        audit(AUDIT);
    }

    public void testFillsConstructorNullBeforeAnyRuntimeAndPreservesSurroundingText() {
        var result = prepare(TEST, true);
        assertEquals(1, result.changes());
        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        assertEquals(TEST.replace("new AuditService(null)",
                "new AuditService(org.mockito.Mockito.mock(sample.Repository.class))"), result.code());
        assertEquals("package sample; public class Subject { }", source.getText());
        var repeat = prepare(result.code(), true);
        assertEquals(result.code(), repeat.code()); assertEquals(0, repeat.changes());
    }

    public void testReusesMockFieldOnlyAfterMockitoExtensionInitialization() {
        String test = TEST.replace("class SubjectTest", "import org.mockito.junit.jupiter.MockitoExtension; @ExtendWith(MockitoExtension.class) class SubjectTest")
                .replace("AuditService audit;", "@Mock Repository repository; AuditService audit;");
        var result = prepare(test, true);
        assertTrue(result.code(), result.code().contains("new AuditService(this.repository)"));
        assertFalse(result.code().contains("org.mockito.Mockito.mock"));
        var early = prepare(test.replace("AuditService audit;", "AuditService audit = new AuditService(null);"), true);
        assertTrue(early.diagnostics().toString(), early.diagnostics().stream().anyMatch(item -> item.getMessage().contains("初始化")));
    }

    public void testReusesInitializedLocalObjectWithoutCreatingAnotherMock() {
        String test = TEST.replace("class SubjectTest", "import static org.mockito.Mockito.mock; class SubjectTest")
                .replace("audit = new AuditService(null);", "Repository repository = mock(Repository.class); audit = new AuditService(null);");
        var result = prepare(test, false);
        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        assertTrue(result.code(), result.code().contains("new AuditService(repository)"));
    }

    public void testDisabledMockitoProducesGenerationDiagnosticInsteadOfUnsafeNull() {
        var result = prepare(TEST, false);
        assertEquals(TEST, result.code());
        assertEquals(1, result.diagnostics().size());
        assertEquals("FIXTURE_CONSTRUCTOR_DEPENDENCY", result.diagnostics().get(0).getCode());
        assertTrue(result.diagnostics().get(0).getMessage().contains("禁止新增 Mockito"));
        assertTrue(result.diagnostics().get(0).getLine() > 0);
    }

    public void testRecursivelyBuildsRealDependencyAndMocksOnlyInterfaceLeaf() {
        myFixture.addFileToProject("sample/Adapter.java", AUDIT.replace("AuditService", "Adapter"));
        audit(AUDIT.replace("Repository repository", "Adapter adapter")
                .replace("this.repository = repository", "this.adapter = adapter").replace("repository.save()", "adapter.record()"));
        var result = prepare(TEST, true);
        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        assertTrue(result.code(), result.code().contains("new AuditService(new sample.Adapter(org.mockito.Mockito.mock(sample.Repository.class)))"));
    }

    public void testLombokRequiredArgsIsSupportedWithoutChangingSource() {
        audit(AUDIT.replace("public class AuditService", "@lombok.RequiredArgsConstructor public class AuditService")
                .replace("public AuditService(Repository repository) { this.repository = repository; }", ""));
        var result = prepare(TEST, true);
        assertEquals(1, result.changes());
        assertTrue(result.diagnostics().isEmpty());
    }

    public void testNullableAndGuardedDependenciesArePreserved() {
        audit(AUDIT.replace("AuditService(Repository", "AuditService(@Nullable Repository"));
        assertEquals(TEST, prepare(TEST, true).code());
        audit(AUDIT.replace("repository.save();", "if (repository != null) repository.save();"));
        var guarded = prepare(TEST, true);
        assertEquals(TEST, guarded.code()); assertTrue(guarded.diagnostics().isEmpty());
    }

    public void testIntentionalNullExceptionAssertionsAreNotRewritten() {
        String test = TEST.replace("@BeforeEach void setUp() { audit = new AuditService(null); }", "")
                .replace("assertEquals(7, subject.run());", "audit = new AuditService(null); assertThrows(NullPointerException.class, () -> audit.record());");
        assertEquals(test, prepare(test, true).code());
        String inside = test.replace("audit = new AuditService(null); assertThrows(NullPointerException.class, () -> audit.record());",
                "assertThrows(NullPointerException.class, () -> new AuditService(null).record());");
        assertEquals(inside, prepare(inside, true).code());
    }

    public void testAmbiguousConstructorIsDiagnosedWithoutGuessing() {
        audit(AUDIT.replace("public void record()",
                "public AuditService(String value) { this.repository = null; } public void record()"));
        var result = prepare(TEST, true);
        assertEquals(TEST, result.code());
        assertTrue(result.diagnostics().get(0).getMessage().contains("重载"));
    }

    public void testCyclicGraphStopsWithDiagnostic() {
        myFixture.addFileToProject("sample/First.java", "package sample; public class First { final Second second; public First(Second second) { this.second=second; } public void record() { second.record(); } }");
        myFixture.addFileToProject("sample/Second.java", "package sample; public class Second { final First first; public Second(First first) { this.first=first; } public void record() { first.record(); } }");
        audit(AUDIT.replace("Repository repository", "First first")
                .replace("this.repository = repository", "this.first = first").replace("repository.save()", "first.record()"));
        var result = prepare(TEST, true);
        assertEquals(TEST, result.code());
        assertTrue(result.diagnostics().get(0).getMessage().contains("循环"));
    }

    public void testDoesNotRunOrAutomaticallyCreateBusinessConstructors() {
        myFixture.addFileToProject("sample/Adapter.java", AUDIT.replace("AuditService", "Adapter")
                .replace("this.repository = repository;", "this.repository = repository; connectExternalSystem();"));
        audit(AUDIT.replace("Repository repository", "Adapter adapter")
                .replace("this.repository = repository", "this.adapter = adapter").replace("repository.save()", "adapter.record()"));
        var result = prepare(TEST, true);
        assertEquals(TEST, result.code());
        assertTrue(result.diagnostics().get(0).getMessage().contains("业务动作"));
    }

    public void testMultipleMockFieldsDoNotSelectAnArbitraryDependency() {
        String test = TEST.replace("class SubjectTest", "import org.mockito.junit.jupiter.MockitoExtension; @ExtendWith(MockitoExtension.class) class SubjectTest")
                .replace("AuditService audit;", "@Mock Repository first; @Mock Repository second; AuditService audit;");
        var result = prepare(test, true);
        assertEquals(test, result.code());
        assertTrue(result.diagnostics().get(0).getMessage().contains("多个同类型"));
    }

    public void testOpenMocksBeforeConstructionAllowsReusingExistingField() {
        String test = TEST.replace("class SubjectTest", "import org.mockito.MockitoAnnotations; class SubjectTest")
                .replace("AuditService audit;", "@Mock Repository repository; AuditService audit;")
                .replace("audit = new AuditService(null);", "MockitoAnnotations.openMocks(this); audit = new AuditService(null);");
        var result = prepare(test, false);
        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        assertTrue(result.code(), result.code().contains("new AuditService(this.repository)"));
    }

    public void testExceptionTestHelperAndNegativeOnlySetupAreProtected() {
        String helpers = """
                package sample;
                class SubjectTest {
                    @Test void negative() { assertThrows(NullPointerException.class, () -> invalid().record()); }
                    AuditService invalid() { return new AuditService(null); }
                    @Test void positive() { assertEquals(1, 1); }
                }
                """;
        assertEquals(helpers, prepare(helpers, true).code());
        String negativeOnly = TEST.replace("assertEquals(7, subject.run());", "assertThrows(NullPointerException.class, () -> audit.record());");
        assertEquals(negativeOnly, prepare(negativeOnly, true).code());
    }

    public void testAnonymousReplacementIsPreserved() {
        String code = TEST.replace("new AuditService(null)", "new AuditService(null) { @Override public void record() { } }");
        assertEquals(code, prepare(code, true).code());
    }

    public void testFieldInjectionOnlyDependencyIsNotConstructedWithNullFields() {
        myFixture.addFileToProject("sample/Adapter.java", "package sample; public class Adapter { @Autowired Repository repository; public void record() { repository.save(); } }");
        audit(AUDIT.replace("Repository repository", "Adapter adapter").replace("this.repository = repository", "this.adapter = adapter")
                .replace("repository.save()", "adapter.record()"));
        var result = prepare(TEST, true);
        assertEquals(TEST, result.code()); assertFalse(result.diagnostics().isEmpty());
    }

    public void testDeferredOpenMocksDoesNotCountAsInitialized() {
        String test = TEST.replace("class SubjectTest", "import org.mockito.MockitoAnnotations; class SubjectTest")
                .replace("AuditService audit;", "@Mock Repository repository; AuditService audit;")
                .replace("audit = new AuditService(null);", "Runnable deferred = () -> MockitoAnnotations.openMocks(this); audit = new AuditService(null);");
        var result = prepare(test, false);
        assertEquals(test, result.code());
        assertTrue(result.diagnostics().toString(), result.diagnostics().get(0).getMessage().contains("初始化"));
    }

    private ConstructorFixturePreflight.Result prepare(String code, boolean mocks) {
        return ConstructorFixturePreflight.prepare(getProject(), source, code, mocks);
    }

    private void audit(String text) {
        var file = myFixture.getTempDirFixture().getFile("sample/AuditService.java");
        if (file == null) { myFixture.addFileToProject("sample/AuditService.java", text); return; }
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText(text);
            com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });
    }
}
