package com.labteto.dshmobile.interop.mcp

import java.io.Closeable
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

const val CURRENT_MCP_PROTOCOL_VERSION = "2026-07-28"
const val LEGACY_MCP_PROTOCOL_VERSION = "2025-06-18"
const val LEGACY_SSE_MCP_PROTOCOL_VERSION = "2024-11-05"

class McpHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("MCP HTTP $statusCode：${responseBody.take(2_000)}")

data class McpToolDefinition(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    val raw: JsonObject,
)

interface McpTransport : Closeable {
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject
}

class McpClient(
    private val transport: McpTransport,
) : Closeable {
    suspend fun listTools(): List<McpToolDefinition> {
        val result = transport.request("tools/list").requireResult()
        return result["tools"]?.jsonArray.orEmpty().map { element ->
            val tool = element.jsonObject
            McpToolDefinition(
                name = tool["name"]?.jsonPrimitive?.content ?: error("MCP 工具缺少 name"),
                description = tool["description"]?.jsonPrimitive?.content,
                inputSchema = tool["inputSchema"]?.jsonObject ?: JsonObject(emptyMap()),
                raw = tool,
            )
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): JsonObject =
        transport.request(
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        ).requireResult()

    suspend fun discover(): JsonObject =
        transport.request("server/discover").requireResult()

    override fun close() = transport.close()

    private fun JsonObject.requireResult(): JsonObject {
        this["error"]?.let { error -> throw IllegalStateException("MCP 返回错误：$error") }
        return this["result"]?.jsonObject ?: error("MCP 响应缺少 result")
    }
}

class McpStreamableHttpTransport(
    private val endpoint: String,
    private val http: OkHttpClient,
    private val json: Json,
    private val protocolVersion: String = CURRENT_MCP_PROTOCOL_VERSION,
    private val clientName: String = "777-android",
    private val clientVersion: String = "1",
    private val clientCapabilities: JsonObject = JsonObject(emptyMap()),
) : McpTransport {
    private val ids = AtomicLong(1L)

    override suspend fun request(method: String, params: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val id = ids.getAndIncrement()
            val enriched = params.withMetadata(
                protocolVersion = protocolVersion,
                clientName = clientName,
                clientVersion = clientVersion,
                clientCapabilities = clientCapabilities,
            )
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", enriched)
            }
            val builder = Request.Builder()
                .url(endpoint)
                .post(
                    json.encodeToString(JsonObject.serializer(), payload)
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", protocolVersion)
                .header("Mcp-Method", method)

            enriched["name"]?.jsonPrimitive?.content?.let {
                builder.header("Mcp-Name", encodeHeaderValue(it))
            }
            enriched["uri"]?.jsonPrimitive?.content?.let {
                builder.header("Mcp-Name", encodeHeaderValue(it))
            }

            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw McpHttpException(response.code, body)
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.startsWith("text/event-stream", ignoreCase = true)) {
                    parseSseResponse(body, id, json)
                } else {
                    json.parseToJsonElement(body).jsonObject
                }
            }
        }

    override fun close() = Unit

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class McpStdioTransport(
    private val command: List<String>,
    private val json: Json,
    private val workingDirectory: File? = null,
    private val protocolVersion: String = CURRENT_MCP_PROTOCOL_VERSION,
    private val clientName: String = "777-android",
    private val clientVersion: String = "1",
) : McpTransport {
    private val ids = AtomicLong(1L)
    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private var reader: java.io.BufferedReader? = null

    override suspend fun request(method: String, params: JsonObject): JsonObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureStarted()
            val id = ids.getAndIncrement()
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put(
                    "params",
                    params.withMetadata(
                        protocolVersion = protocolVersion,
                        clientName = clientName,
                        clientVersion = clientVersion,
                        clientCapabilities = JsonObject(emptyMap()),
                    ),
                )
            }
            writer!!.apply {
                write(json.encodeToString(JsonObject.serializer(), payload))
                newLine()
                flush()
            }
            while (true) {
                val line = reader!!.readLine() ?: error("MCP stdio 进程已结束")
                if (line.isBlank()) continue
                val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: continue
                if (message["id"]?.jsonPrimitive?.content == id.toString()) {
                    return@withContext message
                }
            }
            error("不可达")
        }
    }

    override fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        process?.takeIf(Process::isAlive)?.destroyForcibly()
        process = null
        writer = null
        reader = null
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        require(command.isNotEmpty()) { "MCP stdio 命令不能为空" }
        val next = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process = next
        writer = next.outputStream.bufferedWriter()
        reader = next.inputStream.bufferedReader()
    }
}

private fun JsonObject.withMetadata(
    protocolVersion: String,
    clientName: String,
    clientVersion: String,
    clientCapabilities: JsonObject,
): JsonObject = buildJsonObject {
    this@withMetadata.forEach { (key, value) -> put(key, value) }
    put("_meta", buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", protocolVersion)
        put("io.modelcontextprotocol/clientInfo", buildJsonObject {
            put("name", clientName)
            put("version", clientVersion)
        })
        put("io.modelcontextprotocol/clientCapabilities", clientCapabilities)
    })
}

internal fun parseSseResponse(body: String, id: Long, json: Json): JsonObject {
    var matching: JsonObject? = null
    body.lineSequence().forEach { line ->
        if (!line.startsWith("data:")) return@forEach
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return@forEach
        val objectValue = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return@forEach
        if (objectValue["id"]?.jsonPrimitive?.content == id.toString()) matching = objectValue
    }
    return matching ?: error("MCP SSE 未返回请求 $id 的最终响应")
}

internal fun encodeHeaderValue(value: String): String =
    if (value.all { it.code in 0x20..0x7E }) value
    else "base64:" + Base64.getEncoder().encodeToString(value.toByteArray())
