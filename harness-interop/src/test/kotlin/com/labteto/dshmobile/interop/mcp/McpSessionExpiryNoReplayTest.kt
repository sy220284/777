package com.labteto.dshmobile.interop.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real legacy streamable transport through OkHttp interception. A 404 may be
 * returned AFTER a paid/irreversible tools/call reached the server. The host
 * must never silently reinitialize and execute that tool twice.
 */
class McpSessionExpiryNoReplayTest {
    private fun transport(
        requests: MutableList<String>,
        respond: (method: String, occurrence: Int) -> Pair<Int, String>,
    ): McpLegacyStreamableHttpTransport {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            val method = Json.parseToJsonElement(buffer.readUtf8()).jsonObject["method"]
                ?.jsonPrimitive?.content ?: error("missing method")
            requests += method
            val count = requests.count { it == method }
            val (code, body) = respond(method, count)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .header("Mcp-Session-Id", if (count <= 1) "old" else "new")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        return McpLegacyStreamableHttpTransport("https://example.invalid/mcp", http, Json)
    }

    @Test fun expiredLegacySessionNeverReplaysMutation() = runBlocking {
        val seen = mutableListOf<String>()
        val client = transport(seen) { method, _ ->
            when (method) {
                "initialize" -> 200 to """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""
                "notifications/initialized" -> 202 to ""
                "tools/call" -> 404 to """{"error":"session expired"}"""
                else -> error("unexpected $method")
            }
        }
        try {
            val failure = runCatching { client.request("tools/call", JsonObject(emptyMap())) }
                .exceptionOrNull()
            assertTrue(failure is McpHttpException)
            assertEquals(404, (failure as McpHttpException).statusCode)
            assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), seen)
        } finally { client.close() }
    }

    @Test fun expiredSessionMayReinitializeForReadOnlyDiscoveryOnly() = runBlocking {
        val seen = mutableListOf<String>()
        val client = transport(seen) { method, occurrence ->
            when (method) {
                "initialize" -> 200 to """{"jsonrpc":"2.0","id":${if (occurrence == 1) 1 else 3},"result":{"protocolVersion":"2025-06-18"}}"""
                "notifications/initialized" -> 202 to ""
                "tools/list" -> if (occurrence == 1) 404 to "{}"
                    else 200 to """{"jsonrpc":"2.0","id":4,"result":{"tools":[]}}"""
                else -> error("unexpected $method")
            }
        }
        try {
            val result = client.request("tools/list", JsonObject(emptyMap()))
            assertTrue(result["result"] != null)
            assertEquals(
                listOf("initialize", "notifications/initialized", "tools/list",
                    "initialize", "notifications/initialized", "tools/list"), seen)
        } finally { client.close() }
    }

    @Test fun onlyAnExplicitReadOnlyAllowlistMayReplayAfterExpiry() {
        assertTrue(canReplayMcpAfterSessionExpiry("tools/list"))
        assertTrue(canReplayMcpAfterSessionExpiry("ping"))
        assertTrue(canReplayMcpAfterSessionExpiry("server/discover"))
        assertFalse(canReplayMcpAfterSessionExpiry("tools/call"))
        assertFalse(canReplayMcpAfterSessionExpiry("resources/write"))
        assertFalse(canReplayMcpAfterSessionExpiry("tools/unknown"))
    }
}
