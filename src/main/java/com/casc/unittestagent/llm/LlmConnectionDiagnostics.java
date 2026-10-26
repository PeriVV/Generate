package com.casc.unittestagent.llm;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.casc.unittestagent.util.OperationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.casc.unittestagent.llm.LlmDiagnosticReport.*;

/** Bounded, sequential observations on synthetic inputs using the production client. */
public final class LlmConnectionDiagnostics {
    public record Progress(int request, int planned, String stage, long elapsedMs, long idleMs,
                           long receivedBytes, int events, int contentChars, String completedReport) {
        public Progress afterWaiting(long millis) {
            long wait = Math.max(0, millis);
            return new Progress(request, planned, stage, elapsedMs + wait, idleMs + wait,
                    receivedBytes, events, contentChars, completedReport);
        }
    }

    public LlmDiagnosticReport run(LlmConfig settings, Mode mode, BooleanSupplier cancelled,
                                   Consumer<Progress> listener) {
        LlmConfig config = settings.copy();
        var redactor = new DiagnosticRedactor(config.getApiKey());
        String started = Instant.now().toString();
        List<Sample> samples = new ArrayList<>();
        var operation = OperationContext.capture(cancelled);
        boolean stopped = false;
        String stopReason = "";
        for (int index = 1; index <= mode.count(); index++) {
            if (operation.isCancelled()) { stopped = true; break; }
            final int number = index;
            Probe probe = mode == Mode.GENERATION && index == 2 ? Probe.GENERATION : Probe.BASIC;
            String completed = report(config, mode, started, samples, false, "", redactor).text();
            var trace = new LlmRequestTrace(snapshot -> notify(listener, new Progress(number, mode.count(),
                    snapshot.stage().label, snapshot.elapsedMs(), snapshot.idleMs(), snapshot.bytes(),
                    snapshot.events(), snapshot.contentChars(), completed)));
            long began = System.nanoTime();
            Status status;
            String code, summary, advice;
            boolean stopPlan = false;
            Throwable failure = null;
            try {
                notify(listener, new Progress(number, mode.count(), "校验当前配置", 0, 0, 0, 0, 0, completed));
                var client = new OpenAiCompatibleLlmClient(config, operation::isCancelled, trace, mode == Mode.FRESH);
                if (probe == Probe.BASIC) client.testConnection();
                else {
                    var response = client.generateUnitTest(syntheticRequest());
                    operation.checkCancelled();
                    if (response.getTestCode().length() > 262144)
                        throw new LlmResponseFormatException("合成生成超过诊断语法检查的大小限制（256 Ki 字符）。");
                    try {
                        var unit = com.github.javaparser.StaticJavaParser.parse(response.getTestCode());
                        if (unit.getTypes().isEmpty()) throw new IllegalArgumentException();
                    } catch (RuntimeException invalidCode) {
                        throw new LlmResponseFormatException("合成生成未返回可解析的 Java 类型；生成协议尚未通过。");
                    }
                }
                operation.checkCancelled();
                var snapshot = trace.snapshot();
                status = Status.PASS; code = "LLM-OK";
                summary = probe == Probe.BASIC ? "短请求返回了可解析的聊天响应。" : "合成生成返回了可解析的 Java 类型。";
                advice = probe == Probe.BASIC ? "仅证明本次短请求可用；可继续生成诊断或波动观测。" : "只验证生成负载语法；未在项目中编译、执行，也不证明峰值或实际项目稳定。";
                if ("length".equalsIgnoreCase(snapshot.finishReason())) {
                    status = Status.WARNING; code = "LLM-PROBE_BUDGET";
                    summary = "短请求达到探测输出预算，连通可用，但未证明最终正文完整。";
                    advice = "使用合成生成诊断验证正常输出预算下的完整回复。";
                } else if (snapshot.firstContentMs() < 0) {
                    status = Status.WARNING; code = snapshot.firstReasoningMs() >= 0 ? "LLM-PROBE_REASONING_ONLY" : "LLM-EMPTY_REPLY";
                    summary = "连接有响应，但没有观测到最终正文。";
                    advice = "检查推理输出与响应协议；短探测不能视为正常生成成功。";
                } else if (snapshot.finishReason().isBlank()) {
                    status = Status.WARNING; code = "LLM-MISSING_FINISH_REASON";
                    summary = "响应可解析，但没有提供结束原因。";
                    advice = "核对服务兼容协议，不能据此确认输出完整性。";
                } else if (config.isStreaming() && !snapshot.contentType().toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                    status = Status.WARNING; code = "LLM-STREAM_NOT_OBSERVED";
                    summary += " 请求了流式，但实际返回完整 JSON。";
                    advice = "本次负载可解析；仍需检查服务/网关是否忽略或缓冲流式数据，以及长请求的等待限制。";
                }
            } catch (Exception error) {
                failure = error;
                var diagnosis = LlmFailureDiagnosis.classify(error, trace.snapshot());
                status = error instanceof ProcessCanceledException ? Status.CANCELLED : Status.FAILED;
                code = diagnosis.code(); summary = diagnosis.summary(); advice = diagnosis.advice(); stopPlan = diagnosis.stopPlan();
                if (status == Status.CANCELLED) stopped = true;
            }
            var snapshot = trace.snapshot();
            samples.add(new Sample(number, probe, status, code, redactor.detail(summary), redactor.detail(advice),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began), snapshot.httpStatus(),
                    snapshot.firstDataMs(), snapshot.firstContentMs(), metrics(snapshot, failure, redactor)));
            if (stopped) break;
            if (status == Status.FAILED && (stopPlan || mode == Mode.GENERATION)) {
                if (index < mode.count()) stopReason = "前置诊断未通过，或需先修复鉴权、限流与配置问题。";
                break;
            }
            if (mode == Mode.STABILITY && index < mode.count()) {
                String interim = report(config, mode, started, samples, false, "", redactor).text();
                notify(listener, new Progress(index + 1, mode.count(), "间隔等待（2 秒）", 0, 0, 0, 0, 0, interim));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                try {
                    while (System.nanoTime() < until) { operation.checkCancelled(); Thread.sleep(50); }
                } catch (ProcessCanceledException cancel) { stopped = true; break; }
                catch (InterruptedException cancel) { Thread.currentThread().interrupt(); stopped = true; break; }
            }
        }
        return report(config, mode, started, samples, stopped, stopReason, redactor);
    }

    private static LlmDiagnosticReport report(LlmConfig config, Mode mode, String started, List<Sample> samples,
                                              boolean cancelled, String stopReason, DiagnosticRedactor redactor) {
        String endpoint;
        try { endpoint = new OpenAiCompatibleLlmClient(config).endpointUrl(); }
        catch (RuntimeException invalid) { endpoint = config.getApiUrl(); }
        return new LlmDiagnosticReport(mode, redactor.endpoint(endpoint), redactor.detail(config.getModel()),
                config.isEnabled(), config.isStreaming(), config.isThinkingMode(), config.getTimeoutSeconds(),
                config.isThinkingMode() ? Math.max(config.getMaxTokens(), LlmConfig.THINKING_MIN_OUTPUT_TOKENS) : config.getMaxTokens(),
                started, samples, cancelled, stopReason);
    }

    private static LlmGenerationRequest syntheticRequest() {
        var request = new LlmGenerationRequest();
        request.setClassName("ConnectionProbe"); request.setMethodName("increment");
        request.setMethodSignature("public int increment(int value)");
        request.setMethodSourceCode("public int increment(int value) { return value + 1; }");
        request.setClassSourceCode("public class ConnectionProbe { public int increment(int value) { return value + 1; } }");
        request.setUserInstruction("连接诊断：仅为这个内置合成小方法生成一个简短 JUnit 5 测试类。返回正常生成协议，不使用网络、文件、外部依赖或项目源码。");
        return request;
    }

    private static List<Metric> metrics(LlmRequestTrace.Snapshot s, Throwable error, DiagnosticRedactor redactor) {
        List<Metric> items = new ArrayList<>();
        items.add(new Metric("最终接收阶段", s.stage().label));
        items.add(new Metric("DNS 耗时", time(s.dnsMs())));
        items.add(new Metric("成功建连耗时（含 TLS）", time(s.connectMs())));
        items.add(new Metric("TLS 耗时（包含在建连中）", time(s.tlsMs())));
        items.add(new Metric("发送正文耗时", time(s.uploadMs())));
        items.add(new Metric("发送完成至响应头", time(s.headersMs())));
        items.add(new Metric("首段数据 / 首推理 / 首正文（距请求开始）", time(s.firstDataMs()) + " / " + time(s.firstReasoningMs()) + " / " + time(s.firstContentMs())));
        items.add(new Metric("最长已观测数据接收间隔 / 最后数据后等待", s.maxGapMs() + " ms / " + s.idleMs() + " ms"));
        items.add(new Metric("正文接收字节 / SSE 事件 / 推理片段 / 正文字符", s.bytes() + " / " + s.events() + " / " + s.reasoningEvents() + " / " + s.contentChars()));
        items.add(new Metric("实际 Content-Type", redactor.detail(s.contentType())));
        items.add(new Metric("结束原因", redactor.detail(s.finishReason())));
        items.add(new Metric("HTTP 协议 / 连接复用 / 建连尝试 / 代理类型", redactor.detail(s.protocol()) + " / " + s.reusedConnection() + " / " + s.connectAttempts() + " / " + redactor.detail(s.proxy())));
        items.add(new Metric("服务请求 ID", redactor.detail(s.requestId())));
        items.add(new Metric("Retry-After", redactor.detail(s.retryAfter())));
        if (error != null) {
            items.add(new Metric("异常类型", error.getClass().getName()));
            items.add(new Metric("异常摘要（最多 500 字符）", redactor.detail(error.getMessage())));
        }
        return items;
    }
    private static String time(long value) { return value < 0 ? "未单独测得（未进入、未完成或连接复用）" : value + " ms"; }
    private static void notify(Consumer<Progress> listener, Progress progress) {
        if (listener != null) try { listener.accept(progress); } catch (RuntimeException ignored) { }
    }
}
