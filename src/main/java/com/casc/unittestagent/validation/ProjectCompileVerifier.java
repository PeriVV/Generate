package com.casc.unittestagent.validation;

import com.casc.unittestagent.coverage.ProjectTestEnvironment;
import com.casc.unittestagent.coverage.ProjectTestEnvironmentResolver;
import com.casc.unittestagent.coverage.CompiledTestArtifact;
import com.casc.unittestagent.coverage.TestSandboxCompiler;
import com.casc.unittestagent.coverage.MockitoStaticMockSupport;
import com.casc.unittestagent.coverage.CoverageCandidatePreflight;
import com.casc.unittestagent.model.CompileDiagnostic;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.casc.unittestagent.model.ValidationStage;
import com.casc.unittestagent.util.CodeFingerprint;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 使用 IntelliJ 项目模型和内置 Java 编译能力验证候选测试源码。
 */
public class ProjectCompileVerifier implements TestCodeValidator {
    private final ProjectTestEnvironmentResolver environmentResolver = new ProjectTestEnvironmentResolver();
    private final TestSandboxCompiler sandboxCompiler = new TestSandboxCompiler();
    @Override
    public ValidationResult validate(Project project, PsiFile sourceFile, GeneratedTestFile generatedFile, String code) {
        long started = System.currentTimeMillis();
        ValidationResult result = new ValidationResult();
        result.setCodeHash(CodeFingerprint.sha256(code));
        try {
            List<CompileDiagnostic> syntaxDiagnostics = syntaxDiagnostics(project, generatedFile, code);
            syntaxDiagnostics.addAll(CoverageCandidatePreflight.inspectGeneratedRegions(code));
            if (!syntaxDiagnostics.isEmpty()) {
                syntaxDiagnostics.forEach(d -> d.setValidationStage(ValidationStage.SOURCE_PREFLIGHT));
                result.setStatus(ValidationStatus.FAILED);
                result.setDiagnostics(syntaxDiagnostics);
                return result;
            }

            ProjectTestEnvironment environment = environmentResolver.resolve(project, sourceFile);
            result.setTestEnvironmentNote(environment.getTestEnvironmentNote());
            if (!environment.getDiagnostics().isEmpty()) {
                result.setStatus(ValidationStatus.ENVIRONMENT_ERROR);
                result.setDiagnostics(environment.getDiagnostics());
                return result;
            }
            MockitoStaticMockSupport staticMockSupport = MockitoStaticMockSupport.detect(environment.getClasspath());
            if (MockitoStaticMockSupport.usesStaticMocking(code) && !staticMockSupport.isSupported()) {
                result.setStatus(ValidationStatus.FAILED);
                result.setDiagnostics(Collections.singletonList(new CompileDiagnostic(
                        "ERROR", "MOCKITO_STATIC_MOCK_UNSUPPORTED",
                        "当前项目不支持 Mockito 静态 Mock：" + staticMockSupport.getDescription()
                                + "。请删除 mockStatic/MockedStatic，不要通过修改项目依赖规避。",
                        -1, -1, "", true)));
                return result;
            }
            // Mockito 是可选依赖：只有候选代码实际引用 org.mockito 而 classpath 缺失时
            // 才报错（可修复——删掉 mock 改直构/字面量），零 Mockito 候选不受影响。
            if (codeReferencesMockito(code) && !classpathHasMockito(environment)) {
                result.setStatus(ValidationStatus.FAILED);
                result.setDiagnostics(Collections.singletonList(new CompileDiagnostic(
                        "ERROR", "MOCKITO_UNAVAILABLE",
                        "候选测试引用了 org.mockito，但当前模块测试 classpath 中没有 Mockito。"
                                + "请删除 @Mock/mock()/when() 改用真实构造、字面量或可注入依赖；"
                                + "不要通过修改项目依赖规避。",
                        -1, -1, "", true)));
                return result;
            }
            return compileWithSharedSandbox(environment, sourceFile, generatedFile, code, result);
        } catch (ProcessCanceledException ex) {
            // 用户取消必须按取消语义向上传递，不能归类为环境错误。
            throw ex;
        } catch (Exception ex) {
            result.setStatus(ValidationStatus.ENVIRONMENT_ERROR);
            result.setDiagnostics(Collections.singletonList(new CompileDiagnostic(
                    "ERROR", "VALIDATOR_FAILURE", safeMessage(ex), -1, -1, "", false,
                    "ENVIRONMENT", "")));
            return result;
        } finally {
            result.setDurationMillis(System.currentTimeMillis() - started);
        }
    }

    private ValidationResult compileWithSharedSandbox(ProjectTestEnvironment environment,
                                                      PsiFile sourceFile,
                                                      GeneratedTestFile generatedFile,
                                                      String code,
                                                      ValidationResult result) {
        String targetClassName = targetClassName(sourceFile, environment);
        try (CompiledTestArtifact artifact = sandboxCompiler.compile(
                environment, generatedFile, code, targetClassName)) {
            result.setDiagnostics(new ArrayList<>(artifact.getDiagnostics()));
            if (artifact.isSuccessful()) {
                result.setStatus(ValidationStatus.PASSED);
            } else {
                result.setStatus(artifact.getDiagnostics().stream().anyMatch(item -> !item.isRepairable())
                        ? ValidationStatus.ENVIRONMENT_ERROR : ValidationStatus.FAILED);
            }
            return result;
        }
    }

    private boolean codeReferencesMockito(String code) {
        return code != null && java.util.regex.Pattern
                .compile("org\\.mockito|@Mock\\b|MockitoAnnotations|\\bmock\\s*\\(|\\bwhen\\s*\\(")
                .matcher(code).find();
    }

    private boolean classpathHasMockito(ProjectTestEnvironment environment) {
        for (java.io.File entry : environment.getClasspath()) {
            if (entry != null && entry.getName().startsWith("mockito-core")) return true;
        }
        return false;
    }

    private String targetClassName(PsiFile sourceFile, ProjectTestEnvironment environment) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            if (sourceFile instanceof PsiJavaFile javaFile && javaFile.getClasses().length > 0) {
                String qualifiedName = javaFile.getClasses()[0].getQualifiedName();
                if (qualifiedName != null && !qualifiedName.isBlank()) return qualifiedName;
            }
            String fileName = environment.getSourceFileName();
            String simpleName = fileName == null ? "" : fileName.replaceFirst("\\.java$", "");
            return environment.getSourcePackage().isBlank()
                    ? simpleName : environment.getSourcePackage() + "." + simpleName;
        });
    }

    private List<CompileDiagnostic> syntaxDiagnostics(Project project, GeneratedTestFile generatedFile, String code) {
        if (project == null || code == null || code.isBlank()) {
            return Collections.singletonList(new CompileDiagnostic(
                    "ERROR", "EMPTY_SOURCE", "测试源码为空。", -1, -1, "", true));
        }
        return ApplicationManager.getApplication().runReadAction((Computable<List<CompileDiagnostic>>) () -> {
            String fileName = generatedFile == null || generatedFile.getFileName() == null
                    || generatedFile.getFileName().isBlank() ? "GeneratedTest.java" : generatedFile.getFileName();
            PsiFile candidate = PsiFileFactory.getInstance(project)
                    .createFileFromText(fileName, JavaFileType.INSTANCE, code);
            List<CompileDiagnostic> diagnostics = new ArrayList<>();
            for (PsiErrorElement error : PsiTreeUtil.findChildrenOfType(candidate, PsiErrorElement.class)) {
                int line = lineNumber(code, error.getTextOffset());
                diagnostics.add(new CompileDiagnostic(
                        "ERROR", "JAVA_SYNTAX", error.getErrorDescription(), line, -1,
                        sourceLine(code, line), true));
            }
            return diagnostics;
        });
    }





    private int lineNumber(String code, int offset) {
        int line = 1;
        for (int index = 0; index < Math.min(offset, code.length()); index++) {
            if (code.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String sourceLine(String code, long lineNumber) {
        if (code == null || lineNumber <= 0) {
            return "";
        }
        String[] lines = code.split("\\R", -1);
        int index = (int) lineNumber - 1;
        return index >= 0 && index < lines.length ? lines[index].strip() : "";
    }


    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }


}
