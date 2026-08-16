package com.casc.unittestagent.coverage;

import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 默认执行的生成→编译→JUnit→JaCoCo 最小闭环。 */
public class RuleGeneratedCoverageClosureTest extends TestCase {
    public void testRuleGeneratedCandidateCompilesRunsAndProducesCoverage() throws Exception {
        String productionCode = """
                package sample;
                public class Subject {
                    public int classify(Request request) {
                        if (request == null) return -1;
                        return request.active() ? 1 : 0;
                    }
                }
                class Request { boolean active() { return true; } }
                """;
        MethodModel method = methodModel();
        TestScenario scenario = new TestScenario();
        scenario.setName("normal");
        scenario.setMethodNameEn("shouldClassifyWhenRequestValid");
        scenario.setType("NORMAL");
        GeneratedTestFile generated = new JUnitMockitoTestGenerator().generate(
                method, List.of(scenario), new MockStrategyPlanner().plan(method, List.of(scenario)));

        Path root = Files.createTempDirectory("unitmind-rule-coverage-closure-");
        Path source = root.resolve("source/sample");
        Path classes = root.resolve("classes");
        Files.createDirectories(source);
        Files.createDirectories(classes);
        Path subjectFile = source.resolve("Subject.java");
        Path testFile = source.resolve("SubjectTest.java");
        Files.writeString(subjectFile, productionCode, StandardCharsets.UTF_8);
        Files.writeString(testFile, generated.getContent(), StandardCharsets.UTF_8);

        File javac = javaTool("javac");
        Process compilation = new ProcessBuilder(
                javac.getAbsolutePath(), "--release", "17", "-g",
                "-cp", System.getProperty("java.class.path"),
                "-d", classes.toString(), subjectFile.toString(), testFile.toString())
                .redirectErrorStream(true)
                .start();
        String compilerOutput = new String(compilation.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(compilerOutput + "\n" + generated.getContent(), 0, compilation.waitFor());

        CoverageConfig config = new CoverageConfig();
        config.setExecutionTimeoutSeconds(30);
        try (CompiledTestArtifact artifact = new CompiledTestArtifact(
                root, classes, "sample.SubjectTest", "sample.Subject",
                javaTool("java").getAbsolutePath(), mockitoRuntimeFiles(), new ArrayList<>())) {
            CoverageRunResult result = new JacocoCoverageExecutor().execute(
                    artifact, method, productionCode, config, () -> false);

            assertFalse(result.getSnapshot().getMessage(), result.isEnvironmentError());
            assertTrue(result.getSnapshot().getMessage() + "\n"
                            + result.getSnapshot().getExecution().getOutput(),
                    result.getSnapshot().isAvailable());
            assertTrue(result.getSnapshot().getExecution().isSuccessful());
            assertEquals(1, result.getSnapshot().getExecution().getFound());
            assertTrue(result.getSnapshot().getLine().getCovered() > 0);
        }
    }

    private MethodModel methodModel() {
        MethodModel method = new MethodModel();
        method.setPackageName("sample");
        method.setClassName("Subject");
        method.setMethodName("classify");
        method.setReturnType("int");
        method.setSourceStartLine(3);
        method.setSourceEndLine(6);
        method.setMethodBodyText("{ if (request == null) return -1; return request.active() ? 1 : 0; }");

        ParameterModel parameter = new ParameterModel();
        parameter.setName("request");
        parameter.setType("Request");
        method.getParameters().add(parameter);

        DependencyModel dependency = new DependencyModel();
        dependency.setFieldName("request");
        dependency.setFieldType("Request");
        dependency.setMethodParameter(true);
        dependency.setCalledMethods(List.of("active"));
        dependency.setCallExpressions(List.of("active()"));
        method.getDependencies().add(dependency);
        return method;
    }

    private File javaTool(String name) {
        String suffix = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
        File tool = Path.of(System.getProperty("java.home"), "bin", name + suffix).toFile();
        assertTrue("JDK tool missing: " + tool, tool.isFile());
        return tool;
    }

    private List<File> mockitoRuntimeFiles() {
        List<File> files = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            String name = new File(entry).getName().toLowerCase();
            if (name.startsWith("mockito-core-") || name.startsWith("byte-buddy-")
                    || name.startsWith("byte-buddy-agent-") || name.startsWith("objenesis-")) {
                files.add(new File(entry));
            }
        }
        return files;
    }
}
