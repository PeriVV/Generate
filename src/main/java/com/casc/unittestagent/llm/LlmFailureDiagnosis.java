package com.casc.unittestagent.llm;

import com.intellij.openapi.progress.ProcessCanceledException;
import java.io.InterruptedIOException;
import java.net.*;
import javax.net.ssl.SSLException;

/** Distinguish observed facts from possible causes; HTTP timeouts are not client deadlines. */
final class LlmFailureDiagnosis {
    record Diagnosis(String code, String summary, String advice, boolean stopPlan) { }
    static Diagnosis classify(Throwable error, LlmRequestTrace.Snapshot trace) {
        if (has(error, ProcessCanceledException.class)) return d("CANCELLED", "诊断已停止。", "已保留之前的观测，不按服务失败统计。", true);
        int status = trace.httpStatus();
        String message = messages(error).toLowerCase(java.util.Locale.ROOT);
        if (status == 401) return d("AUTHENTICATION", "服务返回 401：鉴权未通过。", "检查环境变量优先级、Key 是否有效，以及网关要求的认证方式。", true);
        if (status == 403) return d("ACCESS_DENIED", "服务返回 403：访问被拒绝。", "核对模型权限、IP 白名单、账号与内网访问策略。", true);
        if (status == 429) return d("RATE_LIMIT", "服务返回 429：限流或额度限制。", "查看 Retry-After、并发与额度；已停止后续探测，避免继续加压。", true);
        if (status == 407) return d("PROXY_AUTHENTICATION", "代理返回 407：代理认证未通过。", "核对实际代理与代理凭据；它不等同于模型 API Key 无效。", true);
        if (status >= 300 && status < 400) return d("REDIRECT", "服务返回 HTTP " + status + " 重定向。", "诊断不自动跟随跳转；核对最终模型接口地址、网关路由及认证跳转。", true);
        if (status == 404) return d(message.contains("model") ? "MODEL_NOT_FOUND" : "ENDPOINT_NOT_FOUND",
                "服务返回 404：模型或接口路径不存在。", "对照内网服务确认模型标识及 chat/completions 路径；不能仅凭 404 判断网络不通。", true);
        if (status == 400 || status == 422) return d("REQUEST_REJECTED", "服务拒绝请求参数（HTTP " + status + "）。",
                "核对模型支持的 stream、输出预算和参数；若提示不支持流式，可在设置里关闭后重测。", true);
        if (status == 413) return d("REQUEST_TOO_LARGE", "服务返回 413：请求超出限制。", "核对网关请求大小与模型上下文限制。", true);
        if (status == 408 || status == 504) return d("GATEWAY_TIMEOUT", "收到 HTTP " + status + "：服务端或网关等待超时。",
                "按请求 ID 对照网关与推理服务日志，核查排队、首响应和固定总时限；这不是客户端超时异常。", false);
        if (status >= 500) return d("UPSTREAM_FAILURE", "服务返回 HTTP " + status + "。",
                "核对网关上游健康、推理服务容量及排队情况；波动观测可帮助确认是否偶发。", false);
        if (status >= 400) return d("HTTP_ERROR", "服务返回 HTTP " + status + "。", "按响应状态与请求 ID 检查服务配置。", false);
        if (error instanceof LlmProviderResponseException provider) {
            String code = provider.code().toLowerCase(java.util.Locale.ROOT);
            boolean stop = code.contains("auth") || code.contains("api_key") || code.contains("rate") || code.contains("quota");
            return d("PROVIDER_ERROR", "HTTP 成功，但服务在正文中返回错误负载。",
                    "查看脱敏异常摘要中的服务错误码与消息，并按请求 ID 核对鉴权、额度或模型服务状态。", stop);
        }
        if (message.contains("llm stream error:")) return d("STREAM_REMOTE_ERROR", "HTTP 响应之后，流内返回了服务错误。",
                "按请求 ID 和脱敏异常摘要排查上游服务；不能把它等同于网络连接失败。", false);
        if (has(error, UnknownHostException.class)) return d("DNS_FAILURE", "域名解析失败，尚未确认模型服务可达。", "检查内网 DNS、VPN、域名拼写与代理配置。", false);
        if (totalTimeout(error)) return d("TOTAL_DEADLINE", "单次请求总时间已达到客户端上限。",
                "持续心跳也不能延长总期限；查看是否只有推理或无正文，再核对输出预算、服务速度与总时限。", false);
        if (has(error, SocketTimeoutException.class)) {
            return switch (trace.stage()) {
                case CONNECTING, DNS, TLS -> d("CONNECT_TIMEOUT", "建立连接或握手等待超时。", "核对网络、代理、监听端口与 TLS 阶段。", false);
                case UPLOAD -> d("UPLOAD_TIMEOUT", "发送请求时超时。", "检查网络吞吐、代理及服务接收请求的状态。", false);
                case HEADERS -> d("HEADER_TIMEOUT", "请求已发出，但响应头等待超时。", "可能涉及排队或网关缓冲；需对照服务日志，不能据此认定模型已开始生成。", false);
                default -> trace.firstDataMs() < 0
                        ? d("FIRST_DATA_TIMEOUT", "已收到响应头，但首段响应数据等待超时。", "核对推理排队、首响应等待及网关是否缓冲流式数据；可比较空闲上限与网关时限。", false)
                        : d("STREAM_IDLE_TIMEOUT", "收到过响应数据，但后续数据空闲超时。", "查看最后活动、推理/正文数量与最大接收间隔；排查中途停顿和网关转发。", false);
            };
        }
        if (has(error, SSLException.class)) return d("TLS_FAILURE", "TLS 握手或证书验证失败。", "检查内网 CA、证书链、主机名和有效期；不要以关闭证书校验代替修复。", true);
        if (has(error, ConnectException.class) || has(error, NoRouteToHostException.class))
            return d("CONNECT_FAILURE", "TCP 连接失败。", "检查服务是否监听、主机端口、防火墙、VPN 与代理路由。", false);
        if (error instanceof LlmOutputTruncatedException || "length".equalsIgnoreCase(trace.finishReason()))
            return d("OUTPUT_TRUNCATED", "模型输出达到预算上限，生成负载不完整。", "检查推理占用与输出预算，不要把截断回复当作可用测试代码。", false);
        if (message.contains("partial output discarded") || message.contains("finish_reason"))
            return d("INCOMPLETE_STREAM", "响应流在完整结束前中断，部分输出已丢弃。", "对照请求 ID 检查网关断流、上游重启与流式转发；没有把部分代码当作成功。", false);
        if (message.contains("推理内容") || trace.firstReasoningMs() >= 0 && trace.firstContentMs() < 0)
            return d("REASONING_ONLY", "服务只返回推理，没有可用正文。", "检查推理预算、模型输出协议与网关是否丢弃 content；短连接可用不等于生成成功。", false);
        if (message.contains("大小限制")) return d("RESPONSE_TOO_LARGE", "响应超过客户端安全大小上限。", "核查异常大响应或重复片段；报告不保留完整响应。", true);
        if (message.contains("合成生成")) return d("GENERATION_PAYLOAD", "合成生成未返回有效 Java 负载。", "连接与生成负载需要分别判断；检查模型协议及指令遵循，本项未在项目编译/运行。", false);
        if (trace.httpStatus() >= 200 && trace.httpStatus() < 300 && trace.bytes() == 0 && error instanceof LlmResponseFormatException)
            return d("EMPTY_RESPONSE", "HTTP 成功但响应正文为空。", "核对网关是否丢弃回复、模型是否返回内容，以及实际响应类型。", false);
        if (error instanceof LlmResponseFormatException) return d("RESPONSE_FORMAT", "响应不符合预期协议。",
                "检查 Content-Type、choices/message/content、SSE 帧，以及是否返回了代理 HTML 或错误 JSON。", false);
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException)
            return d("CONFIGURATION", "配置校验未通过，或地址无法构造。", "检查 http/https 地址、模型与有效凭据；测试使用本次设置快照。", true);
        if (has(error, java.io.IOException.class)) return d("CONNECTION_INTERRUPTED", "传输失败或连接中断。", "对照阶段、请求 ID 和接收记录检查内网连接及服务日志。", false);
        return d("UNEXPECTED_ERROR", "诊断未能完成。", "保留报告中的异常类型和阶段，结合 IDEA 日志进一步排查。", false);
    }
    private static Diagnosis d(String code, String summary, String advice, boolean stop) { return new Diagnosis("LLM-" + code, summary, advice, stop); }
    private static boolean has(Throwable error, Class<?> type) {
        for (int i = 0; error != null && i < 12; i++, error = error.getCause()) if (type.isInstance(error)) return true;
        return false;
    }
    private static boolean totalTimeout(Throwable error) {
        for (int i = 0; error != null && i < 12; i++, error = error.getCause())
            if (error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException)
                    && error.getMessage() != null && error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout")) return true;
        return false;
    }
    private static String messages(Throwable error) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; error != null && i < 12; i++, error = error.getCause()) out.append(' ').append(error.getMessage());
        return out.toString();
    }
}
