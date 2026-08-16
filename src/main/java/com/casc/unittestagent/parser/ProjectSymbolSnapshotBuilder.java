package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.CallableSymbol;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TypeSymbol;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.search.searches.ReferencesSearch;

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
        snapshot.setTypes(new ArrayList<>(collector.types.values()));
        return snapshot;
    }

    private PsiMethod locateTargetMethod(PsiJavaFile psiFile, Editor editor, MethodModel methodModel) {
        if (editor != null) {
            PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
            PsiMethod current = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
            if (current != null && methodModel.getMethodName().equals(current.getName())
                    && sameParameters(current, methodModel)) {
                return current;
            }
        }
        for (PsiClass psiClass : psiFile.getClasses()) {
            for (PsiMethod method : psiClass.findMethodsByName(methodModel.getMethodName(), false)) {
                if (sameParameters(method, methodModel)) {
                    return method;
                }
            }
        }
        return null;
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
            if (!isProjectType(psiClass) || psiClass.isInterface()) return;
            for (PsiMethod method : psiClass.getMethods()) {
                if (!isAccessible(method) || !looksLikeFixtureMethod(method)) continue;
                CallableSymbol callable = toCallable(method, null);
                callable.setFixtureMethod(true);
                addCallable(psiClass, callable, false);
            }
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
            if (psiClass == null || types.size() >= MAX_TYPE_COUNT) {
                return;
            }
            String qualifiedName = qualifiedName(psiClass);
            if (qualifiedName.isBlank()) {
                return;
            }
            TypeSymbol type = types.computeIfAbsent(qualifiedName, ignored -> {
                TypeSymbol created = new TypeSymbol();
                created.setQualifiedName(qualifiedName);
                created.setKind(kind(psiClass));
                created.setFinalType(psiClass.hasModifierProperty(PsiModifier.FINAL));
                return created;
            });
            if (includeConstructors) {
                for (PsiMethod constructor : psiClass.getConstructors()) {
                    if (isAccessible(constructor)) {
                        addCallable(psiClass, toCallable(constructor, null), true);
                    }
                }
            }
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
