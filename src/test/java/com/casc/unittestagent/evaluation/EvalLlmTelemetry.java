package com.casc.unittestagent.evaluation;

import com.casc.unittestagent.llm.LlmClient;
import com.casc.unittestagent.llm.LlmClientFactory;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmGenerationRequest;
import com.casc.unittestagent.llm.LlmGenerationResponse;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Counts real model calls without inspecting or persisting request contents. */
public final class EvalLlmTelemetry implements LlmClientFactory {
    private final LlmClientFactory delegate;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicLong durationMillis = new AtomicLong();
    private final AtomicReference<String> initialTestCode = new AtomicReference<>("");
    private final AtomicInteger generationRequests = new AtomicInteger();
    private final AtomicInteger compileRepairRequests = new AtomicInteger();
    private final AtomicInteger coverageExplorationRequests = new AtomicInteger();
    private final AtomicInteger coverageCompileRepairRequests = new AtomicInteger();
    private final AtomicInteger runtimeRepairRequests = new AtomicInteger();
    private final AtomicInteger classRuntimeRepairRequests = new AtomicInteger();
    private final AtomicInteger mockPlanningRequests = new AtomicInteger();
    private final AtomicLong generationDurationMillis = new AtomicLong();
    private final AtomicLong compileRepairDurationMillis = new AtomicLong();
    private final AtomicLong coverageExplorationDurationMillis = new AtomicLong();
    private final AtomicLong coverageCompileRepairDurationMillis = new AtomicLong();
    private final AtomicLong runtimeRepairDurationMillis = new AtomicLong();
    private final AtomicLong classRuntimeRepairDurationMillis = new AtomicLong();
    private final AtomicLong mockPlanningDurationMillis = new AtomicLong();

    public EvalLlmTelemetry(LlmClientFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmClient create(LlmConfig config) {
        return new CountingClient(delegate.create(config));
    }

    public int getRequests() { return requests.get(); }
    public int getErrors() { return errors.get(); }
    public long getDurationMillis() { return durationMillis.get(); }
    public String getInitialTestCode() { return initialTestCode.get(); }
    public int getGenerationRequests() { return generationRequests.get(); }
    public int getCompileRepairRequests() { return compileRepairRequests.get(); }
    public int getCoverageExplorationRequests() { return coverageExplorationRequests.get(); }
    public int getCoverageCompileRepairRequests() { return coverageCompileRepairRequests.get(); }
    public int getRuntimeRepairRequests() { return runtimeRepairRequests.get(); }
    public int getClassRuntimeRepairRequests() { return classRuntimeRepairRequests.get(); }
    public int getMockPlanningRequests() { return mockPlanningRequests.get(); }
    public long getGenerationDurationMillis() { return generationDurationMillis.get(); }
    public long getCompileRepairDurationMillis() { return compileRepairDurationMillis.get(); }
    public long getCoverageExplorationDurationMillis() { return coverageExplorationDurationMillis.get(); }
    public long getCoverageCompileRepairDurationMillis() { return coverageCompileRepairDurationMillis.get(); }
    public long getRuntimeRepairDurationMillis() { return runtimeRepairDurationMillis.get(); }
    public long getClassRuntimeRepairDurationMillis() { return classRuntimeRepairDurationMillis.get(); }
    public long getMockPlanningDurationMillis() { return mockPlanningDurationMillis.get(); }

    private final class CountingClient implements LlmClient {
        private final LlmClient client;

        private CountingClient(LlmClient client) { this.client = client; }

        @Override
        public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) throws Exception {
            LlmGenerationResponse response = call(Phase.GENERATION, () -> client.generateUnitTest(request));
            if (response != null && response.getTestCode() != null) {
                initialTestCode.compareAndSet("", response.getTestCode());
            }
            return response;
        }

        @Override
        public LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) throws Exception {
            return call(Phase.COMPILE_REPAIR, () -> client.repairUnitTest(request));
        }

        @Override
        public LlmGenerationResponse improveCoverage(LlmGenerationRequest request) throws Exception {
            return call(coveragePhase(request), () -> client.improveCoverage(request));
        }

        @Override
        public LlmGenerationResponse repairClassTest(LlmGenerationRequest request) throws Exception {
            return call(Phase.CLASS_RUNTIME_REPAIR, () -> client.repairClassTest(request));
        }

        @Override
        public LlmGenerationResponse generateMockStrategy(LlmGenerationRequest request) throws Exception {
            return call(Phase.MOCK_PLANNING, () -> client.generateMockStrategy(request));
        }

        @Override
        public int promptLength(LlmGenerationRequest request) {
            return client.promptLength(request);
        }

        private Phase coveragePhase(LlmGenerationRequest request) {
            if (request != null && request.getCompilerDiagnostics() != null
                    && !request.getCompilerDiagnostics().isEmpty()) return Phase.COVERAGE_COMPILE_REPAIR;
            if (request != null && request.getRuntimeDiagnostics() != null
                    && !request.getRuntimeDiagnostics().isEmpty()) return Phase.RUNTIME_REPAIR;
            return Phase.COVERAGE_EXPLORATION;
        }

        private LlmGenerationResponse call(Phase phase, ThrowingCall call) throws Exception {
            requests.incrementAndGet();
            increment(phase);
            long started = System.nanoTime();
            try {
                return call.run();
            } catch (Exception ex) {
                errors.incrementAndGet();
                throw ex;
            } finally {
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                durationMillis.addAndGet(elapsed);
                addDuration(phase, elapsed);
            }
        }

        private void increment(Phase phase) {
            switch (phase) {
                case GENERATION -> generationRequests.incrementAndGet();
                case COMPILE_REPAIR -> compileRepairRequests.incrementAndGet();
                case COVERAGE_EXPLORATION -> coverageExplorationRequests.incrementAndGet();
                case COVERAGE_COMPILE_REPAIR -> coverageCompileRepairRequests.incrementAndGet();
                case RUNTIME_REPAIR -> runtimeRepairRequests.incrementAndGet();
                case CLASS_RUNTIME_REPAIR -> classRuntimeRepairRequests.incrementAndGet();
                case MOCK_PLANNING -> mockPlanningRequests.incrementAndGet();
            }
        }

        private void addDuration(Phase phase, long elapsed) {
            switch (phase) {
                case GENERATION -> generationDurationMillis.addAndGet(elapsed);
                case COMPILE_REPAIR -> compileRepairDurationMillis.addAndGet(elapsed);
                case COVERAGE_EXPLORATION -> coverageExplorationDurationMillis.addAndGet(elapsed);
                case COVERAGE_COMPILE_REPAIR -> coverageCompileRepairDurationMillis.addAndGet(elapsed);
                case RUNTIME_REPAIR -> runtimeRepairDurationMillis.addAndGet(elapsed);
                case CLASS_RUNTIME_REPAIR -> classRuntimeRepairDurationMillis.addAndGet(elapsed);
                case MOCK_PLANNING -> mockPlanningDurationMillis.addAndGet(elapsed);
            }
        }
    }

    private enum Phase {
        GENERATION, COMPILE_REPAIR, COVERAGE_EXPLORATION,
        COVERAGE_COMPILE_REPAIR, RUNTIME_REPAIR, CLASS_RUNTIME_REPAIR, MOCK_PLANNING
    }

    @FunctionalInterface
    private interface ThrowingCall {
        LlmGenerationResponse run() throws Exception;
    }
}
