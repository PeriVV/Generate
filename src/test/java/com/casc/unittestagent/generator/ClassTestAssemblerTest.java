package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

import java.util.List;

public class ClassTestAssemblerTest extends TestCase {
    public void testMergesImportsSharedFixturesOverloadedTestNamesAndHelpers() {
        ClassModel model = classModel(false);
        MethodGenerationItem first = item("sample.Subject#load(int)", "load(int): int", candidate("""
                @Test
                void shouldLoadWhenValid() { assertEquals(1, helper()); }
                private int helper() { return 1; }
                """));
        MethodGenerationItem second = item("sample.Subject#load(String)", "load(String): String", candidate("""
                @Test
                void shouldLoadWhenValid() { assertEquals(2, helper()); }
                private int helper() { return 2; }
                """));

        ClassTestAssembler.AssemblyResult result = new ClassTestAssembler().assemble(model, List.of(first, second));
        String code = result.getGeneratedTestFile().getContent();

        assertTrue(result.getRejectedMethodIds().isEmpty());
        assertEquals(1, occurrences(code, "private Subject subject;"));
        assertEquals(1, occurrences(code, "private Repository repository;"));
        assertTrue(code.contains("void shouldLoadWhenValid()"));
        assertTrue(code.matches("(?s).*void shouldLoadWhenValid_[0-9a-f]{8}\\(\\).*"));
        assertTrue(code.matches("(?s).*int helper_[0-9a-f]{8}\\(\\).*"));
        assertTrue(first.getGeneratedStartLine() > 0);
        assertTrue(second.getGeneratedEndLine() >= second.getGeneratedStartLine());
    }

    public void testPureStaticClassDropsInjectMocksSubject() {
        ClassModel model = classModel(true);
        MethodGenerationItem item = item("sample.Subject#add(int,int)", "add(int,int): int", candidate("""
                @Test
                void shouldAddWhenValid() { assertEquals(3, Subject.add(1, 2)); }
                """));

        String code = new ClassTestAssembler().assemble(model, List.of(item)).getGeneratedTestFile().getContent();

        assertFalse(code.contains("@InjectMocks"));
        assertFalse(code.contains("private Subject subject"));
        assertTrue(code.contains("Subject.add(1, 2)"));
    }

    public void testRejectsAfterEachLifecycleMethodForPerMethodCandidate() {
        ClassModel model = classModel(false);
        String code = candidate("""
                @AfterEach
                void tearDown() { repository.toString(); }
                @Test
                void shouldLoad() { assertNotNull(subject); }
                """).replace("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.AfterEach;");
        MethodGenerationItem item = item("sample.Subject#load(int)", "load(int): int", code);

        ClassTestAssembler.AssemblyResult result = new ClassTestAssembler().assemble(model, List.of(item));

        assertEquals(List.of(item.getMethodId()), result.getRejectedMethodIds());
        assertTrue(result.getWarnings().get(0).contains("生命周期"));
    }

    public void testScopesBeforeEachSetupToTestsFromItsOwnCandidate() {
        ClassModel model = classModel(false);
        String firstCode = candidate("""
                @BeforeEach
                void setUp() { when(repository.toString()).thenReturn("first"); }
                @Test
                void shouldLoad() { assertEquals("first", repository.toString()); }
                """).replace("import org.junit.jupiter.api.Test;",
                "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.BeforeEach;\nimport static org.mockito.Mockito.when;");
        MethodGenerationItem first = item("sample.Subject#load(int)", "load(int): int", firstCode);
        MethodGenerationItem second = item("sample.Subject#save()", "save(): void", candidate("""
                @Test
                void shouldSave() { assertNotNull(subject); }
                """));

        ClassTestAssembler.AssemblyResult result = new ClassTestAssembler().assemble(model, List.of(first, second));
        String code = result.getGeneratedTestFile().getContent();

        assertTrue(result.getRejectedMethodIds().isEmpty());
        assertEquals(1, occurrences(code, "@BeforeEach"));
        assertTrue(code.contains("MockitoAnnotations.initMocks(this)"));
        assertTrue(code.matches("(?s).*void shouldLoad\\(\\) \\{\\s*setUp\\(\\);.*"));
        int shouldSave = code.indexOf("void shouldSave");
        assertTrue(shouldSave > 0);
        assertFalse(code.substring(shouldSave, code.indexOf('}', shouldSave)).contains("setUp();"));
        assertTrue(result.getWarnings().stream().anyMatch(message -> message.contains("setup helper")));
    }

    public void testDeduplicatesStandardMockitoInitializerFromMethodCandidate() {
        ClassModel model = classModel(false);
        String code = candidate("""
                @BeforeEach
                void initMockito() { MockitoAnnotations.initMocks(this); }
                @Test
                void shouldLoad() { assertNotNull(subject); }
                """).replace("import org.junit.jupiter.api.Test;",
                "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.BeforeEach;"
                        + "\nimport org.mockito.MockitoAnnotations;");

        String assembled = new ClassTestAssembler().assemble(model, List.of(
                item("sample.Subject#load(int)", "load(int): int", code)))
                .getGeneratedTestFile().getContent();

        assertEquals(1, occurrences(assembled, "void initMockito()"));
        assertEquals(0, occurrences(assembled, "initMockito();"));
        assertEquals(1, occurrences(assembled, "MockitoAnnotations.initMocks(this)"));
    }

    private ClassModel classModel(boolean pureStatic) {
        ClassModel model = new ClassModel();
        model.setPackageName("sample");
        model.setClassName("Subject");
        model.setQualifiedName("sample.Subject");
        model.setClassFields(List.of("Repository repository"));
        MethodModel method = new MethodModel();
        method.setStaticMethod(pureStatic);
        model.setMethods(List.of(method));
        return model;
    }

    public void testRejectsSubjectConstructionAndUnknownMockField() {
        ClassModel model = classModel(false);
        MethodGenerationItem constructsSubject = item("sample.Subject#load(int)", "load(int): int", candidate("""
                @Test
                void shouldLoad() { subject = new Subject(); }
                """));
        MethodGenerationItem unknownMock = item("sample.Subject#save()", "save(): void", candidate("""
                @Mock
                private UnknownClient unknownClient;
                @Test
                void shouldSave() { assertNotNull(subject); }
                """));

        ClassTestAssembler.AssemblyResult result = new ClassTestAssembler().assemble(
                model, List.of(constructsSubject, unknownMock));

        assertEquals(List.of(constructsSubject.getMethodId(), unknownMock.getMethodId()),
                result.getRejectedMethodIds());
        assertTrue(result.getWarnings().stream().anyMatch(message -> message.contains("new 被测对象")));
        assertTrue(result.getWarnings().stream().anyMatch(message -> message.contains("未知 @Mock 字段")));
    }

    public void testNormalizesMockAliasAndQualifiedTypeToRealClassField() {
        ClassModel model = classModel(false);
        String aliased = candidate("""
                @Test
                void shouldLoad() { assertNotNull(repoMock); }
                """).replace("private Repository repository;", "private sample.Repository repoMock;");
        MethodGenerationItem item = item("sample.Subject#load(int)", "load(int): int", aliased);

        ClassTestAssembler.AssemblyResult result = new ClassTestAssembler().assemble(model, List.of(item));
        String code = result.getGeneratedTestFile().getContent();

        assertTrue(result.getRejectedMethodIds().isEmpty());
        assertTrue(code.contains("private Repository repository;"));
        assertTrue(code.contains("assertNotNull(repository)"));
        assertFalse(code.contains("repoMock"));
    }

    private MethodGenerationItem item(String id, String signature, String code) {
        GeneratedTestFile file = new GeneratedTestFile();
        file.setContent(code);
        GenerationResult generation = new GenerationResult();
        generation.setGeneratedTestFile(file);
        MethodGenerationItem item = new MethodGenerationItem();
        item.setMethodId(id);
        item.setSignature(signature);
        item.setResult(generation);
        return item;
    }

    private String candidate(String members) {
        return """
                package sample;
                import org.junit.jupiter.api.Test;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import static org.junit.jupiter.api.Assertions.*;

                class SubjectTest {
                    @Mock
                    private Repository repository;
                    @InjectMocks
                    private Subject subject;
                %s
                }
                interface Repository {}
                """.formatted(members.indent(4));
    }

    private int occurrences(String text, String part) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(part, index)) >= 0) { count++; index += part.length(); }
        return count;
    }
}
