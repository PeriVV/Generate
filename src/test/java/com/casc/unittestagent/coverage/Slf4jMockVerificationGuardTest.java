package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import junit.framework.TestCase;

import java.util.List;

public class Slf4jMockVerificationGuardTest extends TestCase {
    private static final String SOURCE = """
            import lombok.extern.slf4j.Slf4j;
            @Slf4j
            class Subject {
                int run() { log.info("started {}", 1); return 7; }
            }
            """;

    public void testRemovesImpossibleLoggerVerifyWhenBusinessAssertionRemains() {
        String testCode = """
                class SubjectTest {
                    @Mock Logger logger;
                    @Test void runReturnsValue() {
                        assertEquals(7, new Subject().run());
                        verify(logger).info(anyString(), any());
                    }
                }
                """;

        Slf4jMockVerificationGuard.Result result =
                Slf4jMockVerificationGuard.inspectAndRepair(
                        SOURCE, testCode, List.of(), true);

        assertTrue(result.isDetected());
        assertTrue(result.isChanged());
        assertEquals(1, result.getRemovedVerifications());
        assertTrue(result.getCode().contains("assertEquals(7"));
        assertFalse(result.getCode().contains("verify(logger)"));
        assertFalse(result.hasBlockedMethods());
    }

    public void testKeepsFailureWhenLoggerVerifyIsOnlyOracleAndClassifiesWrongInstance() {
        String testCode = """
                class SubjectTest {
                    @Mock Logger logger;
                    @Test void logsStart() {
                        new Subject().run();
                        verify(logger).info(anyString(), any());
                    }
                }
                """;
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic();
        diagnostic.setTestId("SubjectTest#logsStart()");
        diagnostic.setCategory("MOCKITO_WANTED_BUT_NOT_INVOKED");
        diagnostic.setMessage("Wanted but not invoked; zero interactions with this mock");

        Slf4jMockVerificationGuard.Result result =
                Slf4jMockVerificationGuard.inspectAndRepair(
                        SOURCE, testCode, List.of(diagnostic), true);

        assertTrue(result.isDetected());
        assertFalse(result.isChanged());
        assertTrue(result.hasBlockedMethods());
        assertTrue(result.getCode().contains("verify(logger)"));
        assertEquals("MOCKITO_VERIFY_WRONG_INSTANCE", diagnostic.getCategory());
        assertTrue(diagnostic.getSuggestion().contains("不是同一实例"));
        assertTrue(result.blockedMessage().contains("唯一 Oracle"));
    }

    public void testDoesNotFlagInjectableLoggerField() {
        String injectableSource = """
                class Subject {
                    private final Logger logger;
                    Subject(Logger logger) { this.logger = logger; }
                    void run() { logger.info("started"); }
                }
                """;
        String testCode = """
                class SubjectTest {
                    @Mock Logger logger;
                    @InjectMocks Subject subject;
                    @Test void logsStart() { subject.run(); verify(logger).info("started"); }
                }
                """;

        Slf4jMockVerificationGuard.Result result =
                Slf4jMockVerificationGuard.inspectAndRepair(
                        injectableSource, testCode, List.of(), true);

        assertFalse(result.isDetected());
        assertFalse(result.isChanged());
    }

    public void testDoesNotFlagLoggerMethodParameter() {
        String parameterSource = """
                @Slf4j class Subject {
                    void run(Logger auditLogger) { auditLogger.info("started"); }
                }
                """;
        String testCode = """
                class SubjectTest {
                    @Mock Logger logger;
                    @Test void logsStart() {
                        new Subject().run(logger);
                        verify(logger).info("started");
                    }
                }
                """;

        Slf4jMockVerificationGuard.Result result =
                Slf4jMockVerificationGuard.inspectAndRepair(
                        parameterSource, testCode, List.of(), true);

        assertFalse(result.isDetected());
    }

    public void testDoesNotModifyUserOwnedCandidate() {
        String testCode = """
                class SubjectTest {
                    @Mock Logger logger;
                    @Test void runReturnsValue() {
                        assertEquals(7, new Subject().run());
                        verify(logger).info(anyString(), any());
                    }
                }
                """;

        Slf4jMockVerificationGuard.Result result =
                Slf4jMockVerificationGuard.inspectAndRepair(
                        SOURCE, testCode, List.of(), false);

        assertTrue(result.isDetected());
        assertFalse(result.isChanged());
        assertTrue(result.hasBlockedMethods());
    }
}
