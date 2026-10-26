package com.casc.unittestagent.llm;

import okhttp3.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Request-scoped measurements; callbacks never contain prompts, credentials or response bodies. */
final class LlmRequestTrace extends EventListener {
    enum Stage {
        CONNECTING("建立连接"), DNS("解析域名"), TLS("TLS 握手"), UPLOAD("发送请求"),
        HEADERS("等待响应头"), FIRST_DATA("等待首段响应数据"), BODY("接收响应数据"),
        REASONING("接收推理片段"), CONTENT("接收正文"), COMPLETE("响应接收完成");
        final String label;
        Stage(String label) { this.label = label; }
    }

    record Snapshot(Stage stage, long elapsedMs, long dnsMs, long connectMs, long tlsMs, long uploadMs,
                    long headersMs, long firstDataMs, long firstReasoningMs, long firstContentMs,
                    long maxGapMs, long idleMs, long bytes, int events, int reasoningEvents, int contentChars,
                    int httpStatus, String contentType, String protocol, String requestId, String retryAfter,
                    boolean reusedConnection, int connectAttempts, String proxy, String finishReason) { }

    private final long started = System.nanoTime();
    private final Consumer<Snapshot> observer;
    private Stage stage = Stage.CONNECTING;
    private long dnsStart, connectStart, tlsStart, uploadStart, sentAt, lastData, endedAt, lastPublished;
    private long dnsMs = -1, connectMs = -1, tlsMs = -1, uploadMs = -1, headersMs = -1;
    private long firstDataMs = -1, firstReasoningMs = -1, firstContentMs = -1, maxGapMs, bytes;
    private int events, reasoningEvents, contentChars, httpStatus, connectAttempts;
    private String contentType = "", protocol = "", requestId = "", retryAfter = "", proxy = "未观测", finishReason = "";
    private boolean reusedConnection;

    LlmRequestTrace(Consumer<Snapshot> observer) { this.observer = observer; }
    private static long ms(long nanos) { return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos)); }

    synchronized Snapshot snapshot() {
        long now = endedAt == 0 ? System.nanoTime() : endedAt;
        long idleStart = lastData > 0 ? lastData : sentAt > 0 ? sentAt : started;
        return new Snapshot(stage, ms(now - started), dnsMs, connectMs, tlsMs, uploadMs, headersMs,
                firstDataMs, firstReasoningMs, firstContentMs, maxGapMs, ms(now - idleStart), bytes,
                events, reasoningEvents, contentChars, httpStatus, contentType, protocol, requestId,
                retryAfter, reusedConnection, connectAttempts, proxy, finishReason);
    }

    private void publish(boolean force) {
        if (observer == null) return;
        synchronized (this) {
            long now = System.nanoTime();
            if (!force && now - lastPublished < TimeUnit.MILLISECONDS.toNanos(100)) return;
            lastPublished = now;
        }
        try { observer.accept(snapshot()); } catch (RuntimeException ignored) { /* Observers cannot break I/O. */ }
    }

    void bodyBytes(long count) {
        synchronized (this) {
            long now = System.nanoTime();
            if (firstDataMs < 0) firstDataMs = ms(now - started);
            if (lastData > 0) maxGapMs = Math.max(maxGapMs, ms(now - lastData));
            lastData = now; bytes += count;
            if (stage == Stage.FIRST_DATA || stage == Stage.HEADERS) stage = Stage.BODY;
        }
        publish(false);
    }

    void sseEvent(int chars, boolean hasReasoning, boolean hasContent, String finish) {
        synchronized (this) {
            long now = System.nanoTime(); events++;
            if (hasReasoning) { reasoningEvents++; if (firstReasoningMs < 0) firstReasoningMs = ms(now - started); stage = Stage.REASONING; }
            if (hasContent) { if (firstContentMs < 0) firstContentMs = ms(now - started); stage = Stage.CONTENT; }
            contentChars = chars;
            if (finish != null && !finish.isBlank()) finishReason = finish;
        }
        publish(false);
    }

    void jsonCompletion(String body) {
        try {
            var choice = new org.json.JSONObject(body).optJSONArray("choices");
            if (choice == null || choice.length() == 0) return;
            var first = choice.optJSONObject(0);
            if (first == null) return;
            var message = first.optJSONObject("message");
            String text = message == null ? "" : ChatCompletionStream.text(message.opt("content"));
            var details = message == null ? null : message.optJSONArray("reasoning_details");
            boolean reasoning = message != null && (!message.optString("reasoning_content", "").isBlank()
                    || details != null && details.length() > 0);
            synchronized (this) { finishReason = first.optString("finish_reason", ""); }
            // Buffered JSON exposes content only after the complete body has arrived.
            synchronized (this) {
                contentChars = text.length();
                if (!text.isBlank() && firstContentMs < 0) firstContentMs = ms(System.nanoTime() - started);
                if (reasoning && firstReasoningMs < 0) firstReasoningMs = ms(System.nanoTime() - started);
                if (reasoning) reasoningEvents++;
            }
        } catch (org.json.JSONException ignored) { /* The normal parser diagnoses malformed responses. */ }
    }

    void finish(boolean completed) {
        synchronized (this) { endedAt = System.nanoTime(); if (completed) stage = Stage.COMPLETE; }
        publish(true);
    }

    @Override public void dnsStart(Call call, String domainName) {
        synchronized (this) { stage = Stage.DNS; dnsStart = System.nanoTime(); } publish(true);
    }
    @Override public void dnsEnd(Call call, String domainName, List<InetAddress> addresses) {
        synchronized (this) { dnsMs = Math.max(0, dnsMs) + ms(System.nanoTime() - dnsStart); }
    }
    @Override public void connectStart(Call call, InetSocketAddress address, Proxy selectedProxy) {
        synchronized (this) { stage = Stage.CONNECTING; connectStart = System.nanoTime(); connectAttempts++; proxy = selectedProxy.type().name(); } publish(true);
    }
    @Override public void secureConnectStart(Call call) {
        synchronized (this) { stage = Stage.TLS; tlsStart = System.nanoTime(); } publish(true);
    }
    @Override public void secureConnectEnd(Call call, Handshake handshake) {
        synchronized (this) { tlsMs = Math.max(0, tlsMs) + ms(System.nanoTime() - tlsStart); }
    }
    @Override public void connectEnd(Call call, InetSocketAddress address, Proxy selectedProxy, Protocol negotiated) {
        synchronized (this) { connectMs = Math.max(0, connectMs) + ms(System.nanoTime() - connectStart); }
    }
    @Override public void connectionAcquired(Call call, Connection connection) {
        synchronized (this) { reusedConnection = connectAttempts == 0; protocol = connection.protocol().toString(); }
    }
    @Override public void requestBodyStart(Call call) {
        synchronized (this) { stage = Stage.UPLOAD; uploadStart = System.nanoTime(); } publish(true);
    }
    @Override public void requestBodyEnd(Call call, long count) {
        synchronized (this) { uploadMs = ms(System.nanoTime() - uploadStart); sentAt = System.nanoTime(); stage = Stage.HEADERS; } publish(true);
    }
    @Override public void responseHeadersEnd(Call call, Response response) {
        synchronized (this) {
            if (sentAt > 0) headersMs = ms(System.nanoTime() - sentAt);
            httpStatus = response.code(); contentType = limited(response.header("Content-Type", ""));
            requestId = limited(response.header("x-request-id", response.header("request-id", response.header("x-trace-id", ""))));
            retryAfter = limited(response.header("Retry-After", "")); stage = Stage.FIRST_DATA;
        }
        publish(true);
    }
    private static String limited(String text) { return text.replaceAll("[\\r\\n\\p{Cntrl}]", " ").substring(0, Math.min(text.length(), 160)); }
}
