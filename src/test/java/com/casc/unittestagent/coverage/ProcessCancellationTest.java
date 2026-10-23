package com.casc.unittestagent.coverage;

import com.intellij.openapi.progress.ProcessCanceledException;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real worker/child processes prove cancellation does not leave writers behind in deleted sandboxes. */
public class ProcessCancellationTest extends TestCase {
    public void testInterruptedCompilerCleansUpItsProcessTreeAndPreservesCancellation() throws Exception {
        exercise(true, false, false);
    }

    public void testInterruptedVersionProbeCleansUpAndPropagatesCancellation() throws Exception {
        exercise(true, false, true);
    }

    public void testCancelledJUnitWorkerCleansUpItsProcessTree() throws Exception {
        exercise(false, false, false);
    }

    public void testTimedOutJUnitWorkerCleansUpItsProcessTree() throws Exception {
        exercise(false, true, false);
    }

    private void exercise(boolean compiler, boolean timeout, boolean versionProbe) throws Exception {
        if (LocalCoverageClosureSupport.isWindows()) return;
        Path root = Files.createTempDirectory("unitmind-process-cleanup-");
        ProcessHandle parent = null;
        ProcessHandle child = null;
        Thread task = null;
        try {
            Path bin = Files.createDirectories(root.resolve("jdk/bin"));
            Path java = bin.resolve("java");
            String script = "#!/bin/sh\n"
                    + "printf '%s\\n' $$ > " + quote(root.resolve("parent.pid")) + "\n"
                    + "sleep 30 &\n"
                    + "printf '%s\\n' $! > " + quote(root.resolve("child.pid")) + "\nwait\n";
            Files.writeString(java, script);
            Files.writeString(bin.resolve("javac"), script);
            assertTrue(java.toFile().setExecutable(true));
            assertTrue(bin.resolve("javac").toFile().setExecutable(true));
            Path sources = Files.createDirectories(root.resolve("source"));
            Path classes = Files.createDirectories(root.resolve("classes"));
            Path source = sources.resolve("Subject.java");
            Files.writeString(source, "class Subject {}");
            var cancelled = new AtomicBoolean();
            var cancellationPreserved = new AtomicBoolean();
            var failure = new AtomicReference<Throwable>();
            var execution = new AtomicReference<CoverageRunResult>();
            var environment = new ProjectTestEnvironment();
            environment.javaExecutable = java.toString();
            var artifact = new CompiledTestArtifact(root, classes, "SubjectTest", "Subject",
                    java.toString(), List.of(), List.of());
            task = new Thread(() -> {
                try {
                    if (compiler) {
                        if (versionProbe) TestSandboxCompiler.detectJavacMajorVersion(bin.resolve("javac").toString());
                        else new TestSandboxCompiler().compileTargetWithModuleJdk(environment, sources, classes, source, "Subject");
                        failure.set(new AssertionError("compiler interruption must propagate cancellation"));
                    } else {
                        var config = new CoverageConfig();
                        config.setExecutionTimeoutSeconds(timeout ? 1 : 30);
                        execution.set(new JacocoCoverageExecutor().executeTestsOnly(artifact, config, cancelled::get));
                    }
                } catch (ProcessCanceledException expected) {
                    cancellationPreserved.set(Thread.currentThread().isInterrupted());
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "unitmind-cancellation-regression");
            task.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(root.resolve("child.pid")) || Files.size(root.resolve("child.pid")) == 0)
                    && System.nanoTime() < deadline) Thread.sleep(20);
            parent = ProcessHandle.of(Long.parseLong(Files.readString(root.resolve("parent.pid")).trim())).orElseThrow();
            child = ProcessHandle.of(Long.parseLong(Files.readString(root.resolve("child.pid")).trim())).orElseThrow();
            if (compiler) task.interrupt();
            else if (!timeout) cancelled.set(true);
            task.join(10000);
            assertFalse("cancelled worker must finish promptly", task.isAlive());
            if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
            if (compiler) assertTrue("interrupted compilation must retain cancellation semantics", cancellationPreserved.get());
            else if (timeout) assertTrue(execution.get().getSnapshot().getExecution().isTimedOut());
            else assertTrue(execution.get().isCancelled());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((parent.isAlive() || child.isAlive()) && System.nanoTime() < deadline) Thread.sleep(20);
            assertFalse("worker process survived cancellation", parent.isAlive());
            assertFalse("child process survived cancellation", child.isAlive());
        } finally {
            if (parent != null && parent.isAlive()) parent.destroyForcibly();
            if (child != null && child.isAlive()) child.destroyForcibly();
            if (task != null && task.isAlive()) { task.interrupt(); task.join(3000); }
            CompiledTestArtifact.deleteRecursively(root);
        }
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
