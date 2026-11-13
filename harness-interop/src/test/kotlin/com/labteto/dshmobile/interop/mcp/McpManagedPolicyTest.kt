package com.labteto.dshmobile.interop.mcp

import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpManagedPolicyTest {
    private class CountingTransport : McpTransport {
        var remoteCalls = 0
        override suspend fun request(method: String, params: JsonObject): JsonObject {
            if (method == "tools/list") return buildJsonObject {
                put("result", buildJsonObject {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "echo")
                            put("inputSchema", buildJsonObject { put("type", "object") })
                        })
                    })
                })
            }
            if (method == "tools/call") remoteCalls++
            return buildJsonObject { put("result", buildJsonObject {}) }
        }
        override fun close() = Unit
    }

    @Test fun policyDenialBlocksConnectBeforeTransportDiscovery() = runBlocking {
        var created = 0
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            verifyHttpEndpoint = { throw IllegalStateException("网络策略已拒绝此域名") },
            transportFactory = { created++; CountingTransport() },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        try {
            val failed = runCatching {
                plugin.connectHttpFromUi(registry.context, "blocked", "https://example.com/mcp")
            }
            assertTrue(failed.isFailure)
            assertEquals(0, created)
            assertTrue(plugin.serverSnapshots().isEmpty())
        } finally { plugin.uninstall(registry.context) }
    }

    @Test fun policyChangeRejectsRegisteredHttpToolBeforeRemoteCall() = runBlocking {
        var allowed = true
        val transport = CountingTransport()
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            verifyHttpEndpoint = { endpoint ->
                assertTrue(endpoint.startsWith("https://example.com/"))
                check(allowed) { "管理策略已禁止远程 MCP" }
            },
            transportFactory = { transport },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        try {
            plugin.connectHttpFromUi(registry.context, "policy", "https://example.com/mcp")
            val tool = "mcp_policy_echo"
            assertTrue(tool in plugin.serverSnapshots().single().tools)
            allowed = false
            val blocked = registry.context.tools.execute(
                tool, buildJsonObject {}, context = ToolContext(approval = { true }),
            )
            assertTrue(blocked.content, blocked.isError)
            assertEquals(0, transport.remoteCalls)
            allowed = true
            val permitted = registry.context.tools.execute(
                tool, buildJsonObject {}, context = ToolContext(approval = { true }),
            )
            assertFalse(permitted.content, permitted.isError)
            assertEquals(1, transport.remoteCalls)
        } finally { plugin.uninstall(registry.context) }
    }
}
