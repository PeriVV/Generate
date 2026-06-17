package com.casc.unittestagent.service;

import com.casc.unittestagent.generator.JUnitMockitoTestGenerator;
import com.casc.unittestagent.generator.MockStrategyPlanner;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.generator.TestScenarioPlanner;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.util.PsiUtils;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 编排一次单元测试生成流程。
 * 该服务串联源码分析、测试数据补全、场景规划、Mock 规划和测试文件生成，并把异常转为可展示告警。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class UnitTestGenerationService {
    /** 日志对象，用于记录生成链路关键数据和异常堆栈。 */
    private static final Logger LOG = Logger.getInstance(UnitTestGenerationService.class);
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
            LOG.info("UnitMind: method = " + methodModel.getClassName() + "#" + methodModel.getMethodName());
            testDataGenerator.enrich(methodModel);
            List<TestScenario> scenarios = testScenarioPlanner.plan(methodModel);
            LOG.info("UnitMind: scenario count = " + scenarios.size());
            MockStrategy mockStrategy = mockStrategyPlanner.plan(methodModel, scenarios);
            int mockCount = mockStrategy.getMockFields() == null ? 0 : mockStrategy.getMockFields().size();
            LOG.info("UnitMind: mock count = " + mockCount);
            GeneratedTestFile generatedTestFile = testGenerator.generate(methodModel, scenarios, mockStrategy);
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
