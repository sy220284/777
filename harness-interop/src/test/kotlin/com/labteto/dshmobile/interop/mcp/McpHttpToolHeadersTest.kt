package com.labteto.dshmobile.interop.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpToolHeadersTest {
    private fun schema(source: String) = Json.parseToJsonElement(source).jsonObject

    @Test fun nestedPropertiesUseTheExactPathAndNullOrMissingValuesAreOmitted() {
        val rules = McpHttpToolHeaders.compile(schema("""{
          "type":"object","properties":{
            "tenant":{"type":"object","properties":{
              "region":{"type":"string","x-mcp-header":"Region"},
              "enabled":{"type":["boolean","null"],"x-mcp-header":"Enabled"}
            }},
            "count":{"type":"integer","x-mcp-header":"Count"}
          }
        }"""))
        assertEquals(3, rules.size)
        val request = Request.Builder().url("https://example.com/mcp")
        McpHttpToolHeaders.apply(request, rules, schema("""{
          "tenant":{"region":"Hello, 世界","enabled":null},
          "region":"must-not-be-used","count":42.0
        }"""))
        val sent = request.build()
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", sent.header("Mcp-Param-Region"))
        assertEquals("42", sent.header("Mcp-Param-Count"))
        assertNull(sent.header("Mcp-Param-Enabled"))
        val absent = Request.Builder().url("https://example.com/mcp")
        McpHttpToolHeaders.apply(absent, rules, schema("{}"))
        assertNull(absent.build().header("Mcp-Param-Region"))
    }

    @Test fun rejectsNonReachablePathsDuplicatesBadNamesAndNonPrimitiveTypes() {
        val invalid = listOf(
            """{"type":"object","properties":{"x":{"type":"number","x-mcp-header":"Number"}}}""",
            """{"type":"object","properties":{"x":{"type":"string","x-mcp-header":"bad name"}}}""",
            """{"type":"object","properties":{"x":{"type":"string","x-mcp-header":"X\\r\\nInjected"}}}""",
            """{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"Region"},"b":{"type":"string","x-mcp-header":"region"}}}""",
            """{"type":"object","properties":{"a":{"type":"array","items":{"type":"string","x-mcp-header":"Wrong"}}}}""",
            """{"type":"object","allOf":[{"properties":{"a":{"type":"string","x-mcp-header":"Wrong"}}}]}""",
            """{"type":"object","properties":{"a":{"type":"string","oneOf":[{"type":"string","x-mcp-header":"Wrong"}]}}}""",
            """{"type":"object","properties":{"a":{"type":"string","x-mcp-header":""}}}""",
            """{"type":"object","properties":{"a":{"x-mcp-header":"MissingType"}}}""",
        )
        invalid.forEach { source ->
            assertThrows(source, IllegalArgumentException::class.java) {
                McpHttpToolHeaders.compile(schema(source))
            }
        }
        val dollar = '$'
        assertThrows(IllegalArgumentException::class.java) {
            McpHttpToolHeaders.compile(schema("""{"type":"object",
              "${dollar}defs":{"x":{"type":"string","x-mcp-header":"FromRef"}},
              "properties":{"p":{"${dollar}ref":"#/${dollar}defs/x"}}}"""))
        }
    }

    @Test fun safeIntegerRangeIsEnforcedBeforeSending() {
        val rules = McpHttpToolHeaders.compile(schema("""{"type":"object","properties":{
          "n":{"type":"integer","x-mcp-header":"Number"}}}"""))
        val valid = Request.Builder().url("https://example.com/mcp")
        McpHttpToolHeaders.apply(valid, rules, schema("""{"n":9007199254740991}"""))
        assertEquals("9007199254740991", valid.build().header("Mcp-Param-Number"))
        for (bad in listOf("9007199254740992", "-9007199254740992", "1.5", "\"2\"")) {
            val builder = Request.Builder().url("https://example.com/mcp")
            assertThrows(bad, IllegalArgumentException::class.java) {
                McpHttpToolHeaders.apply(builder, rules, schema("""{"n":$bad}"""))
            }
        }
    }

    @Test fun modernNegotiatingWireUsesTheSameRulesAsAndroidWorker() = runTest {
        val captured = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            captured += request
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val rpc = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val id = rpc.getValue("id").jsonPrimitive.content
            val method = rpc.getValue("method").jsonPrimitive.content
            val result = when (method) {
                "tools/list" -> """{"tools":[
                  {"name":"echo","inputSchema":{"type":"object","properties":{
                    "tenant":{"type":"object","properties":{
                      "region":{"type":"string","x-mcp-header":"Region"}
                    }}
                  }}},
                  {"name":"invalid","inputSchema":{"type":"object","properties":{
                    "n":{"type":"number","x-mcp-header":"N"}
                  }}}
                ]}"""
                "tools/call" -> """{"resultType":"complete"}"""
                else -> error("unexpected RPC $method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "application/json")
                .body("""{"jsonrpc":"2.0","id":$id,"result":$result}""".toResponseBody())
                .build()
        }.build()
        val wire = McpNegotiatingHttpTransport("https://example.com/mcp", http, Json)
        try {
            // This wrapper mimics the Binder boundary: host observes RPC responses,
            // while the wire in the other process compiled headers from tools/list.
            val binderBoundary = object : McpTransport {
                override suspend fun request(method: String, params: JsonObject) =
                    wire.request(method, params)
                override fun close() = wire.close()
            }
            val client = McpClient(binderBoundary)
            val discovery = client.discoverTools(enforceHttpHeaders = true)
            assertEquals(listOf("echo"), discovery.tools.map { it.name })
            assertTrue(discovery.rejectedTools.getValue("invalid").contains("x-mcp-header"))
            client.callTool("echo", schema("""{"tenant":{"region":"west"}}"""))
            assertEquals(listOf("tools/list", "tools/call"),
                captured.map { it.header("Mcp-Method") })
            assertEquals("echo", captured.last().header("Mcp-Name"))
            assertEquals("west", captured.last().header("Mcp-Param-Region"))
            assertNull(captured.last().header("Mcp-Param-N"))
            assertThrows(IllegalStateException::class.java) {
                // Rejected tools never acquire usable wire-header metadata.
                kotlinx.coroutines.runBlocking {
                    client.callTool("invalid", schema("""{"n":1}"""))
                }
            }
            assertEquals(2, captured.size)
        } finally { wire.close() }
    }
}
