package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.TestExecutionSummary;
import junit.framework.TestCase;
import java.nio.file.*;

public class JacocoReportIntegrityTest extends TestCase {
    public void testCoverageAnalyzerReadsJava21AndJava25ClassFiles() throws Exception {
        byte[] original;
        try (var input = getClass().getResourceAsStream("JacocoReportIntegrityTest.class")) {
            assertNotNull(input);
            original = input.readAllBytes();
        }
        for (int major : new int[]{65, 69}) {
            byte[] bytes = original.clone();
            bytes[6] = (byte) (major >>> 8);
            bytes[7] = (byte) major;
            var coverage = new org.jacoco.core.analysis.CoverageBuilder();
            try {
                new org.jacoco.core.analysis.Analyzer(new org.jacoco.core.data.ExecutionDataStore(), coverage)
                        .analyzeClass(bytes, "Java " + (major - 44));
            } catch (Exception error) {
                throw new AssertionError("ASM loaded from " + org.objectweb.asm.ClassReader.class
                        .getProtectionDomain().getCodeSource().getLocation(), error);
            }
            assertEquals(1, coverage.getClasses().size());
        }
    }
    public void testOneUnreadableReportCannotBeHiddenByAnotherPassingReport() throws Exception {
        Path root = Files.createTempDirectory("unitmind-report-proof-");
        try {
            Files.writeString(root.resolve("TEST-good.xml"), "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase classname='T' name='exercise()'/></testsuite>");
            Files.writeString(root.resolve("TEST-broken.xml"), "<testsuite>");
            var run = read(root); assertTrue(run.isSuccessful()); assertEquals(1, run.getSucceeded());
            assertFalse(run.isFullySuccessful()); assertFalse(run.getEvidenceFailures().isEmpty());
            assertEquals("JUnitReportReadFailure", run.getDiagnostics().get(0).getExceptionType());
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }
    public void testSuiteCountersCannotReplaceMissingTestcaseRecords() throws Exception {
        Path root = Files.createTempDirectory("unitmind-report-counters-");
        try {
            Files.writeString(root.resolve("TEST-incomplete.xml"), "<testsuite tests='2' failures='0' errors='0' skipped='0'><testcase classname='T' name='exercise()'/></testsuite>");
            var run = read(root); assertTrue(run.isSuccessful()); assertEquals(2, run.getFound());
            assertEquals(1, run.getTestCases().size()); assertFalse(run.isFullySuccessful());
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }
    public void testLargeFailedBatchKeepsLateRootAndEveryFailureDiagnostic() throws Exception {
        Path root=Files.createTempDirectory("unitmind-large-failed-batch-");
        try {
            var xml=new StringBuilder("<testsuite tests='25' failures='25' errors='0' skipped='0'>");
            for(int i=0;i<25;i++) xml.append("<testcase classname='T' name='case").append(i).append("()'><failure type='java.lang.IllegalStateException' message='startup'><![CDATA[")
                    .append(i==24 ? "Caused by: sample.LastRoot: deepest cause retained after twentieth diagnostic" : "java.lang.IllegalStateException: context blocked")
                    .append("]]></failure></testcase>");
            Files.writeString(root.resolve("TEST-large.xml"),xml.append("</testsuite>").toString());
            var run=read(root); assertEquals(25,run.getDiagnostics().size());
            assertEquals("sample.LastRoot",run.getDiagnostics().get(24).getRootCauseType());
            assertEquals("deepest cause retained after twentieth diagnostic",run.getDiagnostics().get(24).getRootCauseMessage());
        } finally {CompiledTestArtifact.deleteRecursively(root);}
    }

    private static TestExecutionSummary read(Path reports) throws Exception {
        var method = JacocoCoverageExecutor.class.getDeclaredMethod("readXmlReports", Path.class, TestExecutionSummary.class);
        method.setAccessible(true); var run = new TestExecutionSummary(); run.setExitCode(0);
        method.invoke(new JacocoCoverageExecutor(), reports, run); return run;
    }
}
