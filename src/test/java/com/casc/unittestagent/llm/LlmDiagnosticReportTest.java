package com.casc.unittestagent.llm;

import junit.framework.TestCase;
import java.util.ArrayList;
import java.util.List;
import static com.casc.unittestagent.llm.LlmDiagnosticReport.*;

public class LlmDiagnosticReportTest extends TestCase {
    public void testWaveUsesActualFinishedDenominatorAndCorrectEvenMedian() {
        var report = report(List.of(sample(1, Status.PASS, 100), sample(2, Status.PASS, 200),
                sample(3, Status.PASS, 400), sample(4, Status.PASS, 800), sample(5, Status.FAILED, 900)), false);
        assertTrue(report.text(), report.text().contains("80.0%"));
        assertTrue(report.text(), report.text().contains("100 / 300.0 / 800 ms"));
        assertTrue(report.text().contains("不能保证长生成或峰值稳定性"));
    }
    public void testCancelledUnstartedRequestsDoNotInflateFailureRate() {
        var report = report(List.of(sample(1, Status.PASS, 10), sample(2, Status.FAILED, 20),
                sample(3, Status.PASS, 30), sample(4, Status.CANCELLED, 5)), true);
        assertTrue(report.text(), report.text().contains("66.7%"));
        assertTrue(report.text().contains("已结束 3 次")); assertTrue(report.text().contains("已停止"));
    }
    public void testReportDetachesListsFromTheActiveRun() {
        List<Metric> metrics = new ArrayList<>(); metrics.add(new Metric("HTTP", "200"));
        List<Sample> samples = new ArrayList<>(); samples.add(new Sample(1, Probe.BASIC, Status.PASS, "LLM-OK", "ok", "advice", 1, 200, 0, 0, metrics));
        var report = report(samples, false);
        samples.clear(); metrics.clear();
        assertEquals(1, report.samples().size()); assertEquals(1, report.samples().get(0).metrics().size());
    }
    private LlmDiagnosticReport report(List<Sample> samples, boolean cancelled) {
        return new LlmDiagnosticReport(Mode.STABILITY, "http://fixture/v1", "fixture", true, true, false, 3, 24000, "2026-10-26T00:00:00Z", samples, cancelled, "");
    }
    private Sample sample(int number, Status status, long millis) {
        return new Sample(number, Probe.BASIC, status, "fixture", "summary", "advice", millis, 200, 0, 0, List.of());
    }
}
