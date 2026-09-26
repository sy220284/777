package com.labteto.dshmobile.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentRunPolicyTest {
    @Test
    fun chatKeepsSharedRuntimeButHasNoExecutableCapabilities() {
        val policy = localAgentRunPolicy(LocalUsageMode.CHAT)

        assertFalse(policy.toolsEnabled)
        assertFalse(policy.allowToolExecution)
        assertFalse(policy.imageFallbackToVisionTool)
    }

    @Test
    fun workKeepsFullExecutableCapabilities() {
        val policy = localAgentRunPolicy(LocalUsageMode.WORK)

        assertTrue(policy.toolsEnabled)
        assertTrue(policy.allowToolExecution)
        assertTrue(policy.imageFallbackToVisionTool)
    }
}
