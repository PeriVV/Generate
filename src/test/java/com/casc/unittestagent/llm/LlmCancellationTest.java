package com.casc.unittestagent.llm;

import com.intellij.openapi.progress.ProcessCanceledException;
import junit.framework.TestCase;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A real stalled response proves cancellation closes HTTP I/O, including response-body reads. */
public class LlmCancellationTest extends TestCase {
    public void testCancellationClosesStalledResponseBodyPromptly() throws Exception {
        exercise(false);
    }

    public void testConnectionProbeUsesTheSameCancellationContract() throws Exception {
        exercise(true);
    }

    public void testTotalBudgetBoundsAContinuousResponseWithoutReportingCancellation() throws Exception {
        exercise(false, true);
    }

    private void exercise(boolean probe) throws Exception {
        exercise(probe, false);
    }

    private void exercise(boolean probe, boolean continuousResponse) throws Exception {
        var cancelled = new AtomicBoolean();
        var deadlineObserved = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var socket = new AtomicReference<Socket>();
        var started = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var host = new Thread(() -> {
                try {
                    var connection = server.accept(); socket.set(connection);
                    connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 99999\r\n\r\n{".getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush(); started.countDown();
                    while (continuousResponse && !cancelled.get()) {
                        connection.getOutputStream().write(' ');
                        connection.getOutputStream().flush();
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) { }
            });
            host.setDaemon(true); host.start();
            var config = new LlmConfig();
            config.setApiUrl("http://127.0.0.1:" + server.getLocalPort() + "/chat/completions");
            config.setApiKey("local-fixture"); config.setModel("fixture");
            config.setTimeoutSeconds(continuousResponse ? 2 : 30);
            var client = new OpenAiCompatibleLlmClient(config, cancelled::get);
            var worker = new Thread(() -> {
                try {
                    if (probe) client.testConnection(); else client.generateUnitTest(new LlmGenerationRequest());
                    failure.set(new AssertionError("cancelled HTTP must not return a successful response"));
                } catch (ProcessCanceledException expected) {
                    if (continuousResponse) failure.set(expected);
                } catch (java.io.InterruptedIOException expected) {
                    if (continuousResponse && !cancelled.get()) deadlineObserved.set(true);
                    else failure.set(expected);
                }
                catch (Throwable error) { failure.set(error); }
            });
            long began = System.nanoTime();
            worker.setDaemon(true); worker.start();
            try {
                assertTrue("request never reached the local server", started.await(5, TimeUnit.SECONDS));
                if (!continuousResponse) cancelled.set(true);
                worker.join(continuousResponse ? 10000 : 3000);
                assertFalse("HTTP body read survived task cancellation", worker.isAlive());
                if (continuousResponse) {
                    assertTrue("total deadline must stop a continuously streaming body", deadlineObserved.get());
                    assertTrue("the total budget must not replace a single IO-phase deadline",
                            System.nanoTime() - began >= TimeUnit.SECONDS.toNanos(4));
                }
                if (failure.get() != null) throw new AssertionError(failure.get());
            } finally {
                cancelled.set(true);
                if (socket.get() != null) socket.get().close();
                worker.interrupt(); worker.join(1000); host.join(1000);
            }
        }
    }
}
