package com.casc.unittestagent.validation;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.TestCaseExecution;
import com.casc.unittestagent.model.TestExecutionSummary;
import java.util.*;

/** Run-evidence policy shared by selection, repair, measurement and final write. */
public final class JUnitExecutionEvidence {
    private JUnitExecutionEvidence() { }

    public static Map<String, String> unexecutedMethods(String code, TestExecutionSummary execution) {
        var methods = JUnitTestInventory.methods(code);
        Set<String> declared = new LinkedHashSet<>(); methods.forEach(m -> declared.add(m.id()));
        Set<String> observed = new HashSet<>();
        if (execution != null) for (TestCaseExecution test : execution.getTestCases()) {
            if (test == null || !test.isSuccessful() || test.isSkipped()) continue;
            var method = JUnitTestInventory.resolve(methods, declared, test.getTestId());
            if (method != null) observed.add(method.id());
        }
        Map<String, String> missing = new LinkedHashMap<>();
        for (String id : declared) if (!observed.contains(id)) missing.put(id, "JUnit 未发现或未真实执行该方法组，缺少运行通过证据");
        return Collections.unmodifiableMap(missing);
    }

    public static void requireDeclaredTests(String code, TestExecutionSummary execution) {
        if (execution == null || !execution.isSuccessful()) return;
        if (!execution.isFullySuccessful()) {
            reject(execution, "JUnitIncompleteEvidence", "JUnit 汇总与逐用例执行记录不一致，或存在跳过、未完成的测试。");
            return;
        }
        var missing = unexecutedMethods(code, execution);
        if (!missing.isEmpty()) reject(execution, "JUnitUndiscoveredTests",
                "未获得以下测试方法组的真实执行证明：" + String.join("，", missing.keySet()));
    }

    /** A multiset preserves parameterized/repeated/dynamic invocation multiplicity. */
    public static boolean sameInventory(TestExecutionSummary first, TestExecutionSummary second) {
        return first != null && second != null && first.isFullySuccessful() && second.isFullySuccessful()
                && inventory(first).equals(inventory(second));
    }

    public static void requireSameInventory(TestExecutionSummary baseline, TestExecutionSummary measured) {
        if (measured != null && measured.isFullySuccessful() && !sameInventory(baseline, measured)) {
            reject(measured, "JUnitInventoryChanged", "独立回归与覆盖率运行的测试身份或执行次数不一致，请重新验证。");
        }
    }

    private static Map<String, Integer> inventory(TestExecutionSummary execution) {
        Map<String, Integer> counts = new TreeMap<>();
        for (var test : execution.getTestCases()) counts.merge(test.getTestId(), 1, Integer::sum);
        return counts;
    }

    public static void reject(TestExecutionSummary execution, String type, String message) {
        if (execution.getEvidenceFailures().contains(message)) return;
        execution.rejectEvidence(message);
        var diagnostic = new RuntimeDiagnostic(); diagnostic.setExceptionType(type); diagnostic.setMessage(message);
        execution.getDiagnostics().add(diagnostic);
    }
}
