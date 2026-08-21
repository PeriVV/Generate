package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class JUnitMockitoTestGeneratorTest extends TestCase {
    public void testDeclaresDtoMethodParameterInsteadOfReferencingUndefinedVariable() throws Exception {
        MethodModel method = method("handle", "void");
        method.getParameters().add(parameter("request", "OrderRequest"));
        method.getDependencies().add(dependency("request", "OrderRequest", true, "getName()"));
        MockStrategy strategy = strategy(new MockStrategyItem(
                true, "方法参数数据对象", "OrderRequest", "getName()", "request.getName()",
                "localMock", "只读取 accessor，使用局部 mock"));

        GeneratedTestFile generated = new JUnitMockitoTestGenerator().generate(
                method, List.of(normalScenario()), strategy);

        assertTrue(generated.getContent(), generated.getContent().contains(
                "OrderRequest request = mock(OrderRequest.class);"));
        assertFalse(generated.getContent(), generated.getContent().contains("@Mock\n    private OrderRequest request"));
        assertCompiles("""
                package sample;
                public class Subject {
                    public void handle(OrderRequest request) { request.getName(); }
                }
                class OrderRequest { String getName() { return "name"; } }
                """, generated.getContent());
    }

    public void testSelectedNoMockStrategySuppressesFieldMock() {
        MethodModel method = method("execute", "void");
        method.getDependencies().add(dependency("client", "RemoteClient", false, "send()"));
        MockStrategy strategy = strategy(new MockStrategyItem(
                false, "字段依赖", "RemoteClient", "client", "client", "不处理", "用户关闭"));

        String code = new JUnitMockitoTestGenerator().generate(
                method, List.of(normalScenario()), strategy).getContent();

        assertFalse(code, code.contains("private RemoteClient client;"));
    }

    public void testRuleDraftDoesNotVerifyEveryDiscoveredCall() {
        MethodModel method = method("execute", "void");
        method.getDependencies().add(dependency("client", "RemoteClient", false, "send()"));
        MockStrategy strategy = new MockStrategyPlanner().plan(method, List.of(normalScenario()));

        String code = new JUnitMockitoTestGenerator().generate(
                method, List.of(normalScenario()), strategy).getContent();

        assertFalse(code, code.contains("verify(client)"));
        assertTrue(code, code.contains("assertDoesNotThrow"));
        assertFalse(code, code.contains("MockitoExtension"));
        assertTrue(code, code.contains("MockitoAnnotations.initMocks(this)"));
    }

    public void testOptionalValueDoesNotAssumeNoArgConstructor() {
        MethodModel method = method("find", "Optional<ValueObject>");
        method.setMethodBodyText("{ return repository.find(); }");
        method.getDependencies().add(dependency("repository", "Repository", false, "find()"));
        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method).stream()
                .filter(scenario -> "NORMAL".equals(scenario.getType()))
                .toList();
        MockStrategy strategy = new MockStrategyPlanner().plan(method, scenarios);

        String code = new JUnitMockitoTestGenerator().generate(
                method, scenarios, strategy).getContent();

        assertFalse(code, code.contains("new ValueObject()"));
        assertTrue(code, code.contains("mock(ValueObject.class)"));
        assertFalse(code, code.contains("connection"));
        assertTrue(code, code.contains("expectedValue"));
    }

    public void testStrictInequalityInputsActuallyReachNormalAndExceptionPaths() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                package sample;
                class Subject {
                    int requireNonNegative(int value) {
                        if (value < 0) throw new IllegalArgumentException();
                        return value;
                    }
                }
                """, "requireNonNegative");
        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method).stream()
                .filter(scenario -> "NORMAL".equals(scenario.getType()) || "EXCEPTION".equals(scenario.getType()))
                .toList();

        String code = new JUnitMockitoTestGenerator().generate(
                method, scenarios, new MockStrategyPlanner().plan(method, scenarios)).getContent();

        assertTrue(code, code.contains("int value = 0;"));
        assertTrue(code, code.contains("int value = -1;"));
        assertTrue(code, code.contains("assertThrows(IllegalArgumentException.class"));
    }

    public void testCompoundThrowGuardUsesDeMorganSafeInputs() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                package sample;
                class Subject {
                    int add(int left, int right) {
                        if (left < 0 || right < 0) throw new IllegalArgumentException();
                        return left + right;
                    }
                }
                """, "add");
        assertEquals("(left >= 0) && (right >= 0)", method.getBranches().get(0).getSafeCondition());
        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method).stream()
                .filter(scenario -> "NORMAL".equals(scenario.getType()) || "EXCEPTION".equals(scenario.getType()))
                .toList();

        String code = new JUnitMockitoTestGenerator().generate(
                method, scenarios, new MockStrategyPlanner().plan(method, scenarios)).getContent();

        assertTrue(code, code.contains("int left = 0;"));
        assertTrue(code, code.contains("int right = 0;"));
        assertTrue(code, code.contains("int left = -1;") || code.contains("int right = -1;"));
    }

    public void testBoundaryInputPreservesParameterRelationSafeConditions() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                package sample;
                class Subject {
                    int length(int start, int end) {
                        if (start < 0) throw new IllegalArgumentException("start");
                        if (start >= end) throw new IllegalArgumentException("range");
                        return end - start;
                    }
                }
                """, "length");
        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method).stream()
                .filter(scenario -> "BOUNDARY".equals(scenario.getType()))
                .toList();

        String code = new JUnitMockitoTestGenerator().generate(
                method, scenarios, new MockStrategyPlanner().plan(method, scenarios)).getContent();

        assertEquals(1, scenarios.size());
        assertTrue(scenarios.get(0).getDescription().contains("safeCondition"));
        assertTrue(code, code.contains("int start = 0;"));
        assertTrue(code, code.contains("int end = 100;"));
        assertFalse(code, code.contains("int end = 0;"));
    }

    private MethodModel method(String name, String returnType) {
        MethodModel method = new MethodModel();
        method.setPackageName("sample");
        method.setClassName("Subject");
        method.setMethodName(name);
        method.setReturnType(returnType);
        method.setMethodBodyText("{ }");
        return method;
    }

    private ParameterModel parameter(String name, String type) {
        ParameterModel parameter = new ParameterModel();
        parameter.setName(name);
        parameter.setType(type);
        return parameter;
    }

    private DependencyModel dependency(String name, String type, boolean methodParameter, String call) {
        DependencyModel dependency = new DependencyModel();
        dependency.setFieldName(name);
        dependency.setFieldType(type);
        dependency.setMethodParameter(methodParameter);
        dependency.setCalledMethods(List.of(call.substring(0, call.indexOf('('))));
        dependency.setCallExpressions(List.of(call));
        return dependency;
    }

    private MockStrategy strategy(MockStrategyItem item) {
        MockStrategy strategy = new MockStrategy();
        strategy.setStrategyItems(List.of(item));
        return strategy;
    }

    private TestScenario normalScenario() {
        TestScenario scenario = new TestScenario();
        scenario.setName("normal");
        scenario.setMethodNameEn("shouldExecuteWhenInputValid");
        scenario.setType("NORMAL");
        return scenario;
    }

    private void assertCompiles(String productionCode, String testCode) throws Exception {
        File javac = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "javac.exe" : "javac").toFile();
        assertTrue("测试需要完整 JDK: " + javac, javac.isFile());
        Path root = Files.createTempDirectory("unitmind-rule-generator-test-");
        try {
            Path sourceRoot = root.resolve("source/sample");
            Path output = root.resolve("classes");
            Files.createDirectories(sourceRoot);
            Files.createDirectories(output);
            Path production = sourceRoot.resolve("Subject.java");
            Path test = sourceRoot.resolve("SubjectTest.java");
            Files.writeString(production, productionCode, StandardCharsets.UTF_8);
            Files.writeString(test, testCode, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(
                    javac.getAbsolutePath(),
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString(),
                    "-proc:none",
                    production.toString(), test.toString())
                    .redirectErrorStream(true)
                    .start();
            String diagnostics = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(diagnostics + "\n" + testCode, 0, process.waitFor());
        } finally {
            if (Files.exists(root)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }
    }
}
