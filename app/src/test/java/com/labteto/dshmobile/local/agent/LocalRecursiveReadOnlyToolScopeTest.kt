package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.labteto.dshmobile.harness.agent.AgentToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecursiveReadOnlyToolScopeTest {
    private val tree = LocalSubagentCapabilities(maxDepth = 3, depth = 1)
    private fun tool(name: String) = JsonObject(mapOf("function" to JsonObject(
        mapOf("name" to JsonPrimitive(name)),
    )))

    @Test fun modelVisibilityAndActualExecutionBothRejectSideEffectfulNetworkAndShellTools() {
        val offered = JsonArray(listOf(
            tool("read"), tool("web_search"), tool("web_fetch"), tool("subagent"),
            tool("http_request"), tool("bash"), tool("write"), tool("download_file"),
            tool("mcp_http_connect"), tool("android_tap"),
        ))
        val visible = LocalRecursiveReadOnlyToolScope.visibleSchemas(offered, tree)
        assertEquals(4, visible.size)
        val labels = visible.map {
            ((it as JsonObject)["function"] as JsonObject)["name"].toString().trim('"')
        }.toSet()
        assertEquals(setOf("read", "web_search", "web_fetch", "subagent"), labels)
        for (blocked in listOf("http_request", "bash", "write", "download_file",
            "mcp_http_connect", "android_tap")) {
            val denied = LocalRecursiveReadOnlyToolScope.rejection(blocked, tree)
            assertTrue(blocked, denied?.isError == true)
            assertEquals("NESTED_READONLY_TOOL_BLOCKED", denied?.errorCode)
            assertFalse(blocked in labels)
        }
    }

    @Test fun backgroundFetchCannotEscapeNestedAgentLifecycle() {
        val permitted = AgentToolCall("fetch", "web_fetch", buildJsonObject {
            put("url", "https://example.test")
            put("run_in_background", false)
        })
        assertNull(LocalRecursiveReadOnlyToolScope.rejection(permitted, tree))
        val detached = permitted.copy(arguments = buildJsonObject {
            put("url", "https://example.test")
            put("run_in_background", true)
        })
        val denial = LocalRecursiveReadOnlyToolScope.rejection(detached, tree)
        assertEquals("NESTED_READONLY_BACKGROUND_BLOCKED", denial?.errorCode)
        val supplied = JsonArray(listOf(JsonObject(mapOf("function" to JsonObject(
            mapOf(
                "name" to JsonPrimitive("web_fetch"),
                "parameters" to JsonObject(mapOf(
                    "properties" to JsonObject(mapOf(
                        "url" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "run_in_background" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                    )),
                )),
            ),
        )))))
        val visible = LocalRecursiveReadOnlyToolScope.visibleSchemas(supplied, tree)
        assertFalse(visible.toString().contains("run_in_background"))
    }

    @Test fun terminalChildCannotAdvertiseOrInvokeAnotherSubagent() {
        val leaf = tree.copy(depth = 3)
        assertFalse(LocalRecursiveReadOnlyToolScope.permits("subagent", leaf))
        assertEquals(1, LocalRecursiveReadOnlyToolScope.visibleSchemas(
            JsonArray(listOf(tool("subagent"), tool("read"))), leaf,
        ).size)
        assertTrue(LocalRecursiveReadOnlyToolScope.rejection("subagent", leaf)?.isError == true)
        assertNull(LocalRecursiveReadOnlyToolScope.rejection("read", leaf))
    }

    @Test fun historicalSingleLevelToolScopeRemainsUnchanged() {
        val single = LocalSubagentCapabilities(maxDepth = 1)
        assertTrue(LocalRecursiveReadOnlyToolScope.permits("http_request", single))
        assertNull(LocalRecursiveReadOnlyToolScope.rejection("http_request", single))
        val schemas = JsonArray(listOf(tool("read"), tool("http_request")))
        assertEquals(schemas, LocalRecursiveReadOnlyToolScope.visibleSchemas(schemas, single))
    }
}
