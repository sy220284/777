package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.agent.LocalAgentContinuationPolicy
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentRuntimeFoundationV2Test {
    @Test
    fun frozenRunSurfaceOwnsRouteCapabilitiesAndFingerprint() {
        val profile = LocalModelProfile(
            id = "deepseek-main",
            model = "deepseek-chat",
            baseUrl = "https://api.deepseek.com",
            provider = "DeepSeek",
        )

        val surface = profile.toRunModelSurface()

        assertEquals(profile, surface.profile)
        assertEquals(profile.routeFingerprint(), surface.routeFingerprint)
        assertEquals(LocalModelPromptUpdateMode.APPEND_ONLY, surface.capabilities.toolUpdateMode)
        assertEquals(LocalModelPromptUpdateMode.APPEND_ONLY, surface.capabilities.systemPromptUpdateMode)
        assertEquals(surface.capabilities.promptCachePolicy, surface.promptCachePolicy)
    }

    @Test
    fun continuationPolicyOnlyAllowsBoundedContinuationSafeFailures() {
        val policy = LocalAgentContinuationPolicy(
            maxContinuations = 2,
            prompt = "继续",
        )
        val safe = LocalModelException(
            code = "STREAM_INTERRUPTED",
            message = "interrupted",
            retryable = false,
            admissionState = LocalModelAdmissionState.ADMITTED,
            continuationEligible = true,
        )
        val unsafe = LocalModelException(
            code = "NOT_SENT",
            message = "not sent",
            retryable = true,
            admissionState = LocalModelAdmissionState.NOT_SENT,
            continuationEligible = false,
        )

        assertTrue(policy.allows(safe, 0))
        assertTrue(policy.allows(safe, 1))
        assertFalse(policy.allows(safe, 2))
        assertFalse(policy.allows(unsafe, 0))
        assertNotNull(policy.message(safe, 0))
    }

    @Test
    fun recoverableToolProjectionKeepsOneBoundedPreviewAndDurableLocator() {
        var spills = 0
        val output = "结果".repeat(5_000)

        val durable = projectRecoverableToolResult(
            value = output,
            retention = ToolResultRetention.DURABLE,
            usageMode = LocalUsageMode.WORK,
            callId = "call-1",
            spill = { id, value ->
                spills += 1
                id == "call-1" && value == output
            },
        )
        val ephemeral = projectRecoverableToolResult(
            value = output,
            retention = ToolResultRetention.EPHEMERAL,
            usageMode = LocalUsageMode.WORK,
            callId = "call-2",
            spill = { _, _ ->
                spills += 100
                true
            },
        )

        assertTrue(durable.truncated)
        assertTrue(durable.stored)
        assertTrue(durable.text.contains("tool_output_read"))
        assertTrue(durable.text.contains("call_id=call-1"))
        assertTrue(ephemeral.truncated)
        assertFalse(ephemeral.stored)
        assertTrue(ephemeral.text.contains("本机私有保留上限"))
        assertEquals(1, spills)
    }
}
