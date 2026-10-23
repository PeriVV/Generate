package com.casc.unittestagent.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Cancellation belongs to the initiating task, including when observed on an HTTP watcher thread. */
public final class OperationContext {
    private static final ScheduledThreadPoolExecutor WATCHERS = watchers();
    private final BooleanSupplier cancelled;
    private final ProgressIndicator indicator;
    private final Thread owner;

    private OperationContext(BooleanSupplier cancelled) {
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.owner = Thread.currentThread();
        this.indicator = ApplicationManager.getApplication() == null ? null
                : ProgressManager.getInstance().getProgressIndicator();
    }

    public static OperationContext capture(BooleanSupplier cancelled) { return new OperationContext(cancelled); }

    public boolean isCancelled() {
        try {
            return owner.isInterrupted() || indicator != null && indicator.isCanceled() || cancelled.getAsBoolean();
        } catch (ProcessCanceledException ex) { return true; }
    }

    public void checkCancelled() { if (isCancelled()) throw new ProcessCanceledException(); }

    /** Returns false only for timeout; cancellation is never converted into a compiler/environment error. */
    public boolean waitFor(Process process, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            checkCancelled();
            if (process.waitFor(50, TimeUnit.MILLISECONDS)) { checkCancelled(); return true; }
            if (System.nanoTime() >= deadline) { checkCancelled(); return false; }
        }
    }

    public Registration watchProgressCancellation() {
        return indicator == null ? () -> { } : watch(indicator::cancel);
    }

    public Registration watch(Runnable cancelResource) {
        checkCancelled();
        var future = WATCHERS.scheduleWithFixedDelay(() -> {
            if (isCancelled()) cancelResource.run();
        }, 0, 50, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable { @Override void close(); }

    private static ScheduledThreadPoolExecutor watchers() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "unitmind-cancellation");
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(1, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
