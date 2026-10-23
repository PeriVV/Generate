package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.*;

import com.casc.unittestagent.validation.JUnitTestInventory.Method;
import com.casc.unittestagent.validation.JUnitExecutionEvidence;
import static com.casc.unittestagent.validation.JUnitTestInventory.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Removes whole selected test groups and their proven orphan private helpers; surviving bodies and fixtures stay intact. */
public final class RunnableTestSelector {
    public record Probe(ValidationResult validation, CoverageRunResult run) { }
    @FunctionalInterface public interface Runner { Probe run(String code, boolean coverage); }
    public record Result(RunnableTestSelectionReport report, Probe finalProbe) { }


    public Result select(String original, Runner runner, BooleanSupplier cancelled, CoverageProgressListener progress) {
        var report = new RunnableTestSelectionReport(); report.setOriginalCode(original);
        report.setStatus(RunnableTestSelectionReport.Status.RUNNING);
        var listener = progress == null ? CoverageProgressListener.NONE : progress;
        try {
            List<Method> methods = methods(original);
            report.setOriginalMethods(methods.size());
            if (methods.isEmpty()) return stop(report, "未找到可识别的 JUnit 测试方法；保留原代码。", false);
            Set<String> retained = new LinkedHashSet<>(); methods.forEach(m -> retained.add(m.id()));
            // Every unsuccessful iteration must remove a method or stop. No unbounded repair loop.
            while (!retained.isEmpty()) {
                checkCancelled(cancelled);
                String code = project(original, retained);
                listener.update("正在筛选可运行用例：剩余 " + retained.size() + " / " + methods.size()
                        + " 个测试方法组", 0.1 + 0.65 * (methods.size() - retained.size()) / Math.max(1d, methods.size()));
                Probe probe = execute(runner, code, false, report, cancelled);
                if (environmentFailure(probe)) return stop(report, "执行环境或共享编译结构不可用：" + message(probe), false);
                Map<String, String> exclusions;
                if (probe.validation.getStatus() != ValidationStatus.PASSED) {
                    exclusions = compilationExclusions(code, retained, probe.validation);
                } else if (passed(probe.run.getSnapshot().getExecution())
                        && unexecutedMethods(methods, retained, probe.run.getSnapshot().getExecution()).isEmpty()) {
                    listener.update("正在对保留集合完整回归并采集实际覆盖率...", 0.85);
                    Probe measured = execute(runner, code, true, report, cancelled);
                    if (passed(measured.run.getSnapshot().getExecution())
                            && !JUnitExecutionEvidence.sameInventory(probe.run.getSnapshot().getExecution(),
                            measured.run.getSnapshot().getExecution())) {
                        return stop(report, "独立回归与覆盖率运行的测试身份或执行次数不一致，未替换原代码。", false);
                    }
                    if (measured.validation.getStatus() == ValidationStatus.PASSED
                            && passed(measured.run.getSnapshot().getExecution())
                            && unexecutedMethods(methods, retained, measured.run.getSnapshot().getExecution()).isEmpty()) {
                        report.setStatus(RunnableTestSelectionReport.Status.PASSED);
                        report.setSelectedCode(code); report.setRetainedTests(new ArrayList<>(retained));
                        report.setRemovedHelpers(com.casc.unittestagent.validation.TestSubsetProjection.project(original, retained).removedHelpers());
                        report.setMessage("保留 " + retained.size() + " / " + methods.size() + " 个测试方法组，排除 "
                                + report.getExcludedTests().size() + " 个；保留集合完整回归通过。"
                                + (measured.run.getSnapshot().isAvailable() ? "覆盖率仅来自该集合。"
                                : "覆盖率采集不可用：" + message(measured)));
                        return new Result(report, measured);
                    }
                    if (environmentFailure(measured) && measured.run.getSnapshot().getExecution().getFound() == 0) {
                        // Independent runtime proof remains valid even when the coverage agent cannot start.
                        report.setStatus(RunnableTestSelectionReport.Status.PASSED);
                        report.setSelectedCode(code); report.setRetainedTests(new ArrayList<>(retained));
                        report.setRemovedHelpers(com.casc.unittestagent.validation.TestSubsetProjection.project(original, retained).removedHelpers());
                        report.setMessage("保留 " + retained.size() + " / " + methods.size()
                                + " 个测试方法组，完整 JUnit 回归通过；覆盖率采集不可用：" + message(measured));
                        CoverageRunResult unavailable = new CoverageRunResult(); unavailable.setEnvironmentError(true);
                        unavailable.getSnapshot().setExecution(probe.run.getSnapshot().getExecution());
                        unavailable.getSnapshot().setMessage(message(measured));
                        return new Result(report, new Probe(probe.validation, unavailable));
                    }
                    // A failure appearing only under coverage must not be published as passing.
                    probe = measured;
                    exclusions = runtimeExclusions(code, methods, retained, probe.run.getSnapshot().getExecution());
                } else {
                    exclusions = runtimeExclusions(code, methods, retained, probe.run.getSnapshot().getExecution());
                }
                if (exclusions.isEmpty()) {
                    // Unknown container failures/timeouts require fresh, isolated method-group probes.
                    exclusions = isolate(original, methods, retained, runner, report, cancelled, listener);
                    if (exclusions.isEmpty()) return stop(report,
                            "单独执行与集合执行结果不一致，或失败无法归属到可移除的方法；未替换原代码。", false);
                }
                for (var excluded : exclusions.entrySet()) {
                    Method method = methods.stream().filter(m -> m.id().equals(excluded.getKey())).findFirst().orElseThrow();
                    if (!method.removable()) return stop(report, "失败涉及既有测试 " + method.id()
                            + "，筛选不能移除 UnitMind 生成区间外的测试；未替换原代码。", false);
                }
                exclusions.forEach((id, reason) -> { retained.remove(id); report.exclude(id, reason); });
            }
            return stop(report, "没有获得可运行的测试集合，原代码和排除记录已保留；不展示虚假的 0% 覆盖率。", true);
        } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
            report.setStatus(RunnableTestSelectionReport.Status.CANCELLED); report.setMessage("筛选已取消，未替换原代码。");
            return new Result(report, null);
        } catch (RuntimeException ex) {
            return stop(report, "筛选未完成：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())
                    + "；原代码保留。", false);
        }
    }

    private static Probe execute(Runner runner, String code, boolean coverage, RunnableTestSelectionReport report,
                                 BooleanSupplier cancelled) {
        checkCancelled(cancelled); report.incrementExecutions();
        Probe result = runner.run(code, coverage);
        checkCancelled(cancelled);
        if (result.run != null && result.run.isCancelled()) throw new com.intellij.openapi.progress.ProcessCanceledException();
        if (result.run != null) {
            var execution = result.run.getSnapshot().getExecution();
            SpringRuntimeFailureAnalyzer.enrich(execution.getDiagnostics(), code);
            RuntimeEvidenceArchive.preserveSourceAnalysis(execution);
            report.addRuntimeEvidenceArchive(execution.getEvidenceArchivePath());
            if (SpringRuntimeFailureAnalyzer.hasSharedFailure(execution.getDiagnostics()))
                throw new IllegalStateException("共享 Spring 上下文初始化失败，未将被阻断的方法逐个排除。"
                        + SpringRuntimeFailureAnalyzer.summary(execution.getDiagnostics())
                        + (execution.getEvidenceArchivePath().isBlank() ? "" : "；完整异常：" + execution.getEvidenceArchivePath()));
        }
        return result;
    }

    private static Map<String, String> isolate(String original, List<Method> methods, Set<String> retained,
                                             Runner runner, RunnableTestSelectionReport report,
                                             BooleanSupplier cancelled, CoverageProgressListener progress) {
        Map<String, String> failures = new LinkedHashMap<>(); int index = 0;
        Set<String> protectedMethods = new LinkedHashSet<>();
        methods.stream().filter(m -> !m.removable() && retained.contains(m.id())).forEach(m -> protectedMethods.add(m.id()));
        for (Method method : methods) {
            if (!retained.contains(method.id()) || !method.removable()) continue;
            progress.update("正在隔离检查 " + (++index) + " / " + retained.size() + "：" + method.name(), 0.75);
            Set<String> single = new LinkedHashSet<>(protectedMethods); single.add(method.id());
            Probe probe = execute(runner, project(original, single), false, report, cancelled);
            if (environmentFailure(probe)) throw new IllegalStateException("隔离执行环境不可用：" + message(probe));
            if (probe.validation.getStatus() != ValidationStatus.PASSED
                    || !passed(probe.run.getSnapshot().getExecution())) {
                failures.put(method.id(), "隔离检查未通过：" + message(probe));
            }
        }
        return failures;
    }

    private static Map<String, String> compilationExclusions(String code, Set<String> retained, ValidationResult validation) {
        var current = methods(code); Map<String, String> failures = new LinkedHashMap<>();
        for (CompileDiagnostic diagnostic : validation.getDiagnostics()) {
            if (!"ERROR".equalsIgnoreCase(diagnostic.getSeverity())) continue;
            if (!"TEST_SOURCE".equals(diagnostic.getSourceOrigin())) return Map.of();
            var candidates = current.stream().filter(m -> m.start() <= diagnostic.getLine() && m.end() >= diagnostic.getLine()).toList();
            if (candidates.size() != 1) return Map.of(); // e.g. two methods on the same source line
            Method owner = candidates.get(0);
            if (!retained.contains(owner.id())) return Map.of();
            failures.put(owner.id(), (diagnostic.getValidationStage() == ValidationStage.SOURCE_PREFLIGHT ? "预检未通过：" : "编译未通过：") + diagnostic.getCode() + " - " + diagnostic.getMessage());
        }
        return failures;
    }

    private static Map<String, String> runtimeExclusions(String code, List<Method> methods, Set<String> retained,
                                                        TestExecutionSummary execution) {
        MockitoRuntimeDiagnosticEnricher.enrich(execution.getDiagnostics(), code);
        Map<String, String> failures = new LinkedHashMap<>(); Set<String> secondary = new HashSet<>();
        for (RuntimeDiagnostic diagnostic : execution.getDiagnostics()) {
            List<Method> origins = diagnostic.getOriginLocations().stream().map(o -> resolve(methods, retained, o.testId()))
                    .filter(Objects::nonNull).filter(m -> m.removable()).distinct().toList();
            if (!origins.isEmpty()) {
                origins.forEach(m -> failures.put(m.id(), "Mockito matcher 来源：" + diagnostic.getMessage()));
                secondary.add(diagnostic.getTestId());
            }
        }
        for (TestCaseExecution test : execution.getTestCases()) {
            if (test.isSuccessful() && !test.isSkipped() || secondary.contains(test.getTestId())) continue;
            Method owner = resolve(methods, retained, test.getTestId());
            if (owner == null) return Map.of();
            String reason = execution.getDiagnostics().stream().filter(d -> test.getTestId().equals(d.getTestId()))
                    .map(d -> d.getExceptionType() + ": " + d.getMessage()).findFirst().orElse("未获得完整通过证据");
            failures.putIfAbsent(owner.id(), test.isSkipped() ? "跳过或中止，未真实执行通过" : "运行未通过：" + reason);
        }
        for (RuntimeDiagnostic diagnostic : execution.getDiagnostics()) {
            if (secondary.contains(diagnostic.getTestId())) continue;
            Method owner = resolve(methods, retained, diagnostic.getTestId());
            if (owner != null) failures.putIfAbsent(owner.id(), "运行未通过：" + diagnostic.getExceptionType() + ": " + diagnostic.getMessage());
        }
        if (passed(execution)) failures.putAll(unexecutedMethods(methods, retained, execution));
        return failures;
    }

    public static Map<String, String> unexecutedMethods(String code, TestExecutionSummary execution) {
        return JUnitExecutionEvidence.unexecutedMethods(code, execution);
    }

    /** A green process does not prove private/undiscovered methods actually ran. */
    private static Map<String, String> unexecutedMethods(List<Method> methods, Set<String> retained, TestExecutionSummary execution) {
        Set<String> observed = new HashSet<>();
        for (TestCaseExecution test : execution.getTestCases()) {
            Method method = resolve(methods, retained, test.getTestId());
            if (method != null && test.isSuccessful() && !test.isSkipped()) observed.add(method.id());
        }
        Map<String, String> missing = new LinkedHashMap<>();
        for (String id : retained) if (!observed.contains(id)) missing.put(id, "JUnit 未发现或未真实执行该方法组，缺少运行通过证据");
        return missing;
    }

    private static String project(String original, Set<String> retained) {
        return com.casc.unittestagent.validation.TestSubsetProjection.project(original, retained).code();
    }

    public static boolean passed(TestExecutionSummary execution) {
        return execution != null && execution.isFullySuccessful();
    }

    private static boolean environmentFailure(Probe probe) {
        return probe.validation.getStatus() == ValidationStatus.ENVIRONMENT_ERROR
                || probe.run != null && probe.run.isEnvironmentError();
    }
    private static String message(Probe probe) {
        if (probe.validation.getStatus() != ValidationStatus.PASSED) return probe.validation.getDiagnostics().stream()
                .findFirst().map(d -> d.getCode() + " - " + d.getMessage()).orElse("编译未通过");
        return probe.run.getSnapshot().getMessage();
    }
    private static Result stop(RunnableTestSelectionReport report, String message, boolean empty) {
        report.setStatus(empty ? RunnableTestSelectionReport.Status.NO_RUNNABLE_TESTS : RunnableTestSelectionReport.Status.BLOCKED);
        report.setMessage(message); return new Result(report, null);
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) throw new com.intellij.openapi.progress.ProcessCanceledException();
    }
}
