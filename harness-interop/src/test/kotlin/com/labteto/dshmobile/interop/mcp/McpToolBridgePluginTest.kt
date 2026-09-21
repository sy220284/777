package com.labteto.dshmobile.interop.mcp

import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolBridgePluginTest {
    @Test
    fun connectDiscoversRegistersCallsAndDisconnectsRemoteTools() = runTest {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var closed = false
        var capturedEndpoint: String? = null
        val transport = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests += method to params
                return when (method) {
                    "tools/list" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("tools", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "echo.tool")
                                    put("description", "Echo remote input")
                                    put("inputSchema", buildJsonObject {
                                        put("type", "object")
                                        put("properties", buildJsonObject {
                                            put("text", buildJsonObject { put("type", "string") })
                                        })
                                    })
                                })
                            })
                        })
                    }
                    "tools/call" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("isError", false)
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "remote-ok")
                                })
                            })
                        })
                    }
                    else -> error("unexpected method $method")
                }
            }

            override fun close() {
                closed = true
            }
        }
        val registry = PluginRegistry()
        registry.install(
            McpToolBridgePlugin(
                http = OkHttpClient(),
                json = Json,
                transportFactory = { endpoint ->
                    capturedEndpoint = endpoint
                    transport
                },
            ),
        )

        val connectInput = buildJsonObject {
            put("server_id", "demo")
            put("endpoint", "https://example.com/mcp?token=secret")
        }
        val blockedConnect = registry.context.tools.execute("mcp_http_connect", connectInput)
        assertTrue(blockedConnect.isError)

        val connect = registry.context.tools.execute(
            "mcp_http_connect",
            connectInput,
            context = ToolContext(approval = { true }),
        )
        assertFalse(connect.isError)
        assertEquals("https://example.com/mcp?token=secret", capturedEndpoint)

        val localName = "mcp_demo_echo_tool"
        assertTrue(registry.context.tools.names().contains(localName))

        val blockedCall = registry.context.tools.execute(
            localName,
            buildJsonObject { put("text", "hello") },
        )
        assertTrue(blockedCall.isError)

        val call = registry.context.tools.execute(
            localName,
            buildJsonObject { put("text", "hello") },
            context = ToolContext(approval = { true }),
        )
        assertFalse(call.isError)
        assertTrue(call.content.contains("remote-ok"))
        assertEquals("echo.tool", requests.last().second["name"]?.jsonPrimitive?.content)
        assertEquals("hello", requests.last().second["arguments"]?.let { it as JsonObject }?.get("text")?.jsonPrimitive?.content)

        val listing = registry.context.tools.execute("mcp_server_list", buildJsonObject { })
        assertTrue(listing.content.contains("https://example.com/mcp"))
        assertFalse(listing.content.contains("secret"))

        val disconnected = registry.context.tools.execute(
            "mcp_disconnect",
            buildJsonObject { put("server_id", "demo") },
            context = ToolContext(approval = { true }),
        )
        assertFalse(disconnected.isError)
        assertNull(registry.context.tools.get(localName))
        assertTrue(closed)
    }

    @Test
    fun connectRejectsCredentialsEmbeddedInEndpoint() = runTest {
        val registry = PluginRegistry()
        registry.install(
            McpToolBridgePlugin(
                http = OkHttpClient(),
                json = Json,
                transportFactory = { error("transport should not be created") },
            ),
        )

        val result = runCatching {
            registry.context.tools.execute(
                "mcp_http_connect",
                buildJsonObject {
                    put("server_id", "demo")
                    put("endpoint", "https://user:pass@example.com/mcp")
                },
                context = ToolContext(approval = { true }),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("禁止内嵌用户名或密码"))
    }
}
