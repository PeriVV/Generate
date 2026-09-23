package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.model.CoverageStatus;
import com.casc.unittestagent.toolwindow.UnitMindToolWindowPanel.WriteGateAction;
import junit.framework.TestCase;

/**
 * 写入门禁状态矩阵：RUNNING（结论未定）必须无条件阻断，CANCELLED 必须确认，
 * 两者都不得落入静默放行的兜底分支。
 */
public class WriteGateActionTest extends TestCase {
    public void testFullStatusMatrix() {
        assertEquals(WriteGateAction.ALLOW, action(null, false));
        assertEquals(WriteGateAction.ALLOW, action(CoverageStatus.NOT_RUN, false));
        assertEquals(WriteGateAction.ALLOW, action(CoverageStatus.PASSED, false));
        assertEquals(WriteGateAction.ALLOW, action(CoverageStatus.PASSED, true));

        // 结论未定：force 也不放行。
        assertEquals(WriteGateAction.BLOCK_RUNNING, action(CoverageStatus.RUNNING, false));
        assertEquals(WriteGateAction.BLOCK_RUNNING, action(CoverageStatus.RUNNING, true));

        // 受保护失败：普通写入阻断，force 走“写入原失败测试”确认。
        for (CoverageStatus protectedFailure : java.util.List.of(
                CoverageStatus.TEST_FAILED, CoverageStatus.SUSPECTED_SUT_BUG, CoverageStatus.NEEDS_REVIEW)) {
            assertEquals(protectedFailure.name(), WriteGateAction.BLOCK_PROTECTED,
                    action(protectedFailure, false));
            assertEquals(protectedFailure.name(), WriteGateAction.CONFIRM_FORCE_WRITE,
                    action(protectedFailure, true));
        }

        // 警告级确认：取消/未达标/失效/环境错误。
        for (CoverageStatus warning : java.util.List.of(
                CoverageStatus.CANCELLED, CoverageStatus.BELOW_THRESHOLD,
                CoverageStatus.STALE, CoverageStatus.ENVIRONMENT_ERROR)) {
            assertEquals(warning.name(), WriteGateAction.CONFIRM_WARNING, action(warning, false));
            assertEquals(warning.name(), WriteGateAction.CONFIRM_WARNING, action(warning, true));
        }
    }

    private static WriteGateAction action(CoverageStatus status, boolean force) {
        return UnitMindToolWindowPanel.coverageWriteGateAction(status, force);
    }
}
