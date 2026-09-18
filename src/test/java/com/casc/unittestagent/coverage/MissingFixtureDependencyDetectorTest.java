package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import junit.framework.TestCase;

import java.util.List;

public class MissingFixtureDependencyDetectorTest extends TestCase {
    private static final String SOURCE = """
            class TaskProcessService {
                private final DataProcessService dataProcessService;
                TaskProcessService(DataProcessService dataProcessService) {
                    this.dataProcessService = dataProcessService;
                }
                Object getById(long id) { return dataProcessService.getDtoById(id); }
            }
            """;

    public void testProvesMissingConstructorFixtureFromNpeAndTestDeclarations() {
        String testCode = """
                class TaskProcessServiceTest {
                    @InjectMocks TaskProcessService subject;
                    @Test void getsTask() { assertNotNull(subject.getById(1L)); }
                }
                """;
        RuntimeDiagnostic diagnostic = npe();

        MissingFixtureDependencyDetector.Result result =
                MissingFixtureDependencyDetector.inspect(
                        SOURCE, testCode, List.of(diagnostic));

        assertTrue(result.isProven());
        assertEquals(List.of("DataProcessService dataProcessService"),
                result.getMissingDependencies());
        assertEquals("FIXTURE_DEPENDENCY_MISSING", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("保持原断言"));
    }

    public void testDoesNotClaimMissingWhenMatchingFixtureExists() {
        String testCode = """
                class TaskProcessServiceTest {
                    @Mock DataProcessService dataProcessService;
                    @InjectMocks TaskProcessService subject;
                    @Test void getsTask() { assertNotNull(subject.getById(1L)); }
                }
                """;
        RuntimeDiagnostic diagnostic = npe();

        MissingFixtureDependencyDetector.Result result =
                MissingFixtureDependencyDetector.inspect(
                        SOURCE, testCode, List.of(diagnostic));

        assertFalse(result.isProven());
        assertFalse("FIXTURE_DEPENDENCY_MISSING".equals(diagnostic.getCategory()));
    }

    public void testSupportsLombokRequiredArgsConstructor() {
        String lombokSource = """
                @RequiredArgsConstructor
                class TaskProcessService {
                    private final DataProcessService dataProcessService;
                    Object getById(long id) { return dataProcessService.getDtoById(id); }
                }
                """;
        RuntimeDiagnostic diagnostic = npe();

        MissingFixtureDependencyDetector.Result result =
                MissingFixtureDependencyDetector.inspect(lombokSource,
                        "class T { @InjectMocks TaskProcessService subject; }",
                        List.of(diagnostic));

        assertTrue(result.isProven());
    }

    public void testRequiresRealNullMemberEvidence() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setExceptionType("java.lang.NullPointerException");
        diagnostic.setMessage("unexpected null");

        MissingFixtureDependencyDetector.Result result =
                MissingFixtureDependencyDetector.inspect(SOURCE,
                        "class T { @InjectMocks TaskProcessService subject; }",
                        List.of(diagnostic));

        assertFalse(result.isProven());
    }

    private RuntimeDiagnostic npe() {
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("TaskProcessServiceTest#getsTask()");
        diagnostic.setExceptionType("java.lang.NullPointerException");
        diagnostic.setMessage("Cannot invoke \"DataProcessService.getDtoById(long)\" because "
                + "\"this.dataProcessService\" is null");
        return diagnostic;
    }
}
