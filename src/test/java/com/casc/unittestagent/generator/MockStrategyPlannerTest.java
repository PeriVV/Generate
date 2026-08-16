package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.MockStrategy;
import com.casc.unittestagent.model.MockStrategyItem;
import junit.framework.TestCase;

import java.util.List;

public class MockStrategyPlannerTest extends TestCase {
    public void testStaticMockStrategyIsDisabledByDefault() {
        MethodModel method = new MethodModel();
        method.setMethodBodyText("sorted.sort(Comparator.comparingInt(Evaluator::cost));");

        MockStrategy strategy = new MockStrategyPlanner().plan(method, List.of());
        MockStrategyItem item = strategy.getStrategyItems().stream()
                .filter(candidate -> "Comparator".equals(candidate.getObjectName()))
                .findFirst().orElse(null);

        assertNotNull(item);
        assertFalse(item.isGenerate());
        assertTrue(item.getMockitoStrategy().contains("真实调用"));
    }
}
