package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelToolStepSurfaceTest {
    private fun call(id: String, name: String) =
        AgentToolCall(id = id, name = name, arguments = JsonObject(emptyMap()))

    private fun schema(name: String) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject { put("name", name) })
    }

    @Test fun modelAcceptsOnlyStableGatewayEvenIfTargetPluginsChange() = runBlocking {
        val surface = LocalModelToolStepSurface()
        surface.capture(JsonArray(listOf(schema("tool_search"), schema("tool_call"))))
        val calls = listOf(
            call("1", "tool_search"),
            call("2", "mcp_plugin_direct"),
            call("3", "tool_call"),
        )
        val executed = mutableListOf<String>()
        val result = surface.executeVisibleBatch(calls) { visible ->
            executed += visible.map { it.name }
            visible.map { AgentToolResult("ok") }
        }
        assertEquals(listOf("tool_search", "tool_call"), executed)
        assertEquals("TOOL_NOT_EXPOSED", result[1].errorCode)
        assertTrue(result[1].recoveryHint.orEmpty().contains("tool_call"))
        assertFalse(result[0].isError)
        assertFalse(result[2].isError)
    }

    @Test fun hiddenOnlyBatchNeverInvokesExecutor() = runBlocking {
        val surface = LocalModelToolStepSurface()
        surface.capture(JsonArray(listOf(schema("read"))))
        var invoked = false
        val result = surface.executeVisibleBatch(listOf(call("hidden", "mcp_secret_write"))) {
            invoked = true
            listOf(AgentToolResult("wrong"))
        }
        assertFalse(invoked)
        assertEquals("TOOL_NOT_EXPOSED", result.single().errorCode)
    }
}
