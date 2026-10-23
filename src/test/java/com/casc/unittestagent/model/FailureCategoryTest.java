package com.casc.unittestagent.model;

import junit.framework.TestCase;

import java.util.List;

public class FailureCategoryTest extends TestCase {
    public void testLegacyCandidateRejectionIsNotOfferedAsRuntimeRootCause() {
        assertFalse(FailureCategory.UNSAFE_ORACLE_PATCH.isRuntimeArbitratable());
        assertFalse(FailureCategory.runtimeArbitrationCodes().contains("UNSAFE_ORACLE_PATCH"));
        assertEquals(FailureCategory.UNSAFE_ORACLE_PATCH, FailureCategory.fromCode("UNSAFE_ORACLE_PATCH"));
    }
    public void testFromCodeResolvesCodesFromEveryProducerGroup() {
        assertEquals(FailureCategory.MOCKITO_NO_INTERACTIONS_CONTRADICTION,
                FailureCategory.fromCode("MOCKITO_NO_INTERACTIONS_CONTRADICTION"));
        assertEquals(FailureCategory.FIXTURE_DEPENDENCY_MISSING,
                FailureCategory.fromCode("FIXTURE_DEPENDENCY_MISSING"));
        assertEquals(FailureCategory.MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET,
                FailureCategory.fromCode("MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET"));
        assertEquals(FailureCategory.JUNIT5_NOT_FOUND, FailureCategory.fromCode("JUNIT5_NOT_FOUND"));
        assertEquals(FailureCategory.SANDBOX_COMPILE_TIMEOUT, FailureCategory.fromCode("SANDBOX_COMPILE_TIMEOUT"));
        assertEquals(FailureCategory.MOCKITO_WHEN_ON_SPY, FailureCategory.fromCode("MOCKITO_WHEN_ON_SPY"));
        assertEquals(FailureCategory.UNSAFE_ORACLE_PATCH, FailureCategory.fromCode("UNSAFE_ORACLE_PATCH"));
        assertEquals(FailureCategory.UNSUPPORTED_FIXTURE_CLASSIFICATION,
                FailureCategory.fromCode("UNSUPPORTED_FIXTURE_CLASSIFICATION"));
    }

    public void testFromCodeNormalizesCaseAndWhitespace() {
        assertEquals(FailureCategory.FIXTURE_DEPENDENCY_MISSING,
                FailureCategory.fromCode(" fixture_dependency_missing "));
        assertEquals(FailureCategory.ASSERTION_VALUE_MISMATCH,
                FailureCategory.fromCode("assertion_value_mismatch"));
    }

    public void testFromCodeUnknownValuesFallBackToUnknown() {
        assertEquals(FailureCategory.UNKNOWN, FailureCategory.fromCode("TOTALLY_NEW_THING"));
        assertEquals(FailureCategory.UNKNOWN, FailureCategory.fromCode(null));
        assertEquals(FailureCategory.UNKNOWN, FailureCategory.fromCode(""));
        assertEquals(FailureCategory.UNKNOWN, FailureCategory.fromCode("   "));
        assertFalse(FailureCategory.isKnownCode("TOTALLY_NEW_THING"));
        assertTrue(FailureCategory.isKnownCode("ASSERTION_FAILURE"));
    }

    public void testEveryValueRoundTripsThroughFromCode() {
        for (FailureCategory category : FailureCategory.values()) {
            assertEquals(category, FailureCategory.fromCode(category.name()));
            // UNKNOWN 是“未知码”的兜底，其自身按定义不属于已知诊断码。
            if (category != FailureCategory.UNKNOWN) {
                assertTrue(FailureCategory.isKnownCode(category.name()));
            }
        }
    }

    public void testRequiredUserInfoBindsFailingTestNames() {
        String info = FailureCategory.ASSERTION_VALUE_MISMATCH
                .formatRequiredUserInfo(List.of("shouldDiscount", "shouldNotDiscount"));
        assertTrue(info, info.contains("shouldDiscount、shouldNotDiscount"));
        assertFalse(info, info.contains("{tests}"));
    }

    public void testRequiredUserInfoUsesFallbackWhenNoTestsGiven() {
        String info = FailureCategory.ASSERTION_VALUE_MISMATCH.formatRequiredUserInfo(List.of());
        assertTrue(info, info.contains("本轮全部失败测试"));
        assertEquals("", FailureCategory.NULL_FIXTURE_FAILURE.formatRequiredUserInfo(List.of("t")));
    }

    public void testDisplayTextFormat() {
        assertEquals("断言期望值不一致(ASSERTION_VALUE_MISMATCH)",
                FailureCategory.ASSERTION_VALUE_MISMATCH.displayText());
        assertEquals("未分类(UNKNOWN)", FailureCategory.UNKNOWN.displayText());
    }

    public void testRepairStrategiesOfKeyCategories() {
        assertEquals(RepairStrategy.EVIDENCE_REQUIRED, FailureCategory.ASSERTION_VALUE_MISMATCH.getRepairStrategy());
        assertEquals(RepairStrategy.EVIDENCE_REQUIRED, FailureCategory.MOCKITO_ARGUMENT_MISMATCH.getRepairStrategy());
        assertEquals(RepairStrategy.DETERMINISTIC, FailureCategory.MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET.getRepairStrategy());
        assertEquals(RepairStrategy.DETERMINISTIC, FailureCategory.FIXTURE_DEPENDENCY_MISSING.getRepairStrategy());
        assertEquals(RepairStrategy.ENVIRONMENT, FailureCategory.JUNIT5_NOT_FOUND.getRepairStrategy());
        assertEquals(RepairStrategy.UNCLASSIFIED, FailureCategory.TEST_RUNTIME_FAILURE.getRepairStrategy());
    }

    public void testRuntimeArbitrationCodesCoverRuntimeCategories() {
        List<String> codes = FailureCategory.runtimeArbitrationCodes();
        assertTrue(codes.contains("ASSERTION_VALUE_MISMATCH"));
        assertTrue(codes.contains("MOCKITO_STATIC_MOCK_FORBIDDEN_TARGET"));
        assertTrue(codes.contains("TEST_RUNTIME_FAILURE"));
        assertFalse(codes.contains("JUNIT5_NOT_FOUND"));
        assertFalse(codes.contains("UNKNOWN"));
    }
}
