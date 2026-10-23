package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeFailurePhase;
import com.casc.unittestagent.model.TestExecutionSummary;
import junit.framework.TestCase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real JUnit/Mockito in a child JVM, so erroneous matcher state cannot affect this suite. */
public class MockitoMatcherIsolationIntegrationTest extends TestCase {
    private static final String SOURCE = """
            package sample;
            import org.junit.jupiter.api.*;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.junit.jupiter.MockitoExtension;
            import static org.mockito.Mockito.*;
            import static org.junit.jupiter.api.Assertions.*;
            @ExtendWith(MockitoExtension.class)
            @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
            class MatcherTest {
                @Test @Order(1) void origin() {
                    when(sample.RemoteClient.exec(eq(new Object()), anyString(), any())).thenReturn("ok");
                    assertEquals("ok", sample.RemoteClient.exec(new Object(), "command", null));
                }
                @Test @Order(2) void unrelated() { assertTrue(true); }
                @Test @Order(3) void stillRuns() { assertEquals(2, 1 + 1); }
            }
            """;

    public void testRealStaticCallLeaksMatchersIntoNextTestButKeepsDistinctJUnitIdentity() throws Exception {
        var execution = run(SOURCE);
        assertEquals(3, execution.getFound());
        assertEquals(2, execution.getFailed());
        assertEquals(1, execution.getSucceeded());
        MockitoRuntimeDiagnosticEnricher.enrich(execution.getDiagnostics(), SOURCE);
        var origin = execution.getDiagnostics().stream().filter(d -> d.getTestId().contains("#origin("))
                .findFirst().orElseThrow();
        assertEquals("java.lang.NullPointerException", origin.getExceptionType());
        var detected = execution.getDiagnostics().stream().filter(d -> d.getTestId().contains("#unrelated("))
                .findFirst().orElseThrow();
        assertEquals("MOCKITO_INVALID_MATCHER_USAGE", detected.getCategory());
        assertEquals(detected.getExceptionType() + "\n" + detected.getFailureDetails() + "\n" + detected.getStackTrace(),
                RuntimeFailurePhase.TEST_TEARDOWN, detected.getFailurePhase());
        assertEquals(1, detected.getOriginLocations().size());
        assertEquals("sample.MatcherTest#origin()", detected.getOriginLocations().get(0).testId());
        assertTrue(detected.getOriginLocations().get(0).sourceSnippet().contains("sample.RemoteClient.exec"));
        assertFalse(detected.getTestFrame(), detected.getTestFrame().contains("MatcherTest.origin("));
        assertTrue(detected.getSuggestion().contains("不能据此断定整个类初始化失败"));
        assertTrue(CoverageCandidatePreflight.inspect(SOURCE).stream().anyMatch(d ->
                "MOCKITO_WHEN_ON_REAL_OBJECT".equals(d.getCode())));
    }

    public void testScopedStaticStubbingPassesWholeClassAndPreflight() throws Exception {
        String fixed = SOURCE.replace("when(sample.RemoteClient.exec(eq(new Object()), anyString(), any())).thenReturn(\"ok\");",
                        "try (org.mockito.MockedStatic<sample.RemoteClient> scoped = mockStatic(sample.RemoteClient.class)) { "
                                + "scoped.when(() -> sample.RemoteClient.exec(any(), anyString(), any())).thenReturn(\"ok\");")
                .replace("assertEquals(\"ok\", sample.RemoteClient.exec(new Object(), \"command\", null));",
                        "assertEquals(\"ok\", sample.RemoteClient.exec(new Object(), \"command\", null)); }");
        assertTrue(CoverageCandidatePreflight.inspect(fixed).toString(), CoverageCandidatePreflight.inspect(fixed).isEmpty());
        var execution = run(fixed);
        assertEquals(3, execution.getFound());
        assertEquals(execution.getOutput(), 0, execution.getFailed());
        assertEquals(3, execution.getSucceeded());
    }

    private TestExecutionSummary run(String code) throws Exception {
        Path root = Files.createTempDirectory("unitmind-matcher-isolation-");
        try {
            Path classes = Files.createDirectories(root.resolve("classes"));
            Path remote = root.resolve("RemoteClient.java");
            Files.writeString(remote, "package sample; public class RemoteClient { "
                    + "public static String exec(Object session, String command, Object charset) { return session.toString(); } }");
            Path test = root.resolve("MatcherTest.java"); Files.writeString(test, code);
            var libraries = BundledMockitoRuntime.libraries(17);
            LocalCoverageClosureSupport.compile(remote, classes, libraries);
            LocalCoverageClosureSupport.compile(test, classes, libraries);
            List<File> runtime = new ArrayList<>(libraries); runtime.add(classes.toFile());
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            try (var artifact = new CompiledTestArtifact(root, classes, "sample.MatcherTest", "sample.RemoteClient",
                    java, runtime, List.of())) {
                return new JacocoCoverageExecutor().executeTestsOnly(artifact, new CoverageConfig(), () -> false)
                        .getSnapshot().getExecution();
            }
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }
}
