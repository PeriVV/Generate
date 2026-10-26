package com.casc.unittestagent.llm;

import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Local slow inference, with no external endpoint, credential or model charges. */
public class LlmStreamingTransportTest extends TestCase {
    public void testShortProbeSucceedsButNonStreamingGenerationTimesOut() throws Exception {
        exercise(false);
    }

    public void testStreamingKeepsSlowReasoningAliveAndReturnsOnlyCompleteFinalCode() throws Exception {
        exercise(true);
    }

    private void exercise(boolean stream) throws Exception {
        var requests = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String payload = new JSONObject().put("testCode", "class LocalTest {}")
                .put("mockStrategies", new org.json.JSONArray()).put("warnings", new org.json.JSONArray()).toString();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.incrementAndGet();
                assertEquals(stream, body.optBoolean("stream", false));
                if (body.getJSONArray("messages").getJSONObject(0).getString("content").contains("connectivity checker")) {
                    byte[] result = completion("ok").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, result.length);
                    exchange.getResponseBody().write(result);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream; charset=utf-8" : "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    for (int i = 0; i < 6; i++) {
                        if (stream) {
                            exchange.getResponseBody().write((": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                        }
                        Thread.sleep(400);
                    }
                    String response = stream ? "data: " + new JSONObject().put("choices", new org.json.JSONArray()
                            .put(new JSONObject().put("delta", new JSONObject().put("content", payload)).put("finish_reason", "stop"))) + "\n\n"
                            : completion(payload);
                    exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException clientClosed) { /* Expected on the timed-out baseline. */ }
            catch (Throwable error) { failure.set(error); }
            finally { exchange.close(); }
        });
        server.start();
        var config = new LlmConfig();
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("local-secret-never-in-diagnostics"); config.setModel("intranet-minimax-fixture");
        config.setTimeoutSeconds(2); config.setStreaming(stream);
        try {
            var client = new OpenAiCompatibleLlmClient(config);
            assertEquals("连接成功：ok", client.testConnection());
            if (stream) {
                assertEquals("class LocalTest {}", client.generateUnitTest(new LlmGenerationRequest()).getTestCode());
            } else {
                try { client.generateUnitTest(new LlmGenerationRequest()); fail(); }
                catch (java.net.SocketTimeoutException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("超时"));
                    assertTrue(expected.getMessage().contains("非流式"));
                    assertTrue(expected.getMessage().contains("2 秒"));
                    assertTrue(expected.getMessage().contains("6 秒"));
                    assertFalse(expected.getMessage().contains(config.getApiKey()));
                    assertNotNull(expected.getCause());
                }
            }
            assertEquals(2, requests.get());
            if (failure.get() != null) throw new AssertionError(failure.get());
        } finally { server.stop(0); }
    }

    private static String completion(String content) {
        return new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject()
                .put("finish_reason", "stop").put("message", new JSONObject().put("content", content)))).toString();
    }
}
