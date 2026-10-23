package com.casc.unittestagent.generator;

import com.casc.unittestagent.llm.LlmGenerationResponse;
import com.casc.unittestagent.model.FailureCategory;
import com.casc.unittestagent.model.OracleAssessment;
import com.casc.unittestagent.model.OracleAuthority;
import com.casc.unittestagent.model.OracleDecision;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RuntimeRepairDisposition;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 运行修复的最后代码门禁。模型的文字承诺不足以保护 Oracle，
 * 因此还会对修复前后失败测试的断言、交互验证和期望异常做 AST 指纹比较。
 */
public final class RuntimeOracleGate {
    public OracleAssessment assessment(LlmGenerationResponse response) {
        return assessment(response, null);
    }

    /**
     * observedDiagnostics 传入确定性诊断器给出的运行失败，用作模型越界取值时的回退类别。
     * rootCauseCategory 必须落在封闭分类 FailureCategory 内；模型自造标签会被替换为
     * 观察到的确定性类别或 UNKNOWN，并在证据链中留痕。
     */
    public OracleAssessment assessment(LlmGenerationResponse response,
                                       List<RuntimeDiagnostic> observedDiagnostics) {
        if (response == null) throw new IllegalArgumentException("运行修复响应为空。");
        OracleAssessment assessment = new OracleAssessment();
        try {
            assessment.setDisposition(RuntimeRepairDisposition.valueOf(response.getRepairDisposition()));
            assessment.setDecision(OracleDecision.valueOf(response.getOracleDecision()));
            assessment.setAuthority(OracleAuthority.valueOf(response.getOracleAuthority()));
        } catch (IllegalArgumentException ex) {
            // 仲裁枚举取值来自模型响应，非法/缺失属于响应格式违约：抛 LlmResponseFormatException
            // 让覆盖率闭环按既有格式重试预算纠错，而不是以普通 IAE 绕过重试直接终止。
            throw new com.casc.unittestagent.llm.LlmResponseFormatException("运行修复仲裁枚举无效。", ex);
        }
        assessment.setAuthorityRef(response.getOracleAuthorityRef());
        assessment.setEvidence(response.getDiagnosisEvidence());
        // 必须先落入模型证据再仲裁：越界回退的备注要追加在模型证据之后，而不是被覆盖。
        assessment.setRootCauseCategory(adjudicateCategory(
                response.getDiagnosisRootCauseCategory(), observedDiagnostics, assessment));
        return assessment;
    }

    /** 封闭分类仲裁：合法取值原样规范化返回；越界取值回退为观察到的确定性类别或 UNKNOWN。 */
    private static String adjudicateCategory(String raw,
                                             List<RuntimeDiagnostic> observed,
                                             OracleAssessment assessment) {
        FailureCategory resolved = FailureCategory.fromCode(raw);
        // 只有运行期可仲裁类别合法：编译期环境码（如 JUNIT5_NOT_FOUND）不得进入运行终态漏斗。
        if (resolved != FailureCategory.UNKNOWN && resolved.isRuntimeArbitratable()) return resolved.name();
        String fallback = observed == null ? "" : observed.stream()
                .filter(Objects::nonNull)
                .map(RuntimeDiagnostic::getCategory)
                .filter(category -> category != null && !category.isBlank()
                        && FailureCategory.isKnownCode(category))
                .findFirst()
                .orElse("");
        assessment.setEvidence(appendEvidence(assessment.getEvidence(),
                "模型返回的 rootCauseCategory 不在封闭分类中（原值=" + raw + "），"
                        + (fallback.isBlank() ? "已回退为 UNKNOWN。"
                        : "已回退为确定性诊断类别 " + fallback + "。")));
        return fallback.isBlank() ? FailureCategory.UNKNOWN.name() : fallback;
    }

    private static List<String> appendEvidence(List<String> evidence, String value) {
        List<String> result = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
        if (value != null && !value.isBlank()) result.add(value);
        return result;
    }

    /**
     * 是否允许自动应用“修改 Oracle（断言/期望）”的修复。
     * 标准模式沿用权威分级：仅 USER_REQUIREMENT / EXISTING_TEST / CONTRACT_FACT 可自动改。
     * 严格准出模式下 CORRECT 一律禁止自动应用——权威证据由模型自报，无法完全排除
     * 把被测代码真实缺陷误判成“测试期望写错”的可能，交人工确认更安全。
     */
    public static boolean automaticOracleCorrectionBlocked(OracleAssessment assessment, boolean strictMode) {
        if (assessment == null || assessment.getDecision() != OracleDecision.CORRECT) {
            return false;
        }
        return strictMode || !assessment.getAuthority().permitsAutomaticOracleCorrection();
    }

    /** 严格准出模式的拒绝文案，写入评估证据并透出到修复明细与终态消息。 */
    public static String strictOracleGateMessage(OracleAssessment assessment) {
        return "严格 Oracle 准出已开启：模型判定需要修改断言（oracleDecision=CORRECT）的修复"
                + "不再自动应用。原失败测试与证据已保留，请人工确认这是被测代码的真实缺陷"
                + "（可强制写入作为缺陷回归用例）还是测试期望错误（人工修改断言后重跑）。"
                + "模型自报权威来源=" + assessment.getAuthority()
                + "，证据引用=" + assessment.getAuthorityRef() + "。";
    }

    public void validateRepair(OracleAssessment assessment,
                               String currentCode,
                               String proposedCode,
                               Set<String> editableTestNames) {
        validateRepair(assessment, currentCode, proposedCode, editableTestNames, Set.of());
    }

    /**
     * protectedTestNames：未聚焦、但 Oracle 必须保持不变的测试（如整类修复中的非聚焦失败测试）。
     * 即使 oracleDecision=CORRECT（聚焦测试经权威授权可改期望值）也不豁免它们；
     * 且 CORRECT 只授权修改期望值、不授权删除执行——任何测试的 assumption 与控制流指纹都不得变化。
     */
    public void validateRepair(OracleAssessment assessment,
                               String currentCode,
                               String proposedCode,
                               Set<String> editableTestNames,
                               Set<String> protectedTestNames) {
        if (assessment == null || assessment.getDisposition() != RuntimeRepairDisposition.REPAIR_TEST) {
            throw new IllegalArgumentException("只有 REPAIR_TEST 可以产生代码补丁。");
        }
        Map<String, List<String>> before = oracleFingerprints(currentCode);
        Map<String, List<String>> after = oracleFingerprints(proposedCode);
        if (assessment.getDecision() == OracleDecision.CORRECT) {
            if (!assessment.getAuthority().permitsAutomaticOracleCorrection()) {
                throw new UnsafeOracleCorrectionException(
                        "禁止使用 " + assessment.getAuthority()
                                + " 修改测试 Oracle；只有 USER_REQUIREMENT、EXISTING_TEST 或 CONTRACT_FACT 可授权。");
            }
            rejectExecutionRemoval(before, after);
            rejectUnauthorizedOracleChanges(protectedTestNames, before, after);
            return;
        }
        Set<String> editable = editableTestNames == null ? Set.of() : editableTestNames;
        for (String name : editable) {
            List<String> original = before.getOrDefault(name, List.of());
            List<String> replacement = after.getOrDefault(name, List.of());
            if (!original.equals(replacement)) {
                throw new UnsafeOracleCorrectionException(
                        "oracleDecision=KEEP 但补丁修改了 " + name + " 的 Oracle：原="
                                + original + "，新=" + replacement);
            }
        }
        rejectUnauthorizedOracleChanges(protectedTestNames, before, after);
    }

    /** CORRECT 授权修改期望值，不授权删除执行：所有测试的 assumption/控制流指纹必须不变。 */
    private static void rejectExecutionRemoval(Map<String, List<String>> before,
                                                Map<String, List<String>> after) {
        for (Map.Entry<String, List<String>> entry : before.entrySet()) {
            List<String> originalGuards = executionFingerprints(entry.getValue());
            List<String> replacementGuards = executionFingerprints(
                    after.getOrDefault(entry.getKey(), List.of()));
            if (!originalGuards.equals(replacementGuards)) {
                throw new UnsafeOracleCorrectionException(
                        "oracleDecision=CORRECT 只授权修改期望值，不授权删除执行："
                                + entry.getKey() + " 的 assumption/控制流被改动。");
            }
        }
    }

    /** 非聚焦测试的 Oracle 指纹在 KEEP 与 CORRECT 下都不得变化。 */
    private static void rejectUnauthorizedOracleChanges(Set<String> protectedTestNames,
                                                        Map<String, List<String>> before,
                                                        Map<String, List<String>> after) {
        if (protectedTestNames == null) return;
        for (String name : protectedTestNames) {
            List<String> original = before.getOrDefault(name, List.of());
            List<String> replacement = after.getOrDefault(name, List.of());
            if (!original.equals(replacement)) {
                throw new UnsafeOracleCorrectionException(
                        "非聚焦测试 " + name + " 的 Oracle 未获授权修改：原=" + original + "，新=" + replacement);
            }
        }
    }

    private static List<String> executionFingerprints(List<String> fingerprints) {
        List<String> result = new ArrayList<>();
        for (String fingerprint : fingerprints) {
            if (fingerprint.startsWith("assumption:") || fingerprint.startsWith("control-")) {
                result.add(fingerprint);
            }
        }
        return result;
    }

    static Map<String, List<String>> oracleFingerprints(String code) {
        // Compare contracts after the same audited syntax normalization on both snapshots.
        // No global eq/any stripping: changed values, receivers, modes and control flow remain visible.
        CompilationUnit unit = StaticJavaParser.parse(TestCodeNormalizationPipeline.normalize(code).code());
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!isTest(method)) continue;
            List<String> fingerprints = new ArrayList<>();
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String fingerprint = oracleFingerprint(call);
                if (!fingerprint.isBlank()) {
                    fingerprints.add(fingerprint + "@" + ancestorPath(call, method));
                    fingerprints.addAll(directOracleValueSources(call, method));
                }
            }
            method.findAll(IfStmt.class).forEach(node -> fingerprints.add("control-if:" + normalize(node)));
            method.findAll(TryStmt.class).forEach(node -> fingerprints.add("control-try:" + normalize(node)));
            method.findAll(ReturnStmt.class).forEach(node -> fingerprints.add("control-return:" + normalize(node)));
            method.findAll(ForStmt.class).forEach(node -> fingerprints.add("control-for:" + normalize(node)));
            method.findAll(ForEachStmt.class).forEach(node -> fingerprints.add("control-foreach:" + normalize(node)));
            method.findAll(WhileStmt.class).forEach(node -> fingerprints.add("control-while:" + normalize(node)));
            method.findAll(DoStmt.class).forEach(node -> fingerprints.add("control-do:" + normalize(node)));
            method.findAll(SwitchStmt.class).forEach(node -> fingerprints.add("control-switch:" + normalize(node)));
            result.put(method.getNameAsString(), fingerprints);
        }
        return result;
    }

    /**
     * Protect local values passed directly to an assertion. Comparing only the assertion text
     * misses changes such as replacing {@code actual = sut.value()} with {@code actual = 2}.
     * Receiver setup remains editable, so a repair may still change {@code subject = null} to a
     * valid fixture when the assertion itself calls {@code subject.value()}.
     */
    private static List<String> directOracleValueSources(MethodCallExpr call, MethodDeclaration method) {
        String lower = call.getNameAsString().toLowerCase(Locale.ROOT);
        boolean verification = isMockitoVerification(call);
        if (!(lower.startsWith("assert") || "fail".equals(lower) || verification)) return List.of();
        List<String> result = new ArrayList<>();
        for (Expression originalArgument : call.getArguments()) {
            Expression argument = originalArgument;
            if (verification && argument instanceof MethodCallExpr eq && "eq".equals(eq.getNameAsString())
                    && "org.mockito.ArgumentMatchers".equals(eq.getScope().map(Node::toString).orElse(""))
                    && eq.getArguments().size() == 1) argument = eq.getArgument(0);
            if (!(argument instanceof NameExpr nameExpression)) continue;
            String name = nameExpression.getNameAsString();
            for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
                if (name.equals(variable.getNameAsString()) && precedes(variable, call)) {
                    result.add("oracle-source:" + name + "="
                            + variable.getInitializer().map(RuntimeOracleGate::normalize).orElse("<uninitialized>"));
                }
            }
            for (AssignExpr assignment : method.findAll(AssignExpr.class)) {
                if (assignment.getTarget().isNameExpr()
                        && name.equals(assignment.getTarget().asNameExpr().getNameAsString())
                        && precedes(assignment, call)) {
                    result.add("oracle-assignment:" + name + "=" + normalize(assignment.getValue()));
                }
            }
        }
        return result;
    }

    private static boolean precedes(Node candidate, Node reference) {
        if (candidate.getBegin().isEmpty() || reference.getBegin().isEmpty()) return true;
        com.github.javaparser.Position left = candidate.getBegin().get();
        com.github.javaparser.Position right = reference.getBegin().get();
        return left.line < right.line || (left.line == right.line && left.column <= right.column);
    }

    private static String oracleFingerprint(MethodCallExpr call) {
        String name = call.getNameAsString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("assert") || "fail".equals(lower)) {
            if ("assertthat".equals(lower) && isScopeOfParentCall(call)) return "";
            // 保留整个断言调用，不只保留 expected 参数。否则模型可以把
            // assertEquals(expected, sut.call()) 改成 assertEquals(expected, expected) 绕过门禁。
            return lower + ":" + normalize(call);
        }
        if (lower.startsWith("assume")) return "assumption:" + normalize(call);
        if (isFluentAssertion(call)) return "fluent:" + normalize(call);
        if (isMockitoVerification(call)) return "verify:" + normalize(call);
        return "";
    }

    private static boolean isFluentAssertion(MethodCallExpr call) {
        Expression scope = call.getScope().orElse(null);
        while (scope instanceof MethodCallExpr scopedCall) {
            if ("assertThat".equals(scopedCall.getNameAsString())) return true;
            scope = scopedCall.getScope().orElse(null);
        }
        return false;
    }

    private static String ancestorPath(MethodCallExpr call, MethodDeclaration method) {
        List<String> path = new ArrayList<>();
        Node current = call.getParentNode().orElse(null);
        while (current != null && current != method) {
            path.add(current.getClass().getSimpleName());
            current = current.getParentNode().orElse(null);
        }
        return String.join("/", path);
    }

    private static boolean isMockitoVerification(MethodCallExpr call) {
        if ("verify".equals(call.getNameAsString())) {
            return !isScopeOfParentCall(call);
        }
        Expression scope = call.getScope().orElse(null);
        while (scope instanceof MethodCallExpr scopedCall) {
            String name = scopedCall.getNameAsString();
            if ("verify".equals(name) || "should".equals(name)) return true;
            scope = scopedCall.getScope().orElse(null);
        }
        return false;
    }

    private static boolean isScopeOfParentCall(MethodCallExpr call) {
        return call.getParentNode().filter(parent -> parent instanceof MethodCallExpr outer
                && outer.getScope().filter(scope -> scope == call).isPresent()).isPresent();
    }

    private static boolean isTest(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(annotation -> {
            String name = annotation.getName().getIdentifier();
            return "Test".equals(name) || "ParameterizedTest".equals(name)
                    || "RepeatedTest".equals(name) || "TestFactory".equals(name)
                    || "TestTemplate".equals(name);
        });
    }

    private static String normalize(Node node) {
        if (node == null) return "";
        // Whitespace is insignificant BETWEEN tokens, but significant INSIDE literals.
        // Removing all whitespace from rendered Java also hides changes to expected strings.
        if (node.getTokenRange().isEmpty()) return node.toString();
        StringBuilder result = new StringBuilder();
        for (var token : node.getTokenRange().get()) {
            if (!token.getCategory().isWhitespaceOrComment()) {
                if (result.length() > 0) result.append(' ');
                result.append(token.getText());
            }
        }
        return result.toString();
    }

    public static final class UnsafeOracleCorrectionException extends RepairCandidateRejectedException {
        public UnsafeOracleCorrectionException(String message) { super(Stage.ORACLE_CONTRACT, message); }
    }
}
