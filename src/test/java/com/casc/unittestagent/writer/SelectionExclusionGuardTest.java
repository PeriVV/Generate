package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.RunnableTestSelectionReport;
import junit.framework.TestCase;

public class SelectionExclusionGuardTest extends TestCase {
    public void testOrphanHelpersDoNotReturnFromLegacyFileAndLiveExistingCallersBlockCleanup() {
        String code="package sample; class T { @org.junit.jupiter.api.Test void good() {} @org.junit.jupiter.api.Test void bad(){ helper(); } private void helper(){ inner(); } private void inner(){} }";
        var report=report(); report.setOriginalCode(code);
        var guard=SelectionExclusionGuard.snapshot(report,"source");
        String cleaned=guard.removeExcludedFromExisting(code);
        assertFalse(cleaned.contains("void bad()")); assertFalse(cleaned.contains("void helper()")); assertFalse(cleaned.contains("void inner()"));
        guard.verifyAbsent(cleaned);
        try { guard.verifyAbsent(cleaned.replace("class T {","class T { private void helper(){}")); fail(); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("辅助方法")); }
        try { guard.removeExcludedFromExisting(code.replace("void good() {}","void good() { helper(); }")); fail(); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("仍被已有")); }
    }
    private static final String BAD = "@org.junit.jupiter.api.Test void bad() { throw new IllegalArgumentException(\"a b\"); }";
    private static final String GOOD = "@org.junit.jupiter.api.Test void good() {}";
    private static final String ORIGINAL = "package sample; class T { " + BAD + GOOD + " }";
    private RunnableTestSelectionReport report() {
        var report = new RunnableTestSelectionReport(); report.setOriginalCode(ORIGINAL);
        report.setSelectedCode("package sample; class T { " + GOOD + " }");
        report.setSourceHash("source"); report.setStatus(RunnableTestSelectionReport.Status.PASSED);
        report.exclude("sample.T#bad()", "runtime failure"); return report;
    }
    public void testRemovesExactExcludedCopyOutsideRegionsAndPreservesOtherOwnersAndOverloads() {
        var guard = SelectionExclusionGuard.snapshot(report(), "source");
        String existing = ORIGINAL.replace("class T {", "class T { void bad(int n) {} class Nested { " + BAD + " }");
        String cleaned = guard.removeExcludedFromExisting(existing);
        assertFalse(cleaned.contains(BAD + GOOD));
        assertTrue(cleaned.contains("void bad(int n)")); assertTrue(cleaned.contains("class Nested"));
        guard.verifyAbsent(cleaned);
    }
    public void testFormattingAndCommentsDoNotPreventRemovalButLiteralChangesBlock() {
        var guard = SelectionExclusionGuard.snapshot(report(), "source");
        String reformatted = ORIGINAL.replace("void bad() {", "void bad( ) { /* retained comment */");
        guard.verifyAbsent(guard.removeExcludedFromExisting(reformatted));
        try { guard.removeExcludedFromExisting(ORIGINAL.replace("a b", "ab")); fail("must preserve changed manual test"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("不能安全自动删除")); }
    }
    public void testGeneratedRenamedCopyIsRemovedButUnownedOrChangedAliasIsBlocked() {
        var guard = SelectionExclusionGuard.snapshot(report(), "source");
        String renamed = BAD.replace("bad()", "bad_2()");
        String marked = "package sample; class T {\n// region UnitMind generated tests for old\n" + renamed + "\n// endregion\n}";
        guard.verifyAbsent(guard.removeExcludedFromExisting(marked));
        String multiple = marked.replace(renamed, BAD + "\n" + renamed + "\n" + renamed.replace("bad_2", "bad_3"));
        guard.verifyAbsent(guard.removeExcludedFromExisting(multiple));
        for (String ambiguous : new String[]{"package sample; class T { " + renamed + " }", marked.replace("a b", "changed")}) {
            try { guard.removeExcludedFromExisting(ambiguous); fail("ambiguous alias must be preserved"); }
            catch (IllegalStateException expected) { /* no deletion */ }
        }
    }
    public void testFinalAndRepairedContentCannotReintroduceExcludedIdentity() {
        var guard = SelectionExclusionGuard.snapshot(report(), "source");
        for (String code : new String[]{ORIGINAL, ORIGINAL.replace("a b", "changed"), ORIGINAL.replace("bad()", "bad_2()")}) {
            try { guard.verifyAbsent(code); fail("excluded group reintroduced"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("已排除")); }
        }
    }
    public void testManifestIsImmutableAndEditsKeepIntentUntilExplicitRestore() {
        var report = report(); var guard = SelectionExclusionGuard.snapshot(report, "source");
        report.setStatus(RunnableTestSelectionReport.Status.STALE);
        assertTrue(report.hasActiveExclusions());
        report.releaseExclusions(); assertFalse(report.hasActiveExclusions());
        try { guard.verifyAbsent(ORIGINAL); fail("prepared constraint is immutable"); }
        catch (IllegalStateException expected) { /* existing write still protected */ }
        SelectionExclusionGuard.snapshot(report, "changed source").verifyAbsent(ORIGINAL);
    }
    public void testChangedSourceAndUnboundManifestFailClosed() {
        try { SelectionExclusionGuard.snapshot(report(), "different"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("源码已变更")); }
        var report = report(); report.exclude("sample.T#unknown()", "missing proof");
        try { SelectionExclusionGuard.snapshot(report, "source"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("无法绑定")); }
    }
    public void testPreviousExclusionsRemainActiveAfterRepeatedOrCancelledSelection() {
        var previous = report();
        for (var status : new RunnableTestSelectionReport.Status[]{RunnableTestSelectionReport.Status.PASSED,
                RunnableTestSelectionReport.Status.CANCELLED, RunnableTestSelectionReport.Status.BLOCKED}) {
            var next = new RunnableTestSelectionReport(); next.setStatus(status); next.setSourceHash("source");
            next.setOriginalCode(previous.getSelectedCode()); next.setSelectedCode(previous.getSelectedCode());
            next.inheritExclusions(previous.getActiveExclusionOrigins());
            assertTrue(next.hasActiveExclusions()); assertEquals(0, next.getExcludedTests().size());
            var guard = SelectionExclusionGuard.snapshot(next, "source");
            guard.verifyAbsent(guard.removeExcludedFromExisting(ORIGINAL));
            next.releaseExclusions(); assertFalse(next.hasActiveExclusions());
        }
    }
    public void testUnusedUnavailableImportRemovedWithExcludedMethod() {
        var report = report(); String bad = BAD.replace("throw new IllegalArgumentException(\"a b\");", "new Missing();");
        report.setOriginalCode(ORIGINAL.replace(BAD, bad));
        String existing = report.getOriginalCode().replace("package sample;", "package sample; import unavailable.Missing;");
        String cleaned = SelectionExclusionGuard.snapshot(report, "source").removeExcludedFromExisting(existing);
        assertFalse(cleaned.contains("unavailable.Missing")); assertTrue(cleaned.contains("void good()"));
    }
    public void testRegionLabelsAreExactAndSupportArbitraryIndentAndNestedRegions() {
        String existing = """
                class T {
                  // region UnitMind generated tests for workMore
                  void other() {}
                  // endregion
                  // region UnitMind generated tests for work
                  // region nested
                  void old() {}
                  // endregion
                  // endregion
                }
                """;
        var merger = new TestClassMerger();
        assertFalse(merger.hasRegionForMethod(existing, "wor"));
        String merged = merger.merge(existing, "class T { void fresh() {} }", "work", new MergeOptions());
        assertTrue(merged.contains("void other()")); assertFalse(merged.contains("void old()"));
        assertTrue(merged.contains("void fresh()"));
        String literal = "class T { String marker = \"// region UnitMind generated tests for work\"; void manual() {} }";
        assertFalse(merger.hasRegionForMethod(literal, "work"));
        assertTrue(merger.merge(literal, "class T { void fresh() {} }", "work", new MergeOptions()).contains("void manual()"));
    }
}
