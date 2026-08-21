package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.CallContextModel;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.TestScenario;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import junit.framework.TestCase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EvidenceBasedRulePlanningTest extends TestCase {
    public void testReferenceParameterWithoutEvidenceDoesNotCreateNullIntent() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                class Subject {
                    String normalize(String input) { return input.trim(); }
                }
                """, "normalize");

        assertFalse(method.getParameters().get(0).isNullable());
        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method);
        assertFalse(scenarios.stream().anyMatch(scenario -> scenario.getMethodNameEn().contains("Null")));
    }

    public void testThrowingNullGuardCreatesExceptionButNotNullableBoundary() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                class Subject {
                    String normalize(String input) {
                        if (null == input) throw new IllegalArgumentException();
                        return input.trim();
                    }
                }
                """, "normalize");

        assertFalse(method.getParameters().get(0).isNullable());
        assertEquals("input == null", method.getConstraints().get(0).getExpression());
        assertEquals("input != null", method.getConstraints().get(0).getSafeExpression());
        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method);
        assertEquals(1, scenarios.stream().filter(scenario -> "EXCEPTION".equals(scenario.getType())).count());
        assertFalse(scenarios.stream().anyMatch(scenario -> scenario.getMethodNameEn().contains("HandleNull")));
    }

    public void testHandledNullPathCreatesBoundaryIntent() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                class Subject {
                    String normalize(String input) {
                        if (input == null) return "";
                        return input.trim();
                    }
                }
                """, "normalize");

        assertTrue(method.getParameters().get(0).isNullable());
        assertTrue(new TestScenarioPlanner().plan(method).stream()
                .anyMatch(scenario -> scenario.getMethodNameEn().contains("HandleNullInput")));
    }

    public void testOptionalIntentRequiresDirectSourceDelegationAndUsesBusinessNeutralNames() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                import java.util.Optional;
                class Subject {
                    private OrderRepository orderRepository;
                    Optional<Order> findOrder(long orderId) { return orderRepository.findById(orderId); }
                }
                interface OrderRepository { Optional<Order> findById(long orderId); }
                class Order { }
                """, "findOrder");

        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method);
        assertTrue(scenarios.stream().anyMatch(scenario ->
                "shouldReturnPresentValueWhenFindOrderDependencyFindsValue".equals(scenario.getMethodNameEn())));
        assertTrue(scenarios.stream().anyMatch(scenario ->
                "shouldReturnEmptyWhenFindOrderDependencyFindsNoValue".equals(scenario.getMethodNameEn())));
        assertFalse(scenarios.stream().anyMatch(scenario -> scenario.getMethodNameEn().contains("Connection")));
        assertFalse(scenarios.stream().anyMatch(scenario -> scenario.getMethodNameEn().contains("OwnerId")));

        MockStrategy strategy = new MockStrategyPlanner().plan(method, scenarios);
        assertTrue(strategy.getVerifyRules().isEmpty());
        assertEquals(List.of("when(orderRepository.findById(orderId)).thenReturn(Optional.empty())"),
                strategy.getWhenRules());
    }

    public void testOptionalReturnAloneDoesNotInventPresentAndEmptyContracts() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                import java.util.Optional;
                class Subject {
                    private OrderRepository orderRepository;
                    Optional<Order> findOrder(long orderId) {
                        return orderRepository.findById(orderId).filter(Order::isActive);
                    }
                }
                interface OrderRepository { Optional<Order> findById(long orderId); }
                class Order { boolean isActive() { return true; } }
                """, "findOrder");

        List<TestScenario> scenarios = new TestScenarioPlanner().plan(method);
        assertTrue(scenarios.stream().anyMatch(scenario -> "NORMAL".equals(scenario.getType())));
        assertFalse(scenarios.stream().anyMatch(scenario -> "SOURCE_DIRECT_RETURN".equals(scenario.getOracleEvidence())));
        assertTrue(new MockStrategyPlanner().plan(method, scenarios).getWhenRules().isEmpty());
    }

    public void testMockReturnTypeComesFromDependencyCallContext() {
        MethodModel method = new MethodModel();
        method.setMethodName("displayName");
        method.setReturnType("String");
        method.setMethodBodyText("{ return repository.findById(id).map(Order::name).orElse(\"\"); }");
        DependencyModel dependency = new DependencyModel();
        dependency.setFieldName("repository");
        dependency.setFieldType("OrderRepository");
        dependency.setCallExpressions(List.of("findById(id)"));
        method.setDependencies(List.of(dependency));
        CallContextModel context = new CallContextModel();
        context.setOwner("repository:OrderRepository");
        context.setMethodName("findById");
        context.setExpression("repository.findById(id)");
        context.setReturnType("Optional<Order>");
        method.setCallContexts(List.of(context));
        TestScenario empty = new TestScenario();
        empty.getExpectedAssertions().add("returned value equals Optional.empty()");

        MockStrategy strategy = new MockStrategyPlanner().plan(method, List.of(empty));
        assertEquals(List.of("when(repository.findById(id)).thenReturn(Optional.empty())"), strategy.getWhenRules());
        assertFalse(strategy.getWhenRules().get(0).contains("connection"));
    }

    public void testMultipleExceptionBranchesReceiveUniqueMethodNames() {
        MethodModel method = new JavaSourceAnalyzer().analyze("""
                class Subject {
                    int divide(int value, int divisor) {
                        if (value < 0) throw new IllegalArgumentException();
                        if (divisor == 0) throw new ArithmeticException();
                        return value / divisor;
                    }
                }
                """, "divide");

        List<TestScenario> exceptions = new TestScenarioPlanner().plan(method).stream()
                .filter(scenario -> "EXCEPTION".equals(scenario.getType()))
                .toList();
        Set<String> names = new HashSet<>();
        exceptions.forEach(scenario -> names.add(scenario.getMethodNameEn()));
        assertEquals(2, exceptions.size());
        assertEquals(2, names.size());
    }
}
