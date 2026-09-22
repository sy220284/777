package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.ToolAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApprovalPolicyTest {
    @Test
    fun autoApprovalOnlyCoversWorkspaceWrites() {
        assertTrue(canAutoApprove(ToolAccess.WORKSPACE_WRITE))
        assertFalse(canAutoApprove(ToolAccess.SESSION_WRITE))
        assertFalse(canAutoApprove(ToolAccess.PROCESS))
        assertFalse(canAutoApprove(ToolAccess.AGENT_CONTROL))
        assertFalse(canAutoApprove(ToolAccess.DEVICE))
        assertFalse(canAutoApprove(ToolAccess.PRIVILEGED))
    }
}
