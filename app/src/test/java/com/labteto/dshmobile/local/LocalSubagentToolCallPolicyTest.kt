package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.local.agent.LocalSubagentToolCallPolicy
import com.labteto.dshmobile.local.tools.LocalModelToolStepSurface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubagentToolCallPolicyTest {
    @Test
    fun readonlySubagentCannotUseMainScreenWithoutVirtualLease() {
        val policy = policy(allowMutation = false, virtualScreenId = null, exposed = "android_tap")

        val result = policy.rejection(call("android_tap"))

        assertEquals("SUBAGENT_DEVICE_SCOPE_BLOCKED", result?.errorCode)
    }

    @Test
    fun virtualScreenCallMustUseAssignedScreenId() {
        val policy = policy(
            allowMutation = false,
            virtualScreenId = "screen-1",
            exposed = "android_vscreen_tap",
        )

        val result = policy.rejection(call("android_vscreen_tap", "screen-2"))

        assertEquals("SUBAGENT_VIRTUAL_SCREEN_MISMATCH", result?.errorCode)
    }

    @Test
    fun unexposedProviderCallIsRejectedBeforeExecution() {
        val policy = policy(allowMutation = true, virtualScreenId = null, exposed = "read")

        val result = policy.rejection(call("write"))

        assertEquals("TOOL_NOT_EXPOSED", result?.errorCode)
    }

    @Test
    fun exposedVirtualScreenCallWithAssignedIdPasses() {
        val policy = policy(
            allowMutation = false,
            virtualScreenId = "screen-1",
            exposed = "android_vscreen_tap",
        )

        assertNull(policy.rejection(call("android_vscreen_tap", "screen-1")))
    }

    private fun policy(
        allowMutation: Boolean,
        virtualScreenId: String?,
        exposed: String,
    ): LocalSubagentToolCallPolicy {
        val surface = LocalModelToolStepSurface()
        surface.capture(
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", exposed)
                            },
                        )
                    },
                ),
            ),
        )
        return LocalSubagentToolCallPolicy(allowMutation, virtualScreenId, surface)
    }

    private fun call(name: String, screenId: String? = null): AgentToolCall =
        AgentToolCall(
            id = "call-1",
            name = name,
            arguments = buildJsonObject {
                screenId?.let { put("id", it) }
            },
        )
}
