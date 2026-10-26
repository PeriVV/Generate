package com.casc.unittestagent.llm;

import java.util.List;
import java.util.Locale;

/** Detached, credential-free results suitable for showing and copying after the request has ended. */
public record LlmDiagnosticReport(Mode mode, String endpoint, String model, boolean enabled, boolean streaming, boolean thinking,
                                  int timeoutSeconds, int outputBudget, String startedAt,
                                  List<Sample> samples, boolean cancelled, String stoppedReason) {
    public enum Mode {
        BASIC("基础诊断（1 次短请求）", 1), FRESH("新连接诊断（DNS / 建连 / TLS）", 1),
        GENERATION("生成诊断（短请求 + 合成生成）", 2),
        STABILITY("波动观测（5 次短请求，间隔 2 秒）", 5);
        private final String label; private final int count;
        Mode(String label, int count) { this.label = label; this.count = count; }
        public int count() { return count; }
        @Override public String toString() { return label; }
        public String description() {
            return switch (this) {
                case BASIC -> "检查当前配置与短回复，不证明长生成可用。";
                case FRESH -> "发送 1 次短请求，不复用连接池，便于观测域名解析、建连和 TLS；系统 DNS 仍可能有缓存。";
                case GENERATION -> "发送 2 次请求；合成小方法使用正常生成协议与输出预算，验证代码负载，未在项目编译/运行。";
                case STABILITY -> "最多 5 次短请求，观察耗时与失败分布；不能据此保证长时、峰值或实际项目的稳定性。";
            } + " 可能消耗模型额度；只发送内置合成内容，不发送项目源码。";
        }
    }
    public enum Probe { BASIC, GENERATION }
    public enum Status { PASS, WARNING, FAILED, CANCELLED }
    public record Metric(String name, String value) { }
    public record Sample(int number, Probe probe, Status status, String code, String summary, String advice,
                         long elapsedMs, int httpStatus, long firstDataMs, long firstContentMs, List<Metric> metrics) {
        public Sample { metrics = List.copyOf(metrics); }
    }
    public LlmDiagnosticReport { samples = List.copyOf(samples); }

    public String text() {
        StringBuilder out = new StringBuilder("UnitMind 大模型诊断报告\n");
        out.append("开始时间（UTC）：").append(startedAt).append('\n')
                .append("模式：").append(mode).append('\n').append("地址：").append(endpoint).append('\n')
                .append("模型：").append(model).append("；生成启用大模型：").append(enabled).append("；请求流式：").append(streaming)
                .append("；思考模式：").append(thinking).append('\n')
                .append("连接/读写空闲上限：").append(timeoutSeconds).append(" 秒；单次总上限：")
                .append(3L * timeoutSeconds).append(" 秒；正常生成输出预算：").append(outputBudget).append('\n')
                .append("报告已隐藏密钥、URL 认证及查询参数，不包含请求正文或完整模型回复；仍包含主机、模型和服务请求 ID。\n")
                .append("诊断不自动重试或跟随重定向，每次仅提交一次 HTTP 请求，暴露首尝试失败；正常生成原有重试策略不变。\n")
                .append("各接收时点为客户端观测；不能仅凭等待时间证明服务端正在排队。\n")
                .append("配置取自本次设置快照；正常生成前请在设置里保存。\n\n");
        long pass = samples.stream().filter(s -> s.status() == Status.PASS).count();
        long warning = samples.stream().filter(s -> s.status() == Status.WARNING).count();
        long failed = samples.stream().filter(s -> s.status() == Status.FAILED).count();
        long finished = pass + warning + failed;
        out.append(cancelled ? "状态：已停止，保留已完成观测\n" : failed > 0 ? "状态：诊断未全部通过\n"
                        : finished < mode.count() && stoppedReason.isBlank() ? "状态：进行中，等待后续观测\n"
                        : warning > 0 ? "状态：完成，有提示\n" : "状态：完成\n")
                .append("计划 ").append(mode.count()).append(" 次；已结束 ").append(finished)
                .append(" 次；通过 ").append(pass).append("，提示 ").append(warning).append("，失败 ").append(failed).append('\n');
        if (!stoppedReason.isBlank()) out.append("后续未执行：").append(stoppedReason).append('\n');
        if (mode == Mode.STABILITY && finished > 0) {
            out.append(String.format(Locale.ROOT, "本次短请求连通成功率（含提示）：%.1f%%；已取消请求不计入分母。\n", 100d * (pass + warning) / finished));
            var successful = samples.stream().filter(s -> s.status() == Status.PASS || s.status() == Status.WARNING)
                    .mapToLong(Sample::elapsedMs).sorted().toArray();
            if (successful.length > 0) out.append("成功请求耗时 min/中位数/max：").append(successful[0]).append(" / ")
                    .append(String.format(Locale.ROOT, "%.1f", successful.length % 2 == 0
                            ? (successful[successful.length / 2 - 1] + successful[successful.length / 2]) / 2d
                            : (double) successful[successful.length / 2]))
                    .append(" / ").append(successful[successful.length - 1]).append(" ms\n");
            out.append("仅为本次最多 5 次短请求观测，不能保证长生成或峰值稳定性。\n");
        }
        for (var sample : samples) {
            out.append("\n[请求 ").append(sample.number()).append("] ")
                    .append(sample.probe() == Probe.BASIC ? "短请求" : "合成生成")
                    .append(" · ").append(sample.status()).append(" · ").append(sample.code()).append('\n')
                    .append(sample.summary()).append('\n').append("建议：").append(sample.advice()).append('\n')
                    .append("端到端耗时：").append(sample.elapsedMs()).append(" ms；HTTP：")
                    .append(sample.httpStatus() == 0 ? "未收到" : sample.httpStatus()).append('\n');
            for (var metric : sample.metrics()) out.append("  ").append(metric.name()).append("：").append(metric.value()).append('\n');
        }
        return out.toString();
    }
}
