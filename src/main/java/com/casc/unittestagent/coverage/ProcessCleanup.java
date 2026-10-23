package com.casc.unittestagent.coverage;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded cleanup for an interrupted or timed out worker, including its current child processes. */
final class ProcessCleanup {
    private ProcessCleanup() { }

    static void terminate(Process process) {
        if (process == null || !process.isAlive()) return;
        // Cleanup must still wait when the worker thread arrived here already interrupted.
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> children = process.descendants().toList();
        try {
            children.forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                process.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
