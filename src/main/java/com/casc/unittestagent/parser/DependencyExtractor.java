package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.DependencyModel;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从类字段和方法调用中识别外部依赖。
 * 该类结合注解和命名启发式判断依赖类型，并记录被测方法实际调用了哪些依赖方法。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class DependencyExtractor {
    /**
     * 从目标方法中提取参数约束。
     * 该方法只处理能关联到参数的二元表达式，避免把无关条件误导入测试数据规划。
     *
      * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
      *
     * @param classDeclaration JavaParser 解析出的类声明节点
     * @param methodDeclaration JavaParser 解析出的目标方法声明节点
     * @return 提取到的约束模型列表
     */
    public List<DependencyModel> extract(ClassOrInterfaceDeclaration classDeclaration, MethodDeclaration methodDeclaration) {
        Map<String, DependencyModel> dependencies = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();

        if (classDeclaration == null || methodDeclaration == null) {
            return new ArrayList<>();
        }

        for (FieldDeclaration field : classDeclaration.getFields()) {
            boolean annotationDependency = field.getAnnotations().stream()
                    .anyMatch(annotation -> isDependencyAnnotation(annotation.getNameAsString()));
            for (VariableDeclarator variable : field.getVariables()) {
                String fieldName = variable.getNameAsString();
                String fieldType = variable.getType().toString();
                fields.put(fieldName, fieldType);
                if (annotationDependency || looksLikeDependency(fieldName, fieldType)) {
                    dependencies.put(fieldName, dependency(fieldName, fieldType));
                }
            }
        }

        for (Parameter parameter : methodDeclaration.getParameters()) {
            String parameterName = parameter.getNameAsString();
            String parameterType = parameter.getType().toString();
            if (looksLikeExternalParameter(parameterName, parameterType)) {
                DependencyModel dependency = dependency(parameterName, parameterType);
                dependency.setMethodParameter(true);
                dependencies.put(parameterName, dependency);
                fields.put(parameterName, parameterType);
            }
        }

        for (MethodCallExpr call : methodDeclaration.findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) {
                continue;
            }
            String scope = call.getScope().get().toString();
            if (!fields.containsKey(scope)) {
                continue;
            }
            DependencyModel dependency = dependencies.computeIfAbsent(scope, name -> dependency(name, fields.get(name)));
            Set<String> methods = new LinkedHashSet<>(dependency.getCalledMethods());
            methods.add(call.getNameAsString());
            dependency.setCalledMethods(new ArrayList<>(methods));
            Set<String> expressions = new LinkedHashSet<>(dependency.getCallExpressions());
            expressions.add(callExpression(call, classDeclaration));
            dependency.setCallExpressions(new ArrayList<>(expressions));
        }

        return new ArrayList<>(dependencies.values());
    }

    /**
     * 判断注解是否代表依赖注入。
     * 支持常见注入注解，使依赖识别不完全依赖命名规则。
     */
    private boolean isDependencyAnnotation(String annotationName) {
        return "Autowired".equals(annotationName) || "Resource".equals(annotationName) || "Inject".equals(annotationName);
    }

    /**
     * 根据字段名和类型判断是否像外部依赖。
     * 命名启发式覆盖常见数据库、远程调用、缓存、文件和权限依赖。
     */
    private boolean looksLikeDependency(String fieldName, String fieldType) {
        String text = (fieldName + " " + fieldType).toLowerCase(Locale.ROOT);
        return text.contains("mapper")
                || text.contains("repository")
                || text.contains("dao")
                || text.contains("client")
                || text.contains("api")
                || text.contains("rest")
                || text.contains("feign")
                || text.contains("jdbc")
                || text.contains("template")
                || text.contains("registry")
                || text.contains("redis")
                || text.contains("cache")
                || text.contains("file")
                || text.contains("storage")
                || text.contains("permission")
                || text.contains("auth")
                || text.contains("usercontext");
    }

    /**
     * 判断方法参数是否应作为 Mock 依赖处理。
     * 当前 MVP 使用类型名和参数名启发式，覆盖 Servlet、HTTP、客户端、数据访问和服务接口等常见外部协作者。
     */
    private boolean looksLikeExternalParameter(String parameterName, String parameterType) {
        String simpleType = simpleType(parameterType);
        String text = (parameterName + " " + simpleType).toLowerCase(Locale.ROOT);
        if (looksLikeDtoParameter(simpleType)) {
            return false;
        }
        return "HttpServletRequest".equals(simpleType)
                || "HttpServletResponse".equals(simpleType)
                || simpleType.endsWith("FilterChain")
                || simpleType.endsWith("RestTemplate")
                || simpleType.endsWith("WebClient")
                || simpleType.endsWith("RedisTemplate")
                || simpleType.endsWith("JdbcTemplate")
                || "ObjectMapper".equals(simpleType)
                || "RuntimeDataSourceRegistry".equals(simpleType)
                || simpleType.endsWith("Mapper")
                || simpleType.endsWith("Repository")
                || simpleType.endsWith("Dao")
                || simpleType.endsWith("Client")
                || simpleType.endsWith("Service")
                || text.contains("servlet")
                || text.contains("filter")
                || text.contains("chain")
                || text.contains("http");
    }

    /**
     * 判断方法参数类型是否像 DTO 或业务入参对象。
     * 这类对象应生成测试数据，而不是作为 Mockito Mock 字段注入。
     */
    private boolean looksLikeDtoParameter(String simpleType) {
        if (simpleType == null || simpleType.isBlank()) {
            return false;
        }
        if ("HttpServletRequest".equals(simpleType) || "HttpServletResponse".equals(simpleType)) {
            return false;
        }
        return simpleType.endsWith("Request")
                || simpleType.endsWith("Response")
                || simpleType.endsWith("DTO")
                || simpleType.endsWith("VO")
                || simpleType.endsWith("BO")
                || simpleType.endsWith("PO")
                || simpleType.endsWith("Entity")
                || simpleType.endsWith("Model")
                || simpleType.endsWith("Command")
                || simpleType.endsWith("Query")
                || simpleType.endsWith("Param")
                || simpleType.endsWith("Form")
                || simpleType.endsWith("Result");
    }

    /**
     * 创建依赖模型。
     * 该方法统一初始化依赖类型和调用方法列表，避免提取流程中重复设置默认值。
     */
    private DependencyModel dependency(String fieldName, String fieldType) {
        DependencyModel dependency = new DependencyModel();
        dependency.setFieldName(fieldName);
        dependency.setFieldType(fieldType);
        dependency.setDependencyType(dependencyType(fieldName, fieldType));
        dependency.setCalledMethods(new ArrayList<>());
        dependency.setCallExpressions(new ArrayList<>());
        return dependency;
    }

    /**
     * 根据字段名和类型推断依赖业务类型。
     * 分类用于生成预览解释，当前不影响业务代码执行。
     */
    private String dependencyType(String fieldName, String fieldType) {
        String text = (fieldName + " " + fieldType).toLowerCase(Locale.ROOT);
        if (text.contains("mapper") || text.contains("repository") || text.contains("dao")
                || text.contains("jdbc") || text.contains("template") || text.contains("registry")) {
            return "DATABASE";
        }
        if (text.contains("client") || text.contains("api") || text.contains("rest") || text.contains("feign")) {
            return "REMOTE_CLIENT";
        }
        if (text.contains("redis") || text.contains("cache")) {
            return "CACHE";
        }
        if (text.contains("file") || text.contains("storage")) {
            return "FILE";
        }
        if (text.contains("permission") || text.contains("auth") || text.contains("usercontext")) {
            return "PERMISSION";
        }
        if (text.contains("servlet") || text.contains("request") || text.contains("response") || text.contains("filterchain")) {
            return "SERVLET";
        }
        return "UNKNOWN";
    }

    /**
     * 生成带实参的依赖调用表达式。
     * 对类内常量做类名限定，保证测试代码中引用 HEADER_NAME 等常量时语义清晰且可编译。
     */
    private String callExpression(MethodCallExpr call, ClassOrInterfaceDeclaration classDeclaration) {
        List<String> arguments = new ArrayList<>();
        call.getArguments().forEach(argument -> arguments.add(normalizeArgument(argument.toString(), classDeclaration)));
        return call.getNameAsString() + "(" + String.join(", ", arguments) + ")";
    }

    /**
     * 标准化调用参数表达式。
     * 如果参数是当前类声明的常量，则在测试代码中使用 ClassName.CONSTANT 的形式引用。
     */
    private String normalizeArgument(String argument, ClassOrInterfaceDeclaration classDeclaration) {
        for (FieldDeclaration field : classDeclaration.getFields()) {
            boolean staticField = field.isStatic();
            for (VariableDeclarator variable : field.getVariables()) {
                if (staticField && argument.equals(variable.getNameAsString())) {
                    return classDeclaration.getNameAsString() + "." + argument;
                }
            }
        }
        return argument;
    }

    /**
     * 提取类型的简单名称。
     * 参数可能带包名或泛型，统一转成简单名称后再应用外部依赖识别规则。
     */
    private String simpleType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }
}
