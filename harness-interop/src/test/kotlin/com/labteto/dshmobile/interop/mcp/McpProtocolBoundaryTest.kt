package com.labteto.dshmobile.interop.mcp

import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolBoundaryTest {
    @Test
    fun paginatedToolsCarryCursorAndIncludeEveryPage() = runTest {
        val cursors = mutableListOf<String?>()
        val client = McpClient(object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                assertEquals("tools/list", method)
                cursors += params["cursor"]?.jsonPrimitive?.content
                return page(if (cursors.size == 1) "first" else "second", if (cursors.size == 1) "next" else null)
            }
            override fun close() = Unit
        })
        assertEquals(listOf("first", "second"), client.listTools().map { it.name })
        assertEquals(listOf(null, "next"), cursors)
    }

    @Test
    fun duplicateCursorsAndToolNamesAreRejectedWithoutAnInfiniteLoop() = runTest {
        for (sameNames in listOf(false, true)) {
            var requests = 0
            val client = McpClient(object : McpTransport {
                override suspend fun request(method: String, params: JsonObject): JsonObject {
                    requests++
                    return page(if (sameNames) "tool" else "tool-$requests", "same")
                }
                override fun close() = Unit
            })
            val failure = runCatching { client.listTools() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(2, requests)
        }
    }

    @Test
    fun emptyPagesAreAllowedButTotalPageCountIsBounded() = runTest {
        var requests = 0
        val client = McpClient(object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests++
                return buildJsonObject { put("result", buildJsonObject {
                    put("tools", buildJsonArray { })
                    put("nextCursor", "cursor-$requests")
                }) }
            }
            override fun close() = Unit
        })
        assertTrue(runCatching { client.listTools() }.exceptionOrNull() is IllegalStateException)
        assertEquals(128, requests)
    }

    @Test
    fun toolCountIsBoundedAcrossPages() = runTest {
        var requests = 0
        val client = McpClient(object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests++
                return buildJsonObject { put("result", buildJsonObject {
                    put("tools", buildJsonArray {
                        repeat(65) { index -> add(buildJsonObject { put("name", "tool-$requests-$index") }) }
                    })
                    put("nextCursor", "cursor-$requests")
                }) }
            }
            override fun close() = Unit
        })
        assertTrue(runCatching { client.listTools() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, requests)
    }

    @Test
    fun multilineSseHandlesCommentsNotificationsUnicodeAndAllLineEndings() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val event = listOf(
                "\uFEFF: heartbeat", "data: {\"method\":\"notifications/progress\"}", "",
                "data: {\"id\":2,\"result\":{}}", "",
                "event: message", "data: {\"jsonrpc\":\"2.0\",\"id\":1,",
                "data: \"result\":{\"text\":\"中文\"}}", "", "",
            ).joinToString(newline)
            assertTrue(parseSseResponse(event, 1, Json).toString().contains("中文"))
        }
    }

    @Test
    fun matchingResponseReturnsBeforeServerClosesStream() {
        val data = "data: {\"id\":1,\"result\":{}}\n\n".toByteArray()
        val source = object : InputStream() {
            var offset = 0
            override fun read(): Int = if (offset < data.size) data[offset++].toInt() and 255
                else error("must not wait for EOF after complete response")
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (offset >= data.size) error("must not read after complete response")
                val count = minOf(len, data.size - offset)
                data.copyInto(buffer, off, offset, offset + count)
                offset += count
                return count
            }
        }
        assertEquals("1", readSseResponse(source, 1, Json)["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun oversizedAndMissingResponseSseFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) {
            readSseResponse(("data: " + "x".repeat(100)).byteInputStream(), 1, Json, maxBytes = 32)
        }
        assertThrows(IllegalStateException::class.java) {
            parseSseResponse("data: {\"id\":2,\"result\":{}}\n\n", 1, Json)
        }
    }

    @Test
    fun authAndRateLimitFailuresDoNotTriggerLegacyRequests() = runTest {
        for (code in listOf(401, 403, 408, 429)) {
            var requests = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("failure").body("{}".toResponseBody()).build()
            }.build()
            val transport = McpNegotiatingHttpTransport("http://example.com/mcp", http, Json)
            try {
                val failure = runCatching { transport.request("tools/list") }.exceptionOrNull()
                assertTrue(failure is McpHttpException)
                assertEquals(code, (failure as McpHttpException).statusCode)
                assertEquals(1, requests)
            } finally { transport.close() }
        }
    }

    @Test
    fun malformedAndOversizedCursorsAreRejected() = runTest {
        for (cursor in listOf(JsonPrimitive(1), JsonPrimitive(""), JsonPrimitive("x".repeat(4_097)))) {
            val client = McpClient(object : McpTransport {
                override suspend fun request(method: String, params: JsonObject) = buildJsonObject {
                    put("result", buildJsonObject { put("tools", buildJsonArray { }); put("nextCursor", cursor) })
                }
                override fun close() = Unit
            })
            assertTrue(runCatching { client.listTools() }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun definitionByteBudgetAppliesAcrossPages() = runTest {
        var requests = 0
        val description = "x".repeat(4 * 1024 * 1024)
        val client = McpClient(object : McpTransport {
            override suspend fun request(method: String, params: JsonObject) = buildJsonObject {
                requests++
                put("result", buildJsonObject {
                    put("tools", buildJsonArray { add(buildJsonObject {
                        put("name", "tool-$requests"); put("description", description)
                    }) })
                    put("nextCursor", "cursor-$requests")
                })
            }
            override fun close() = Unit
        })
        assertTrue(runCatching { client.listTools() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, requests)
    }

    @Test
    fun persistentSseHasPerEventBoundsWithoutBoundingItsWholeLifetime() {
        val events = mutableListOf<String>()
        readMcpSseEvents("event: endpoint\r\ndata: /post\r\n\r\ndata: one\n\ndata: two\n\n".byteInputStream(), maxEventBytes = 40) { _, data ->
            events += data
            false
        }
        assertEquals(listOf("/post", "one", "two"), events)
        assertThrows(IllegalArgumentException::class.java) {
            readMcpSseEvents("data: ${"x".repeat(100)}".byteInputStream(), maxEventBytes = 32) { _, _ -> false }
        }
    }

    private fun page(name: String, next: String?): JsonObject = buildJsonObject {
        put("result", buildJsonObject {
            put("tools", buildJsonArray { add(buildJsonObject { put("name", name) }) })
            next?.let { put("nextCursor", it) }
        })
    }
}
