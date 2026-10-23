package com.casc.unittestagent.validation;

import com.casc.unittestagent.model.GeneratedTestFile;
import com.casc.unittestagent.model.ValidationResult;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

/**
 * 生成测试代码验证器抽象，便于编排层使用真实编译器或测试替身。
 */
public interface TestCodeValidator {
    ValidationResult validate(Project project, PsiFile sourceFile, GeneratedTestFile generatedFile, String code);
    default ValidationResult validate(Project project, PsiFile sourceFile, GeneratedTestFile generatedFile,
                                      String code, java.util.function.BooleanSupplier cancelled) {
        var operation = com.casc.unittestagent.util.OperationContext.capture(cancelled);
        operation.checkCancelled();
        var result = validate(project, sourceFile, generatedFile, code);
        operation.checkCancelled();
        return result;
    }
}
