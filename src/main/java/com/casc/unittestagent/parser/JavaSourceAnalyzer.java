package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.SkippedMethodModel;
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
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ReferenceType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    /** 调用链上下文提取器，负责识别被测方法内部调用形态。 */
    private final CallContextExtractor callContextExtractor = new CallContextExtractor();
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
        return analyze(sourceText, preferredMethodName, -1);
    }

    /**
     * 按光标所在源码行精确选择方法，避免内部类或重载方法仅凭名称匹配到错误声明。
     */
    @SuppressWarnings("unchecked")
    public MethodModel analyze(String sourceText, String preferredMethodName, int preferredSourceLine) {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(sourceText));
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            throw new IllegalStateException(parseFailureMessage(parseResult));
        }

        CompilationUnit compilationUnit = parseResult.getResult().get();
        MethodDeclaration methodDeclaration = findMethod(compilationUnit, preferredMethodName, preferredSourceLine);
        if (methodDeclaration == null) {
            throw new IllegalStateException("当前 Java 文件中未找到可分析的方法。");
        }
        Optional<ClassOrInterfaceDeclaration> classDeclaration =
                methodDeclaration.findAncestor(ClassOrInterfaceDeclaration.class);
        if (classDeclaration.isEmpty()) {
            throw new IllegalStateException("当前 Java 文件中未找到类。");
        }

        return toMethodModel(compilationUnit, classDeclaration.get(), methodDeclaration);
    }

    /**
     * 一次解析当前顶层具体类，并为所有符合范围的方法构建 MethodModel。
     */
    public ClassModel analyzeClass(String sourceText, String preferredClassName, int preferredSourceLine) {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(sourceText));
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            throw new IllegalStateException(parseFailureMessage(parseResult));
        }
        CompilationUnit compilationUnit = parseResult.getResult().get();
        ClassOrInterfaceDeclaration classDeclaration = findTopLevelClass(
                compilationUnit, preferredClassName, preferredSourceLine);
        if (classDeclaration == null) {
            if (compilationUnit.findFirst(com.github.javaparser.ast.body.RecordDeclaration.class,
                    declaration -> declaration.isTopLevelType()).isPresent()) {
                throw new IllegalStateException("整类生成首期不支持 record。");
            }
            if (compilationUnit.findFirst(com.github.javaparser.ast.body.EnumDeclaration.class,
                    declaration -> declaration.isTopLevelType()).isPresent()) {
                throw new IllegalStateException("整类生成首期不支持 enum。");
            }
            if (compilationUnit.findFirst(com.github.javaparser.ast.body.AnnotationDeclaration.class,
                    declaration -> declaration.isTopLevelType()).isPresent()) {
                throw new IllegalStateException("整类生成首期不支持注解类型。");
            }
            throw new IllegalStateException("当前 Java 文件中未找到可生成测试的顶层类。");
        }
        if (classDeclaration.isInterface()) {
            throw new IllegalStateException("整类生成首期不支持接口。");
        }
        if (classDeclaration.isAbstract()) {
            throw new IllegalStateException("整类生成首期不支持抽象类。");
        }

        ClassModel classModel = new ClassModel();
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getName().asString()).orElse("");
        classModel.setPackageName(packageName);
        classModel.setClassName(classDeclaration.getNameAsString());
        classModel.setQualifiedName(packageName.isBlank() ? classDeclaration.getNameAsString()
                : packageName + "." + classDeclaration.getNameAsString());
        classModel.setImports(extractImports(compilationUnit));
        classDeclaration.getRange().ifPresent(range -> {
            classModel.setSourceStartLine(range.begin.line);
            classModel.setSourceEndLine(range.end.line);
        });
        List<String> fields = new ArrayList<>();
        classDeclaration.getFields().forEach(field -> field.getVariables().forEach(variable ->
                fields.add(variable.getType() + " " + variable.getNameAsString())));
        classModel.setClassFields(fields);

        List<MethodModel> methods = new ArrayList<>();
        List<SkippedMethodModel> skipped = new ArrayList<>();
        Set<String> fieldNames = classFieldNames(classDeclaration);
        for (MethodDeclaration method : classDeclaration.getMethods()) {
            String reason = skipReason(method, fieldNames);
            if (reason != null) {
                skipped.add(new SkippedMethodModel(displaySignature(method), reason));
                continue;
            }
            methods.add(toMethodModel(compilationUnit, classDeclaration, method));
        }
        classModel.setMethods(methods);
        classModel.setSkippedMethods(skipped);
        if (methods.isEmpty()) {
            throw new IllegalStateException("当前类中没有符合整类生成范围的方法。");
        }
        return classModel;
    }

    private MethodModel toMethodModel(CompilationUnit compilationUnit,
                                      ClassOrInterfaceDeclaration classDeclaration,
                                      MethodDeclaration methodDeclaration) {
        MethodModel model = new MethodModel();
        model.setLanguage("Java");
        model.setPackageName(compilationUnit.getPackageDeclaration().map(declaration -> declaration.getName().asString()).orElse(""));
        model.setClassName(classDeclaration.getNameAsString());
        model.setMethodName(methodDeclaration.getNameAsString());
        model.setReturnType(methodDeclaration.getType().toString());
        model.setAccessModifier(accessModifier(methodDeclaration));
        model.setProtectedMethod(methodDeclaration.isProtected());
        model.setStaticMethod(methodDeclaration.isStatic());
        model.setSignature(displaySignature(methodDeclaration));
        String owner = model.getPackageName() == null || model.getPackageName().isBlank()
                ? model.getClassName() : model.getPackageName() + "." + model.getClassName();
        model.setMethodId(owner + "#" + methodDeclaration.getNameAsString() + "("
                + erasedParameterTypes(methodDeclaration) + ")");
        model.setImports(extractImports(compilationUnit));
        model.setThrownExceptions(extractThrownExceptions(methodDeclaration));
        model.setMethodBodyText(methodDeclaration.getBody().map(Object::toString).orElse(""));
        methodDeclaration.getRange().ifPresent(range -> {
            model.setSourceStartLine(range.begin.line);
            model.setSourceEndLine(range.end.line);
        });
        model.setDeclaredMethodNames(extractDeclaredMethodNames(classDeclaration));
        model.setParameters(extractParameters(methodDeclaration));
        model.setConstraints(constraintExtractor.extract(methodDeclaration));
        model.setBranches(constraintExtractor.extractBranches(methodDeclaration));
        model.setDependencies(dependencyExtractor.extract(classDeclaration, methodDeclaration));
        model.setUsages(methodUsageExtractor.extract(classDeclaration, methodDeclaration));
        model.setCallContexts(callContextExtractor.extract(classDeclaration, methodDeclaration));
        return model;
    }

    private ClassOrInterfaceDeclaration findTopLevelClass(CompilationUnit compilationUnit,
                                                           String preferredClassName,
                                                           int preferredSourceLine) {
        List<ClassOrInterfaceDeclaration> classes = compilationUnit.findAll(ClassOrInterfaceDeclaration.class,
                declaration -> declaration.isTopLevelType());
        if (preferredSourceLine > 0) {
            Optional<ClassOrInterfaceDeclaration> byLine = classes.stream()
                    .filter(declaration -> declaration.getRange().map(range ->
                            range.begin.line <= preferredSourceLine && preferredSourceLine <= range.end.line).orElse(false))
                    .findFirst();
            if (byLine.isPresent()) return byLine.get();
        }
        if (preferredClassName != null && !preferredClassName.isBlank()) {
            for (ClassOrInterfaceDeclaration declaration : classes) {
                if (preferredClassName.equals(declaration.getNameAsString())) return declaration;
            }
        }
        return classes.isEmpty() ? null : classes.get(0);
    }

    private String skipReason(MethodDeclaration method, Set<String> fieldNames) {
        if (method.isPrivate()) return "private 方法不在首期整类范围内";
        if (method.isAbstract()) return "abstract 方法无可执行方法体";
        if (method.isNative()) return "native 方法无可执行方法体";
        if (method.getBody().isEmpty()) return "方法无可执行方法体";
        if (isObjectOverride(method)) return "Object 基础方法默认跳过";
        if (isSimpleGetter(method, fieldNames)) return "简单 getter 默认跳过";
        if (isSimpleSetter(method, fieldNames)) return "简单 setter 默认跳过";
        return null;
    }

    private boolean isObjectOverride(MethodDeclaration method) {
        if (("toString".equals(method.getNameAsString()) || "hashCode".equals(method.getNameAsString()))
                && method.getParameters().isEmpty()) return true;
        return "equals".equals(method.getNameAsString()) && method.getParameters().size() == 1
                && "Object".equals(method.getParameter(0).getType().asString());
    }

    private boolean isSimpleGetter(MethodDeclaration method, Set<String> fieldNames) {
        if (!method.getParameters().isEmpty() || method.getType().isVoidType()) return false;
        List<Statement> statements = method.getBody().orElseThrow().getStatements();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt()) return false;
        ReturnStmt returnStmt = statements.get(0).asReturnStmt();
        if (returnStmt.getExpression().isEmpty()) return false;
        return fieldName(returnStmt.getExpression().get()).map(fieldNames::contains).orElse(false);
    }

    private boolean isSimpleSetter(MethodDeclaration method, Set<String> fieldNames) {
        if (!method.getType().isVoidType() || method.getParameters().size() != 1) return false;
        List<Statement> statements = method.getBody().orElseThrow().getStatements();
        if (statements.size() != 1 || !statements.get(0).isExpressionStmt()) return false;
        Expression expression = statements.get(0).asExpressionStmt().getExpression();
        if (!expression.isAssignExpr()) return false;
        AssignExpr assign = expression.asAssignExpr();
        Optional<String> target = fieldName(assign.getTarget());
        return target.map(fieldNames::contains).orElse(false)
                && assign.getValue().isNameExpr()
                && method.getParameter(0).getNameAsString().equals(assign.getValue().asNameExpr().getNameAsString());
    }

    private Optional<String> fieldName(Expression expression) {
        if (expression instanceof NameExpr) return Optional.of(((NameExpr) expression).getNameAsString());
        if (expression instanceof FieldAccessExpr) {
            FieldAccessExpr field = (FieldAccessExpr) expression;
            if (field.getScope().isThisExpr()) return Optional.of(field.getNameAsString());
        }
        return Optional.empty();
    }

    private Set<String> classFieldNames(ClassOrInterfaceDeclaration declaration) {
        Set<String> names = new HashSet<>();
        declaration.getFields().forEach(field -> field.getVariables().forEach(variable -> names.add(variable.getNameAsString())));
        return names;
    }

    private String displaySignature(MethodDeclaration method) {
        List<String> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) parameters.add(parameter.getType().asString());
        return method.getNameAsString() + "(" + String.join(", ", parameters) + "): " + method.getType();
    }

    private String erasedParameterTypes(MethodDeclaration method) {
        List<String> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            String erased = eraseType(parameter.getType().asString());
            if (parameter.isVarArgs() && !erased.endsWith("[]")) erased += "[]";
            parameters.add(erased);
        }
        return String.join(",", parameters);
    }

    private String eraseType(String type) {
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < type.length(); index++) {
            char current = type.charAt(index);
            if (current == '<') { depth++; continue; }
            if (current == '>') { depth = Math.max(0, depth - 1); continue; }
            if (depth == 0 && !Character.isWhitespace(current)) result.append(current);
        }
        return result.toString().replace("...", "[]");
    }

    /**
     * 识别方法真实访问修饰符。
     */
    private String accessModifier(MethodDeclaration methodDeclaration) {
        if (methodDeclaration.isPublic()) {
            return "public";
        }
        if (methodDeclaration.isProtected()) {
            return "protected";
        }
        if (methodDeclaration.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    /**
     * 选择本次生成要分析的方法。
     * 优先使用光标所在方法，其次选择 public 方法，最后退回文件中的第一个方法。
     */
    private MethodDeclaration findMethod(CompilationUnit compilationUnit,
                                         String preferredMethodName,
                                         int preferredSourceLine) {
        List<MethodDeclaration> methods = compilationUnit.findAll(MethodDeclaration.class);
        if (preferredSourceLine > 0) {
            Optional<MethodDeclaration> atCaret = methods.stream()
                    .filter(method -> preferredMethodName == null || preferredMethodName.isBlank()
                            || preferredMethodName.equals(method.getNameAsString()))
                    .filter(method -> method.getRange().map(range ->
                            range.begin.line <= preferredSourceLine && preferredSourceLine <= range.end.line).orElse(false))
                    .min(Comparator.comparingInt((MethodDeclaration method) -> method.getRange()
                            .map(range -> range.end.line - range.begin.line).orElse(Integer.MAX_VALUE)));
            if (atCaret.isPresent()) return atCaret.get();
        }
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
