package com.casc.unittestagent.llm;

import junit.framework.TestCase;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import javax.net.ssl.SSLHandshakeException;

public class LlmFailureDiagnosisTest extends TestCase {
    public void testGatewayTimeoutDoesNotBecomeClientSocketTimeout() {
        assertEquals("LLM-GATEWAY_TIMEOUT", classify(new SocketTimeoutException(), LlmRequestTrace.Stage.BODY, 504, 1));
        assertEquals("LLM-FIRST_DATA_TIMEOUT", classify(new SocketTimeoutException(), LlmRequestTrace.Stage.FIRST_DATA, 200, -1));
        assertEquals("LLM-STREAM_IDLE_TIMEOUT", classify(new SocketTimeoutException(), LlmRequestTrace.Stage.REASONING, 200, 10));
        assertEquals("LLM-HEADER_TIMEOUT", classify(new SocketTimeoutException(), LlmRequestTrace.Stage.HEADERS, 0, -1));
    }
    public void testTotalDeadlineSurvivesWrappedSocketCauseAndHeartbeats() {
        var total = new InterruptedIOException("timeout"); total.initCause(new SocketTimeoutException("read timeout"));
        assertEquals("LLM-TOTAL_DEADLINE", classify(total, LlmRequestTrace.Stage.REASONING, 200, 1));
    }
    public void testCancellationIsNotAServiceFailureEvenAfterHeaders() {
        assertEquals("LLM-CANCELLED", classify(new com.intellij.openapi.progress.ProcessCanceledException(), LlmRequestTrace.Stage.BODY, 403, 1));
    }
    public void testTlsDnsRefusalAndWrappedConnectTimeoutHaveDistinctAdvice() {
        assertEquals("LLM-TLS_FAILURE", classify(new SSLHandshakeException("certificate"), LlmRequestTrace.Stage.TLS, 0, -1));
        assertEquals("LLM-DNS_FAILURE", classify(new IOException(new UnknownHostException("private")), LlmRequestTrace.Stage.DNS, 0, -1));
        assertEquals("LLM-CONNECT_FAILURE", classify(new ConnectException("refused"), LlmRequestTrace.Stage.CONNECTING, 0, -1));
        var timed = new ConnectException("connect failed"); timed.initCause(new SocketTimeoutException());
        assertEquals("LLM-CONNECT_TIMEOUT", classify(timed, LlmRequestTrace.Stage.CONNECTING, 0, -1));
    }
    public void testAuthRateLimitAndBadParametersStopFurtherRequests() {
        for (int status : new int[]{401, 403, 404, 400, 422, 429}) {
            var result = LlmFailureDiagnosis.classify(new IOException("error"), sample(LlmRequestTrace.Stage.BODY, status, 1));
            assertTrue(status + " must not trigger more probes", result.stopPlan());
        }
        assertFalse(LlmFailureDiagnosis.classify(new IOException(), sample(LlmRequestTrace.Stage.BODY, 503, 1)).stopPlan());
    }
    public void testPartialStreamAndTruncationAreNotAcceptedAsFormatSuccess() {
        assertEquals("LLM-INCOMPLETE_STREAM", classify(new IOException("partial output discarded; finish_reason missing"), LlmRequestTrace.Stage.BODY, 200, 1));
        assertEquals("LLM-OUTPUT_TRUNCATED", classify(new LlmOutputTruncatedException("length"), LlmRequestTrace.Stage.COMPLETE, 200, 1));
    }
    private String classify(Throwable error, LlmRequestTrace.Stage stage, int status, long firstData) {
        return LlmFailureDiagnosis.classify(error, sample(stage, status, firstData)).code();
    }
    private LlmRequestTrace.Snapshot sample(LlmRequestTrace.Stage stage, int status, long firstData) {
        return new LlmRequestTrace.Snapshot(stage, 100, -1, -1, -1, -1, -1, firstData, -1, -1,
                10, 20, firstData < 0 ? 0 : 100, 0, 0, 0, status, "", "", "", "", false, 1, "DIRECT", "");
    }
}
