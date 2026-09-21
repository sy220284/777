package com.labteto.dshmobile.interop.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    @Test
    fun listsAndCallsToolsThroughTransport() = runTest {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val transport = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests += method to params
                return when (method) {
                    "tools/list" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("tools", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "echo")
                                    put("description", "echo")
                                    put("inputSchema", buildJsonObject {
                                        put("type", "object")
                                    })
                                })
                            })
                        })
                    }
                    "tools/call" -> buildJsonObject {
                        put("result", buildJsonObject { put("resultType", "complete") })
                    }
                    else -> error("unexpected method")
                }
            }

            override fun close() = Unit
        }

        val client = McpClient(transport)
        val tools = client.listTools()
        assertEquals("echo", tools.single().name)
        client.callTool("echo", buildJsonObject { put("text", "hi") })
        assertEquals(listOf("tools/list", "tools/call"), requests.map { it.first })
        assertEquals("echo", requests.last().second["name"].toString().trim('"'))
    }

    @Test
    fun headerEncodingPreservesAsciiAndProtectsUnicode() {
        assertEquals("plain", encodeHeaderValue("plain"))
        val encoded = encodeHeaderValue("中文")
        assertTrue(encoded.startsWith("base64:"))
    }
}
