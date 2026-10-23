package com.casc.unittestagent.validation;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class TestSubsetProjectionTest {
    private static final String SOURCE="""
            package sample;
            class T {
              @org.junit.jupiter.api.BeforeEach void setup() { shared(); }
              void shared() {}
              @org.junit.jupiter.api.Test void good() { org.junit.jupiter.api.Assertions.assertTrue(true); }
              @org.junit.jupiter.api.Test void bad() throws Exception { reflected(); }
              private void reflected() throws Exception { inner(); }
              private void inner() throws Exception { Object.class.getDeclaredField("missing"); }
              private void unrelated() {}
            }
            """;
    private TestSubsetProjection.Result project(String code) { return TestSubsetProjection.project(code,Set.of("sample.T#good()")); }
    @Test public void removesOnlyExcludedTestAndItsOrphanPrivateHelperChain() {
        var result=project(SOURCE);
        assertEquals(2,result.removedHelpers().size());
        assertFalse(result.code().contains("void reflected()")); assertFalse(result.code().contains("void inner()"));
        assertTrue(result.code().contains("void unrelated()")); assertTrue(result.code().contains("void shared()"));
        assertTrue(result.code().contains("@org.junit.jupiter.api.BeforeEach void setup() { shared(); }"));
        assertTrue(result.code().contains("@org.junit.jupiter.api.Test void good() { org.junit.jupiter.api.Assertions.assertTrue(true); }"));
    }
    @Test public void lifecycleFieldsConstructorsAndSurvivingTestKeepTheirHelpers() {
        for(String code:java.util.List.of(
                SOURCE.replace("void setup() { shared(); }","void setup() throws Exception { reflected(); }"),
                SOURCE.replace("class T {","class T { Object callback = this::reflected;"),
                SOURCE.replace("class T {","class T { T() throws Exception { reflected(); }"),
                SOURCE.replace("assertTrue(true)","assertTrue(reflected())"))) {
            assertTrue(project(code).removedHelpers().isEmpty());
        }
    }
    @Test public void namedParameterizedProviderAndMethodReferenceRemain() {
        for(String code:java.util.List.of(
                SOURCE.replace("@org.junit.jupiter.api.Test void good()", "@org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.MethodSource(\"reflected\") void good()"),
                SOURCE.replace("void good() {", "void good() { Runnable callback = this::reflected;"))) {
            assertTrue(project(code).removedHelpers().isEmpty());
        }
    }
    @Test public void dynamicMethodLookupIncludingReachableHelperPreventsDeletion() {
        for(String code:java.util.List.of(
                SOURCE.replace("void good() {", "void good() { getClass().getDeclaredMethod(dynamicName());"),
                SOURCE.replace("void shared() {}", "void shared() { getClass().getDeclaredMethod(dynamicName()); }")))
            assertTrue(project(code).removedHelpers().isEmpty());
    }
    @Test public void humanHelperOutsideGeneratedRegionIsNeverPruned() {
        String code=SOURCE.replace("  @org.junit.jupiter.api.Test void bad()", "  // region UnitMind generated tests for target\n  @org.junit.jupiter.api.Test void bad()")
                .replace("  private void reflected()", "  // endregion\n  private void reflected()");
        assertTrue(project(code).removedHelpers().isEmpty());
    }
    @Test public void annotationsAndOverloadAmbiguityConservativelyKeepSupportMembers() {
        assertTrue(project(SOURCE.replace("private void reflected()", "@Deprecated private void reflected()")).removedHelpers().isEmpty());
        String code=SOURCE.replace("void good() {", "void good() { reflected(1);")
                .replace("private void unrelated() {}", "private void reflected(int n) {} private void unrelated() {}");
        assertTrue(project(code).removedHelpers().isEmpty());
    }
    @Test public void unknownUnusedHelperCannotDisappearWithoutExcludedCaller() {
        var result=TestSubsetProjection.project(SOURCE,Set.of("sample.T#good()","sample.T#bad()"));
        assertEquals(SOURCE,result.code()); assertTrue(result.removedHelpers().isEmpty());
    }
    @Test public void dynamicLookupInLiveCandidateRetainsOtherPossibleCallbackTargets() {
        String code=SOURCE.replace("void good() {", "void good() { reflected();")
                .replace("void bad() throws Exception { reflected(); }", "void bad() throws Exception { reflected(); callback(); }")
                .replace("private void reflected() throws Exception { inner(); }", "private void reflected() throws Exception { getClass().getDeclaredMethod(dynamicName()); }")
                .replace("private void unrelated() {}", "private void callback() {} private void unrelated() {}");
        assertTrue(project(code).removedHelpers().isEmpty());
    }
}
