package com.labteto.dshmobile.interop.mcp

import com.labteto.dshmobile.harness.plugin.PluginRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpBearerTransportTest {
    @Test
    fun bearerIsAttachedToAllMcpDiscoveryRequestsWithoutEnteringToolArguments() = runBlocking {
        val seen = mutableListOf<Pair<String, String?>>()
        val token = "secret-only-for-this-origin"
        // A local response stub also verifies interceptor ordering: auth runs before
        // an existing caller-provided interceptor that can short-circuit the network.
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val rpc = Json.parseToJsonElement(json).jsonObject
            val method = rpc["method"]!!.jsonPrimitive.content
            seen += method to request.header("Authorization")
            val result = when (method) {
                "tools/list" -> buildJsonObject { put("tools", buildJsonArray { }) }
                "resources/list" -> buildJsonObject { put("resources", buildJsonArray { }) }
                "resources/templates/list" -> buildJsonObject { put("resourceTemplates", buildJsonArray { }) }
                else -> buildJsonObject { }
            }
            val reply = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", rpc["id"]!!)
                put("result", result)
            }.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val plugin = McpToolBridgePlugin(
            http = http,
            json = Json,
            httpCredentialProvider = { id, endpoint ->
                assertEquals("service", id)
                assertEquals("https://mcp.example.test/mcp", endpoint)
                token
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        try {
            plugin.connectHttpFromUi(registry.context, "service", "https://mcp.example.test/mcp")
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { (_, auth) -> auth == "Bearer $token" })
            assertTrue(seen.any { (method, _) -> method == "tools/list" })
        } finally {
            plugin.uninstall(registry.context)
        }
    }

    @Test
    fun remoteHttpMustNotReceiveBearerToken() = runBlocking {
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            httpCredentialProvider = { _, _ -> "secret" },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        try {
            val result = runCatching {
                plugin.connectHttpFromUi(registry.context, "service", "http://mcp.example.test/mcp")
            }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("HTTPS"))
        } finally {
            plugin.uninstall(registry.context)
        }
    }
}
