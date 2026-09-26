package com.labteto.dshmobile.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunContextTest {
    @Test
    fun optionalToolViewIsRunLocalAndDeduplicated() {
        val first = AgentRunContext(
            sessionId = "s1",
            route = AgentModelRoute("deepseek-flash", "https://api.deepseek.com"),
            permissions = AgentPermissionScope(allowMutation = true),
        )
        val second = AgentRunContext(
            sessionId = "s2",
            route = AgentModelRoute("deepseek-flash", "https://api.deepseek.com"),
            permissions = AgentPermissionScope(allowMutation = true),
        )

        first.enableOptionalTools(listOf("lsp_hover", "lsp_hover", "android_tap"))

        assertEquals(setOf("lsp_hover", "android_tap"), first.optionalTools())
        assertTrue(second.optionalTools().isEmpty())
    }

    @Test
    fun cancellationFactIsStable() {
        val context = AgentRunContext(
            sessionId = "s",
            route = AgentModelRoute("deepseek-flash", "https://api.deepseek.com"),
            permissions = AgentPermissionScope(allowMutation = false, planMode = true),
        )

        assertFalse(context.isCancelled)
        context.cancel("user")
        context.cancel("later")

        assertTrue(context.isCancelled)
        assertEquals("user", context.cancelReason)
        assertFalse(context.permissions.allowMutation)
        assertTrue(context.permissions.planMode)
    }
}
