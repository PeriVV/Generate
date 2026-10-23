package com.casc.unittestagent.service;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.util.CodeFingerprint;

/** One invalidation rule for all verification reports; views do not decide which proof survives an edit. */
public final class GenerationEvidenceInvalidator {
    private GenerationEvidenceInvalidator() { }

    public static void testEdited(GenerationResult result, String code) {
        if (result == null) return;
        if (!result.getFinalTestFileReport().getTargetPath().isBlank())
            result.getFinalTestFileReport().markStale("测试预览已修改，请重新验证最终合并文件。");
        var stale = ValidationResult.status(ValidationStatus.STALE);
        stale.setCodeHash(CodeFingerprint.sha256(code));
        result.setValidationResult(stale);
        result.getRuntimeValidationReport().setStatus(ClassJUnitStatus.STALE);
        result.getRuntimeValidationReport().setMessage("测试代码已修改，运行验证结果失效。");
        if (result.getRunnableTestSelectionReport().getStatus() == RunnableTestSelectionReport.Status.PASSED)
            result.getRunnableTestSelectionReport().setStatus(RunnableTestSelectionReport.Status.STALE);
        var coverage = result.getCoverageReport();
        if (coverage.getStatus() != CoverageStatus.NOT_RUN) {
            coverage.setStatus(CoverageStatus.STALE);
            coverage.setMessage("测试代码已修改，覆盖率结果失效。");
        }
    }
}
