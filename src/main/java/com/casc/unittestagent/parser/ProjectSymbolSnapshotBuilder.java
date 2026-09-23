package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TypeSymbol;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.search.searches.ClassInheritorsSearch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用 IntelliJ Java PSI 构造被测方法的真实类型和可调用成员快照。
 */
public class ProjectSymbolSnapshotBuilder {
    private static final int MAX_REACHABLE_DEPTH = 2;
    private static final int MAX_TYPE_COUNT = 40;
    private static final int MAX_METHOD_COUNT_PER_TYPE = 40;
    /** 字段构造器兜底在 40 类型主预算之外允许追加的独立名额，避免抢占可达类型。 */
    private static final int MAX_FIELD_CONSTRUCTOR_TYPE_COUNT = 12;
    /** Lombok 构造器注解：生成的构造器在源码 PSI 中不存在，必须按 Lombok 语义合成签名。 */
    private static final Set<String> LOMBOK_CONSTRUCTOR_ANNOTATIONS =
            Set.of("NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor");

    /**
     * 构造符号快照。调用方必须在 read action 中执行。
     */
    public ProjectSymbolSnapshot build(Project project, PsiFile psiFile, Editor editor, MethodModel methodModel) {
        ProjectSymbolSnapshot snapshot = new ProjectSymbolSnapshot();
        if (project == null || !(psiFile instanceof PsiJavaFile) || methodModel == null) {
            snapshot.getUnresolvedSymbols().add("缺少项目、Java PSI 文件或方法模型。");
            return snapshot;
        }
        if (DumbService.isDumb(project)) {
            snapshot.getUnresolvedSymbols().add("IntelliJ 项目索引尚未完成。");
            return snapshot;
        }

        Module module = psiFile.getVirtualFile() == null
                ? null
                : ProjectFileIndex.getInstance(project).getModuleForFile(psiFile.getVirtualFile());
        snapshot.setModuleName(module == null ? "" : module.getName());
        snapshot.setLanguageLevel(PsiUtil.getLanguageLevel(psiFile).toString());

        PsiMethod targetMethod = locateTargetMethod((PsiJavaFile) psiFile, editor, methodModel);
        if (targetMethod == null || targetMethod.getContainingClass() == null) {
            snapshot.getUnresolvedSymbols().add("无法在 PSI 中定位被测方法：" + methodModel.getMethodName());
            return snapshot;
        }

        PsiClass targetClass = targetMethod.getContainingClass();
        snapshot.setTargetType(qualifiedName(targetClass));
        snapshot.setTargetMethod(toCallable(targetMethod, null));
        List<String> testExamples = existingTestExamples(project, targetClass, targetMethod);
        snapshot.setTestExamples(testExamples);
        snapshot.setContractFacts(contractFacts(targetClass, targetMethod, testExamples));

        SnapshotCollector collector = new SnapshotCollector(project, targetMethod, snapshot);
        collector.addType(targetClass, true);
        for (PsiParameter parameter : targetMethod.getParameterList().getParameters()) {
            collector.addFixtureType(PsiTypesUtil.getPsiClass(parameter.getType()));
        }
        collector.collectFromMethod(targetMethod, 0);
        collector.addClassFieldConstructorSignatures(targetClass);
        snapshot.setTypes(new ArrayList<>(collector.types.values()));
        return snapshot;
    }

    private PsiMethod locateTargetMethod(PsiJavaFile psiFile, Editor editor, MethodModel methodModel) {
        PsiMethod current = null;
        if (editor != null) {
            PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
            current = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        }

        List<PsiMethod> matching = new ArrayList<>();
        for (PsiMethod method : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            if (methodModel.getMethodName().equals(method.getName()) && sameParameters(method, methodModel)) {
                matching.add(method);
            }
        }
        if (matching.isEmpty()) return null;

        Document document = PsiDocumentManager.getInstance(psiFile.getProject()).getDocument(psiFile);
        if (current != null && matching.contains(current)
                && sourceRangeDistance(current, methodModel, document) == 0) {
            return current;
        }

        // JavaParser has already selected the target by caret line. Preserve that decision when PSI's
        // caret element is whitespace/an annotation edge and therefore cannot directly find a method.
        // This also prevents an inner concrete method from falling back to an outer abstract method
        // with the same name and parameter list.
        if (methodModel.getSourceStartLine() > 0 && methodModel.getSourceEndLine() > 0 && document != null) {
            PsiMethod byRange = null;
            int bestDistance = Integer.MAX_VALUE;
            for (PsiMethod method : matching) {
                int distance = sourceRangeDistance(method, methodModel, document);
                if (distance < bestDistance) {
                    byRange = method;
                    bestDistance = distance;
                }
            }
            if (byRange != null && sourceRangesOverlap(byRange, methodModel, document)) return byRange;
        }

        if (current != null && matching.contains(current) && ownerMatches(current, methodModel)) return current;
        for (PsiMethod method : matching) {
            if (ownerMatches(method, methodModel)) return method;
        }
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private boolean ownerMatches(PsiMethod method, MethodModel model) {
        PsiClass owner = method == null ? null : method.getContainingClass();
        return owner != null && model != null && model.getClassName() != null
                && model.getClassName().equals(owner.getName());
    }

    private int sourceRangeDistance(PsiMethod method, MethodModel model, Document document) {
        int[] range = sourceLines(method, document);
        if (range == null || model == null) return Integer.MAX_VALUE;
        return Math.abs(range[0] - model.getSourceStartLine())
                + Math.abs(range[1] - model.getSourceEndLine());
    }

    private boolean sourceRangesOverlap(PsiMethod method, MethodModel model, Document document) {
        int[] range = sourceLines(method, document);
        return range != null && model != null
                && range[1] >= model.getSourceStartLine()
                && range[0] <= model.getSourceEndLine();
    }

    private int[] sourceLines(PsiMethod method, Document document) {
        if (method == null || document == null || method.getTextRange() == null) return null;
        int startOffset = Math.max(0, Math.min(method.getTextRange().getStartOffset(), document.getTextLength()));
        int endOffset = Math.max(startOffset,
                Math.min(Math.max(startOffset, method.getTextRange().getEndOffset() - 1), document.getTextLength()));
        return new int[]{document.getLineNumber(startOffset) + 1, document.getLineNumber(endOffset) + 1};
    }

    private boolean sameParameters(PsiMethod method, MethodModel methodModel) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length != methodModel.getParameters().size()) {
            return false;
        }
        for (int index = 0; index < parameters.length; index++) {
            String expected = simpleType(methodModel.getParameters().get(index).getType());
            String actual = simpleType(parameters[index].getType().getCanonicalText());
            if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private CallableSymbol toCallable(PsiMethod method, PsiType actualReturnType) {
        CallableSymbol symbol = new CallableSymbol();
        PsiClass owner = method.getContainingClass();
        String ownerName = qualifiedName(owner);
        List<String> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            parameters.add(canonicalType(parameter.getType()));
        }
        symbol.setOwnerQualifiedName(ownerName);
        symbol.setName(method.isConstructor() ? simpleType(ownerName) : method.getName());
        PsiType returnType = method.isConstructor() ? null : actualReturnType == null ? method.getReturnType() : actualReturnType;
        symbol.setReturnType(returnType == null ? "" : canonicalType(returnType));
        symbol.setParameterTypes(parameters);
        symbol.setVisibility(visibility(method));
        symbol.setStaticMethod(method.hasModifierProperty(PsiModifier.STATIC));
        symbol.setVarArgs(method.isVarArgs());
        symbol.setConstructor(method.isConstructor());
        symbol.setResolved(true);
        symbol.setId(ownerName + "#" + (method.isConstructor() ? "<init>" : method.getName())
                + "(" + String.join(",", parameters) + ")");
        PsiFile sourceFile = method.getContainingFile();
        if (method.getBody() != null && sourceFile != null && sourceFile.getVirtualFile() != null
                && ProjectFileIndex.getInstance(method.getProject()).isInSourceContent(sourceFile.getVirtualFile())) {
            symbol.setSourceCode(limit(method.getText(), 2400));
        }
        return symbol;
    }

    private List<String> existingTestExamples(Project project, PsiClass targetClass, PsiMethod targetMethod) {
        List<String> examples = new ArrayList<>();
        Set<String> seenFiles = new LinkedHashSet<>();
        collectTestExamples(project, targetMethod, examples, seenFiles);
        if (examples.size() < 3) collectTestExamples(project, targetClass, examples, seenFiles);
        return examples;
    }

    private void collectTestExamples(Project project,
                                     PsiElement target,
                                     List<String> examples,
                                     Set<String> seenFiles) {
        if (target == null || examples.size() >= 3) return;
        ReferencesSearch.search(target).forEach(reference -> {
            if (examples.size() >= 3) return false;
            PsiFile file = reference.getElement() == null ? null : reference.getElement().getContainingFile();
            if (file == null || file.getVirtualFile() == null
                    || !ProjectFileIndex.getInstance(project).isInTestSourceContent(file.getVirtualFile())
                    || !seenFiles.add(file.getVirtualFile().getPath())) {
                return true;
            }
            PsiClass testClass = PsiTreeUtil.getParentOfType(reference.getElement(), PsiClass.class, false);
            String text = testClass == null ? file.getText() : testClass.getText();
            examples.add(limit(text, 8000));
            return true;
        });
    }

    private List<String> contractFacts(PsiClass targetClass,
                                       PsiMethod targetMethod,
                                       List<String> examples) {
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        if (targetMethod != null) {
            facts.add("TARGET_SIGNATURE: " + targetMethod.getName() + targetMethod.getParameterList().getText()
                    + " -> " + (targetMethod.getReturnType() == null ? "void"
                    : targetMethod.getReturnType().getCanonicalText()));
        }
        if (targetClass != null) {
            for (PsiMethod method : targetClass.getMethods()) {
                String name = method.getName();
                if (method.isConstructor() || name.startsWith("set") || name.startsWith("with")
                        || name.startsWith("add") || name.startsWith("put")) {
                    facts.add("FIXTURE_API: " + method.getText().lines().findFirst().orElse(method.getName()));
                }
                if (facts.size() >= 16) break;
            }
        }
        for (String example : examples) {
            if (example == null) continue;
            for (String line : example.lines().toList()) {
                String trimmed = line.strip();
                if (trimmed.contains("assert") || trimmed.startsWith("verify(")) {
                    facts.add("EXISTING_TEST_ORACLE: " + limit(trimmed, 500));
                }
                if (facts.size() >= 32) return new ArrayList<>(facts);
            }
        }
        return new ArrayList<>(facts);
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "\n// ... truncated";
    }

    private String visibility(PsiMethod method) {
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

    private String qualifiedName(PsiClass psiClass) {
        if (psiClass == null) {
            return "";
        }
        return psiClass.getQualifiedName() == null ? psiClass.getName() : psiClass.getQualifiedName();
    }

    private String simpleType(String type) {
        if (type == null) {
            return "";
        }
        String value = type.replace("...", "[]").trim();
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private String canonicalType(PsiType type) {
        if (type == null) {
            return "";
        }
        String value = type.getCanonicalText();
        String suffix = "";
        if (value.endsWith("[]")) {
            suffix = "[]";
            value = value.substring(0, value.length() - 2);
        }
        if (!value.contains(".") && Set.of(
                "String", "Object", "Long", "Integer", "Boolean", "Double", "Float",
                "Short", "Byte", "Character", "Void", "Class", "Throwable", "Exception",
                "RuntimeException").contains(value)) {
            value = "java.lang." + value;
        }
        return value + suffix;
    }

    private final class SnapshotCollector {
        private final Project project;
        private final PsiMethod targetMethod;
        private final ProjectSymbolSnapshot snapshot;
        private final Map<String, TypeSymbol> types = new LinkedHashMap<>();
        private final Map<String, Set<String>> callableIds = new LinkedHashMap<>();
        private final Set<String> visitedMethods = new LinkedHashSet<>();
        /** 字段构造器兜底已占用的独立类型名额；addType 的预算检查会放宽这部分。 */
        private int extraFieldTypeSlots;

        private SnapshotCollector(Project project, PsiMethod targetMethod, ProjectSymbolSnapshot snapshot) {
            this.project = project;
            this.targetMethod = targetMethod;
            this.snapshot = snapshot;
        }

        private void collectFromMethod(PsiMethod method, int depth) {
            if (method == null || method.getBody() == null || depth > MAX_REACHABLE_DEPTH) {
                return;
            }
            CallableSymbol current = toCallable(method, null);
            if (!visitedMethods.add(current.getId())) {
                return;
            }

            Collection<PsiMethodCallExpression> calls =
                    PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class);
            for (PsiMethodCallExpression call : calls) {
                JavaResolveResult resolution = call.resolveMethodGenerics();
                if (!(resolution.getElement() instanceof PsiMethod)) {
                    snapshot.getUnresolvedSymbols().add(trim(call.getText()));
                    continue;
                }
                PsiMethod resolved = (PsiMethod) resolution.getElement();
                PsiType actualReturnType = actualReturnType(call, resolved, resolution);
                addMethodAndRelevantOverloads(resolved, actualReturnType);
                if (depth < MAX_REACHABLE_DEPTH && isProjectSource(resolved)) {
                    collectFromMethod(resolved, depth + 1);
                }
            }

            for (PsiNewExpression expression : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiNewExpression.class)) {
                PsiMethod constructor = expression.resolveConstructor();
                if (constructor == null) {
                    snapshot.getUnresolvedSymbols().add(trim(expression.getText()));
                } else {
                    addCallable(constructor.getContainingClass(), toCallable(constructor, null), true);
                }
            }

            for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiReferenceExpression.class)) {
                PsiElement resolved = reference.resolve();
                if (resolved instanceof PsiField) {
                    addType(PsiTypesUtil.getPsiClass(((PsiField) resolved).getType()), true);
                }
            }
        }

        private PsiType actualReturnType(PsiMethodCallExpression call,
                                         PsiMethod method,
                                         JavaResolveResult resolution) {
            PsiType declaredReturnType = method.getReturnType();
            if (declaredReturnType == null) {
                return null;
            }
            PsiSubstitutor substitutor = resolution.getSubstitutor();
            PsiParameter[] parameters = method.getParameterList().getParameters();
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            int count = Math.min(parameters.length, arguments.length);
            for (int index = 0; index < count; index++) {
                PsiClass parameterClass = PsiTypesUtil.getPsiClass(parameters[index].getType());
                PsiType argumentType = arguments[index].getType();
                if (parameterClass instanceof PsiTypeParameter && argumentType != null) {
                    substitutor = substitutor.put((PsiTypeParameter) parameterClass, argumentType);
                }
            }
            PsiType substituted = substitutor.substitute(declaredReturnType);
            return substituted == null ? declaredReturnType : substituted;
        }

        private void addMethodAndRelevantOverloads(PsiMethod resolved, PsiType actualReturnType) {
            PsiClass owner = resolved.getContainingClass();
            if (owner == null) {
                return;
            }
            addType(owner, false);
            addCallable(owner, toCallable(resolved, actualReturnType), false);
            addFixtureType(PsiTypesUtil.getPsiClass(actualReturnType == null ? resolved.getReturnType() : actualReturnType));
            for (PsiMethod overload : owner.findMethodsByName(resolved.getName(), true)) {
                if (isAccessible(overload)) {
                    addCallable(overload.getContainingClass(), toCallable(overload, null), false);
                }
            }
        }

        private void addFixtureType(PsiClass psiClass) {
            if (psiClass == null) return;
            addType(psiClass, true);
            if (!isProjectType(psiClass)) return;
            if (psiClass.isInterface() || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                addConcreteFixtureSubtypes(psiClass);
            }
            if (psiClass.isInterface()) return;
            for (PsiMethod method : psiClass.getMethods()) {
                if (!isAccessible(method) || !looksLikeFixtureMethod(method)) continue;
                CallableSymbol callable = toCallable(method, null);
                callable.setFixtureMethod(true);
                addCallable(psiClass, callable, false);
            }
        }

        private void addConcreteFixtureSubtypes(PsiClass baseType) {
            TypeSymbol base = types.get(qualifiedName(baseType));
            if (base == null) return;
            LinkedHashSet<String> concrete = new LinkedHashSet<>(base.getConcreteSubtypes());
            for (PsiClass inheritor : ClassInheritorsSearch.search(baseType, baseType.getUseScope(), true)) {
                if (types.size() >= MAX_TYPE_COUNT || concrete.size() >= 12) break;
                if (!isProjectType(inheritor) || inheritor.isInterface()
                        || inheritor.hasModifierProperty(PsiModifier.ABSTRACT)) continue;
                String subtypeName = qualifiedName(inheritor);
                if (subtypeName.isBlank() || !concrete.add(subtypeName)) continue;
                addType(inheritor, true);
            }
            base.setConcreteSubtypes(new ArrayList<>(concrete));
        }

        private boolean looksLikeFixtureMethod(PsiMethod method) {
            String name = method.getName();
            return name.startsWith("set") || name.startsWith("add") || name.startsWith("with")
                    || name.startsWith("put") || name.startsWith("init") || name.startsWith("create")
                    || name.startsWith("of") || name.equals("builder");
        }

        private boolean isProjectType(PsiClass psiClass) {
            PsiFile file = psiClass.getContainingFile();
            return file != null && file.getVirtualFile() != null
                    && ProjectFileIndex.getInstance(project).isInSourceContent(file.getVirtualFile());
        }

        private void addType(PsiClass psiClass, boolean includeConstructors) {
            if (psiClass == null) {
                return;
            }
            String qualifiedName = qualifiedName(psiClass);
            if (qualifiedName.isBlank()) {
                return;
            }
            // 已有类型回填成员不增加类型数量，不受预算限制；新类型受主预算约束，
            // 字段构造器兜底启用的独立名额除外。
            if (!types.containsKey(qualifiedName)
                    && types.size() >= MAX_TYPE_COUNT + extraFieldTypeSlots) {
                return;
            }
            TypeSymbol type = types.computeIfAbsent(qualifiedName, ignored -> {
                TypeSymbol created = new TypeSymbol();
                created.setQualifiedName(qualifiedName);
                created.setKind(kind(psiClass));
                created.setFinalType(psiClass.hasModifierProperty(PsiModifier.FINAL));
                created.setAbstractType(psiClass.hasModifierProperty(PsiModifier.ABSTRACT));
                created.setSealedType(psiClass.hasModifierProperty(PsiModifier.SEALED));
                List<String> permitted = new ArrayList<>();
                for (PsiType permittedType : psiClass.getPermitsListTypes()) {
                    PsiClass resolved = PsiTypesUtil.getPsiClass(permittedType);
                    String name = qualifiedName(resolved);
                    if (!name.isBlank()) permitted.add(name);
                }
                created.setPermittedSubtypes(permitted);
                return created;
            });
            if (includeConstructors) {
                for (PsiMethod constructor : psiClass.getConstructors()) {
                    if (isAccessible(constructor)) {
                        addCallable(psiClass, toCallable(constructor, null), true);
                    }
                }
                if (psiClass.isRecord()) {
                    addCallable(psiClass, recordCanonicalConstructor(psiClass), true);
                }
                appendLombokConstructors(psiClass);
            }
        }

        /**
         * 被测类全部实例字段类型的构造器签名兜底。方法可达性收集覆盖不到只在更深层
         * 调用链或本类其他方法中使用的字段：模型拿不到构造器时会被迫猜测，并回归到
         * getDeclaredField 反射改私有字段（被 CoverageCandidatePreflight 一票否决）。
         * 约束：只补构造器签名——不带 sourceCode、不收集方法、不做具体子类型展开
         * （@Mock 字段由 Mockito 直接 mock 接口，无需实现类）；新增类型使用主预算
         * 之外的独立名额，可达性收集的类型始终优先。
         */
        private void addClassFieldConstructorSignatures(PsiClass targetClass) {
            if (targetClass == null) return;
            for (PsiField field : targetClass.getFields()) {
                if (extraFieldTypeSlots >= MAX_FIELD_CONSTRUCTOR_TYPE_COUNT) break;
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
                PsiClass fieldClass = PsiTypesUtil.getPsiClass(field.getType());
                String name = qualifiedName(fieldClass);
                if (fieldClass == null || name.isBlank()) continue;
                TypeSymbol existing = types.get(name);
                if (existing != null && !existing.getConstructors().isEmpty()) continue;
                if (existing == null) extraFieldTypeSlots++;
                addType(fieldClass, true);
                TypeSymbol added = types.get(name);
                if (added == null) {
                    // 预算守卫拒绝新增时收回名额；已存在类型回填构造器本就不占名额。
                    if (existing == null) extraFieldTypeSlots--;
                } else {
                    for (CallableSymbol constructor : added.getConstructors()) {
                        constructor.setSourceCode("");
                    }
                }
            }
        }

        /**
         * 按 Lombok 语义合成构造器签名。@RequiredArgsConstructor 等注解生成的构造器
         * 只存在于编译期产物，PSI 源码中不可见；快照缺失时模型无从得知依赖参数列表，
         * 只能猜测并回归到 getDeclaredField 反射。跳过 static 字段和已初始化的 final
         * 字段（无法在构造器中再次赋值）；RequiredArgs 只含未初始化 final 与
         * @NonNull 字段。签名与显式构造器重合时由 addCallable 的 id 去重。
         */
        private void appendLombokConstructors(PsiClass psiClass) {
            LinkedHashSet<String> present = new LinkedHashSet<>();
            for (PsiAnnotation candidate : psiClass.getAnnotations()) {
                String name = annotationSimpleName(candidate);
                if (LOMBOK_CONSTRUCTOR_ANNOTATIONS.contains(name)) present.add(name);
            }
            for (String annotation : present) {
                boolean requiredOnly = "RequiredArgsConstructor".equals(annotation);
                boolean noArguments = "NoArgsConstructor".equals(annotation);
                List<String> parameters = new ArrayList<>();
                if (!noArguments) {
                    for (PsiField field : psiClass.getFields()) {
                        if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
                        if (field.hasModifierProperty(PsiModifier.FINAL) && field.getInitializer() != null) continue;
                        if (requiredOnly && !(field.hasModifierProperty(PsiModifier.FINAL)
                                || hasAnnotation(field, "NonNull"))) continue;
                        parameters.add(canonicalType(field.getType()));
                    }
                }
                addCallable(psiClass, synthesizedConstructor(psiClass, parameters), true);
            }
        }

        private CallableSymbol synthesizedConstructor(PsiClass psiClass, List<String> parameters) {
            CallableSymbol symbol = new CallableSymbol();
            String ownerName = qualifiedName(psiClass);
            symbol.setOwnerQualifiedName(ownerName);
            symbol.setName(simpleType(ownerName));
            symbol.setParameterTypes(parameters);
            symbol.setVisibility("public");
            symbol.setConstructor(true);
            symbol.setResolved(true);
            symbol.setId(ownerName + "#<init>(" + String.join(",", parameters) + ")");
            return symbol;
        }

        private boolean hasAnnotation(PsiField field, String simpleName) {
            for (PsiAnnotation annotation : field.getAnnotations()) {
                if (simpleName.equals(annotationSimpleName(annotation))) return true;
            }
            return false;
        }

        private String annotationSimpleName(PsiAnnotation annotation) {
            PsiJavaCodeReferenceElement reference =
                    PsiTreeUtil.getChildOfType(annotation, PsiJavaCodeReferenceElement.class);
            if (reference != null) return reference.getReferenceName();
            String qualified = annotation.getQualifiedName();
            if (qualified == null || qualified.isBlank()) return "";
            int dot = qualified.lastIndexOf('.');
            return dot < 0 ? qualified : qualified.substring(dot + 1);
        }

        private CallableSymbol recordCanonicalConstructor(PsiClass recordType) {
            CallableSymbol symbol = new CallableSymbol();
            String ownerName = qualifiedName(recordType);
            List<String> parameters = new ArrayList<>();
            for (PsiRecordComponent component : recordType.getRecordComponents()) {
                parameters.add(canonicalType(component.getType()));
            }
            symbol.setOwnerQualifiedName(ownerName);
            symbol.setName(simpleType(ownerName));
            symbol.setParameterTypes(parameters);
            symbol.setVisibility(recordType.hasModifierProperty(PsiModifier.PUBLIC)
                    ? "public" : "package-private");
            symbol.setConstructor(true);
            symbol.setResolved(true);
            symbol.setId(ownerName + "#<init>(" + String.join(",", parameters) + ")");
            if (recordType.getRecordHeader() != null) {
                symbol.setSourceCode(limit("record " + recordType.getName()
                        + recordType.getRecordHeader().getText(), 2400));
            }
            return symbol;
        }

        private void addCallable(PsiClass owner, CallableSymbol callable, boolean constructor) {
            if (owner == null || callable == null) {
                return;
            }
            addType(owner, false);
            String ownerName = qualifiedName(owner);
            TypeSymbol type = types.get(ownerName);
            if (type == null) {
                return;
            }
            Set<String> ids = callableIds.computeIfAbsent(ownerName, ignored -> new LinkedHashSet<>());
            if (!ids.add(callable.getId()) || ids.size() > MAX_METHOD_COUNT_PER_TYPE) {
                return;
            }
            if (constructor) {
                type.getConstructors().add(callable);
            } else {
                type.getMethods().add(callable);
            }
        }

        private boolean isAccessible(PsiMethod method) {
            return method == targetMethod
                    || JavaPsiFacade.getInstance(project).getResolveHelper().isAccessible(method, targetMethod, targetMethod.getContainingClass());
        }

        private boolean isProjectSource(PsiMethod method) {
            PsiFile file = method.getContainingFile();
            return file != null && file.getVirtualFile() != null
                    && ProjectFileIndex.getInstance(project).isInSourceContent(file.getVirtualFile());
        }

        private String kind(PsiClass psiClass) {
            if (psiClass.isInterface()) {
                return "interface";
            }
            if (psiClass.isEnum()) {
                return "enum";
            }
            if (psiClass.isRecord()) {
                return "record";
            }
            return "class";
        }

        private String trim(String expression) {
            if (expression == null) {
                return "";
            }
            String compact = expression.replace('\n', ' ').replace('\r', ' ').trim();
            return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
        }
    }
}
