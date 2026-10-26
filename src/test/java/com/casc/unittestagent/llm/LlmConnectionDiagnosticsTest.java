package com.casc.unittestagent.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.casc.unittestagent.llm.LlmDiagnosticReport.*;

/** Real local HTTP observations; no private endpoint, project source or paid model is used. */
public class LlmConnectionDiagnosticsTest extends TestCase {
    public void testGenerationChecksBothBudgetsAndPublishesReasoningBeforeCompletion() throws Exception {
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        try (var fixture = new Fixture((exchange, number, request) -> {
            budgets.add(request.getInt("max_tokens"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("x-request-id", "local-request-" + number);
            exchange.sendResponseHeaders(200, 0);
            if (number == 1) write(exchange, event("{\"status\":\"ok\"}", "stop"));
            else {
                write(exchange, "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n");
                Thread.sleep(180); write(exchange, ": heartbeat\n\n"); Thread.sleep(180);
                write(exchange, event(generation(), "stop"));
            }
        })) {
            var config = fixture.config(); config.setThinkingMode(true); config.setMaxTokens(1024);
            List<LlmConnectionDiagnostics.Progress> progress = new CopyOnWriteArrayList<>();
            var report = new LlmConnectionDiagnostics().run(config, Mode.GENERATION, () -> false, progress::add);
            assertEquals(2, fixture.requests.get()); assertEquals(List.of(1024, LlmConfig.THINKING_MIN_OUTPUT_TOKENS), budgets);
            assertEquals(Status.PASS, report.samples().get(0).status()); assertEquals(Status.PASS, report.samples().get(1).status());
            assertTrue(progress.toString(), progress.stream().anyMatch(p -> p.request() == 2 && p.stage().contains("推理") && p.contentChars() == 0));
            var second = report.samples().get(1);
            assertTrue(second.firstDataMs() >= 0); assertTrue(second.firstContentMs() >= 200);
            assertTrue(report.text(), report.text().contains("local-request-2"));
            assertTrue(report.text().contains("未在项目中编译、执行"));
            assertFalse(report.text().contains(fixture.secret));
            fixture.assertHealthy();
        }
    }

    public void testRateLimitStopsWaveAndKeepsRetryAfterWithoutLeakingErrorCredentials() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("Retry-After", "8");
            exchange.getResponseHeaders().set("x-request-id", "limited-1");
            json(exchange, 429, "{\"error\":\"api_key=fixture-secret-literal authorization=Bearer hidden-value\"}");
        })) {
            var report = run(fixture, Mode.STABILITY);
            assertEquals(1, fixture.requests.get()); assertEquals("LLM-RATE_LIMIT", report.samples().get(0).code());
            assertFalse(report.stoppedReason().isBlank()); assertTrue(report.text().contains("Retry-After：8"));
            assertTrue(report.text().contains("limited-1")); assertFalse(report.text().contains(fixture.secret));
            assertFalse(report.text(), report.text().contains("hidden-value")); fixture.assertHealthy();
        }
    }

    public void testWaveRecordsIntermittentFailuresInsteadOfClaimingServiceStable() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            json(exchange, number == 2 || number == 4 ? 503 : 200, number == 2 || number == 4 ? "temporary" : completion("ok"));
        })) {
            fixture.streaming = false;
            var report = run(fixture, Mode.STABILITY);
            assertEquals(5, fixture.requests.get()); assertEquals(5, report.samples().size());
            assertEquals(2L, report.samples().stream().filter(s -> s.status() == Status.FAILED).count());
            assertTrue(report.text(), report.text().contains("60.0%"));
            assertTrue(report.text().contains("不能保证长生成或峰值稳定性")); fixture.assertHealthy();
        }
    }

    public void testCancelBetweenWaveRequestsSendsNoNextRequest() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> json(exchange, 200, completion("ok")))) {
            var cancel = new AtomicBoolean();
            var report = new LlmConnectionDiagnostics().run(fixture.config(), Mode.STABILITY, cancel::get,
                    progress -> { if (progress.stage().contains("间隔等待")) cancel.set(true); });
            assertTrue(report.cancelled()); assertEquals(1, fixture.requests.get()); assertEquals(1, report.samples().size());
            assertFalse(report.text().contains("失败 1")); fixture.assertHealthy();
        }
    }

    public void testCancellationClosesActiveStreamAndRetainsItsMeasurements() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            for (int i = 0; i < 20; i++) { write(exchange, ": heartbeat\n\n"); Thread.sleep(150); }
        })) {
            var cancel = new AtomicBoolean();
            var result = new AtomicReference<LlmDiagnosticReport>();
            var failure = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try { result.set(new LlmConnectionDiagnostics().run(fixture.config(), Mode.BASIC, cancel::get,
                        progress -> { if (progress.receivedBytes() > 0) cancel.set(true); })); }
                catch (Throwable error) { failure.set(error); }
            });
            worker.start(); worker.join(4000);
            try {
                assertFalse("cancelled diagnostic did not release HTTP", worker.isAlive());
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertTrue(result.get().cancelled()); assertEquals(Status.CANCELLED, result.get().samples().get(0).status());
                assertTrue(result.get().samples().get(0).firstDataMs() >= 0); assertEquals(1, fixture.requests.get());
            } finally { cancel.set(true); worker.join(2000); }
        }
    }

    public void testHeaderWaitAndBodyIdleHaveDifferentTimeoutCodes() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            if (number == 1) Thread.sleep(1500);
            else {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                write(exchange, "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"wait\"}}]}\n\n");
                Thread.sleep(1500); return;
            }
            json(exchange, 200, completion("ok"));
        })) {
            var config = fixture.config(); config.setTimeoutSeconds(1);
            var first = new LlmConnectionDiagnostics().run(config, Mode.BASIC, () -> false, null);
            var second = new LlmConnectionDiagnostics().run(config, Mode.BASIC, () -> false, null);
            assertEquals("LLM-HEADER_TIMEOUT", first.samples().get(0).code());
            assertEquals("LLM-STREAM_IDLE_TIMEOUT", second.samples().get(0).code());
        }
    }

    public void testPrematureStreamIsDiscardedAndInvalidJavaIsAGenerationFailure() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            write(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        })) {
            assertEquals("LLM-INCOMPLETE_STREAM", run(fixture, Mode.BASIC).samples().get(0).code());
        }
        try (var fixture = new Fixture((exchange, number, request) -> json(exchange, 200,
                completion(number == 1 ? "ok" : "{\"testCode\":\"not valid Java\"}")))) {
            var report = run(fixture, Mode.GENERATION);
            assertEquals(2, fixture.requests.get()); assertEquals("LLM-GENERATION_PAYLOAD", report.samples().get(1).code());
        }
    }

    public void testFreshConnectionRecordsSetupAndJsonFallbackIsExplicit() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> json(exchange, 200, completion("ok")))) {
            var report = run(fixture, Mode.FRESH);
            assertEquals("LLM-STREAM_NOT_OBSERVED", report.samples().get(0).code());
            assertTrue(report.text(), report.text().contains("false / 1 /"));
            assertTrue(report.text().contains("实际 Content-Type：application/json")); fixture.assertHealthy();
        }
    }

    public void testInvalidConfigurationAndAlreadyCancelledRunsSendNoRequests() throws Exception {
        var config = new LlmConfig();
        var invalid = new LlmConnectionDiagnostics().run(config, Mode.GENERATION, () -> false, null);
        assertEquals("LLM-CONFIGURATION", invalid.samples().get(0).code()); assertEquals(1, invalid.samples().size());
        var cancelled = new LlmConnectionDiagnostics().run(config, Mode.STABILITY, () -> true, null);
        assertTrue(cancelled.cancelled()); assertTrue(cancelled.samples().isEmpty());
    }

    public void testHttpSuccessWithProviderErrorIsNotReportedAsConnectivitySuccess() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> json(exchange, 200,
                "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"fixture-secret-literal not permitted\"}}"))) {
            var report = run(fixture, Mode.STABILITY);
            assertEquals(1, fixture.requests.get()); assertEquals("LLM-PROVIDER_ERROR", report.samples().get(0).code());
            assertEquals(200, report.samples().get(0).httpStatus()); assertFalse(report.text().contains(fixture.secret));
        }
        try (var fixture = new Fixture((exchange, number, request) -> json(exchange, 200,
                "{\"base_resp\":{\"status_code\":1002,\"status_msg\":\"service rejected\"}}"))) {
            assertEquals("LLM-PROVIDER_ERROR", run(fixture, Mode.BASIC).samples().get(0).code());
        }
    }

    public void testEmptyReasoningMetadataAndExplicitNullErrorDoNotInventReasoning() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            write(exchange, "data: {\"error\":null,\"choices\":[{\"delta\":{\"content\":\"  \",\"reasoning_details\":[]},\"finish_reason\":\"stop\"}]}\n\n");
        })) {
            var report = run(fixture, Mode.BASIC);
            assertEquals("LLM-EMPTY_REPLY", report.samples().get(0).code());
            assertTrue(report.text(), report.text().contains("未单独测得"));
        }
    }

    public void testStreamErrorAndOversizedSyntheticCodeHaveSpecificFailures() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            write(exchange, "data: {\"error\":{\"message\":\"upstream overloaded\"}}\n\n");
        })) { assertEquals("LLM-STREAM_REMOTE_ERROR", run(fixture, Mode.BASIC).samples().get(0).code()); }
        try (var fixture = new Fixture((exchange, number, request) -> json(exchange, 200, completion(number == 1 ? "ok"
                : new JSONObject().put("testCode", "x".repeat(262145)).toString())))) {
            assertEquals("LLM-RESPONSE_TOO_LARGE", run(fixture, Mode.GENERATION).samples().get(1).code());
        }
    }

    public void testImmediateRetryAndRedirectCannotSilentlySubmitExtraProbes() throws Exception {
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("Retry-After", "0"); json(exchange, 503, "temporary");
        })) {
            assertEquals("LLM-UPSTREAM_FAILURE", run(fixture, Mode.BASIC).samples().get(0).code());
            assertEquals(1, fixture.requests.get());
        }
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("Location", "/v1/chat/completions"); json(exchange, 307, "redirect");
        })) {
            assertEquals("LLM-REDIRECT", run(fixture, Mode.BASIC).samples().get(0).code());
            assertEquals(1, fixture.requests.get());
        }
    }

    public void testTruncatedCredentialEchoInRequestIdAndHttpErrorNeverEntersReport() throws Exception {
        String key = "private-" + "long-credential-value".repeat(24);
        try (var fixture = new Fixture((exchange, number, request) -> {
            exchange.getResponseHeaders().set("x-request-id", key);
            json(exchange, 503, "echo " + key);
        })) {
            var config = fixture.config(); config.setApiKey(key);
            var report = new LlmConnectionDiagnostics().run(config, Mode.BASIC, () -> false, null);
            assertFalse(report.text(), report.text().contains(key.substring(0, 24)));
            assertFalse(report.text(), report.text().contains("long-credential-value"));
            assertEquals("LLM-UPSTREAM_FAILURE", report.samples().get(0).code());
        }
    }

    private LlmDiagnosticReport run(Fixture fixture, Mode mode) {
        return new LlmConnectionDiagnostics().run(fixture.config(), mode, () -> false, null);
    }
    private static String generation() {
        return new JSONObject().put("testCode", "import org.junit.jupiter.api.Test; class ConnectionProbeTest { @Test void increments() {} }")
                .put("warnings", List.of()).put("mockStrategies", List.of()).toString();
    }
    private static String event(String content, String finish) {
        return "data: " + new JSONObject().put("choices", List.of(new JSONObject().put("delta", new JSONObject().put("content", content))
                .put("finish_reason", finish))) + "\n\n";
    }
    private static String completion(String content) {
        return new JSONObject().put("choices", List.of(new JSONObject().put("message", new JSONObject().put("content", content))
                .put("finish_reason", "stop"))).toString();
    }
    private static void write(HttpExchange exchange, String body) throws java.io.IOException {
        exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
    }
    private static void json(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange, int number, JSONObject request) throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final String secret = "fixture-secret-literal";
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ExecutorService executor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "diagnostic-local-fixture"); t.setDaemon(true); return t; });
        final HttpServer server;
        boolean streaming = true;
        Fixture(Handler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    handler.handle(exchange, requests.incrementAndGet(), request);
                } catch (java.io.IOException | InterruptedException expectedClosure) { }
                catch (Throwable error) { failure.set(error); }
            }); server.start();
        }
        LlmConfig config() {
            var config = new LlmConfig(); config.setEnabled(true); config.setApiKey(secret);
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setModel("local-minimax-fixture"); config.setTimeoutSeconds(5); config.setStreaming(streaming); return config;
        }
        void assertHealthy() { if (failure.get() != null) throw new AssertionError(failure.get()); }
        @Override public void close() throws Exception { server.stop(0); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }
}
