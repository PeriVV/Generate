package com.casc.unittestagent.generator;

import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ParameterModel;
import com.casc.unittestagent.model.TestDataRangeConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 为方法参数补充普通值、边界值和非法值。
 * 该类连接参数模型与随机数据生成器，使场景规划阶段可以直接复用已计算的测试数据。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestDataGenerator {
    /** 随机数据生成器，负责提供普通值、边界值和非法值。 */
    private final RandomTestDataGenerator randomGenerator = new RandomTestDataGenerator();

    /**
     * 为方法参数填充测试数据候选值。
     * 普通值、边界值和非法值在这里集中计算，后续场景规划和代码生成只消费结果。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param methodModel 源码分析得到的被测方法模型
     */
    public void enrich(MethodModel methodModel) {
        if (methodModel == null || methodModel.getParameters() == null) {
            return;
        }

        for (ParameterModel parameter : methodModel.getParameters()) {
            List<String> normalValues = new ArrayList<>();
            normalValues.add(randomGenerator.generateValueExpression(parameter.getType()));
            parameter.setNormalValues(normalValues);

            List<String> boundaryValues = new ArrayList<>();
            TestDataRangeConfigManager.findByType(parameter.getType())
                    .filter(this::hasNumericBoundary)
                    .ifPresent(config -> {
                        boundaryValues.add(randomGenerator.minValueExpression(parameter.getType()));
                        boundaryValues.add(randomGenerator.maxValueExpression(parameter.getType()));
                    });
            parameter.setBoundaryValues(boundaryValues);

            List<String> invalidValues = new ArrayList<>();
            if (!parameter.isNullable() && supportsBelowMinInvalidValue(parameter.getType())) {
                invalidValues.add(randomGenerator.belowMinValueExpression(parameter.getType()));
            }
            parameter.setInvalidValues(invalidValues);
        }
    }

    /**
     * 判断当前类型是否适合用低于最小值构造非法输入。
     * boolean、char 等候选值类型没有越界概念，不能伪造一个“低于最小值”的异常值。
     */
    private boolean supportsBelowMinInvalidValue(String javaType) {
        return TestDataRangeConfigManager.findByType(javaType)
                .map(config -> !config.isCandidateType() && config.getMinValue() != null)
                .orElse(false);
    }

    private boolean hasNumericBoundary(TestDataRangeConfig config) {
        return config != null && !config.isCandidateType()
                && config.getMinValue() != null && config.getMaxValue() != null;
    }
}
