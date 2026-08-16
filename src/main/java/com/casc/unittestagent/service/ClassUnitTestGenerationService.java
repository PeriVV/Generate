package com.casc.unittestagent.service;

import com.casc.unittestagent.generator.ClassTestAssembler;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.GenerationScope;
import com.casc.unittestagent.model.MethodGenerationItem;
import com.casc.unittestagent.model.MethodGenerationStatus;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.util.PsiUtils;
import com.casc.unittestagent.util.StringUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 编排当前顶层 Java 类的逐方法测试生成、事务式 AST 聚合和增量完整类编译门禁。 */
public class ClassUnitTestGenerationService {
    private static final Logger LOG = Logger.getInstance(ClassUnitTestGenerationService.class);
    private final JavaSourceAnalyzer analyzer;
    private final TestDataGenerator testDataGenerator;
    private final UnitTestGenerationService methodService;
    private final ClassTestAssembler assembler;

    public ClassUnitTestGenerationService() {
        this(new JavaSourceAnalyzer(), new TestDataGenerator(),
                new UnitTestGenerationService(), new ClassTestAssembler());
    }

    ClassUnitTestGenerationService(JavaSourceAnalyzer analyzer,
                                   TestDataGenerator testDataGenerator,
                                   UnitTestGenerationService methodService,
                                   ClassTestAssembler assembler) {
        this.analyzer = analyzer;
        this.testDataGenerator = testDataGenerator;
        this.methodService = methodService;
        this.assembler = assembler;
    }

    /** 只执行整类静态分析，不读取 LLM 配置、不发起网络请求。 */
    public ClassGenerationResult analyzeClass(Project project, PsiFile psiFile, Editor editor) {
        ClassGenerationResult result = new ClassGenerationResult();
        try {
            LOG.info("UnitMind: start static class analysis");
            Computable<ClassModel> analysis = () -> {
                if (!(psiFile instanceof PsiJavaFile)) {
                    throw new IllegalStateException("请先打开一个 Java 文件。");
                }
                String source = PsiUtils.getSourceText(psiFile);
                String className = currentTopLevelClassName(psiFile, editor);
                int line = caretLine(editor);
                return analyzer.analyzeClass(source, className, line);
            };
            ClassModel classModel = project == null
                    ? ApplicationManager.getApplication().runReadAction(analysis)
                    : DumbService.getInstance(project).runReadActionInSmartMode(analysis);
            List<String> signatures = classModel.getMethods().stream().map(MethodModel::getSignature).toList();
            String fixture = sharedFixtureSummary(classModel);
            List<MethodGenerationItem> items = new ArrayList<>();
            for (MethodModel method : classModel.getMethods()) {
                try {
                    testDataGenerator.enrich(method);
                } catch (RuntimeException ex) {
                    String message = method.getSignature() + " 测试数据补全失败，已保留静态分析结果：" + safeMessage(ex);
                    result.getWarnings().add(message);
                    LOG.warn("UnitMind: class method test-data enrichment failed, methodId="
                            + method.getMethodId() + ", errorType=" + ex.getClass().getSimpleName());
                }
                GenerationResult methodResult = new GenerationResult();
                methodResult.setGenerationScope(GenerationScope.CLASS);
                methodResult.setMethodModel(method);
                methodResult.setClassMethodSignatures(signatures);
                methodResult.setSharedFixtureSummary(fixture);
                MethodGenerationItem item = new MethodGenerationItem();
                item.setMethodId(method.getMethodId());
                item.setSignature(method.getSignature());
                item.setResult(methodResult);
                item.setStatus(MethodGenerationStatus.ANALYZED);
                items.add(item);
            }
            result.setClassModel(classModel);
            result.setMethodItems(items);
            // 保留首个方法作为现有编译修复管线的主上下文；完整方法列表仍保存在 methodItems。
            result.setMethodModel(classModel.getMethods().get(0));
            result.setClassMethodSignatures(signatures);
            result.setSharedFixtureSummary(fixture);
            LOG.info("UnitMind: static class analysis completed, class=" + classModel.getQualifiedName()
                    + ", included=" + classModel.getMethods().size()
                    + ", skipped=" + classModel.getSkippedMethods().size());
        } catch (Exception ex) {
            String message = safeMessage(ex);
            result.getWarnings().add(message);
            LOG.warn("UnitMind: static class analysis failed, errorType="
                    + ex.getClass().getSimpleName() + ", message=" + message, ex);
        }
        return result;
    }

    public ClassGenerationResult planScenarios(ClassGenerationResult result) {
        if (result == null) return new ClassGenerationResult();
        for (MethodGenerationItem item : result.getMethodItems()) {
            if (item.getStatus() == MethodGenerationStatus.CANCELLED) continue;
            methodService.planScenarios(item.getResult());
            item.setStatus(MethodGenerationStatus.PLANNED);
        }
        syncPrimaryContext(result);
        return result;
    }

    public ClassGenerationResult planMockStrategies(ClassGenerationResult result) {
        if (result == null) return new ClassGenerationResult();
        planScenarios(result);
        for (MethodGenerationItem item : result.getMethodItems()) {
            if (item.getStatus() == MethodGenerationStatus.CANCELLED) continue;
            methodService.planMockStrategy(item.getResult());
            item.setStatus(MethodGenerationStatus.PLANNED);
        }
        syncPrimaryContext(result);
        return result;
    }

    /**
     * 顺序生成各方法候选，并将每个方法作为一次独立事务提交到当前已通过的测试类。
     * 任一方法只有在“当前完整类”编译通过后才会成为后续方法的基线，避免把多组
     * 聚合错误堆积到最后一次整类修复中。
     */
    public ClassGenerationResult generateClassTest(Project project,
                                                    PsiFile psiFile,
                                                    ClassGenerationResult result,
                                                    ProgressIndicator indicator) {
        long started = System.currentTimeMillis();
        if (result == null || result.getClassModel() == null || result.getMethodItems().isEmpty()) {
            throw new IllegalStateException("请先完成整类静态分析。");
        }
        planMockStrategies(result);
        int total = result.getMethodItems().size();
        List<MethodGenerationItem> integratedItems = new ArrayList<>();
        GeneratedTestFile generatedFile = null;
        ValidationResult aggregateValidation = ValidationResult.status(ValidationStatus.NOT_RUN);
        for (int index = 0; index < total; index++) {
            MethodGenerationItem item = result.getMethodItems().get(index);
            if (indicator != null) {
                indicator.setText("正在生成 " + item.getSignature());
                indicator.setText2((index + 1) + " / " + total);
                indicator.setFraction(total == 0 ? 1d : (double) index / total);
                if (indicator.isCanceled()) {
                    cancelRemaining(result, index);
                    break;
                }
            }
            item.setStatus(MethodGenerationStatus.GENERATING);
            try {
                GenerationResult generated = methodService.generateTestCode(project, psiFile, item.getResult());
                item.setResult(generated);
                if (generated.getGeneratedTestFile() == null
                        || generated.getGeneratedTestFile().getContent() == null
                        || generated.getGeneratedTestFile().getContent().isBlank()) {
                    useRuleFallback(item, "生成结果为空");
                } else if (generated.isRuleBasedFallback()) {
                    useRuleFallback(item, generated.getLlmMessage() == null
                            ? "大模型未启用或生成失败" : generated.getLlmMessage());
                } else if (generated.getValidationResult() != null
                        && generated.getValidationResult().getStatus() == ValidationStatus.ENVIRONMENT_ERROR) {
                    // 环境问题不能通过换成低质量规则草稿解决；保留 LLM 候选进入增量门禁，
                    // 同时把未验证状态明确暴露给用户。
                    item.setStatus(MethodGenerationStatus.SUCCESS);
                    item.setMessage("单方法候选因项目环境问题未能独立校验");
                    result.getWarnings().add(item.getSignature() + "："
                            + candidateValidationMessage(generated));
                } else if (generated.getValidationResult() == null
                        || generated.getValidationResult().getStatus() != ValidationStatus.PASSED) {
                    useRuleFallback(item, candidateValidationMessage(generated));
                } else {
                    item.setStatus(MethodGenerationStatus.SUCCESS);
                }
                addWarnings(result, generated.getWarnings());
            } catch (Exception ex) {
                useRuleFallback(item, safeMessage(ex));
                result.getWarnings().add(item.getSignature() + " 生成失败，已使用规则草稿：" + safeMessage(ex));
            }

            if (item.getStatus() == MethodGenerationStatus.SUCCESS
                    || item.getStatus() == MethodGenerationStatus.RULE_FALLBACK) {
                if (indicator != null) {
                    indicator.setText("正在增量校验 " + item.getSignature());
                }
                IncrementalAssembly integration = integrateMethod(project, psiFile, result,
                        integratedItems, item, generatedFile, aggregateValidation);
                generatedFile = integration.generatedFile;
                aggregateValidation = integration.validation;
                if (integration.accepted) integratedItems.add(item);
            }
        }

        // 直接发布最后一个已验证检查点，避免最终重新聚合产生未经门禁的新文本。
        // 没有任何方法通过（或生成前即取消）时，仅构造一个空的类级草稿。
        if (generatedFile == null) {
            ClassTestAssembler.AssemblyResult assembly = assembleWithFallback(result, integratedItems);
            addWarnings(result, assembly.getWarnings());
            generatedFile = assembly.getGeneratedTestFile();
        }
        result.setGeneratedTestFile(generatedFile);
        result.setGeneratedByLlm(result.getMethodItems().stream().anyMatch(item ->
                item.getStatus() == MethodGenerationStatus.SUCCESS
                        && item.getResult() != null && item.getResult().isGeneratedByLlm()));
        result.setRuleBasedFallback(result.getMethodItems().stream()
                .anyMatch(item -> item.getStatus() == MethodGenerationStatus.RULE_FALLBACK));
        syncPrimaryContext(result);

        if (!result.isCancelled()) {
            if (integratedItems.isEmpty()) {
                // 空测试类即使 Java 语法可编译也不是可用产物，不能进入正常写入流程。
                aggregateValidation = ValidationResult.status(ValidationStatus.FAILED);
            }
            result.setValidationResult(aggregateValidation);
            result.setComplete(!integratedItems.isEmpty());
            int failedCount = (int) result.getMethodItems().stream()
                    .filter(item -> item.getStatus() == MethodGenerationStatus.FAILED).count();
            if (integratedItems.isEmpty()) {
                result.setLlmMessage("整类生成结束，但没有方法候选通过增量编译门禁。");
            } else if (aggregateValidation.getStatus() == ValidationStatus.PASSED) {
                result.setLlmMessage("已逐方法生成并通过增量完整类编译校验：接纳 "
                        + integratedItems.size() + " / " + total
                        + (failedCount == 0 ? "。" : "，隔离失败 " + failedCount + " 个。"));
            } else if (aggregateValidation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR) {
                result.setLlmMessage("已逐方法聚合 " + integratedItems.size() + " / " + total
                        + " 个候选，但项目编译环境无法完成校验。");
            } else {
                result.setLlmMessage("整类增量生成结束，但最终编译门禁未通过。");
            }
        } else {
            result.setComplete(false);
            result.setValidationResult(aggregateValidation);
            result.setLlmMessage("整类生成已取消，当前仅为部分草稿。");
        }
        if (indicator != null) indicator.setFraction(1d);
        logGenerationOutcome(result, System.currentTimeMillis() - started);
        return result;
    }

    /**
     * 将当前方法追加到已通过前缀并立即做完整类编译。失败时只回退当前方法，
     * 不允许本轮错误污染先前检查点，也不把诊断留到最终整类统一修复。
     */
    private IncrementalAssembly integrateMethod(Project project,
                                                 PsiFile psiFile,
                                                 ClassGenerationResult classResult,
                                                 List<MethodGenerationItem> integratedItems,
                                                 MethodGenerationItem current,
                                                 GeneratedTestFile previousFile,
                                                 ValidationResult previousValidation) {
        List<MethodGenerationItem> prefix = new ArrayList<>(integratedItems);
        prefix.add(current);
        try {
            ClassTestAssembler.AssemblyResult assembly = assembleWithFallback(classResult, prefix);
            addWarnings(classResult, assembly.getWarnings());
            IncrementalValidation validation = validateIncrementalClass(
                    project, psiFile, assembly.getGeneratedTestFile());
            if (isAcceptableIncrementalStatus(validation.validation)) {
                addEnvironmentWarning(classResult, current, validation.validation);
                return new IncrementalAssembly(validation.generatedFile, validation.validation, true);
            }

            String llmFailure = incrementalValidationMessage(validation.validation);
            if (current.getStatus() == MethodGenerationStatus.SUCCESS) {
                useRuleFallback(current, "合并到当前测试类后编译失败：" + llmFailure);
                classResult.getWarnings().add(current.getSignature()
                        + " 的 LLM 候选未通过增量完整类门禁，已仅回退该方法：" + llmFailure);
                if (current.getStatus() == MethodGenerationStatus.RULE_FALLBACK) {
                    ClassTestAssembler.AssemblyResult fallbackAssembly =
                            assembleWithFallback(classResult, prefix);
                    addWarnings(classResult, fallbackAssembly.getWarnings());
                    IncrementalValidation fallbackValidation = validateIncrementalClass(
                            project, psiFile, fallbackAssembly.getGeneratedTestFile());
                    if (isAcceptableIncrementalStatus(fallbackValidation.validation)) {
                        addEnvironmentWarning(classResult, current, fallbackValidation.validation);
                        return new IncrementalAssembly(fallbackValidation.generatedFile,
                                fallbackValidation.validation, true);
                    }
                    llmFailure += "；规则兜底仍失败："
                            + incrementalValidationMessage(fallbackValidation.validation);
                }
            }
            rejectCurrentMethod(classResult, current, llmFailure);
        } catch (Exception ex) {
            rejectCurrentMethod(classResult, current, safeMessage(ex));
        }
        return new IncrementalAssembly(previousFile,
                previousValidation == null
                        ? ValidationResult.status(ValidationStatus.NOT_RUN) : previousValidation,
                false);
    }

    /** 使用现有编译器管线做只读门禁，显式关闭 LLM 修复，保证每轮只处理当前方法。 */
    private IncrementalValidation validateIncrementalClass(Project project,
                                                           PsiFile psiFile,
                                                           GeneratedTestFile generatedFile) {
        GenerationResult probe = new GenerationResult();
        probe.setGeneratedByLlm(false);
        GeneratedTestFile validated = methodService.validateAndRepair(
                project, psiFile, probe, generatedFile, new LlmConfig());
        ValidationResult validation = probe.getValidationResult() == null
                ? ValidationResult.status(ValidationStatus.NOT_RUN) : probe.getValidationResult();
        return new IncrementalValidation(validated, validation);
    }

    private boolean isAcceptableIncrementalStatus(ValidationResult validation) {
        return validation != null && (validation.getStatus() == ValidationStatus.PASSED
                || validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR);
    }

    private void addEnvironmentWarning(ClassGenerationResult result,
                                       MethodGenerationItem item,
                                       ValidationResult validation) {
        if (validation == null || validation.getStatus() != ValidationStatus.ENVIRONMENT_ERROR) return;
        String warning = item.getSignature() + " 增量完整类校验受项目环境阻断，当前候选未被误判为代码错误。";
        if (!result.getWarnings().contains(warning)) result.getWarnings().add(warning);
    }

    private void rejectCurrentMethod(ClassGenerationResult result,
                                     MethodGenerationItem item,
                                     String reason) {
        item.setStatus(MethodGenerationStatus.FAILED);
        item.setMessage("未进入整类测试：" + reason);
        String warning = item.getSignature() + " 未进入整类测试，已回滚到上一可编译检查点：" + reason;
        if (!result.getWarnings().contains(warning)) result.getWarnings().add(warning);
    }

    private String incrementalValidationMessage(ValidationResult validation) {
        if (validation == null) return "未返回编译校验结果";
        if (validation.getDiagnostics().isEmpty()) return "校验状态为 " + validation.getStatus();
        return validation.getDiagnostics().get(0).getCode() + " - "
                + validation.getDiagnostics().get(0).getMessage();
    }

    private void logGenerationOutcome(ClassGenerationResult result, long durationMillis) {
        if (result == null) return;
        long successful = result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.SUCCESS).count();
        long fallback = result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.RULE_FALLBACK).count();
        long failed = result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.FAILED).count();
        long cancelled = result.getMethodItems().stream()
                .filter(item -> item.getStatus() == MethodGenerationStatus.CANCELLED).count();
        ValidationStatus validation = result.getValidationResult() == null
                ? ValidationStatus.NOT_RUN : result.getValidationResult().getStatus();
        int repairs = result.getValidationResult() == null
                ? 0 : result.getValidationResult().getRepairAttempts();
        LOG.info("UnitMind class generation outcome: methods=" + result.getMethodItems().size()
                + ", successful=" + successful + ", ruleFallback=" + fallback
                + ", failed=" + failed + ", cancelled=" + cancelled
                + ", finalValidation=" + validation + ", finalRepairRounds=" + repairs
                + ", durationMs=" + Math.max(0L, durationMillis));
    }

    private ClassTestAssembler.AssemblyResult assembleWithFallback(ClassGenerationResult classResult,
                                                                    List<MethodGenerationItem> items) {
        ClassTestAssembler.AssemblyResult assembly = assembler.assemble(classResult.getClassModel(), items);
        if (assembly.getRejectedMethodIds().isEmpty()) return assembly;
        Set<String> rejected = new LinkedHashSet<>(assembly.getRejectedMethodIds());
        for (MethodGenerationItem item : items) {
            if (!rejected.contains(item.getMethodId())) continue;
            useRuleFallback(item, "LLM 候选与整类 fixture 冲突");
        }
        ClassTestAssembler.AssemblyResult fallbackAssembly = assembler.assemble(classResult.getClassModel(), items);
        addWarnings(classResult, assembly.getWarnings());
        if (!fallbackAssembly.getRejectedMethodIds().isEmpty()) {
            throw new IllegalStateException("规则候选仍无法聚合：" + fallbackAssembly.getRejectedMethodIds()
                    + "；" + String.join("；", fallbackAssembly.getWarnings()));
        }
        return fallbackAssembly;
    }

    private void useRuleFallback(MethodGenerationItem item, String reason) {
        try {
            GeneratedTestFile fallback = methodService.generateRuleBasedTestFile(item.getResult());
            fallback.setContent(withFallbackTodo(fallback.getContent(), item.getMethodId()));
            item.getResult().setGeneratedTestFile(fallback);
            item.getResult().setGeneratedByLlm(false);
            item.getResult().setRuleBasedFallback(true);
            item.setStatus(MethodGenerationStatus.RULE_FALLBACK);
            item.setMessage(reason);
        } catch (Exception ex) {
            item.setStatus(MethodGenerationStatus.FAILED);
            item.setMessage(reason + "；规则草稿也生成失败：" + safeMessage(ex));
        }
    }

    private String candidateValidationMessage(GenerationResult generated) {
        if (generated == null || generated.getValidationResult() == null) {
            return "单方法候选未完成编译校验";
        }
        if (generated.getValidationResult().getDiagnostics().isEmpty()) {
            return "单方法候选编译校验状态为 " + generated.getValidationResult().getStatus();
        }
        return "单方法候选修复后仍未通过："
                + generated.getValidationResult().getDiagnostics().get(0).getCode() + " - "
                + generated.getValidationResult().getDiagnostics().get(0).getMessage();
    }

    private String withFallbackTodo(String code, String methodId) {
        if (code == null || code.contains("TODO")) return code;
        int annotation = code.indexOf("@Test");
        if (annotation < 0) annotation = code.indexOf("@ParameterizedTest");
        int body = annotation < 0 ? -1 : code.indexOf('{', annotation);
        if (body < 0) return code;
        return code.substring(0, body + 1)
                + "\n        // TODO: UnitMind 已使用规则兜底，请核对 " + methodId + " 的业务断言。\n"
                + code.substring(body + 1);
    }

    private void cancelRemaining(ClassGenerationResult result, int startIndex) {
        result.setCancelled(true);
        for (int index = startIndex; index < result.getMethodItems().size(); index++) {
            MethodGenerationItem item = result.getMethodItems().get(index);
            item.setStatus(MethodGenerationStatus.CANCELLED);
            item.setMessage("用户取消整类生成");
        }
    }

    private void syncPrimaryContext(ClassGenerationResult result) {
        if (result.getMethodItems().isEmpty()) return;
        GenerationResult first = result.getMethodItems().get(0).getResult();
        if (first == null) return;
        result.setMethodModel(first.getMethodModel());
        result.setScenarios(first.getScenarios());
        result.setMockStrategy(first.getMockStrategy());
        result.setSymbolSnapshot(first.getSymbolSnapshot());
    }

    private String sharedFixtureSummary(ClassModel model) {
        String subject = StringUtils.lowerCamel(model.getClassName());
        return "testClass=" + model.getClassName() + "Test, subjectField=" + subject
                + ", targetType=" + model.getQualifiedName()
                + ", classFields=" + String.join(", ", model.getClassFields());
    }

    private String currentTopLevelClassName(PsiFile file, Editor editor) {
        if (editor == null) return "";
        int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), file.getTextLength()));
        PsiElement element = file.findElementAt(offset);
        PsiClass current = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (current == null) return "";
        if (current.getName() == null) {
            throw new IllegalStateException("整类生成首期不支持匿名类或局部类。");
        }
        if (current.getContainingClass() != null) {
            throw new IllegalStateException("整类生成首期不支持成员内部类，请将光标放到顶层具体类中。");
        }
        return current.getName() == null ? "" : current.getName();
    }

    private int caretLine(Editor editor) {
        if (editor == null || editor.getDocument().getLineCount() == 0) return -1;
        int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), editor.getDocument().getTextLength()));
        return editor.getDocument().getLineNumber(offset) + 1;
    }

    private void addWarnings(GenerationResult result, List<String> warnings) {
        if (warnings == null) return;
        for (String warning : warnings) {
            if (warning != null && !warning.isBlank() && !result.getWarnings().contains(warning)) {
                result.getWarnings().add(warning);
            }
        }
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static class IncrementalValidation {
        protected final GeneratedTestFile generatedFile;
        protected final ValidationResult validation;

        private IncrementalValidation(GeneratedTestFile generatedFile, ValidationResult validation) {
            this.generatedFile = generatedFile;
            this.validation = validation;
        }
    }

    private static class IncrementalAssembly extends IncrementalValidation {
        private final boolean accepted;

        private IncrementalAssembly(GeneratedTestFile generatedFile,
                                    ValidationResult validation,
                                    boolean accepted) {
            super(generatedFile, validation);
            this.accepted = accepted;
        }
    }
}
