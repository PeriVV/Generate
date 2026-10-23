package com.casc.unittestagent.ui;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.ui.component.FinalTestFileReportPanel;
import com.intellij.testFramework.LightPlatformTestCase;

import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;

public class FinalTestFileReportPanelTest extends LightPlatformTestCase {
    public void testSavedGbkCrLfFileRetainsItsVerifiedCoverage() throws Exception {
        var path = java.nio.file.Files.createTempFile("unitmind 中文 CRLF-", ".java");
        try {
            var gbk = java.nio.charset.Charset.forName("GBK");
            java.nio.file.Files.writeString(path, "class 中文 {}\r\n", gbk);
            var virtual = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            virtual.setCharset(gbk);
            var report = report(); report.setTargetPath(path.toString()); report.setWritten(true);
            report.getMethodCoverage().setCodeHash(com.casc.unittestagent.util.CodeFingerprint.sha256("class 中文 {}\n"));
            assertEquals("100.0% (2/2)", table(new FinalTestFileReportPanel(report)).getValueAt(0, 1));
            assertEquals(CoverageStatus.BELOW_THRESHOLD, report.getMethodCoverage().getStatus());
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }
    public void testMethodAndClassCountersHaveExplicitDistinctScope() throws Exception {
        var report = report();
        var path = java.nio.file.Files.createTempFile("unitmind-final-panel-", ".java");
        java.nio.file.Files.writeString(path, "verified"); path.toFile().deleteOnExit();
        report.setTargetPath(path.toString()); report.setWritten(true);
        report.getMethodCoverage().setCodeHash(com.casc.unittestagent.util.CodeFingerprint.sha256("verified"));
        JTable table = table(new FinalTestFileReportPanel(report));
        assertEquals("最终完整测试文件 → 所选业务方法", table.getValueAt(0, 0));
        assertEquals("100.0% (2/2)", table.getValueAt(0, 1));
        assertEquals("不适用", table.getValueAt(0, 2));
        assertEquals("50.0% (2/4)", table.getValueAt(1, 1));
        assertEquals("50.0% (1/2)", table.getValueAt(1, 2));
    }
    public void testStaleFailedOrUnavailableResultsNeverDisplayOldPassingPercentages() {
        for (var status : new CoverageStatus[]{CoverageStatus.STALE, CoverageStatus.TEST_FAILED,
                CoverageStatus.CANCELLED, CoverageStatus.ENVIRONMENT_ERROR, CoverageStatus.RUNNING}) {
            var report = report(); report.getMethodCoverage().setStatus(status);
            JTable table = table(new FinalTestFileReportPanel(report));
            assertEquals("不可用", table.getValueAt(0, 1));
            assertEquals("不可用", table.getValueAt(1, 1));
        }
    }
    public void testChangedWrittenFileNeverDisplaysPreviousCoverage() throws Exception {
        var report = report();
        var path = java.nio.file.Files.createTempFile("unitmind-final-panel-stale-", ".java");
        try {
            java.nio.file.Files.writeString(path, "manually changed");
            report.setTargetPath(path.toString()); report.setWritten(true);
            report.getMethodCoverage().setCodeHash(com.casc.unittestagent.util.CodeFingerprint.sha256("verified"));
            var table = table(new FinalTestFileReportPanel(report));
            assertEquals(CoverageStatus.STALE, report.getMethodCoverage().getStatus());
            assertEquals("不可用", table.getValueAt(0, 1)); assertEquals("不可用", table.getValueAt(1, 1));
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }
    public void testCoverageAndRuntimeMustReferenceSameFinalFile() {
        var report = report();
        report.getMethodCoverage().setCodeHash("new"); report.getMethodCoverage().setSourceHash("source");
        var runtime = ClassJUnitRepairReport.status(ClassJUnitStatus.PASSED);
        runtime.setCodeHash("old"); runtime.setSourceHash("source");
        assertEquals("不可用", table(new FinalTestFileReportPanel(report, runtime)).getValueAt(0, 1));
    }
    private FinalTestFileReport report() {
        var report = new FinalTestFileReport(); report.setTargetPath("/synthetic/SubjectTest.java");
        report.getMethodCoverage().setStatus(CoverageStatus.BELOW_THRESHOLD);
        var method = report.getMethodCoverage().getGenerated();
        method.setAvailable(true); method.setLine(new CoverageMetric(2, 0));
        var type = report.getClassCoverage(); type.setAvailable(true);
        type.setLine(new CoverageMetric(2, 2)); type.setBranch(new CoverageMetric(1, 1));
        return report;
    }
    private JTable table(Container component) {
        for (Component child : component.getComponents()) {
            if (child instanceof JTable t) return t;
            if (child instanceof Container c) { var found = table(c); if (found != null) return found; }
        }
        return null;
    }
}
