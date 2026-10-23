package com.casc.unittestagent.toolwindow;

import com.casc.unittestagent.model.ClassJUnitStatus;
import com.casc.unittestagent.toolwindow.UnitMindToolWindowPanel.WriteGateAction;
import junit.framework.TestCase;

/** 未运行、失效和环境错误不能通过普通写入；force 仍不能绕过运行中的任务。 */
public class WriteGateActionTest extends TestCase {
    public void testFullStatusMatrix() {
        assertEquals(WriteGateAction.BLOCK_PROTECTED, action(null, false));
        assertEquals(WriteGateAction.CONFIRM_FORCE_WRITE, action(null, true));
        for (ClassJUnitStatus status : ClassJUnitStatus.values()) {
            if (status == ClassJUnitStatus.RUNNING) {
                assertEquals(WriteGateAction.BLOCK_RUNNING, action(status, false));
                assertEquals(WriteGateAction.BLOCK_RUNNING, action(status, true));
            } else if (status == ClassJUnitStatus.PASSED) {
                assertEquals(WriteGateAction.ALLOW, action(status, false));
                assertEquals(WriteGateAction.ALLOW, action(status, true));
            } else {
                assertEquals(status.name(), WriteGateAction.BLOCK_PROTECTED, action(status, false));
                assertEquals(status.name(), WriteGateAction.CONFIRM_FORCE_WRITE, action(status, true));
            }
        }
    }

    private static WriteGateAction action(ClassJUnitStatus status, boolean force) {
        return UnitMindToolWindowPanel.runtimeWriteGateAction(status, force);
    }
}
