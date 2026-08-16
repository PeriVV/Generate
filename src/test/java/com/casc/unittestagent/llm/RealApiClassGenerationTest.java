package com.casc.unittestagent.llm;

import com.casc.unittestagent.generator.ClassTestAssembler;
import com.casc.unittestagent.generator.GeneratedTestCodePostProcessor;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.generator.TestScenarioPlanner;
import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GenerationScope;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.model.TypeSymbol;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实 OpenAI-compatible API 整类生成验收。不使用 StubLlmClient 或伪造响应。
 * 日常回归默认跳过；显式设置 UNITMIND_REAL_API_TEST=true 时才会产生真实请求和费用。
 */
public class RealApiClassGenerationTest extends TestCase {
    public void testGeneratesAndCompilesWholeClassWithRealApi() throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("UNITMIND_REAL_API_TEST"))) return;
        String apiKey = System.getenv("UNITMIND_API_KEY");
        assertTrue("UNITMIND_API_KEY must be configured for the real API test", apiKey != null && !apiKey.isBlank());

        String productionSource = """
                package sample;

                public class OrderService {
                    private final TaxClient taxClient;

                    public OrderService(TaxClient taxClient) {
                        this.taxClient = taxClient;
                    }

                    public static int calculateTotal(int unitPrice, int quantity) {
                        if (unitPrice < 0 || quantity < 0) {
                            throw new IllegalArgumentException("negative input");
                        }
                        if (quantity == 0) return 0;
                        return unitPrice * quantity;
                    }

                    public int calculateWithTax(int unitPrice, int quantity) {
                        int subtotal = calculateTotal(unitPrice, quantity);
                        return subtotal + taxClient.taxFor(subtotal);
                    }

                    static String normalizeCode(String code) {
                        if (code == null) throw new IllegalArgumentException("code");
                        return code.trim().toUpperCase();
                    }
                }

                interface TaxClient {
                    int taxFor(int subtotal);
                }
                """;
        ClassModel classModel = new JavaSourceAnalyzer().analyzeClass(productionSource, "OrderService", 3);
        List<String> signatures = classModel.getMethods().stream().map(MethodModel::getSignature).toList();
        LlmConfig config = realConfig(apiKey);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        TestDataGenerator dataGenerator = new TestDataGenerator();
        TestScenarioPlanner scenarioPlanner = new TestScenarioPlanner();
        MockStrategyPlanner mockPlanner = new MockStrategyPlanner();
        List<MethodGenerationItem> items = new ArrayList<>();

        for (MethodModel method : classModel.getMethods()) {
            dataGenerator.enrich(method);
            List<TestScenario> scenarios = scenarioPlanner.plan(method);
            LlmGenerationRequest request = request(method, scenarios, signatures,
                    symbolSnapshot(method, classModel), classModel);
            LlmGenerationResponse response = client.generateUnitTest(request);
            assertNotNull(response.getTestCode());
            assertTrue(response.getTestCode().contains("OrderServiceTest"));

            GeneratedTestFile candidate = new GeneratedTestFile();
            candidate.setPackageName("sample");
            candidate.setClassName("OrderServiceTest");
            candidate.setFileName("OrderServiceTest.java");
            candidate.setContent(response.getTestCode());
            GenerationResult methodResult = new GenerationResult();
            methodResult.setMethodModel(method);
            methodResult.setScenarios(scenarios);
            methodResult.setMockStrategy(mockPlanner.plan(method, scenarios));
            methodResult.setGeneratedTestFile(candidate);
            methodResult.setGeneratedByLlm(true);
            MethodGenerationItem item = new MethodGenerationItem();
            item.setMethodId(method.getMethodId());
            item.setSignature(method.getSignature());
            item.setResult(methodResult);
            items.add(item);
        }

        ClassTestAssembler.AssemblyResult assembly = new ClassTestAssembler().assemble(classModel, items);
        if (!assembly.getRejectedMethodIds().isEmpty()) {
            JUnitMockitoTestGenerator ruleGenerator = new JUnitMockitoTestGenerator();
            for (MethodGenerationItem item : items) {
                if (!assembly.getRejectedMethodIds().contains(item.getMethodId())) continue;
                item.getResult().setGeneratedTestFile(ruleGenerator.generate(item.getResult().getMethodModel(),
                        item.getResult().getScenarios(), item.getResult().getMockStrategy()));
                item.getResult().setRuleBasedFallback(true);
            }
            assembly = new ClassTestAssembler().assemble(classModel, items);
        }
        assertTrue("Candidates still rejected after rule fallback: " + assembly.getWarnings(),
                assembly.getRejectedMethodIds().isEmpty());
        String completeTest = assembly.getGeneratedTestFile().getContent();
        assertTrue(completeTest.contains("calculateTotal"));
        assertTrue(completeTest.contains("calculateWithTax"));
        assertTrue(completeTest.contains("normalizeCode"));
        assertTrue(completeTest.contains("@Test"));
        CompileOutcome outcome = compile(productionSource, completeTest);
        int repairAttempts = 0;
        while (!outcome.success() && repairAttempts < 2) {
            repairAttempts++;
            MethodGenerationItem affected = affectedItem(items, outcome.line());
            GenerationResult context = affected == null ? items.get(0).getResult() : affected.getResult();
            MethodModel method = context.getMethodModel();
            LlmGenerationRequest repair = request(method, context.getScenarios(), signatures,
                    symbolSnapshot(method, classModel), classModel);
            repair.setExistingTestCodeSummary(completeTest);
            repair.setCompilerDiagnostics(List.of(new CompileDiagnostic(
                    "ERROR", "REAL_JAVAC_FAILED", outcome.output(), outcome.line(), -1, "", true)));
            repair.setRepairAttempt(repairAttempts);
            repair.setUserInstruction("当前是整类测试修复，只修复编译错误；必须保留全部三个方法的测试和 UnitMind method 标记。");
            LlmGenerationResponse repaired = client.repairUnitTest(repair);
            assertNotNull(repaired.getTestCode());
            completeTest = new GeneratedTestCodePostProcessor().process(
                    repaired.getTestCode(), method).getCode();
            outcome = compile(productionSource, completeTest);
        }
        assertTrue("Real API generated class did not compile after " + repairAttempts
                + " repair rounds:\n" + outcome.output() + "\n\n" + completeTest, outcome.success());
        assertTrue(completeTest.contains("calculateTotal"));
        assertTrue(completeTest.contains("calculateWithTax"));
        assertTrue(completeTest.contains("normalizeCode"));
    }

    private LlmConfig realConfig(String apiKey) {
        LlmConfig config = new LlmConfig();
        config.setEnabled(true);
        config.setApiKey(apiKey);
        config.setApiUrl(value("unitmind.realApiUrl", "UNITMIND_API_URL",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
        config.setModel(value("unitmind.realModel", "UNITMIND_MODEL", "qwen3.7-flash"));
        config.setTimeoutSeconds(Integer.parseInt(System.getProperty("unitmind.realTimeoutSeconds", "300")));
        config.setMaxTokens(Integer.parseInt(System.getProperty("unitmind.realMaxTokens", "6000")));
        config.setTemperature(0.1d);
        config.setFallbackToRuleBased(false);
        return config;
    }

    private String value(String property, String environment, String fallback) {
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) return configured;
        configured = System.getenv(environment);
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private LlmGenerationRequest request(MethodModel method,
                                         List<TestScenario> scenarios,
                                         List<String> signatures,
                                         ProjectSymbolSnapshot snapshot,
                                         ClassModel classModel) {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setGenerationScope(GenerationScope.CLASS);
        request.setMethodId(method.getMethodId());
        request.setClassMethodSignatures(signatures);
        request.setSharedFixtureSummary("testClass=OrderServiceTest, subjectField=orderService, targetType=sample.OrderService, classFields=TaxClient taxClient");
        request.setPackageName(method.getPackageName());
        request.setClassName(method.getClassName());
        request.setMethodName(method.getMethodName());
        request.setMethodSignature(method.getSignature());
        request.setReturnType(method.getReturnType());
        request.setAccessModifier(method.getAccessModifier());
        request.setStaticMethod(method.isStaticMethod());
        request.setMethodSourceCode(method.getMethodBodyText());
        request.setImports(String.join("\n", method.getImports()));
        request.setClassFields(String.join("\n", classModel.getClassFields()));
        request.setKnownTypes(List.of("sample.OrderService", "sample.TaxClient", "java.lang.String", "java.lang.IllegalArgumentException"));
        request.setParameters(method.getParameters());
        request.setConstraints(method.getConstraints());
        request.setBranches(method.getBranches());
        request.setDependencies(method.getDependencies());
        request.setUsages(method.getUsages());
        request.setCallContexts(method.getCallContexts());
        request.setSymbolSnapshot(snapshot);
        request.setScenarios(scenarios);
        request.setTestFramework("JUnit5");
        request.setMockFramework("Mockito");
        return request;
    }

    private ProjectSymbolSnapshot symbolSnapshot(MethodModel target, ClassModel classModel) {
        ProjectSymbolSnapshot snapshot = new ProjectSymbolSnapshot();
        snapshot.setModuleName("real-api-integration");
        snapshot.setLanguageLevel("17");
        snapshot.setTargetType(classModel.getQualifiedName());
        CallableSymbol targetMethod = callable(target);
        snapshot.setTargetMethod(targetMethod);

        TypeSymbol orderService = new TypeSymbol();
        orderService.setQualifiedName(classModel.getQualifiedName());
        CallableSymbol constructor = new CallableSymbol();
        constructor.setId("sample.OrderService#<init>(sample.TaxClient)");
        constructor.setOwnerQualifiedName("sample.OrderService");
        constructor.setName("OrderService");
        constructor.setConstructor(true);
        constructor.setVisibility("public");
        constructor.setParameterTypes(List.of("sample.TaxClient"));
        orderService.setConstructors(List.of(constructor));
        orderService.setMethods(classModel.getMethods().stream().map(this::callable).toList());

        TypeSymbol taxClient = new TypeSymbol();
        taxClient.setQualifiedName("sample.TaxClient");
        taxClient.setKind("interface");
        CallableSymbol taxFor = new CallableSymbol();
        taxFor.setId("sample.TaxClient#taxFor(int)");
        taxFor.setOwnerQualifiedName("sample.TaxClient");
        taxFor.setName("taxFor");
        taxFor.setReturnType("int");
        taxFor.setParameterTypes(List.of("int"));
        taxFor.setVisibility("package-private");
        taxClient.setMethods(List.of(taxFor));
        snapshot.setTypes(List.of(orderService, taxClient));
        return snapshot;
    }

    private CallableSymbol callable(MethodModel method) {
        CallableSymbol symbol = new CallableSymbol();
        symbol.setId(method.getMethodId());
        symbol.setOwnerQualifiedName("sample.OrderService");
        symbol.setName(method.getMethodName());
        symbol.setReturnType(method.getReturnType());
        symbol.setParameterTypes(method.getParameters().stream().map(parameter -> parameter.getType()).toList());
        symbol.setVisibility(method.getAccessModifier());
        symbol.setStaticMethod(method.isStaticMethod());
        return symbol;
    }

    private MethodGenerationItem affectedItem(List<MethodGenerationItem> items, int line) {
        for (MethodGenerationItem item : items) {
            if (line >= item.getGeneratedStartLine() && line <= item.getGeneratedEndLine()) return item;
        }
        return null;
    }

    private CompileOutcome compile(String productionSource, String testSource) throws Exception {
        Path root = Files.createTempDirectory("unitmind-real-class-");
        Path source = root.resolve("sample/OrderService.java");
        Path test = root.resolve("sample/OrderServiceTest.java");
        Path classes = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, productionSource, StandardCharsets.UTF_8);
        Files.writeString(test, testSource, StandardCharsets.UTF_8);
        String javaHome = System.getenv("JAVA_HOME");
        Path javac = javaHome == null || javaHome.isBlank()
                ? Path.of(System.getProperty("java.home"), "bin", "javac")
                : Path.of(javaHome, "bin", "javac");
        if (!Files.isExecutable(javac)) javac = Path.of("/usr/bin/javac");
        Process process = new ProcessBuilder(javac.toString(), "--release", "17",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                source.toString(), test.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        Matcher matcher = Pattern.compile("OrderServiceTest\\.java:(\\d+):").matcher(output);
        int line = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        return new CompileOutcome(exit == 0, line, output);
    }

    private record CompileOutcome(boolean success, int line, String output) {}
}
