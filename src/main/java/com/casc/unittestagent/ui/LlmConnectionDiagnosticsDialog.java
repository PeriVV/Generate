package com.casc.unittestagent.ui;

import com.casc.unittestagent.llm.*;
import com.casc.unittestagent.ui.component.LlmConnectionDiagnosticsPanel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import javax.swing.*;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/** Each run owns its cancellation flag; late callbacks cannot update a new run or a closed dialog. */
public final class LlmConnectionDiagnosticsDialog extends DialogWrapper {
    private final Project project;
    private final LlmConfig settings;
    private final BooleanSupplier parentCancelled;
    private final LlmConnectionDiagnosticsPanel panel;
    private final Timer timer;
    private volatile boolean closed;
    private Run current;
    private static final class Run {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<TimedProgress> progress = new AtomicReference<>();
        boolean completed;
    }
    private record TimedProgress(LlmConnectionDiagnostics.Progress value, long observedAt) { }

    public LlmConnectionDiagnosticsDialog(@Nullable Project project, LlmConfig settings, BooleanSupplier parentCancelled) {
        super(project);
        this.project = project; this.settings = settings.copy(); this.parentCancelled = parentCancelled;
        panel = new LlmConnectionDiagnosticsPanel(this::start, () -> { if (current != null) current.cancelled.set(true); },
                text -> CopyPasteManager.getInstance().setContents(new StringSelection(text)));
        panel.setTimeoutSeconds(settings.getTimeoutSeconds());
        timer = new Timer(250, event -> {
            if (current == null || current.completed || closed) return;
            if (project != null && project.isDisposed() || parentCancelled != null && parentCancelled.getAsBoolean()) {
                doCancelAction(); return;
            }
            if (current.cancelled.get()) return;
            var timed = current.progress.get();
            if (timed != null) {
                var value = timed.value();
                long waiting = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timed.observedAt());
                panel.showProgress(value.afterWaiting(waiting));
            }
        });
        setTitle("UnitMind 大模型连接诊断"); setCancelButtonText("关闭"); init();
        Disposer.register(getDisposable(), () -> {
            closed = true; timer.stop(); if (current != null) current.cancelled.set(true);
        });
    }
    @Override protected JComponent createCenterPanel() { return panel; }
    @Override protected Action[] createActions() { return new Action[]{getCancelAction()}; }

    private void start(LlmDiagnosticReport.Mode mode) {
        if (closed || current != null && !current.completed) return;
        Run run = new Run(); current = run; panel.setRunning(true); timer.start();
        try { ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var result = new LlmConnectionDiagnostics().run(settings, mode,
                        () -> run.cancelled.get() || closed || project != null && project.isDisposed()
                                || parentCancelled != null && parentCancelled.getAsBoolean(),
                        progress -> run.progress.set(new TimedProgress(progress, System.nanoTime())));
                SwingUtilities.invokeLater(() -> {
                    if (!owns(run)) return;
                    run.completed = true; timer.stop(); panel.showReport(result);
                });
            } catch (Exception | LinkageError error) {
                SwingUtilities.invokeLater(() -> finishFailure(run, error.getClass().getSimpleName()));
            }
        }); } catch (RuntimeException schedulingFailure) { finishFailure(run, schedulingFailure.getClass().getSimpleName()); }
    }
    private void finishFailure(Run run, String type) {
        if (!owns(run)) return;
        run.completed = true; timer.stop();
        // Unexpected infrastructure errors do not export arbitrary exception messages.
        panel.showFailure("诊断未完成（" + type + "）。请重试或结合 IDEA 日志排查。未复制请求或凭据。");
    }
    private boolean owns(Run run) { return !closed && current == run && (project == null || !project.isDisposed()); }
}
