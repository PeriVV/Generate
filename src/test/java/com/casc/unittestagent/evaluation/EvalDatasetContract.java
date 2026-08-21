package com.casc.unittestagent.evaluation;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Freezes the v1 dataset shape so accidental case deletion cannot skew trends. */
public final class EvalDatasetContract {
    private EvalDatasetContract() {}

    public static void verify(List<EvalCaseManifest> cases) {
        require(cases.size() == 74, "expected 74 cases, found " + cases.size());
        require(count(cases, item -> item.getScope() == EvalCaseManifest.Scope.METHOD) == 60,
                "expected 60 METHOD cases");
        require(count(cases, item -> item.getScope() == EvalCaseManifest.Scope.CLASS) == 14,
                "expected 14 CLASS cases");
        require(count(cases, EvalCaseManifest::isSmoke) == 15, "expected 15 smoke cases");
        require(count(cases, item -> item.getJdkVersion().equals("17")) == 74,
                "all cases must declare JDK 17");
        require(count(cases, item -> item.getDifficulty() == EvalCaseManifest.Difficulty.EASY) == 22,
                "expected 22 EASY cases");
        require(count(cases, item -> item.getDifficulty() == EvalCaseManifest.Difficulty.MEDIUM) == 36,
                "expected 36 MEDIUM cases");
        require(count(cases, item -> item.getDifficulty() == EvalCaseManifest.Difficulty.HARD) == 16,
                "expected 16 HARD cases");

        List<EvalCaseManifest> methods = cases.stream()
                .filter(item -> item.getScope() == EvalCaseManifest.Scope.METHOD).toList();
        require(count(methods, item -> item.getBranchBand() == EvalCaseManifest.BranchBand.NONE) == 10,
                "expected 10 branch-free methods");
        require(count(methods, item -> item.getBranchBand() == EvalCaseManifest.BranchBand.LOW) == 30,
                "expected 30 low-branch methods");
        require(count(methods, item -> item.getBranchBand() == EvalCaseManifest.BranchBand.HIGH) == 20,
                "expected 20 high-branch methods");
        require(count(methods, item -> item.getSourceKind() == EvalCaseManifest.SourceKind.OPEN_SOURCE) == 22,
                "expected 22 open-source methods");

        Map<String, Integer> expectedPrefixes = Map.of(
                "pure-", 8, "boundary-", 8, "collection-", 6, "mock-", 10,
                "fixture-", 6, "jsoup-", 6, "lang-", 6, "real-", 10);
        expectedPrefixes.forEach((prefix, expected) -> require(
                count(methods, item -> item.getId().startsWith(prefix)) == expected,
                "expected " + expected + " method cases with prefix " + prefix));

        List<EvalCaseManifest> classes = cases.stream()
                .filter(item -> item.getScope() == EvalCaseManifest.Scope.CLASS).toList();
        Map<String, Integer> expectedClassPrefixes = Map.of(
                "class-pure-", 2, "class-mock-", 3, "class-mixed-", 2,
                "class-fixture-", 1, "class-existing-", 2, "class-real-", 4);
        expectedClassPrefixes.forEach((prefix, expected) -> require(
                count(classes, item -> item.getId().startsWith(prefix)) == expected,
                "expected " + expected + " class cases with prefix " + prefix));
    }

    private static int count(List<EvalCaseManifest> cases, Predicate<EvalCaseManifest> predicate) {
        return (int) cases.stream().filter(predicate).count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("Invalid evaluation dataset: " + message);
    }
}
