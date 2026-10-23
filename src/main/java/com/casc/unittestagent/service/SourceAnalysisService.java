package com.casc.unittestagent.service;

import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MethodUsageModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.parser.ProjectSymbolSnapshotBuilder;
import com.casc.unittestagent.util.CodeFingerprint;
import com.casc.unittestagent.util.PsiUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;


/** PSI/JavaParser analysis and project symbol enrichment, independent of generation and repair loops. */
final class SourceAnalysisService {
    private static final Logger LOG = Logger.getInstance(SourceAnalysisService.class);
    private static final int MAX_USAGE_COUNT = 20;
    private final JavaSourceAnalyzer javaSourceAnalyzer = new JavaSourceAnalyzer();
    private final TestDataGenerator testDataGenerator = new TestDataGenerator();
    private final ProjectSymbolSnapshotBuilder symbolSnapshotBuilder;
    private final BooleanSupplier cancelled;

    SourceAnalysisService(ProjectSymbolSnapshotBuilder symbolSnapshotBuilder, BooleanSupplier cancelled) {
        this.symbolSnapshotBuilder = symbolSnapshotBuilder;
        this.cancelled = cancelled;
    }

    public GenerationResult analyzeMethod(Project project, PsiFile psiFile, Editor editor) {
        com.casc.unittestagent.util.OperationContext.capture(cancelled).checkCancelled();
        GenerationResult result = new GenerationResult();
        try {
            LOG.info("UnitMind: start static method analysis");
            Computable<StaticAnalysisContext> analysis =
                    () -> buildStaticAnalysisContext(project, psiFile, editor);
            StaticAnalysisContext staticContext = project == null
                    ? ApplicationManager.getApplication().runReadAction(analysis)
                    : DumbService.getInstance(project).runReadActionInSmartMode(analysis);
            MethodModel methodModel = staticContext.getMethodModel();
            testDataGenerator.enrich(methodModel);
            result.setMethodModel(methodModel);
            if (!methodModel.getAnalysisLimitation().isBlank()) result.getWarnings().add(methodModel.getAnalysisLimitation());
            result.setSymbolSnapshot(staticContext.getSymbolSnapshot());
        } catch (Exception ex) {
            if (ex instanceof ProcessCanceledException cancellation) throw cancellation;
            LOG.warn("UnitMind: static method analysis failed, errorType=" + ex.getClass().getSimpleName());
            result.getWarnings().add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return result;
    }

    private StaticAnalysisContext buildStaticAnalysisContext(Project project, PsiFile psiFile, Editor editor) {
        String sourceText = PsiUtils.getSourceText(psiFile);
        String preferredMethodName = PsiUtils.getCurrentMethodName(psiFile, editor);
        int preferredSourceLine = -1;
        if (editor != null && editor.getDocument().getLineCount() > 0) {
            int caretOffset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), editor.getDocument().getTextLength()));
            preferredSourceLine = editor.getDocument().getLineNumber(caretOffset) + 1;
        }

        MethodModel methodModel;
        try {
            methodModel = javaSourceAnalyzer.analyze(sourceText, preferredMethodName, preferredSourceLine);
        } catch (Exception parseException) {
            if (parseException instanceof ProcessCanceledException cancellation) throw cancellation;
            LOG.warn("UnitMind: JavaParser analysis failed, use PSI fallback, errorType="
                    + parseException.getClass().getSimpleName());
            methodModel = buildPsiFallbackModel(psiFile, editor, preferredMethodName);
            methodModel.setAnalyzedSourceHash(CodeFingerprint.sha256(sourceText));
            methodModel.setAnalysisLimitation("JavaParser 无法完整解析当前源码，已使用 PSI 降级分析；参数约束与分支信息可能不完整。");
        }
        enrichUsagesFromPsi(project, psiFile, editor, preferredMethodName, methodModel);
        ProjectSymbolSnapshot snapshot = symbolSnapshotBuilder.build(project, psiFile, editor, methodModel);
        return new StaticAnalysisContext(methodModel, snapshot);
    }

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
    String methodSignature(MethodModel methodModel) {
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
    String classFields(MethodModel methodModel) {
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
     * 汇总发送给大模型的已知类型白名单。
     * 大模型只能在这些类型、源码 import 和 JDK/JUnit/Mockito 常用类型中选择，避免按命名习惯猜类型。
     */
    List<String> knownTypes(MethodModel methodModel,
                                    List<String> chainedCallReturnTypes,
                                    List<String> requestImports) {
        Set<String> types = new LinkedHashSet<>();
        addKnownType(types, methodModel.getClassName());
        addKnownType(types, methodModel.getReturnType());
        for (String importName : requestImports) {
            if (!importName.endsWith(".*")) {
                addKnownType(types, simpleTypeName(importName));
            }
        }
        for (ParameterModel parameter : methodModel.getParameters()) {
            addKnownType(types, parameter.getType());
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            addKnownType(types, dependency.getFieldType());
        }
        for (String chainedCallReturnType : chainedCallReturnTypes) {
            addKnownType(types, chainedTypeName(chainedCallReturnType));
        }
        types.add("String");
        types.add("Object");
        types.add("List");
        types.add("Map");
        types.add("Set");
        types.add("Optional");
        types.add("Path");
        types.add("Files");
        types.add("TempDir");
        types.add("Method");
        return new ArrayList<>(types);
    }

    private void addKnownType(Set<String> types, String type) {
        String simple = simpleTypeName(type);
        if (!simple.isBlank()) {
            types.add(simple);
        }
    }

    /**
     * 尽量解析链式调用中间节点的真实返回类型。
     * 无法解析时显式写入 UNKNOWN_USE_RETURNS_DEEP_STUBS，让大模型不要猜中间返回类型。
     */
    List<String> chainedCallReturnTypes(Project project, MethodModel methodModel) {
        List<String> returnTypes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<CallContextModel> callContexts = methodModel.getCallContexts() == null ? new ArrayList<>() : methodModel.getCallContexts();
        for (CallContextModel callContext : callContexts) {
            if (!isChainContext(callContext)) {
                continue;
            }
            String receiverName = ownerName(callContext.getOwner());
            String receiverType = receiverType(methodModel, callContext.getOwner(), receiverName);
            collectChainedCallReturnTypes(project, methodModel, callContext.getExpression(), receiverName, receiverType, returnTypes, seen);
        }
        if (!returnTypes.isEmpty()) {
            return returnTypes;
        }
        String body = methodModel.getMethodBodyText() == null ? "" : methodModel.getMethodBodyText();
        for (ParameterModel parameter : methodModel.getParameters()) {
            collectChainedCallReturnTypes(project, methodModel, body, parameter.getName(), parameter.getType(), returnTypes, seen);
        }
        for (DependencyModel dependency : methodModel.getDependencies()) {
            collectChainedCallReturnTypes(project, methodModel, body, dependency.getFieldName(), dependency.getFieldType(), returnTypes, seen);
        }
        return returnTypes;
    }

    private void collectChainedCallReturnTypes(Project project,
                                               MethodModel methodModel,
                                               String expression,
                                               String receiverName,
                                               String receiverType,
                                               List<String> returnTypes,
                                               Set<String> seen) {
        if (receiverName == null || receiverName.isBlank() || expression == null || expression.isBlank()) {
            return;
        }
        List<ChainSegment> segments = parseCallChain(expression, receiverName);
        if (segments.isEmpty()) {
            return;
        }
        String currentExpression = receiverName;
        String currentType = receiverType;
        for (ChainSegment segment : segments) {
            if (currentType == null || currentType.isBlank()) {
                returnTypes.add(currentExpression + "." + segment.asCode() + "(): UNKNOWN_USE_RETURNS_DEEP_STUBS");
                break;
            }
            String cacheKey = currentType + "|" + segment.getMethodName() + "|" + segment.getArgumentCount();
            if (!seen.add(cacheKey + "|" + currentExpression)) {
                currentExpression = currentExpression + "." + segment.asCode();
                continue;
            }
            PsiClass psiClass = resolvePsiClass(project, methodModel, currentType);
            String returnType = resolveMethodReturnType(psiClass, segment.getMethodName(), segment.getArgumentCount());
            if (returnType == null || returnType.isBlank()) {
                returnTypes.add(currentExpression + "." + segment.asCode() + "(): UNKNOWN_USE_RETURNS_DEEP_STUBS");
                break;
            }
            returnTypes.add(currentExpression + "." + segment.asCode() + "(): " + returnType);
            currentExpression = currentExpression + "." + segment.asCode();
            currentType = rawType(returnType);
        }
    }

    private String resolveMethodReturnType(PsiClass psiClass, String methodName, int argumentCount) {
        if (psiClass == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        String resolvedReturnType = null;
        for (PsiMethod method : psiClass.findMethodsByName(methodName, true)) {
            if (method.getParameterList().getParametersCount() == argumentCount) {
                String candidate = canonicalTypeText(method.getReturnType(), "");
                if (resolvedReturnType != null && !resolvedReturnType.equals(candidate)) {
                    return null;
                }
                resolvedReturnType = candidate;
            }
        }
        return resolvedReturnType;
    }

    List<CallContextModel> enrichChainCallContexts(Project project, MethodModel methodModel) {
        List<CallContextModel> contexts = new ArrayList<>();
        if (methodModel == null || methodModel.getCallContexts() == null) {
            return contexts;
        }

        Set<String> contextKeys = new LinkedHashSet<>();
        for (CallContextModel callContext : methodModel.getCallContexts()) {
            CallContextModel copy = copyCallContext(callContext);
            if (copy != null && contextKeys.add(callContextKey(copy))) {
                contexts.add(copy);
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        for (CallContextModel callContext : methodModel.getCallContexts()) {
            if (!isChainContext(callContext) || "CHAIN_STEP_CALL".equals(callContext.getKind())) {
                continue;
            }
            String receiverName = ownerName(callContext.getOwner());
            String receiverType = receiverType(methodModel, callContext.getOwner(), receiverName);
            List<ChainSegment> segments = parseCallChain(callContext.getExpression(), receiverName);
            String currentType = receiverType;
            String currentExpression = receiverName;
            for (ChainSegment segment : segments) {
                if (currentType == null || currentType.isBlank()) {
                    break;
                }
                String cacheKey = currentType + "|" + segment.getMethodName() + "|" + segment.getArgumentCount();
                if (!seen.add(cacheKey)) {
                    currentType = null;
                    break;
                }
                PsiClass psiClass = resolvePsiClass(project, methodModel, currentType);
                String returnType = resolveMethodReturnType(psiClass, segment.getMethodName(), segment.getArgumentCount());
                CallContextModel synthetic = new CallContextModel();
                synthetic.setKind("CHAIN_STEP_CALL");
                synthetic.setOwner(currentType);
                synthetic.setMethodName(segment.getMethodName());
                synthetic.setExpression(currentExpression + "." + segment.asCode());
                synthetic.setReturnType(returnType == null || returnType.isBlank() ? "UNKNOWN_USE_RETURNS_DEEP_STUBS" : returnType);
                synthetic.setSignature((currentType == null || currentType.isBlank() ? "" : currentType) + "." + segment.getMethodName()
                        + "(" + segment.getArgumentCount() + ")");
                synthetic.setSourceCode("");
                synthetic.setParameterUsageSummary("");
                if (contextKeys.add(callContextKey(synthetic))) {
                    contexts.add(synthetic);
                }
                currentType = rawType(synthetic.getReturnType());
                currentExpression = currentExpression + "." + segment.asCode();
            }
        }
        return contexts;
    }

    private String callContextKey(CallContextModel context) {
        return String.join("|",
                contextValue(context.getKind()),
                contextValue(context.getOwner()),
                contextValue(context.getMethodName()),
                contextValue(context.getExpression()),
                contextValue(context.getSignature()));
    }

    private String contextValue(String value) {
        return value == null ? "" : value;
    }

    private CallContextModel copyCallContext(CallContextModel source) {
        if (source == null) {
            return null;
        }
        CallContextModel context = new CallContextModel();
        context.setKind(source.getKind());
        context.setOwner(source.getOwner());
        context.setMethodName(source.getMethodName());
        context.setExpression(source.getExpression());
        context.setReturnType(source.getReturnType());
        context.setSignature(source.getSignature());
        context.setSourceCode(source.getSourceCode());
        context.setParameterUsageSummary(source.getParameterUsageSummary());
        context.setNestedCalls(source.getNestedCalls() == null ? new ArrayList<>() : new ArrayList<>(source.getNestedCalls()));
        return context;
    }

    private PsiClass resolvePsiClass(Project project, MethodModel methodModel, String type) {
        if (project == null || type == null || type.isBlank()) {
            return null;
        }
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        String simple = simpleTypeName(type);
        if (type.contains(".")) {
            PsiClass direct = facade.findClass(type, scope);
            if (direct != null) {
                return direct;
            }
        }
        for (String importName : methodModel.getImports()) {
            if (importName.endsWith("." + simple)) {
                PsiClass imported = facade.findClass(importName, scope);
                if (imported != null) {
                    return imported;
                }
            }
        }
        if (methodModel.getPackageName() != null && !methodModel.getPackageName().isBlank()) {
            PsiClass samePackage = facade.findClass(methodModel.getPackageName() + "." + simple, scope);
            if (samePackage != null) {
                return samePackage;
            }
        }
        return facade.findClass("java.lang." + simple, scope);
    }

    private boolean isChainContext(CallContextModel callContext) {
        return callContext != null
                && callContext.getExpression() != null
                && callContext.getExpression().contains(".")
                && callContext.getOwner() != null
                && !callContext.getOwner().isBlank();
    }

    private String receiverType(MethodModel methodModel, String owner, String receiverName) {
        String type = ownerType(owner);
        if (type != null && !type.isBlank()) {
            return type;
        }
        if (receiverName != null && !receiverName.isBlank()) {
            for (ParameterModel parameter : methodModel.getParameters()) {
                if (receiverName.equals(parameter.getName())) {
                    return parameter.getType();
                }
            }
            for (DependencyModel dependency : methodModel.getDependencies()) {
                if (receiverName.equals(dependency.getFieldName())) {
                    return dependency.getFieldType();
                }
            }
        }
        return "";
    }

    private String ownerType(String owner) {
        if (owner == null || owner.isBlank()) {
            return "";
        }
        int colon = owner.indexOf(':');
        return colon < 0 ? owner.trim() : owner.substring(colon + 1).trim();
    }

    private String ownerName(String owner) {
        if (owner == null || owner.isBlank()) {
            return "";
        }
        int colon = owner.indexOf(':');
        return colon < 0 ? owner.trim() : owner.substring(0, colon).trim();
    }

    private List<ChainSegment> parseCallChain(String expression, String receiverName) {
        List<ChainSegment> segments = new ArrayList<>();
        if (expression == null || receiverName == null) {
            return segments;
        }
        String text = expression.trim();
        if (!text.startsWith(receiverName)) {
            return segments;
        }
        int index = receiverName.length();
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index >= text.length() || text.charAt(index) != '.') {
            return segments;
        }
        index++;
        while (index < text.length()) {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            int nameStart = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '(' || c == '.' || Character.isWhitespace(c)) {
                    break;
                }
                index++;
            }
            if (index >= text.length() || index == nameStart || text.charAt(index) != '(') {
                break;
            }
            String methodName = text.substring(nameStart, index).trim();
            int openParen = index;
            int closeParen = matchingParen(text, openParen);
            if (closeParen < 0) {
                break;
            }
            String arguments = text.substring(openParen + 1, closeParen);
            segments.add(new ChainSegment(methodName, arguments));
            index = closeParen + 1;
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index >= text.length() || text.charAt(index) != '.') {
                break;
            }
            index++;
        }
        return segments;
    }

    private int matchingParen(String text, int openParen) {
        if (text == null || openParen < 0 || openParen >= text.length() || text.charAt(openParen) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = openParen; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String rawType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        if (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        return value.trim();
    }

    private String canonicalTypeText(PsiType type, String defaultValue) {
        return type == null ? defaultValue : type.getCanonicalText();
    }

    private String chainedTypeName(String chainedCallReturnType) {
        if (chainedCallReturnType == null || chainedCallReturnType.isBlank()) {
            return "";
        }
        int index = chainedCallReturnType.lastIndexOf("): ");
        if (index < 0) {
            return chainedCallReturnType;
        }
        return chainedCallReturnType.substring(index + 3).trim();
    }

    List<String> augmentedImportsFromChainTypes(MethodModel methodModel,
                                                        List<String> chainedCallReturnTypes) {
        Set<String> imports = new LinkedHashSet<>();
        if (methodModel != null && methodModel.getImports() != null) {
            imports.addAll(methodModel.getImports());
        }
        if (chainedCallReturnTypes != null) {
            for (String chainedCallReturnType : chainedCallReturnTypes) {
                String type = chainedTypeName(chainedCallReturnType);
                if (type.contains(".") && !type.startsWith("java.lang.")) {
                    imports.add(type);
                }
            }
        }
        return new ArrayList<>(imports);
    }

    private static final class ChainSegment {
        private final String methodName;
        private final String arguments;

        private ChainSegment(String methodName, String arguments) {
            this.methodName = methodName == null ? "" : methodName.trim();
            this.arguments = arguments == null ? "" : arguments.trim();
        }

        private String getMethodName() {
            return methodName;
        }

        private int getArgumentCount() {
            if (arguments.isBlank()) {
                return 0;
            }
            int count = 1;
            int depth = 0;
            for (int i = 0; i < arguments.length(); i++) {
                char c = arguments.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    count++;
                }
            }
            return count;
        }

        private String asCode() {
            return methodName + "(" + arguments + ")";
        }
    }

    private String simpleTypeName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        if (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    /**
     * 保留规则生成器推导出的文件名和包名，仅替换大模型返回的源码。
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
        model.setAccessModifier(psiAccessModifier(method));
        model.setProtectedMethod(method.hasModifierProperty(PsiModifier.PROTECTED));
        model.setStaticMethod(method.hasModifierProperty(PsiModifier.STATIC));
        model.setOwnerInterface(containingClass.isInterface());
        model.setOwnerAbstract(containingClass.hasModifierProperty(PsiModifier.ABSTRACT));
        model.setImports(extractPsiImports(psiFile));
        model.setThrownExceptions(extractPsiThrownExceptions(method));
        model.setMethodBodyText(method.getBody() == null ? "" : method.getBody().getText());
        Document sourceDocument = PsiDocumentManager.getInstance(psiFile.getProject()).getDocument(psiFile);
        if (sourceDocument != null) {
            model.setSourceStartLine(sourceDocument.getLineNumber(method.getTextRange().getStartOffset()) + 1);
            model.setSourceEndLine(sourceDocument.getLineNumber(method.getTextRange().getEndOffset()) + 1);
        }
        model.setDeclaredMethodNames(extractPsiMethodNames(containingClass));
        model.setParameters(extractPsiParameters(method));
        model.setSignature(psiMethodSignature(method));
        String ownerName = containingClass.getQualifiedName() == null ? containingClass.getName() : containingClass.getQualifiedName();
        List<String> idTypes = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            idTypes.add(typeText(parameter.getType(), "Object").replaceAll("<.*>", "").replace("...", "[]"));
        }
        model.setMethodId(ownerName + "#" + method.getName() + "(" + String.join(",", idTypes) + ")");
        model.setDependencies(extractPsiDependencies(containingClass, model.getMethodBodyText()));
        model.setCallContexts(extractPsiCallContexts(containingClass, method, model));
        return model;
    }

    private List<CallContextModel> extractPsiCallContexts(PsiClass containingClass, PsiMethod method, MethodModel model) {
        List<CallContextModel> contexts = new ArrayList<>();
        if (method.getBody() == null) {
            return contexts;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : method.getBody().getText().isBlank()
                ? new ArrayList<PsiMethodCallExpression>()
                : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
            CallContextModel context = psiCallContext(containingClass, method, model, call);
            if (context != null && seen.add(context.getKind() + "|" + context.getExpression())) {
                contexts.add(context);
            }
        }
        return contexts;
    }

    private CallContextModel psiCallContext(PsiClass containingClass, PsiMethod currentMethod, MethodModel model, PsiMethodCallExpression call) {
        String expression = call.getText();
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || methodName.isBlank()) {
            return null;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null || "this".equals(qualifier.getText())) {
            PsiMethod resolved = call.resolveMethod();
            if (resolved != null && resolved != currentMethod && resolved.getContainingClass() == containingClass) {
                CallContextModel context = simplePsiCallContext("INTERNAL_METHOD_CALL",
                        model.getClassName(),
                        methodName,
                        expression,
                        typeText(resolved.getReturnType(), ""),
                        psiMethodSignature(resolved),
                        resolved.getBody() == null ? "" : resolved.getBody().getText(),
                        psiParameterUsageSummary(resolved));
                context.setNestedCalls(psiNestedCallSummaries(resolved));
                return context;
            }
            return null;
        }
        String qualifierText = qualifier.getText();
        if (Character.isUpperCase(qualifierText.charAt(0))) {
            PsiMethod resolved = call.resolveMethod();
            return simplePsiCallContext("STATIC_CALL", qualifierText, methodName, expression,
                    resolved == null ? "" : typeText(resolved.getReturnType(), ""), "", "", "");
        }
        String root = rootReceiver(qualifierText);
        for (ParameterModel parameter : model.getParameters()) {
            if (parameter.getName().equals(root)) {
                PsiMethod resolved = call.resolveMethod();
                return simplePsiCallContext(qualifierText.contains(".") ? "PARAMETER_CHAIN_CALL" : "PARAMETER_OBJECT_CALL",
                        root + ":" + parameter.getType(), methodName, expression,
                        resolved == null ? "" : typeText(resolved.getReturnType(), ""), "", "", "");
            }
        }
        for (DependencyModel dependency : model.getDependencies()) {
            if (dependency.getFieldName().equals(root)) {
                PsiMethod resolved = call.resolveMethod();
                return simplePsiCallContext(qualifierText.contains(".") ? "FIELD_CHAIN_CALL" : "FIELD_DEPENDENCY_CALL",
                        root + ":" + dependency.getFieldType(), methodName, expression,
                        resolved == null ? "" : typeText(resolved.getReturnType(), ""), "", "", "");
            }
        }
        return null;
    }

    private CallContextModel simplePsiCallContext(String kind,
                                                  String owner,
                                                  String methodName,
                                                  String expression,
                                                  String returnType,
                                                  String signature,
                                                  String sourceCode,
                                                  String parameterUsageSummary) {
        CallContextModel context = new CallContextModel();
        context.setKind(kind);
        context.setOwner(owner);
        context.setMethodName(methodName);
        context.setExpression(expression);
        context.setReturnType(returnType);
        context.setSignature(signature);
        context.setSourceCode(sourceCode);
        context.setParameterUsageSummary(parameterUsageSummary);
        return context;
    }

    private List<String> psiNestedCallSummaries(PsiMethod method) {
        List<String> summaries = new ArrayList<>();
        if (method == null || method.getBody() == null) {
            return summaries;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
            PsiMethod resolved = call.resolveMethod();
            if (resolved != null && resolved != method && seen.add(resolved.getName())) {
                summaries.add(psiMethodSignature(resolved) + "; parameterUsage=" + psiParameterUsageSummary(resolved));
            }
        }
        return summaries;
    }

    private String psiParameterUsageSummary(PsiMethod method) {
        if (method == null || method.getBody() == null) {
            return "";
        }
        List<String> usages = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            Set<String> calls = new LinkedHashSet<>();
            for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                if (qualifier != null && parameter.getName().equals(rootReceiver(qualifier.getText()))) {
                    calls.add(qualifier.getText() + "." + call.getMethodExpression().getReferenceName() + "(...)");
                }
            }
            if (!calls.isEmpty()) {
                usages.add(parameter.getName() + " -> " + String.join(", ", calls));
            }
        }
        return usages.isEmpty() ? "未发现参数对象方法读取" : String.join("; ", usages);
    }

    private String rootReceiver(String qualifierText) {
        int dot = qualifierText == null ? -1 : qualifierText.indexOf('.');
        return dot < 0 ? qualifierText : qualifierText.substring(0, dot);
    }

    /**
     * 识别 PSI 方法真实访问修饰符。
     */
    private String psiAccessModifier(PsiMethod method) {
        if (method.hasModifierProperty(PsiModifier.PUBLIC)) {
            return "public";
        }
        if (method.hasModifierProperty(PsiModifier.PROTECTED)) {
            return "protected";
        }
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return "private";
        }
        return "package-private";
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
     * PSI 降级路径同样只接受显式可空注解；引用类型本身不构成 null 测试意图的证据。
     */
    private List<ParameterModel> extractPsiParameters(PsiMethod method) {
        List<ParameterModel> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            ParameterModel model = new ParameterModel();
            model.setName(parameter.getName());
            model.setType(typeText(parameter.getType(), "Object"));
            model.setNullable(!isPrimitive(parameter.getType()) && hasPsiNullableAnnotation(parameter));
            parameters.add(model);
        }
        return parameters;
    }

    private boolean isPrimitive(PsiType type) {
        return type instanceof com.intellij.psi.PsiPrimitiveType;
    }

    private boolean hasPsiNullableAnnotation(PsiParameter parameter) {
        if (parameter.getModifierList() == null) return false;
        return java.util.Arrays.stream(parameter.getModifierList().getAnnotations()).anyMatch(annotation -> {
            String qualifiedName = annotation.getQualifiedName();
            String name = qualifiedName == null ? annotation.getNameReferenceElement() == null
                    ? "" : annotation.getNameReferenceElement().getReferenceName() : qualifiedName;
            return name != null && (name.endsWith(".Nullable") || "Nullable".equals(name)
                    || name.endsWith(".CheckForNull") || "CheckForNull".equals(name)
                    || name.endsWith(".NullAllowed") || "NullAllowed".equals(name)
                    || name.endsWith(".MaybeNull") || "MaybeNull".equals(name));
        });
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

    /**
     * 静态分析阶段产物。
     */
    private static final class StaticAnalysisContext {
        /** 当前方法模型。 */
        private final MethodModel methodModel;
        /** 当前方法的 PSI 真实符号快照。 */
        private final ProjectSymbolSnapshot symbolSnapshot;

        private StaticAnalysisContext(MethodModel methodModel, ProjectSymbolSnapshot symbolSnapshot) {
            this.methodModel = methodModel;
            this.symbolSnapshot = symbolSnapshot;
        }

        private MethodModel getMethodModel() {
            return methodModel;
        }

        private ProjectSymbolSnapshot getSymbolSnapshot() {
            return symbolSnapshot;
        }
    }
}
