package com.labteto.dshmobile.interop.mcp

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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

            http.newCall(builder.build()).executeCancellable { response ->
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
    private val lineProcess = McpLineProcess(command, workingDirectory)

    override suspend fun request(method: String, params: JsonObject): JsonObject = mutex.withLock {
        try {
            lineProcess.ensureStarted()
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
            lineProcess.writeLine(json.encodeToString(JsonObject.serializer(), payload))
            while (true) {
                val line = lineProcess.readLine()
                if (line.isBlank()) continue
                val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: continue
                if (message["id"]?.jsonPrimitive?.content == id.toString()) return@withLock message
            }
            error("不可达")
        } catch (error: CancellationException) {
            lineProcess.abort()
            throw error
        }
    }

    override fun close() = lineProcess.close()
}

internal class McpLineProcess(
    private val command: List<String>,
    private val workingDirectory: File? = null,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private var lines: Channel<String>? = null
    private var readerJob: Job? = null
    private var closed = false

    /**
     * Ensures a child process exists.
     *
     * @return true when a new process was started, including restart after a crash.
     */
    fun ensureStarted(): Boolean {
        check(!closed) { "MCP stdio 传输已关闭" }
        if (process?.isAlive == true) return false
        resetProcess()
        require(command.isNotEmpty()) { "MCP stdio 命令不能为空" }

        val next = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val channel = Channel<String>(Channel.UNLIMITED)
        process = next
        writer = next.outputStream.bufferedWriter()
        lines = channel
        readerJob = scope.launch {
            try {
                next.inputStream.bufferedReader().use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        if (channel.trySend(line).isFailure) break
                    }
                }
                channel.close(IllegalStateException("MCP stdio 进程已结束"))
            } catch (error: Throwable) {
                channel.close(error)
            }
        }
        return true
    }

    suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
        val output = writer ?: error("MCP stdio 进程未启动")
        output.write(line)
        output.newLine()
        output.flush()
    }

    suspend fun readLine(): String {
        val channel = lines ?: error("MCP stdio 进程未启动")
        val result = channel.receiveCatching()
        result.getOrNull()?.let { return it }
        val error = result.exceptionOrNull() ?: IllegalStateException("MCP stdio 进程已结束")
        abort()
        throw error
    }

    fun abort() {
        if (!closed) resetProcess()
    }

    private fun resetProcess() {
        readerJob?.cancel()
        readerJob = null
        runCatching { writer?.close() }
        writer = null
        lines?.cancel()
        lines = null
        process?.let { child ->
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
            runCatching { child.outputStream.close() }
            if (child.isAlive) child.destroyForcibly()
        }
        process = null
    }

    override fun close() {
        if (closed) return
        closed = true
        resetProcess()
        scope.cancel()
    }
}

internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    val token = continuation.tryResumeWithException(error)
                    if (token != null) continuation.completeResume(token)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val value = block(it)
                            val token = continuation.tryResume(value)
                            if (token != null) continuation.completeResume(token)
                        }
                    } catch (error: Throwable) {
                        val token = continuation.tryResumeWithException(error)
                        if (token != null) continuation.completeResume(token)
                    }
                }
            },
        )
    }

internal suspend fun Call.awaitResponseCancellable(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    val token = continuation.tryResumeWithException(error)
                    if (token != null) continuation.completeResume(token)
                }

                override fun onResponse(call: Call, response: Response) {
                    val token = continuation.tryResume(response)
                    if (token != null) {
                        continuation.completeResume(token)
                    } else {
                        response.close()
                    }
                }
            },
        )
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
