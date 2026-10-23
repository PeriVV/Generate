package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.*;
import com.casc.unittestagent.ui.component.RuntimeEvidencePanel;
import junit.framework.TestCase;
import org.json.JSONObject;
import java.nio.file.*;
import java.util.*;

public class RuntimeExceptionEvidenceTest extends TestCase {
    public void testDeepLongChainAndMultilineRootAreKeptWithoutEightLayerOrTwelveKLimit() {
        StringBuilder raw = new StringBuilder("java.lang.IllegalStateException: startup\n");
        for(int i=0; i<16; i++) raw.append("Caused by: sample.Cause").append(i).append(": wrapper ").append(i).append("\n\tat sample.Long.method(Long.java:1)\n".repeat(50));
        raw.append("Caused by: sample.RealRoot: required configuration\nsecond detail line\n\tat sample.Root.load(Root.java:999)\n");
        var d = JacocoCoverageExecutor.runtimeDiagnostic("T#one()","java.lang.IllegalStateException","startup",raw.toString());
        assertEquals(raw.toString(),d.getFailureDetails()); assertTrue(d.getFailureDetails().length()>12000);
        assertEquals(17,d.getCauseChain().size()); assertEquals(17,d.getCauseDetails().size());
        assertEquals("sample.RealRoot",d.getRootCauseType()); assertEquals("required configuration\nsecond detail line",d.getRootCauseMessage());
        assertEquals("at sample.Root.load(Root.java:999)",d.getRootCauseFrame());
        MockitoRuntimeDiagnosticEnricher.enrich(List.of(d),"class T {}");
        assertEquals("at sample.Root.load(Root.java:999)",d.getRootCauseFrame());
        var execution = new TestExecutionSummary(); execution.setDiagnostics(List.of(d));
        assertTrue(RuntimeEvidencePanel.fullText(execution).contains(raw));
    }
    public void testSuppressedCauseDoesNotBecomeMainRoot() {
        String raw="""
                java.lang.IllegalStateException: startup
                \tSuppressed: sample.Suppressed: noise
                \tCaused by: sample.SuppressedRoot: unrelated
                \t\tat sample.Noise.fail(Noise.java:1)
                Caused by: sample.ActualRoot: actual
                \tat sample.Real.fail(Real.java:2)
                \tSuppressed: sample.CloseError: cleanup
                \tCaused by: sample.CloseCause: also unrelated
                """;
        var d=JacocoCoverageExecutor.runtimeDiagnostic("T#one()","java.lang.IllegalStateException","startup",raw);
        assertEquals("sample.ActualRoot",d.getRootCauseType()); assertEquals("actual",d.getRootCauseMessage());
        assertEquals(List.of("sample.ActualRoot"),d.getCauseChain()); assertEquals(3,d.getCauseDetails().size());
        assertEquals(raw,d.getFailureDetails());
    }
    public void testFailedEvidenceSurvivesSandboxCleanupWithExactXmlAndRawText() throws Exception {
        Path root=Files.createTempDirectory("unitmind-raw-source-"); Path parent=Files.createTempDirectory("unitmind-raw-archive-");
        try {
            Path reports=Files.createDirectories(root.resolve("reports")); String xml="<testsuite>original XML &amp; raw</testsuite>";
            Files.writeString(reports.resolve("TEST-original.xml"),xml); Files.writeString(reports.resolve("process-output.log"),"complete process output\n".repeat(60000));
            var d=JacocoCoverageExecutor.runtimeDiagnostic("T#one()","java.lang.IllegalStateException","startup","Caused by: sample.Root: " + "x".repeat(20000));
            var execution=new TestExecutionSummary(); execution.setDiagnostics(List.of(d));
            RuntimeEvidenceArchive.preserve(reports,execution,parent);
            assertEquals("",execution.getEvidenceArchiveError()); assertFalse(execution.getEvidenceArchivePath().isBlank());
            CompiledTestArtifact.deleteRecursively(root);
            Path archive=Path.of(execution.getEvidenceArchivePath()); assertEquals(xml,Files.readString(archive.resolve("TEST-original.xml")));
            var json=new JSONObject(Files.readString(archive.resolve("execution.json"))).getJSONArray("diagnostics").getJSONObject(0);
            assertEquals(d.getFailureDetails(),json.getString("failureDetails")); assertEquals(d.getRootCauseMessage(),json.getString("rootCauseMessage"));
            assertTrue(Files.size(archive.resolve("process-output.log"))>1024*1024);
            assertEquals(archive.toString(),d.getEvidenceArchivePath());
        } finally { CompiledTestArtifact.deleteRecursively(root); CompiledTestArtifact.deleteRecursively(parent); }
    }
    public void testArchiveFailureIsReportedWithoutReplacingRuntimeCause() throws Exception {
        Path file=Files.createTempFile("unitmind-archive-unwritable-",".txt"); Path reports=Files.createTempDirectory("unitmind-reports-");
        try {
            var execution=new TestExecutionSummary(); var d=JacocoCoverageExecutor.runtimeDiagnostic("T#one()","sample.Root","actual","sample.Root: actual");execution.setDiagnostics(List.of(d));
            RuntimeEvidenceArchive.preserve(reports,execution,file);
            assertTrue(execution.getEvidenceArchivePath().isBlank()); assertFalse(execution.getEvidenceArchiveError().isBlank());
            assertEquals("sample.Root",d.getRootCauseType()); assertEquals("actual",d.getRootCauseMessage());
        } finally { Files.deleteIfExists(file); CompiledTestArtifact.deleteRecursively(reports); }
    }
}
