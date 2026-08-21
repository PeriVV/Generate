package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import junit.framework.TestCase;

public class TestDataGeneratorEvidenceTest extends TestCase {
    public void testUnknownReferenceTypeDoesNotReuseRandomValueAsBoundaryOrInvalidInput() {
        MethodModel method = new MethodModel();
        ParameterModel parameter = parameter("request", "OrderRequest", false);
        method.getParameters().add(parameter);

        new TestDataGenerator().enrich(method);

        assertEquals(1, parameter.getNormalValues().size());
        assertTrue(parameter.getBoundaryValues().isEmpty());
        assertTrue(parameter.getInvalidValues().isEmpty());
    }

    public void testStringPatternValueIsNotMislabelledAsBoundaryOrInvalidInput() {
        MethodModel method = new MethodModel();
        ParameterModel parameter = parameter("name", "String", false);
        method.getParameters().add(parameter);

        new TestDataGenerator().enrich(method);

        assertTrue(parameter.getBoundaryValues().isEmpty());
        assertTrue(parameter.getInvalidValues().isEmpty());
    }

    public void testConfiguredNumericRangeStillProducesBoundaryAndInvalidCandidates() {
        MethodModel method = new MethodModel();
        ParameterModel parameter = parameter("count", "int", false);
        method.getParameters().add(parameter);

        new TestDataGenerator().enrich(method);

        assertEquals(2, parameter.getBoundaryValues().size());
        assertEquals(1, parameter.getInvalidValues().size());
    }

    public void testNullableInputIsBoundaryEvidenceButNotAutomaticallyInvalid() {
        MethodModel method = new MethodModel();
        ParameterModel parameter = parameter("name", "String", true);
        method.getParameters().add(parameter);

        new TestDataGenerator().enrich(method);

        assertTrue(parameter.getInvalidValues().isEmpty());
        assertTrue(new TestScenarioPlanner().plan(method).stream()
                .anyMatch(scenario -> scenario.getMethodNameEn().contains("HandleNullName")));
    }

    private ParameterModel parameter(String name, String type, boolean nullable) {
        ParameterModel parameter = new ParameterModel();
        parameter.setName(name);
        parameter.setType(type);
        parameter.setNullable(nullable);
        return parameter;
    }
}
