package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestRuntimeExecutionTest extends TestCase {
    public void testUtf8RedirectedOutputDoesNotChangeProjectGbkDefaultCharset() throws Exception {
        Path sandbox = Files.createTempDirectory("unitmind 中文输出-");
        try {
            var runtime = new TestRuntimeConfiguration(List.of("-Dfile.encoding=GBK"), Map.of(), Map.of(), Set.of(),
                    true, sandbox.toFile(), "", "test");
            var result = assertRuntime(sandbox, runtime, """
                    assertEquals("GBK", java.nio.charset.Charset.defaultCharset().name());
                    System.out.println("中文运行输出");
                    """, true);
            assertTrue(result.getSnapshot().getExecution().getOutput(), result.getSnapshot().getExecution().getOutput().contains("中文运行输出"));
        } finally { CompiledTestArtifact.deleteRecursively(sandbox); }
    }
    public void testArgumentsPropertiesEnvironmentAndDirectoryInBothExecutionModes() throws Exception {
        Path project = Files.createTempDirectory("unitmind runtime project ");
        Path sandbox = Files.createTempDirectory("unitmind-runtime-process-");
        try {
            Path cwd = Files.createDirectories(project.resolve("run dir"));
            String value = "literal & $(touch SHOULD_NOT_EXIST) `echo x` spaces";
            TestRuntimeConfiguration runtime = new TestRuntimeConfiguration(
                    List.of("-Xmx128m", "-Dpriority=argument", "--add-opens", "java.base/java.lang=ALL-UNNAMED"),
                    Map.of("priority", "system", "literal", value), Map.of("UNITMIND_TEST_ENV", value),
                    Set.of("PATH"), true, cwd.toFile(), "", "test");
            String checks = """
                    assertEquals("system", System.getProperty("priority"));
                    assertEquals(%s, System.getProperty("literal"));
                    assertEquals(%s, System.getenv("UNITMIND_TEST_ENV"));
                    assertNull(System.getenv("PATH"));
                    assertEquals(%s, java.nio.file.Path.of("").toRealPath().toString());
                    assertTrue(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xmx128m"));
                    assertTrue(String.class.getDeclaredField("value").trySetAccessible());
                    """.formatted(JSONObject.quote(value), JSONObject.quote(value), JSONObject.quote(cwd.toRealPath().toString()));
            assertRuntime(sandbox, runtime, checks, true);
            assertFalse(Files.exists(cwd.resolve("SHOULD_NOT_EXIST")));
        } finally {
            CompiledTestArtifact.deleteRecursively(project);
            CompiledTestArtifact.deleteRecursively(sandbox);
        }
    }

    public void testConfiguredJacocoAgentIsReusedRatherThanInstrumentedTwice() throws Exception {
        Path project = Files.createTempDirectory("unitmind-project-agent-");
        Path sandbox = Files.createTempDirectory("unitmind-agent-execution-");
        try {
            Path agent = project.resolve("project-agent.jar");
            org.jacoco.agent.AgentJar.extractTo(agent.toFile());
            TestRuntimeConfiguration runtime = new TestRuntimeConfiguration(
                    List.of("-javaagent:" + agent + "=destfile=" + project.resolve("project.exec")),
                    Map.of(), Map.of(), Set.of(), true, project.toFile(), "", "test");
            Files.writeString(project.resolve("project.exec"), "existing-project-report");
            assertRuntime(sandbox, runtime, """
                    assertEquals(1L, java.lang.management.ManagementFactory.getRuntimeMXBean()
                        .getInputArguments().stream().filter(arg -> arg.startsWith("-javaagent:")).count());
                    """, true);
            assertEquals("existing-project-report", Files.readString(project.resolve("project.exec")));
        } finally {
            CompiledTestArtifact.deleteRecursively(project);
            CompiledTestArtifact.deleteRecursively(sandbox);
        }
    }

    static CoverageRunResult assertRuntime(Path sandbox, TestRuntimeConfiguration runtime, String checks, boolean coverageToo) throws Exception {
        Path sources = Files.createDirectories(sandbox.resolve("sources/sample"));
        Path classes = Files.createDirectories(sandbox.resolve("classes"));
        String source = """
                package sample;
                public class Subject {
                    public int value() { return 42; }
                }
                """;
        Path subject = sources.resolve("Subject.java");
        Files.writeString(subject, source);
        LocalCoverageClosureSupport.compile(subject, classes, List.of());
        Path test = sources.resolve("SubjectTest.java");
        Files.writeString(test, "package sample; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; "
                + "class SubjectTest { @Test void runtime() throws Exception { assertEquals(42, new Subject().value()); " + checks + " } }");
        LocalCoverageClosureSupport.compile(test, classes, List.of());
        String java = Path.of(System.getProperty("java.home"), "bin", LocalCoverageClosureSupport.isWindows() ? "java.exe" : "java").toString();
        try (CompiledTestArtifact artifact = new CompiledTestArtifact(sandbox, classes, "sample.SubjectTest", "sample.Subject",
                java, List.of(), List.of(), false, runtime.getWorkingDirectory(), runtime)) {
            JacocoCoverageExecutor executor = new JacocoCoverageExecutor();
            CoverageRunResult only = executor.executeTestsOnly(artifact, new CoverageConfig(), () -> false);
            assertTrue(only.getSnapshot().getMessage() + only.getSnapshot().getExecution().getOutput(), only.getSnapshot().getExecution().isSuccessful());
            if (coverageToo) {
                MethodModel method = new MethodModel();
                method.setMethodName("value");
                method.setReturnType("int");
                method.setSourceStartLine(3);
                method.setSourceEndLine(3);
                CoverageRunResult coverage = executor.execute(artifact, method, source, new CoverageConfig(), () -> false);
                assertTrue(coverage.getSnapshot().getMessage() + coverage.getSnapshot().getExecution().getOutput(), coverage.getSnapshot().getExecution().isSuccessful());
                assertTrue(coverage.getSnapshot().getMessage(), coverage.getSnapshot().isAvailable());
            }
            return only;
        }
    }
}
