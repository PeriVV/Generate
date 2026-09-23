package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.model.CoveragePhase;
import com.casc.unittestagent.model.CoverageReport;
import junit.framework.TestCase;

/** 覆盖率闭环全局进度映射测试。 */
public class CoverageProgressTest extends TestCase {
    public void testProgressIncreasesAcrossCandidateBoundary() {
        CoverageConfig config = new CoverageConfig();
        config.setMaxRounds(3);

        double firstImprovement = UnitTestGenerationService.coverageProgressFraction(
                config, CoveragePhase.IMPROVING, 1, 0d);
        double secondCompile = UnitTestGenerationService.coverageProgressFraction(
                config, CoveragePhase.COMPILING, 2, 0d);
        double secondExecution = UnitTestGenerationService.coverageProgressFraction(
                config, CoveragePhase.EXECUTING, 2, 0d);
        double secondAnalysis = UnitTestGenerationService.coverageProgressFraction(
                config, CoveragePhase.ANALYZING, 2, 0d);

        assertTrue(secondCompile > firstImprovement);
        assertTrue(secondExecution > secondCompile);
        assertTrue(secondAnalysis > secondExecution);
        assertTrue(UnitTestGenerationService.coverageProgressFraction(
                config, CoveragePhase.VALIDATING, 4, 0d) > secondAnalysis);
    }

    public void testReportRejectsRegressiveProgressUpdate() {
        CoverageReport report = new CoverageReport();
        report.updateProgress(CoveragePhase.IMPROVING, 1, "补测", 0.40d);
        report.updateProgress(CoveragePhase.COMPILING, 2, "编译", 0.20d);

        assertEquals(0.40d, report.getProgressFraction());
        assertEquals(CoveragePhase.COMPILING, report.getPhase());
        assertEquals(2, report.getCurrentCandidate());
    }
}
