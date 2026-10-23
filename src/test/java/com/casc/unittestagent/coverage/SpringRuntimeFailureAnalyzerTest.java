package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.*;
import junit.framework.TestCase;
import java.util.*;

public class SpringRuntimeFailureAnalyzerTest extends TestCase {
    static RuntimeDiagnostic primary(String id, String context) {
        return JacocoCoverageExecutor.runtimeDiagnostic(id, "java.lang.IllegalStateException", "Failed to load ApplicationContext for " + context(context),
                "java.lang.IllegalStateException: Failed to load ApplicationContext\n"
                + "\tat org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate.loadContext(DefaultCacheAwareContextLoaderDelegate.java:180)\n"
                + "Caused by: org.springframework.util.PlaceholderResolutionException: Could not resolve placeholder 'service.name' in value \"${service.name}\"\n"
                + "\tat org.springframework.util.PropertyPlaceholderHelper.replacePlaceholders(PropertyPlaceholderHelper.java:100)");
    }
    private static String context(String value) { return "[MergedContextConfiguration@12345 testClass = sample.T, classes = [sample." + value + "], contextCustomizers = [Customizer@abcdef]]"; }
    private static RuntimeDiagnostic secondary(String id, String context) {
        return JacocoCoverageExecutor.runtimeDiagnostic(id, "java.lang.IllegalStateException", "ApplicationContext failure threshold (1) exceeded: skipping repeated attempt to load context for " + context(context).replace("@12345", "@98765"),
                "java.lang.IllegalStateException: ApplicationContext failure threshold (1) exceeded\n"
                + "\tat org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate.loadContext(DefaultCacheAwareContextLoaderDelegate.java:145)");
    }
    public void testThresholdLinksOnlyToMatchingPrimaryAndKeepsOriginalEvidence() {
        var primary = primary("sample.T#first()", "One"); var secondary = secondary("sample.T#second()", "One");
        var unrelated = secondary("sample.Other#third()", "Two");
        String original = secondary.getFailureDetails();
        MockitoRuntimeDiagnosticEnricher.enrich(List.of(secondary, primary, unrelated), "class T {}");
        for (var d : List.of(primary, secondary, unrelated)) {
            assertEquals(SpringRuntimeFailureAnalyzer.CATEGORY, d.getCategory());
            assertEquals(RuntimeFailurePhase.TEST_SETUP, d.getFailurePhase()); assertTrue(d.isSharedContextFailure());
        }
        assertEquals(primary.getTestId(), secondary.getPrimaryFailureTestId());
        assertEquals("", unrelated.getPrimaryFailureTestId());
        assertEquals(original, secondary.getFailureDetails());
        assertFalse(secondary.getRootCauseMessage().contains("Could not resolve")); // no fabricated cause
    }
    public void testLiteralPropertyValuesContainingHexAtSignNeverBecomeObjectIdentities() {
        var first=primary("sample.T#first()","One");var next=secondary("sample.T#next()","One");
        first.setMessage(first.getMessage().replace("classes =", "propertySourceProperties = [\"user=user@abcdef\"], classes ="));
        next.setMessage(next.getMessage().replace("classes =", "propertySourceProperties = [\"user=user@fedcba\"], classes ="));
        SpringRuntimeFailureAnalyzer.enrich(List.of(first,next));
        assertFalse(first.getContextKey().equals(next.getContextKey()));assertTrue(next.getPrimaryFailureTestId().isBlank());
    }

    public void testProgrammaticContextRequiresActualSetupFrameAndAbsenceOfTestBodyFrame() {
        String code="package sample; class T { @org.junit.jupiter.api.BeforeEach void setup() {} @org.junit.jupiter.api.Test void body() {} }";
        var setup=primary("sample.T#body()","One");setup.setExceptionType("org.springframework.beans.factory.BeanCreationException");
        setup.setMessage("Error creating bean");setup.setFailureDetails("bean failed");
        setup.setStackTrace(List.of("at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:600)","at sample.T.setup(T.java:1)"));
        MockitoRuntimeDiagnosticEnricher.enrich(List.of(setup),code);
        assertTrue(setup.isSharedContextFailure());assertEquals(RuntimeFailurePhase.TEST_SETUP,setup.getFailurePhase());
        assertTrue(setup.getContextKey().startsWith("fixture:"));
        SpringFixtureAnalyzer.enrich(List.of(SpringFixtureAnalyzerTest.SOURCE),List.of(setup));
        assertFalse(SpringFixtureAnalyzer.allowsPropertyRepair(List.of(setup))); // @TestPropertySource alone cannot configure a manually created context
        var body=primary("sample.T#body()","One");body.setExceptionType("org.springframework.beans.factory.BeanCreationException");
        body.setMessage("Error creating bean");body.setFailureDetails("bean failed");
        body.setStackTrace(List.of("at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:600)","at sample.T.setup(T.java:1)","at sample.T.body(T.java:2)"));
        MockitoRuntimeDiagnosticEnricher.enrich(List.of(body),code);
        assertFalse(body.isSharedContextFailure());
    }

    public void testThresholdAloneReportsMissingFirstCause() {
        var d = secondary("sample.T#one()", "One");
        assertTrue(SpringRuntimeFailureAnalyzer.hasSharedFailure(List.of(d)));
        assertTrue(SpringRuntimeFailureAnalyzer.summary(List.of(d)).contains("首次启动根因未出现在本次报告"));
    }
    public void testBusinessIllegalStateOrAssertionMentioningContextIsNotSharedProof() {
        var business = JacocoCoverageExecutor.runtimeDiagnostic("sample.T#one()", "java.lang.IllegalStateException", "invalid page", "at sample.T.one(T.java:20)");
        var assertion = primary("sample.T#two()", "One"); assertion.setExceptionType("org.opentest4j.AssertionFailedError");
        var wordsOnly = primary("sample.T#three()", "One"); wordsOnly.setStackTrace(List.of("at sample.T.three(T.java:22)"));
        MockitoRuntimeDiagnosticEnricher.enrich(List.of(business, assertion, wordsOnly), "class T {}");
        assertEquals("DOMAIN_PRECONDITION_FAILURE", business.getCategory());
        assertFalse(business.isSharedContextFailure()); assertFalse(assertion.isSharedContextFailure()); assertFalse(wordsOnly.isSharedContextFailure());
    }
    public void testSelectorBlocksSharedFailureWithoutDiscardingIndividualTestsOrCoverage() {
        String source = "package sample; class T { @org.junit.jupiter.api.Test void first() {} @org.junit.jupiter.api.Test void second() {} }";
        var result = new RunnableTestSelector().select(source, (code, coverage) -> {
            assertFalse(coverage);
            var run = new CoverageRunResult(); var execution = run.getSnapshot().getExecution();
            execution.setFound(2); execution.setFailed(2); execution.setExitCode(1);
            execution.setDiagnostics(List.of(secondary("sample.T#second()", "One"), primary("sample.T#first()", "One")));
            execution.setEvidenceArchivePath("/local/runtime-evidence/failure-1");
            execution.setTestCases(List.of(new TestCaseExecution("sample.T#first()",false,false),new TestCaseExecution("sample.T#second()",false,false)));
            return new RunnableTestSelector.Probe(ValidationResult.status(ValidationStatus.PASSED), run);
        }, () -> false, null);
        assertEquals(RunnableTestSelectionReport.Status.BLOCKED, result.report().getStatus());
        assertEquals(source, result.report().getOriginalCode()); assertEquals("", result.report().getSelectedCode());
        assertTrue(result.report().getExcludedTests().isEmpty()); assertEquals(1,result.report().getExecutions());
        assertTrue(result.report().getMessage().contains("service.name"));
        assertEquals(List.of("/local/runtime-evidence/failure-1"), result.report().copy().getRuntimeEvidenceArchives());
    }
}
