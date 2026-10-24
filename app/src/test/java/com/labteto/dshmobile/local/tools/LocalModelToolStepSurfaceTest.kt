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

    @Test fun newlyCreatedMcpCannotRunInTheConnectingStepEvenInABatch() = runBlocking {
        val surface = LocalModelToolStepSurface()
        surface.capture(JsonArray(listOf(schema("mcp_stdio_connect"), schema("read"))))
        val calls = listOf(
            call("1", "mcp_stdio_connect"),
            call("2", "mcp_x_twitter_x_post"),
            call("3", "read"),
        )
        val actuallyExecuted = mutableListOf<String>()
        val results = surface.executeVisibleBatch(calls) { visible ->
            actuallyExecuted += visible.map { it.name }
            visible.map { AgentToolResult("ok") }
        }
        assertEquals(listOf("mcp_stdio_connect", "read"), actuallyExecuted)
        assertEquals(listOf(false, true, false), results.map { it.isError })
        assertEquals("TOOL_NOT_EXPOSED", results[1].errorCode)

        // After a fresh model request exposes the new schema, the same call is admissible.
        surface.capture(JsonArray(listOf(schema("mcp_x_twitter_x_post"))))
        val next = surface.executeVisibleBatch(listOf(calls[1])) { visible ->
            actuallyExecuted += visible.map { it.name }
            visible.map { AgentToolResult("posted") }
        }
        assertFalse(next.single().isError)
        assertEquals("posted", next.single().content)
        assertTrue(actuallyExecuted.contains("mcp_x_twitter_x_post"))
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
