package com.casc.unittestagent.dependency;

import junit.framework.TestCase;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MavenDependencyImporterTest extends TestCase {
    public void testSelectedPomIsRegisteredAndSuccessWaitsForActualModuleImport() {
        var backend = new FakeBackend();
        var pom = Path.of("工作区 with space", "module", "pom.xml");
        var result = new MavenDependencyImporter(backend).importPom(pom);
        assertEquals(pom, backend.target);
        assertEquals(List.of("register", "read"), backend.calls);
        assertEquals(Promise.State.PENDING, result.getState());
        backend.read.setResult(null);
        assertEquals(List.of("register", "read", "resolve"), backend.calls);
        backend.resolve.setResult(null);
        assertEquals(List.of("register", "read", "resolve", "import"), backend.calls);
        assertEquals(Promise.State.PENDING, result.getState());
        backend.imported.setResult(null);
        assertEquals(Promise.State.SUCCEEDED, result.getState());
    }

    public void testReadFailureIsReportedAndDoesNotClaimImportSuccess() {
        var backend = new FakeBackend(); var failure = new AtomicReference<Throwable>();
        var result = new MavenDependencyImporter(backend).importPom(Path.of("pom.xml"));
        result.onError(failure::set);
        backend.read.setError(new IllegalStateException("repository unavailable"));
        assertEquals(Promise.State.REJECTED, result.getState());
        assertEquals("repository unavailable", failure.get().getMessage());
        assertEquals(List.of("register", "read"), backend.calls);
    }

    public void testResolveCancellationDoesNotLeaveRequestPending() {
        var backend = new FakeBackend(); var failure = new AtomicReference<Throwable>();
        var result = new MavenDependencyImporter(backend).importPom(Path.of("pom.xml"));
        result.onError(failure::set); backend.read.setResult(null); backend.resolve.cancel();
        assertEquals(Promise.State.REJECTED, result.getState());
        assertNotNull(failure.get());
        assertEquals(List.of("register", "read", "resolve"), backend.calls);
    }

    public void testLegacyIdeWaitsForItsSeparateImportPromise() {
        var manager = new LegacyManager(); var imported = MavenDependencyImporter.awaitLegacyImport(manager);
        assertSame(manager.imported, imported);
        assertEquals(Promise.State.PENDING, imported.getState());
        manager.imported.setResult(null); assertEquals(Promise.State.SUCCEEDED, imported.getState());
    }

    public void testModernIdeDoesNotRequireRemovedLegacyWaitApi() {
        assertEquals(Promise.State.SUCCEEDED, MavenDependencyImporter.awaitLegacyImport(new Object()).getState());
    }

    public static class LegacyManager {
        final AsyncPromise<Void> imported = new AsyncPromise<>();
        public Promise<Void> waitForImportCompletion() { return imported; }
    }

    private static class FakeBackend implements MavenDependencyImporter.Backend {
        final List<String> calls = new ArrayList<>();
        final AsyncPromise<Void> read = new AsyncPromise<>(), resolve = new AsyncPromise<>(), imported = new AsyncPromise<>();
        Path target;
        @Override public void register(Path pom) { target = pom; calls.add("register"); }
        @Override public Promise<?> read() { calls.add("read"); return read; }
        @Override public Promise<?> resolve() { calls.add("resolve"); return resolve; }
        @Override public Promise<?> imported() { calls.add("import"); return imported; }
    }
}
