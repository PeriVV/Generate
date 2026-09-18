package com.casc.unittestagent.validation;

import com.casc.unittestagent.coverage.ProjectTestEnvironment;
import com.casc.unittestagent.coverage.ProjectTestEnvironmentResolver;
import com.casc.unittestagent.coverage.CompiledTestArtifact;
import com.casc.unittestagent.coverage.TestSandboxCompiler;
import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationResult;
import com.casc.unittestagent.model.ValidationStatus;
import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.CompilerConfigurationImpl;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.PathUtil;
import org.jetbrains.jps.model.java.impl.compiler.ProcessorConfigProfileImpl;

import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies clean-checkout compilation when project classes live in another source module. */
public class ProjectCompileVerifierMultiModuleTest extends JavaCodeInsightFixtureTestCase {
    @Override
    protected void tuneFixture(JavaModuleFixtureBuilder<?> moduleBuilder) throws Exception {
        super.tuneFixture(moduleBuilder);
        moduleBuilder.setLanguageLevel(LanguageLevel.JDK_17);
        moduleBuilder.addJdk(System.getProperty("java.home"));
    }

    public void testCompilesAgainstUnbuiltTransitiveModuleSources() throws Exception {
        addFrameworkStubs();
        Path moduleRootPath = Files.createTempDirectory("unitmind-common-module-");
        Path sourceRootPath = moduleRootPath.resolve("src/main/java");
        Path dtoPath = sourceRootPath.resolve("com/casc/platform/common/model/dto/ResultDto.java");
        Files.createDirectories(dtoPath.getParent());
        Files.writeString(dtoPath, """
                package com.casc.platform.common.model.dto;
                import lombok.Data;
                @Data
                public class ResultDto {
                    private String value;
                }
                """);
        Path responsePath = sourceRootPath.resolve("com/casc/platform/common/model/ResponseModel.java");
        Files.createDirectories(responsePath.getParent());
        Files.writeString(responsePath, """
                package com.casc.platform.common.model;
                import lombok.Builder;
                import lombok.Data;
                @Data
                @Builder
                public class ResponseModel<T> {
                    private T data;
                    public static <T> ResponseModel<T> success(T data) {
                        return ResponseModel.<T>builder().data(data).build();
                    }
                }
                """);
        Path errorCodePath = sourceRootPath.resolve("com/casc/platform/common/constant/ErrorCode.java");
        Files.createDirectories(errorCodePath.getParent());
        Files.writeString(errorCodePath, """
                package com.casc.platform.common.constant;
                import com.casc.platform.common.model.ResponseModel;
                public enum ErrorCode {
                    SAMPLE;
                    public ResponseModel<String> response() { return ResponseModel.success("ok"); }
                }
                """);
        VirtualFile moduleRoot = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(moduleRootPath.toRealPath());
        VirtualFile sourceRoot = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(sourceRootPath.toRealPath());
        assertNotNull(moduleRoot);
        assertNotNull(sourceRoot);
        Module commonModule = PsiTestUtil.addModule(
                getProject(), JavaModuleType.getModuleType(), "platform-common", moduleRoot);
        PsiTestUtil.addSourceContentToRoots(commonModule, sourceRoot);
        ModuleRootModificationUtil.addDependency(getModule(), commonModule);
        configureLombok(commonModule);
        myFixture.addClass("package sample; public class SubjectTest {}");

        PsiFile source = myFixture.configureByText("Subject.java", """
                package sample;
                import com.casc.platform.common.constant.ErrorCode;
                import com.casc.platform.common.model.dto.ResultDto;
                public class Subject {
                    public String read(ResultDto dto) { return dto.getValue(); }
                    public Object response() { return ErrorCode.SAMPLE.response(); }
                }
                """);
        String code = """
                package sample;
                import com.casc.platform.common.model.dto.ResultDto;
                class SubjectTest {
                    void readsValue() { new Subject().read(new ResultDto()); }
                }
                """;

        ProjectTestEnvironment environment = new ProjectTestEnvironmentResolver().resolve(getProject(), source);
        assertFalse("processor path: " + environment.getAnnotationProcessorPath()
                        + "; diagnostics: " + environment.getDiagnostics(),
                environment.getAnnotationProcessorPath().isEmpty());

        ValidationResult result = new ProjectCompileVerifier().validate(
                getProject(), source, generatedFile(), code);

        assertEquals(result.getDiagnostics().toString(), ValidationStatus.PASSED, result.getStatus());
        try (CompiledTestArtifact artifact = new TestSandboxCompiler().compile(
                environment, generatedFile(), code, "sample.Subject")) {
            assertTrue(artifact.getDiagnostics().toString(), artifact.isSuccessful());
        }
    }

    private void addFrameworkStubs() {
        myFixture.addClass("package org.junit.jupiter.api; public @interface Test {}");
        myFixture.addClass("package org.mockito; public final class Mockito {}");
    }

    private void configureLombok(Module commonModule) {
        String lombokPath = PathUtil.getJarPathForClass(lombok.Data.class);
        assertNotNull(lombokPath);
        java.io.File lombokJar = new java.io.File(lombokPath);
        PsiTestUtil.addLibrary(getTestRootDisposable(), commonModule, "lombok-test-classpath",
                lombokJar.getParent(), lombokJar.getName());
        ProcessorConfigProfileImpl profile = new ProcessorConfigProfileImpl("lombok-test");
        profile.setEnabled(true);
        profile.setObtainProcessorsFromClasspath(false);
        profile.setProcessorPath(lombokPath);
        profile.addModuleName(commonModule.getName());
        ((CompilerConfigurationImpl) CompilerConfiguration.getInstance(getProject()))
                .addModuleProcessorProfile(profile);
    }

    private GeneratedTestFile generatedFile() {
        GeneratedTestFile file = new GeneratedTestFile();
        file.setClassName("SubjectTest");
        file.setFileName("SubjectTest.java");
        file.setPackageName("sample");
        return file;
    }
}
