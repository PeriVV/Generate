package com.casc.unittestagent.coverage;

import junit.framework.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Real Java parent/child workers on every OS, without a shell-specific test double. */
public class ProcessTreeLifecycleTest extends TestCase {
    public void testCleanupStopsRealJavaParentAndChild() throws Exception { exercise(false); }
    public void testCleanupWorksOnInterruptedThreadAndPreservesItsFlag() throws Exception { exercise(true); }

    private void exercise(boolean interrupted) throws Exception {
        Path root = Files.createTempDirectory("unitmind 进程 space-");
        Process process = null; ProcessHandle child = null;
        try {
            Path source = root.resolve("Worker.java");
            Files.writeString(source, """
                    class Worker {
                        public static void main(String[] args) throws Exception {
                            if (args[0].equals("child")) { Thread.sleep(30000); return; }
                            String binary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                            Process child = new ProcessBuilder(binary, "-cp", args[1], "Worker", "child").start();
                            java.nio.file.Files.writeString(java.nio.file.Path.of(args[1], "child.pid"), Long.toString(child.pid()));
                            child.waitFor();
                        }
                    }
                    """);
            LocalCoverageClosureSupport.compile(source, root, java.util.List.of());
            String binary = Path.of(System.getProperty("java.home"), "bin", BuildConfigurationProcess.isWindows() ? "java.exe" : "java").toString();
            process = new ProcessBuilder(binary, "-cp", root.toString(), "Worker", "parent", root.toString()).redirectErrorStream(true).start();
            Path pid = root.resolve("child.pid"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((!Files.exists(pid) || Files.size(pid) == 0) && System.nanoTime() < deadline) Thread.sleep(20);
            child = ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElseThrow();
            if (interrupted) Thread.currentThread().interrupt();
            ProcessCleanup.terminate(process);
            assertEquals(interrupted, Thread.currentThread().isInterrupted());
            Thread.interrupted();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertFalse(process.isAlive()); assertFalse(child.isAlive());
        } finally {
            Thread.interrupted();
            if (child != null && child.isAlive()) child.destroyForcibly();
            ProcessCleanup.terminate(process);
            CompiledTestArtifact.deleteRecursively(root);
        }
    }
}
