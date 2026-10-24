package com.labteto.dshmobile.local.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkToolPriorityTest {
    @Test fun explicitToolDiscoveryKeepsPriorityAcrossStepsUntilTaskChanges() {
        val policy = LocalWorkToolPriority()
        assertTrue(policy.isNewTask("用 MCP 获取 X 平台帖子"))
        assertFalse(policy.isNewTask("用 MCP 获取 X 平台帖子"))
        assertFalse(policy.isNewTask("用 MCP 获取 X 平台帖子"))
        assertTrue(policy.isNewTask("改为查询 GitHub PR"))
        assertFalse(policy.isNewTask("改为查询 GitHub PR"))
        policy.reset()
        assertTrue(policy.isNewTask("改为查询 GitHub PR"))
    }
}
