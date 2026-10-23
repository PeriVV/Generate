package com.casc.unittestagent.coverage;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 批量扫描目标：对每个已准备仓库挑选多个代表性类，自动提取全部可调用方法
 * （public/protected、非抽象、有方法体），不做任何"好测"筛选——重载、throws 子句、
 * 泛型、数组参数、静态工厂都进扫描，专门暴露规则生成器在真实形态上的问题。
 */
final class OpenSourceSweepTargets {
    /** 每个文件最多提取的方法数，控制整轮扫描时长（编译+隔离 JVM 执行约数秒/方法）。 */
    private static final int MAX_METHODS_PER_FILE = 6;

    private record RepoConfig(String repo, String moduleDir, String... sourcePaths) { }

    private static final List<RepoConfig> REPOS = List.of(
            new RepoConfig("commons-codec", "",
                    "org/apache/commons/codec/binary/Hex.java",
                    "org/apache/commons/codec/binary/StringUtils.java",
                    "org/apache/commons/codec/net/InetAddressUtils.java"),
            new RepoConfig("commons-io", "",
                    "org/apache/commons/io/FilenameUtils.java",
                    "org/apache/commons/io/EndianUtils.java"),
            new RepoConfig("commons-lang", "",
                    "org/apache/commons/lang3/math/NumberUtils.java",
                    "org/apache/commons/lang3/math/Fraction.java",
                    "org/apache/commons/lang3/ObjectUtils.java"),
            new RepoConfig("commons-text", "",
                    "org/apache/commons/text/similarity/JaroWinklerSimilarity.java",
                    "org/apache/commons/text/similarity/LevenshteinDistance.java",
                    "org/apache/commons/text/StrSubstitutor.java"),
            new RepoConfig("guava", "guava",
                    "com/google/common/base/Strings.java",
                    "com/google/common/base/Preconditions.java",
                    "com/google/common/math/IntMath.java"),
            new RepoConfig("jackson-databind", "",
                    "com/fasterxml/jackson/databind/type/TypeParser.java"),
            new RepoConfig("jackson-core", "",
                    "com/fasterxml/jackson/core/io/NumberInput.java"),
            new RepoConfig("HikariCP", "",
                    "com/zaxxer/hikari/util/UtilityElf.java"),
            new RepoConfig("mybatis-3", "",
                    "org/apache/ibatis/parsing/GenericTokenParser.java"),
            new RepoConfig("spring-petclinic", "",
                    "org/springframework/samples/petclinic/owner/PetValidator.java",
                    "org/springframework/samples/petclinic/owner/PetTypeFormatter.java"),
            new RepoConfig("joda-time", "",
                    "org/joda/time/field/FieldUtils.java",
                    "org/joda/time/Days.java"),
            new RepoConfig("gson", "gson",
                    "com/google/gson/internal/bind/util/ISO8601Utils.java",
                    "com/google/gson/internal/LazilyParsedNumber.java"),
            new RepoConfig("junit-pioneer", "",
                    "org/junitpioneer/internal/PioneerUtils.java",
                    "org/junitpioneer/internal/TestNameFormatter.java"),
            new RepoConfig("eclipse-collections", "eclipse-collections",
                    "org/eclipse/collections/impl/Counter.java"));

    static final class Target {
        final String repo;
        final String methodName;
        final String className;
        final String targetClass;
        final Path sourceFile;
        final String sourceText;
        final int declarationLine;
        final String signature;

        Target(String repo, String methodName, String className, String targetClass,
               Path sourceFile, String sourceText, int declarationLine, String signature) {
            this.repo = repo;
            this.methodName = methodName;
            this.className = className;
            this.targetClass = targetClass;
            this.sourceFile = sourceFile;
            this.sourceText = sourceText;
            this.declarationLine = declarationLine;
            this.signature = signature;
        }
    }

    static List<Target> extract() {
        List<Target> targets = new ArrayList<>();
        for (RepoConfig config : REPOS) {
            Path repo = Path.of("tmp/oss").toAbsolutePath().resolve(config.repo);
            for (String sourcePath : config.sourcePaths) {
                Path standard = repo.resolve(config.moduleDir).resolve("src/main/java").resolve(sourcePath);
                Path file = Files.isRegularFile(standard)
                        ? standard
                        : repo.resolve(config.moduleDir).resolve("src").resolve(sourcePath);
                if (!Files.isRegularFile(file) || !prepared(repo.resolve(config.moduleDir))) continue;
                targets.addAll(extractFromFile(config.repo, file, sourcePath));
            }
        }
        return targets;
    }

    private static boolean prepared(Path moduleDir) {
        return Files.isDirectory(moduleDir.resolve("target/classes"))
                || Files.isDirectory(moduleDir.resolve("build/classes/java/main"));
    }

    private static List<Target> extractFromFile(String repo, Path file, String sourcePath) {
        List<Target> targets = new ArrayList<>();
        try {
            String sourceText = Files.readString(file, StandardCharsets.UTF_8);
            CompilationUnit unit = StaticJavaParser.parse(sourceText);
            String targetClass = sourcePath.endsWith(".java")
                    ? sourcePath.substring(0, sourcePath.length() - ".java".length()).replace('/', '.')
                    : sourcePath.replace('/', '.');
            int fileStart = targets.size();
            for (TypeDeclaration<?> type : unit.getTypes()) {
                // 接口/抽象主体的实例方法走 untestable 空测试类路径，扫了只添噪音。
                boolean untestableOwner = type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c
                        && (c.isInterface() || c.isAbstract());
                for (MethodDeclaration method : type.getMethods()) {
                    // 截断检查与可见性检查必须分离：合在一起会在首个不可见方法处
                    // 提前 break，系统性丢失其后的可见方法（实测 ~30% 目标被吞）。
                    if (targets.size() - fileStart >= MAX_METHODS_PER_FILE) break;
                    if (!isVisible(method) || untestableOwner) continue;
                    targets.add(new Target(repo, method.getNameAsString(),
                            type.getNameAsString(), targetClass, file, sourceText,
                            method.getBegin().map(p -> p.line).orElse(-1), signatureOf(method)));
                }
            }
        } catch (Exception parseFailure) {
            // 解析失败的目标不进扫描；矩阵契约测试会单独暴露编译级问题。
        }
        return targets;
    }

    private static boolean isVisible(MethodDeclaration method) {
        boolean publicOrProtected = method.isPublic() || method.isProtected();
        return publicOrProtected
                && !method.isAbstract()
                && method.getBody().isPresent()
                && method.getParameters().size() <= 4
                && !isMain(method);
    }

    private static boolean isMain(MethodDeclaration method) {
        return "main".equals(method.getNameAsString())
                && method.isStatic()
                && method.getParameters().size() == 1
                && method.getParameter(0).getTypeAsString().contains("String[]");
    }

    private static String signatureOf(MethodDeclaration method) {
        return (method.isProtected() ? "protected " : "public ")
                + method.getTypeAsString() + " " + method.getNameAsString()
                + "(" + method.getParameters().stream()
                        .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                        .reduce((a, b) -> a + ", " + b).orElse("") + ")"
                + (method.getThrownExceptions().isEmpty() ? ""
                        : " throws " + method.getThrownExceptions().stream()
                                .map(Object::toString).reduce((a, b) -> a + "," + b).orElse(""))
                + " @" + method.getBegin().map(p -> String.valueOf(p.line)).orElse("?")
                + (method.isStatic() ? " [static]" : "")
                + (method.getAnnotationByName("Deprecated").isPresent() ? " [deprecated]" : "");
    }

    private OpenSourceSweepTargets() {
    }
}
