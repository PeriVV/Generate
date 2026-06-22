package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ReferenceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 将当前 Java 源码解析为单元测试生成所需的方法模型。
 * 该类聚合约束解析、依赖解析和参数解析，是 IDE 动作进入生成管线后的第一步。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class JavaSourceAnalyzer {
    /** 约束提取器，负责识别 if 条件中的参数限制。 */
    private final ConstraintExtractor constraintExtractor = new ConstraintExtractor();
    /** 依赖提取器，负责识别字段依赖和调用方法。 */
    private final DependencyExtractor dependencyExtractor = new DependencyExtractor();
    /** 调用点提取器，负责识别当前类内对被测方法的调用。 */
    private final MethodUsageExtractor methodUsageExtractor = new MethodUsageExtractor();
    /** JavaParser 实例，显式使用 Java 17 语言级别以兼容 switch expression、record、yield 和 var。 */
    private final JavaParser javaParser;

    /**
     * 创建源码分析器并配置 JavaParser 语言级别。
     * 这里不用 StaticJavaParser 的全局状态，避免插件中其他调用方覆盖配置后影响当前解析结果。
     */
    public JavaSourceAnalyzer() {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(configuration);
    }

    /**
     * 解析源码并构建单元测试生成所需的方法模型。
     * 会优先选择光标所在方法；无法定位时退回到第一个 public 方法或第一个方法。
     *
     * @param sourceText 当前 Java 源码文本
     * @param preferredMethodName 用户光标所在方法名，非空时优先分析该方法
     * @return 结构化的方法模型
     * @throws IllegalStateException 当源码中找不到类或可分析方法时抛出
     */
    public MethodModel analyze(String sourceText, String preferredMethodName) {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(sourceText));
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            throw new IllegalStateException(parseFailureMessage(parseResult));
        }

        CompilationUnit compilationUnit = parseResult.getResult().get();
        Optional<ClassOrInterfaceDeclaration> classDeclaration = compilationUnit.findFirst(ClassOrInterfaceDeclaration.class);
        if (classDeclaration.isEmpty()) {
            throw new IllegalStateException("当前 Java 文件中未找到类。");
        }

        MethodDeclaration methodDeclaration = findMethod(classDeclaration.get(), preferredMethodName);
        if (methodDeclaration == null) {
            throw new IllegalStateException("当前 Java 文件中未找到可分析的方法。");
        }

        MethodModel model = new MethodModel();
        model.setLanguage("Java");
        model.setPackageName(compilationUnit.getPackageDeclaration().map(declaration -> declaration.getName().asString()).orElse(""));
        model.setClassName(classDeclaration.get().getNameAsString());
        model.setMethodName(methodDeclaration.getNameAsString());
        model.setReturnType(methodDeclaration.getType().toString());
        model.setProtectedMethod(methodDeclaration.isProtected());
        model.setImports(extractImports(compilationUnit));
        model.setThrownExceptions(extractThrownExceptions(methodDeclaration));
        model.setMethodBodyText(methodDeclaration.getBody().map(Object::toString).orElse(""));
        model.setDeclaredMethodNames(extractDeclaredMethodNames(classDeclaration.get()));
        model.setParameters(extractParameters(methodDeclaration));
        model.setConstraints(constraintExtractor.extract(methodDeclaration));
        model.setBranches(constraintExtractor.extractBranches(methodDeclaration));
        model.setDependencies(dependencyExtractor.extract(classDeclaration.get(), methodDeclaration));
        model.setUsages(methodUsageExtractor.extract(classDeclaration.get(), methodDeclaration));
        return model;
    }

    /**
     * 选择本次生成要分析的方法。
     * 优先使用光标所在方法，其次选择 public 方法，最后退回文件中的第一个方法。
     */
    private MethodDeclaration findMethod(ClassOrInterfaceDeclaration classDeclaration, String preferredMethodName) {
        List<MethodDeclaration> methods = classDeclaration.findAll(MethodDeclaration.class);
        if (preferredMethodName != null && !preferredMethodName.isBlank()) {
            for (MethodDeclaration method : methods) {
                if (preferredMethodName.equals(method.getNameAsString())) {
                    return method;
                }
            }
        }

        for (MethodDeclaration method : methods) {
            if (method.isPublic()) {
                return method;
            }
        }

        return methods.isEmpty() ? null : methods.get(0);
    }

    /**
     * 从方法声明中提取参数模型。
     * 非基本类型默认标记为可空，为异常输入生成提供基础判断。
     */
    private List<ParameterModel> extractParameters(MethodDeclaration methodDeclaration) {
        List<ParameterModel> parameters = new ArrayList<>();
        for (Parameter parameter : methodDeclaration.getParameters()) {
            ParameterModel model = new ParameterModel();
            model.setName(parameter.getNameAsString());
            model.setType(parameter.getType().toString());
            model.setNullable(!parameter.getType().isPrimitiveType());
            parameters.add(model);
        }
        return parameters;
    }

    /**
     * 提取当前类声明的方法名。
     * Mock 策略规划需要知道哪些无作用域调用属于本类内部方法，而不是普通函数或语言结构。
     */
    private List<String> extractDeclaredMethodNames(ClassOrInterfaceDeclaration classDeclaration) {
        List<String> names = new ArrayList<>();
        for (MethodDeclaration method : classDeclaration.getMethods()) {
            names.add(method.getNameAsString());
        }
        return names;
    }

    /**
     * 从编译单元中提取 import 文本。
     * 生成测试代码时优先复用源码中的框架 import，避免在 javax 与 jakarta 之间误选。
     */
    private List<String> extractImports(CompilationUnit compilationUnit) {
        List<String> imports = new ArrayList<>();
        for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
            imports.add(importDeclaration.getNameAsString() + (importDeclaration.isAsterisk() ? ".*" : ""));
        }
        return imports;
    }

    /**
     * 从方法声明中提取 throws 类型。
     * 这些类型用于判断测试方法是否需要统一声明 throws Exception，以及异常场景应断言什么类型。
     */
    private List<String> extractThrownExceptions(MethodDeclaration methodDeclaration) {
        List<String> exceptions = new ArrayList<>();
        for (ReferenceType type : methodDeclaration.getThrownExceptions()) {
            exceptions.add(type.asString());
        }
        return exceptions;
    }

    /**
     * 将 JavaParser 的问题列表整理成中文可展示文本。
     * 服务层会把该文本放入告警，同时尝试 PSI 降级，用户能看到真实失败原因而不是空白页。
     */
    private String parseFailureMessage(ParseResult<CompilationUnit> parseResult) {
        StringBuilder message = new StringBuilder();
        message.append("源码解析失败。\n\n");
        message.append("可能原因：\n");
        message.append("- 当前源码使用了插件暂不支持的 Java 新语法；\n");
        message.append("- JavaParser 版本或语言级别配置过低；\n");
        message.append("- 源码存在语法错误。\n\n");
        message.append("解析错误：\n");
        if (parseResult.getProblems().isEmpty()) {
            message.append("- 未返回具体解析错误。");
        } else {
            parseResult.getProblems().forEach(problem -> message.append("- ").append(problem.getVerboseMessage()).append("\n"));
        }
        return message.toString().trim();
    }
}
