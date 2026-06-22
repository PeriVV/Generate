package com.casc.unittestagent.service;

import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.generator.TestScenarioPlanner;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.llm.OpenAiCompatibleLlmClient;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.util.PsiUtils;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 编排一次单元测试生成流程。
 * 该服务串联源码分析、测试数据补全、场景规划、Mock 规划和测试文件生成，并把异常转为可展示告警。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class UnitTestGenerationService {
    /** 日志对象，用于记录生成链路关键数据和异常堆栈。 */
    private static final Logger LOG = Logger.getInstance(UnitTestGenerationService.class);
    /** 发送给大模型的调用点数量上限，避免引用过多导致 Prompt 失控。 */
    private static final int MAX_USAGE_COUNT = 20;
    /** 源码分析器，负责将 PSI 文本转换为方法模型。 */
    private final JavaSourceAnalyzer javaSourceAnalyzer = new JavaSourceAnalyzer();
    /** 测试数据补全器，负责为方法参数填充候选值。 */
    private final TestDataGenerator testDataGenerator = new TestDataGenerator();
    /** 测试场景规划器，负责根据模型生成用例类型。 */
    private final TestScenarioPlanner testScenarioPlanner = new TestScenarioPlanner();
    /** Mock 策略规划器，负责根据依赖调用生成规则描述。 */
    private final MockStrategyPlanner mockStrategyPlanner = new MockStrategyPlanner();
    /** 测试源码生成器，负责输出最终 JUnit 与 Mockito 代码。 */
    private final JUnitMockitoTestGenerator testGenerator = new JUnitMockitoTestGenerator();

    /**
     * 执行单元测试源码生成。
     * 方法把分析模型和场景转换为一个可预览、可写入磁盘的测试文件模型。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param project 当前 IntelliJ 项目上下文，用于消息提示、写命令和对话框归属
     * @param psiFile 当前 PSI 文件，通常是用户正在编辑的 Java 源文件
     * @param editor 当前编辑器，用于读取光标位置并定位优先分析的方法
     * @return 生成流程结果或生成的测试文件模型
     */
    public GenerationResult generate(Project project, PsiFile psiFile, Editor editor) {
        GenerationResult result = new GenerationResult();
        try {
            LOG.info("UnitMind: start generate test code");
            String sourceText = PsiUtils.getSourceText(psiFile);
            String preferredMethodName = PsiUtils.getCurrentMethodName(psiFile, editor);

            MethodModel methodModel;
            try {
                methodModel = javaSourceAnalyzer.analyze(sourceText, preferredMethodName);
            } catch (Exception parseException) {
                result.getWarnings().add(parseException.getMessage() == null ? parseException.getClass().getSimpleName() : parseException.getMessage());
                methodModel = buildPsiFallbackModel(psiFile, editor, preferredMethodName);
                result.getWarnings().add("已启用 PSI 兜底解析，仅保留当前方法的基础结构和字段依赖，用于继续生成测试草稿。");
            }
            enrichUsagesFromPsi(project, psiFile, editor, preferredMethodName, methodModel);
            LOG.info("UnitMind: method = " + methodModel.getClassName() + "#" + methodModel.getMethodName());
            testDataGenerator.enrich(methodModel);
            List<TestScenario> scenarios = testScenarioPlanner.plan(methodModel);
            LOG.info("UnitMind: scenario count = " + scenarios.size());
            MockStrategy ruleMockStrategy = mockStrategyPlanner.plan(methodModel, scenarios);
            int mockCount = ruleMockStrategy.getMockFields() == null ? 0 : ruleMockStrategy.getMockFields().size();
            LOG.info("UnitMind: mock count = " + mockCount);
            GeneratedTestFile ruleGeneratedTestFile = ensureGeneratedFile(methodModel,
                    testGenerator.generate(methodModel, scenarios, ruleMockStrategy));

            MockStrategy mockStrategy = ruleMockStrategy;
            GeneratedTestFile generatedTestFile = ruleGeneratedTestFile;
            LlmConfig llmConfig = LlmConfigManager.load();
            LOG.info("UnitMind LLM: enabled=" + llmConfig.isEnabled());
            LOG.info("UnitMind LLM: apiUrl=" + safeLog(llmConfig.getApiUrl()));
            LOG.info("UnitMind LLM: model=" + safeLog(llmConfig.getModel()));
            LOG.info("UnitMind LLM: apiKey=" + llmConfig.maskedApiKey());
            LOG.info("UnitMind LLM: method=" + methodModel.getClassName() + "#" + methodModel.getMethodName());
            if (llmConfig.isEnabled()) {
                try {
                    LlmGenerationRequest llmRequest = buildLlmRequest(methodModel, scenarios, sourceText);
                    OpenAiCompatibleLlmClient llmClient = new OpenAiCompatibleLlmClient(llmConfig);
                    LOG.info("UnitMind LLM: prompt length=" + llmClient.promptLength(llmRequest));
                    LlmGenerationResponse llmResponse = llmClient.generateUnitTest(llmRequest);
                    LOG.info("UnitMind LLM: response length=" + (llmResponse.getRawResponse() == null ? 0 : llmResponse.getRawResponse().length()));
                    LOG.info("UnitMind LLM: mock strategy count=" + (llmResponse.getMockStrategy() == null || llmResponse.getMockStrategy().getStrategyItems() == null
                            ? 0 : llmResponse.getMockStrategy().getStrategyItems().size()));
                    LOG.info("UnitMind LLM: test code length=" + (llmResponse.getTestCode() == null ? 0 : llmResponse.getTestCode().length()));
                    if (llmResponse.getMockStrategy() != null
                            && llmResponse.getMockStrategy().getStrategyItems() != null
                            && !llmResponse.getMockStrategy().getStrategyItems().isEmpty()) {
                        mockStrategy = llmResponse.getMockStrategy();
                    }
                    generatedTestFile = copyWithContent(ruleGeneratedTestFile, llmResponse.getTestCode());
                    result.setGeneratedByLlm(true);
                    result.setRuleBasedFallback(false);
                    result.setLlmMessage("大模型生成完成。");
                    result.getWarnings().addAll(llmResponse.getWarnings());
                } catch (Exception llmException) {
                    LOG.warn("UnitMind LLM: generation failed", llmException);
                    String message = llmException.getMessage() == null ? llmException.getClass().getSimpleName() : llmException.getMessage();
                    result.getWarnings().add("大模型生成失败：" + message);
                    result.setGeneratedByLlm(false);
                    result.setRuleBasedFallback(true);
                    result.setLlmMessage("大模型生成失败：" + message);
                    if (!llmConfig.isFallbackToRuleBased()) {
                        result.getWarnings().add("已禁用规则兜底，但测试代码页不能空白，当前显示最小规则草稿。");
                        generatedTestFile = minimalFallbackFile(methodModel);
                    }
                }
            } else {
                result.setGeneratedByLlm(false);
                result.setRuleBasedFallback(true);
                result.setLlmMessage("当前测试代码为规则兜底生成，大模型生成失败或未启用。");
            }
            generatedTestFile = ensureGeneratedFile(methodModel, generatedTestFile);
            int codeLength = generatedTestFile == null || generatedTestFile.getContent() == null ? 0 : generatedTestFile.getContent().length();
            LOG.info("UnitMind: generated code length = " + codeLength);
            LOG.info("UnitMind: generated test file path = " + generatedTestPath(psiFile, generatedTestFile));

            result.setMethodModel(methodModel);
            result.setScenarios(scenarios);
            result.setMockStrategy(mockStrategy);
            result.setGeneratedTestFile(generatedTestFile);
        } catch (Exception ex) {
            LOG.warn("UnitMind: generate test code failed", ex);
            result.getWarnings().add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return result;
    }

    /**
     * 基于当前结果和用户补充要求再次调用大模型。
     */
    public GenerationResult regenerateWithLlmFeedback(GenerationResult result, String currentCode, String feedback) throws Exception {
        if (result == null || result.getMethodModel() == null) {
            throw new IllegalStateException("请先完成方法分析。");
        }
        LlmConfig llmConfig = LlmConfigManager.load();
        if (!llmConfig.isEnabled()) {
            throw new IllegalStateException("大模型未启用，请先在 UnitMind 设置中启用并测试连接。");
        }
        MethodModel methodModel = result.getMethodModel();
        LlmGenerationRequest llmRequest = buildLlmRequest(methodModel, result.getScenarios(), methodModel.getMethodBodyText());
        llmRequest.setExistingTestCodeSummary(currentCode);
        llmRequest.setUserInstruction(feedback);
        try {
            OpenAiCompatibleLlmClient llmClient = new OpenAiCompatibleLlmClient(llmConfig);
            LOG.info("UnitMind LLM: regenerate method=" + methodModel.getClassName() + "#" + methodModel.getMethodName());
            LOG.info("UnitMind LLM: prompt length=" + llmClient.promptLength(llmRequest));
            LlmGenerationResponse llmResponse = llmClient.generateUnitTest(llmRequest);
            LOG.info("UnitMind LLM: response length=" + (llmResponse.getRawResponse() == null ? 0 : llmResponse.getRawResponse().length()));
            LOG.info("UnitMind LLM: mock strategy count=" + (llmResponse.getMockStrategy() == null || llmResponse.getMockStrategy().getStrategyItems() == null
                    ? 0 : llmResponse.getMockStrategy().getStrategyItems().size()));
            LOG.info("UnitMind LLM: test code length=" + (llmResponse.getTestCode() == null ? 0 : llmResponse.getTestCode().length()));
            if (llmResponse.getMockStrategy() != null
                    && llmResponse.getMockStrategy().getStrategyItems() != null
                    && !llmResponse.getMockStrategy().getStrategyItems().isEmpty()) {
                result.setMockStrategy(llmResponse.getMockStrategy());
            }
            result.setGeneratedTestFile(copyWithContent(result.getGeneratedTestFile(), llmResponse.getTestCode()));
            result.setGeneratedByLlm(true);
            result.setRuleBasedFallback(false);
            result.setLlmMessage("大模型重新生成完成。");
            result.getWarnings().addAll(llmResponse.getWarnings());
            return result;
        } catch (Exception ex) {
            LOG.warn("UnitMind LLM: regenerate failed", ex);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            result.setLlmMessage("大模型重新生成失败：" + message);
            throw new IllegalStateException("大模型重新生成失败：" + message, ex);
        }
    }

    /**
     * 构造发送给大模型的结构化上下文。
     */
    private LlmGenerationRequest buildLlmRequest(MethodModel methodModel, List<TestScenario> scenarios, String sourceText) {
        LlmGenerationRequest request = new LlmGenerationRequest();
        request.setPackageName(methodModel.getPackageName());
        request.setClassName(methodModel.getClassName());
        request.setMethodName(methodModel.getMethodName());
        request.setMethodSignature(methodSignature(methodModel));
        request.setReturnType(methodModel.getReturnType());
        request.setMethodSourceCode(methodModel.getMethodBodyText() == null || methodModel.getMethodBodyText().isBlank()
                ? sourceText : methodModel.getMethodBodyText());
        request.setImports(String.join("\n", methodModel.getImports()));
        request.setClassFields(classFields(methodModel));
        request.setParameters(methodModel.getParameters());
        request.setConstraints(methodModel.getConstraints());
        request.setBranches(methodModel.getBranches());
        request.setDependencies(methodModel.getDependencies());
        request.setUsages(methodModel.getUsages());
        request.setScenarios(scenarios);
        request.setExistingTestCodeSummary("");
        request.setTestFramework("JUnit5");
        request.setMockFramework("Mockito");
        return request;
    }

    /**
     * 使用 IntelliJ 引用索引补充项目内调用点。
     * 如果索引没有结果，则保留 JavaParser 在当前文件中提取到的 usages。
     */
    private void enrichUsagesFromPsi(Project project, PsiFile psiFile, Editor editor, String preferredMethodName, MethodModel methodModel) {
        if (project == null || methodModel == null) {
            return;
        }
        PsiMethod method = currentPsiMethod(psiFile, editor, preferredMethodName);
        if (method == null || methodModel.getMethodName() == null || !methodModel.getMethodName().equals(method.getName())) {
            return;
        }

        List<MethodUsageModel> usages = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ReferencesSearch.search(method).forEach(reference -> {
            if (usages.size() >= MAX_USAGE_COUNT) {
                return false;
            }
            MethodUsageModel usage = psiUsage(project, reference);
            if (usage == null || isTestUsage(usage.getCallerFilePath())) {
                return true;
            }
            String key = usage.getCallerFilePath() + ":" + usage.getLineNumber() + ":" + usage.getCallExpression();
            if (seen.add(key)) {
                usages.add(usage);
            }
            return true;
        });
        if (!usages.isEmpty()) {
            methodModel.setUsages(usages);
            LOG.info("UnitMind: psi usage count = " + usages.size());
        }
    }

    /**
     * 将一个 PSI 引用转换成发送给大模型的调用点模型。
     */
    private MethodUsageModel psiUsage(Project project, PsiReference reference) {
        if (reference == null || reference.getElement() == null) {
            return null;
        }
        PsiElement element = reference.getElement();
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, false);
        if (call == null) {
            return null;
        }

        MethodUsageModel usage = new MethodUsageModel();
        PsiClass callerClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        PsiMethod callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        PsiFile containingFile = element.getContainingFile();
        usage.setCallerClassName(callerClass == null ? "" : callerClass.getName());
        usage.setCallerFilePath(containingFile == null || containingFile.getVirtualFile() == null ? "" : containingFile.getVirtualFile().getPath());
        usage.setCallerMethodName(callerMethod == null ? "<initializer>" : callerMethod.getName());
        usage.setCallerSignature(callerMethod == null ? "" : psiMethodSignature(callerMethod));
        usage.setCallExpression(call.getText());
        String receiver = call.getMethodExpression().getQualifierExpression() == null
                ? "" : call.getMethodExpression().getQualifierExpression().getText();
        usage.setReceiver(receiver);
        usage.setCallStyle(psiCallStyle(receiver, callerClass == null ? "" : callerClass.getName()));
        usage.setArgumentExpressions(psiArgumentExpressions(call));
        usage.setLineNumber(psiLineNumber(project, containingFile, element));
        usage.setConfidence("HIGH");
        usage.setSourceSnippet(trimSnippet(psiSourceSnippet(call)));
        return usage;
    }

    /**
     * 构造 PSI 方法签名文本。
     */
    private String psiMethodSignature(PsiMethod method) {
        StringBuilder builder = new StringBuilder();
        builder.append(typeText(method.getReturnType(), "void")).append(" ").append(method.getName()).append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(typeText(parameters[i].getType(), "Object")).append(" ").append(parameters[i].getName());
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * 提取 PSI 调用实参。
     */
    private List<String> psiArgumentExpressions(PsiMethodCallExpression call) {
        List<String> arguments = new ArrayList<>();
        for (PsiExpression expression : call.getArgumentList().getExpressions()) {
            arguments.add(expression.getText());
        }
        return arguments;
    }

    /**
     * 判断 PSI 调用风格。
     */
    private String psiCallStyle(String receiver, String callerClassName) {
        if (receiver == null || receiver.isBlank()) {
            return "unqualified";
        }
        if ("this".equals(receiver)) {
            return "this";
        }
        if ("super".equals(receiver)) {
            return "super";
        }
        if (receiver.equals(callerClassName)) {
            return "class";
        }
        return "qualified";
    }

    /**
     * 计算引用所在行号。
     */
    private int psiLineNumber(Project project, PsiFile file, PsiElement element) {
        if (project == null || file == null || element == null) {
            return -1;
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return -1;
        }
        return document.getLineNumber(element.getTextRange().getStartOffset()) + 1;
    }

    /**
     * 获取包含调用表达式的语句片段。
     */
    private String psiSourceSnippet(PsiMethodCallExpression call) {
        PsiStatement statement = PsiTreeUtil.getParentOfType(call, PsiStatement.class, false);
        return statement == null ? call.getText() : statement.getText();
    }

    /**
     * 过滤测试目录中的调用点，避免把已有测试代码当成业务用法。
     */
    private boolean isTestUsage(String filePath) {
        if (filePath == null) {
            return false;
        }
        String normalized = filePath.replace('\\', '/');
        return normalized.contains("/src/test/");
    }

    /**
     * 限制单个调用片段长度，避免局部语句过长导致 Prompt 膨胀。
     */
    private String trimSnippet(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }

    /**
     * 构造方法签名文本，便于 Prompt 不只依赖方法名。
     */
    private String methodSignature(MethodModel methodModel) {
        StringBuilder builder = new StringBuilder();
        builder.append(methodModel.getReturnType()).append(" ").append(methodModel.getMethodName()).append("(");
        for (int i = 0; i < methodModel.getParameters().size(); i++) {
            ParameterModel parameter = methodModel.getParameters().get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameter.getType()).append(" ").append(parameter.getName());
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * 提取字段依赖摘要。
     */
    private String classFields(MethodModel methodModel) {
        StringBuilder builder = new StringBuilder();
        for (DependencyModel dependency : methodModel.getDependencies()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(dependency.getFieldType()).append(" ").append(dependency.getFieldName());
        }
        return builder.toString();
    }

    /**
     * 保留规则生成器推导出的文件名和包名，仅替换大模型返回的源码。
     */
    private GeneratedTestFile copyWithContent(GeneratedTestFile base, String content) {
        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName(base == null ? "" : base.getPackageName());
        file.setClassName(base == null ? "" : base.getClassName());
        file.setFileName(base == null ? "" : base.getFileName());
        file.setContent(content == null ? "" : content);
        return file;
    }

    /**
     * 兜底确保测试代码不为空。
     */
    private GeneratedTestFile ensureGeneratedFile(MethodModel methodModel, GeneratedTestFile file) {
        if (file == null || file.getContent() == null || file.getContent().trim().isEmpty()) {
            return minimalFallbackFile(methodModel);
        }
        return file;
    }

    /**
     * 最小测试类草稿，保证失败场景下测试代码页不空白。
     */
    private GeneratedTestFile minimalFallbackFile(MethodModel methodModel) {
        String className = methodModel == null || methodModel.getClassName() == null || methodModel.getClassName().isBlank()
                ? "Generated" : methodModel.getClassName();
        String packageName = methodModel == null || methodModel.getPackageName() == null ? "" : methodModel.getPackageName();
        String testClassName = className + "Test";
        StringBuilder code = new StringBuilder();
        if (!packageName.isBlank()) {
            code.append("package ").append(packageName).append(";\n\n");
        }
        code.append("import org.junit.jupiter.api.Test;\n\n");
        code.append("class ").append(testClassName).append(" {\n\n");
        code.append("    @Test\n");
        code.append("    void shouldExecuteMethod() {\n");
        code.append("        // TODO: 大模型生成失败，当前为 UnitMind 规则兜底测试草稿。\n");
        code.append("    }\n");
        code.append("}\n");

        GeneratedTestFile file = new GeneratedTestFile();
        file.setPackageName(packageName);
        file.setClassName(testClassName);
        file.setFileName(testClassName + ".java");
        file.setContent(code.toString());
        return file;
    }

    private String safeLog(String value) {
        return value == null ? "" : value;
    }

    /**
     * 推导生成测试文件的目标路径。
     * 日志使用与写入器一致的路径规则，便于从生成阶段直接定位后续会落盘的位置。
     */
    private String generatedTestPath(PsiFile sourceFile, GeneratedTestFile generatedTestFile) {
        if (generatedTestFile == null) {
            return "";
        }
        if (sourceFile == null || sourceFile.getVirtualFile() == null) {
            return generatedTestFile.getFileName();
        }
        String sourcePath = sourceFile.getVirtualFile().getPath();
        String normalized = sourcePath.replace('\\', '/');
        int index = normalized.indexOf("/src/main/java/");
        if (index >= 0) {
            int lastSlash = normalized.lastIndexOf('/');
            String root = normalized.substring(0, index);
            String relativeDir = lastSlash > index ? normalized.substring(index + "/src/main/java/".length(), lastSlash) : "";
            return root + "/src/test/java/" + relativeDir + "/" + generatedTestFile.getFileName();
        }
        int lastSlash = normalized.lastIndexOf('/');
        String parent = lastSlash >= 0 ? normalized.substring(0, lastSlash) : normalized;
        return parent + "/generated-test/" + generatedTestFile.getFileName();
    }

    /**
     * 使用 IntelliJ PSI 构造基础方法模型。
     * 该降级路径绕过整类 JavaParser 解析失败问题，优先保留光标所在方法、参数、返回值和字段依赖。
     */
    private MethodModel buildPsiFallbackModel(PsiFile psiFile, Editor editor, String preferredMethodName) {
        PsiMethod method = currentPsiMethod(psiFile, editor, preferredMethodName);
        if (method == null) {
            throw new IllegalStateException("源码解析失败，且 PSI 未能定位当前方法。请确认光标位于需要生成测试的方法内部。");
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            throw new IllegalStateException("PSI 降级解析失败：当前方法没有所属类。");
        }

        MethodModel model = new MethodModel();
        model.setLanguage("Java");
        model.setPackageName(psiFile instanceof PsiJavaFile ? ((PsiJavaFile) psiFile).getPackageName() : "");
        model.setClassName(containingClass.getName());
        model.setMethodName(method.getName());
        model.setReturnType(typeText(method.getReturnType(), "void"));
        model.setProtectedMethod(method.hasModifierProperty(PsiModifier.PROTECTED));
        model.setImports(extractPsiImports(psiFile));
        model.setThrownExceptions(extractPsiThrownExceptions(method));
        model.setMethodBodyText(method.getBody() == null ? "" : method.getBody().getText());
        model.setDeclaredMethodNames(extractPsiMethodNames(containingClass));
        model.setParameters(extractPsiParameters(method));
        model.setDependencies(extractPsiDependencies(containingClass, model.getMethodBodyText()));
        return model;
    }

    /**
     * 提取 PSI 类中声明的方法名。
     * PSI 降级路径也需要这些名称来识别本类内部调用。
     */
    private List<String> extractPsiMethodNames(PsiClass psiClass) {
        List<String> names = new ArrayList<>();
        for (PsiMethod method : psiClass.getMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * 定位当前应分析的方法。
     * 优先取光标所在方法；如果光标不在方法内，则按 JavaParser 同样策略回退到指定方法名或首个 public 方法。
     */
    private PsiMethod currentPsiMethod(PsiFile psiFile, Editor editor, String preferredMethodName) {
        if (psiFile != null && editor != null) {
            PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
            PsiMethod current = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
            if (current != null) {
                return current;
            }
        }

        PsiClass psiClass = firstPsiClass(psiFile);
        if (psiClass == null) {
            return null;
        }
        for (PsiMethod method : psiClass.getMethods()) {
            if (preferredMethodName != null && preferredMethodName.equals(method.getName())) {
                return method;
            }
        }
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC)) {
                return method;
            }
        }
        PsiMethod[] methods = psiClass.getMethods();
        return methods.length == 0 ? null : methods[0];
    }

    /**
     * 获取文件中的首个类声明。
     * PSI 降级只需要当前文件主类的字段和方法上下文，内部类不在本次兜底范围内主动展开。
     */
    private PsiClass firstPsiClass(PsiFile psiFile) {
        if (!(psiFile instanceof PsiJavaFile)) {
            return null;
        }
        PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
        return classes.length == 0 ? null : classes[0];
    }

    /**
     * 提取 PSI import 列表。
     * 生成代码时需要复用 JdbcTemplate、Servlet 等类型来源，避免因为降级路径丢失 import。
     */
    private List<String> extractPsiImports(PsiFile psiFile) {
        List<String> imports = new ArrayList<>();
        if (!(psiFile instanceof PsiJavaFile) || ((PsiJavaFile) psiFile).getImportList() == null) {
            return imports;
        }
        for (PsiImportStatement importStatement : ((PsiJavaFile) psiFile).getImportList().getImportStatements()) {
            String qualifiedName = importStatement.getQualifiedName();
            if (qualifiedName != null) {
                imports.add(qualifiedName);
            }
        }
        return imports;
    }

    /**
     * 提取方法声明中的 throws 类型。
     * 降级模型保留这些类型，保证测试方法签名仍能处理受检异常。
     */
    private List<String> extractPsiThrownExceptions(PsiMethod method) {
        List<String> exceptions = new ArrayList<>();
        for (PsiType type : method.getThrowsList().getReferencedTypes()) {
            exceptions.add(type.getPresentableText());
        }
        return exceptions;
    }

    /**
     * 提取方法参数。
     * PSI 给出的类型文本直接进入模型，后续测试数据生成器继续按已有规则补默认值。
     */
    private List<ParameterModel> extractPsiParameters(PsiMethod method) {
        List<ParameterModel> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            ParameterModel model = new ParameterModel();
            model.setName(parameter.getName());
            model.setType(typeText(parameter.getType(), "Object"));
            model.setNullable(!parameter.getType().equals(PsiType.INT)
                    && !parameter.getType().equals(PsiType.LONG)
                    && !parameter.getType().equals(PsiType.BOOLEAN)
                    && !parameter.getType().equals(PsiType.DOUBLE)
                    && !parameter.getType().equals(PsiType.FLOAT)
                    && !parameter.getType().equals(PsiType.SHORT)
                    && !parameter.getType().equals(PsiType.BYTE)
                    && !parameter.getType().equals(PsiType.CHAR));
            parameters.add(model);
        }
        return parameters;
    }

    /**
     * 提取字段依赖并结合方法体文本补充调用表达式。
     * 降级路径不做完整 AST 分析，但会识别 RuntimeDataSourceRegistry 和 JdbcTemplate 这类核心协作者。
     */
    private List<DependencyModel> extractPsiDependencies(PsiClass containingClass, String methodBodyText) {
        List<DependencyModel> dependencies = new ArrayList<>();
        for (PsiField field : containingClass.getFields()) {
            String fieldName = field.getName();
            String fieldType = typeText(field.getType(), "Object");
            if (!looksLikeDependency(fieldName, fieldType)) {
                continue;
            }
            DependencyModel dependency = new DependencyModel();
            dependency.setFieldName(fieldName);
            dependency.setFieldType(fieldType);
            dependency.setDependencyType(dependencyType(fieldName, fieldType));
            dependency.setCalledMethods(new ArrayList<>());
            dependency.setCallExpressions(new ArrayList<>());
            if (methodBodyText != null && methodBodyText.contains(fieldName + ".getJdbcTemplate(")) {
                dependency.getCalledMethods().add("getJdbcTemplate");
                dependency.getCallExpressions().add("getJdbcTemplate(any(), any())");
            }
            if (methodBodyText != null && methodBodyText.contains(fieldName + ".hasConfig(")) {
                dependency.getCalledMethods().add("hasConfig");
                dependency.getCallExpressions().add("hasConfig(any(), any())");
            }
            dependencies.add(dependency);
        }
        return dependencies;
    }

    /**
     * 判断字段是否应作为外部依赖。
     * 规则覆盖常见仓储、客户端、运行时数据源注册表和 JdbcTemplate。
     */
    private boolean looksLikeDependency(String fieldName, String fieldType) {
        String text = (fieldName + " " + fieldType).toLowerCase(Locale.ROOT);
        return text.contains("mapper")
                || text.contains("repository")
                || text.contains("dao")
                || text.contains("client")
                || text.contains("template")
                || text.contains("registry")
                || text.contains("jdbc")
                || text.contains("redis")
                || text.contains("cache")
                || text.contains("service");
    }

    /**
     * 推断依赖业务类型。
     * 该分类主要用于 Mock 预览解释，运行时数据源和 JdbcTemplate 统一归为数据库协作。
     */
    private String dependencyType(String fieldName, String fieldType) {
        String text = (fieldName + " " + fieldType).toLowerCase(Locale.ROOT);
        if (text.contains("jdbc") || text.contains("template") || text.contains("registry")
                || text.contains("mapper") || text.contains("repository") || text.contains("dao")) {
            return "DATABASE";
        }
        if (text.contains("client")) {
            return "REMOTE_CLIENT";
        }
        if (text.contains("redis") || text.contains("cache")) {
            return "CACHE";
        }
        return "UNKNOWN";
    }

    /**
     * 转换 PSI 类型文本。
     * 优先使用 presentableText 保持生成源码简洁，缺失时使用调用方给定默认值。
     */
    private String typeText(PsiType type, String defaultValue) {
        return type == null ? defaultValue : type.getPresentableText();
    }
}
