package com.labteto.dshmobile.interop.mcp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import kotlinx.serialization.json.JsonNull
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
        val tools = mutableListOf<McpToolDefinition>()
        val names = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var definitionBytes = 0
        repeat(MAX_TOOL_PAGES) {
            val result = transport.request("tools/list", buildJsonObject {
                cursor?.let { put("cursor", it) }
            }).requireResult()
            result["tools"]?.jsonArray.orEmpty().forEach { element ->
                val tool = element.jsonObject
                val name = tool["name"]?.jsonPrimitive?.content ?: error("MCP 工具缺少 name")
                require(name.isNotBlank() && names.add(name)) { "MCP 工具名称为空或重复：$name" }
                require(tools.size < MAX_TOOLS) { "MCP 工具数量超过 $MAX_TOOLS 上限" }
                val bytes = tool.toString().toByteArray(Charsets.UTF_8).size
                require(bytes <= MAX_DEFINITION_BYTES - definitionBytes) { "MCP 工具定义总大小超过上限" }
                definitionBytes += bytes
                tools += McpToolDefinition(
                    name = name,
                    description = tool["description"]?.jsonPrimitive?.content,
                    inputSchema = tool["inputSchema"]?.jsonObject ?: JsonObject(emptyMap()),
                    raw = tool,
                )
            }
            val next = result["nextCursor"]?.takeUnless { it == JsonNull } ?: return tools
            val value = next.jsonPrimitive
            require(value.isString && value.content.length in 1..MAX_CURSOR_CHARS && cursors.add(value.content)) {
                "MCP 分页游标无效或重复"
            }
            cursor = value.content
        }
        error("MCP 工具分页超过 $MAX_TOOL_PAGES 页上限")
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

    private companion object {
        const val MAX_TOOLS = 128
        const val MAX_TOOL_PAGES = 128
        const val MAX_DEFINITION_BYTES = 8 * 1024 * 1024
        const val MAX_CURSOR_CHARS = 4_096
    }

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
                if (!response.isSuccessful) {
                    throw McpHttpException(response.code, response.readMcpBodyBounded())
                }
                response.readMcpRpcResponse(id, json)
            }
        }

    override fun close() = Unit

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal class McpLineProcess(
    private val command: List<String>,
    private val workingDirectory: File? = null,
    private val commandResolver: (List<String>) -> List<String> = { it },
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private data class QueuedLine(val value: String, val utf8Bytes: Int)
    private data class LineQueue(
        val channel: Channel<QueuedLine>,
        val queuedBytes: AtomicLong = AtomicLong(),
    )

    private var writer: java.io.BufferedWriter? = null
    private var lines: LineQueue? = null
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

        val resolvedCommand = commandResolver(command)
        require(resolvedCommand.isNotEmpty()) { "MCP stdio 解析后的命令不能为空" }
        val next = ProcessBuilder(resolvedCommand)
            .directory(workingDirectory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment().putAll(environmentProvider()) }
            .start()
        val queue = LineQueue(Channel(STDIO_QUEUE_CAPACITY))
        process = next
        writer = next.outputStream.bufferedWriter()
        lines = queue
        readerJob = scope.launch {
            try {
                next.inputStream.bufferedReader().use { input ->
                    while (true) {
                        val line = readLineBounded(input, MAX_STDIO_LINE_CHARS) ?: break
                        val bytes = line.toByteArray(Charsets.UTF_8).size
                        val total = queue.queuedBytes.addAndGet(bytes.toLong())
                        if (total > MAX_STDIO_QUEUE_BYTES) {
                            queue.queuedBytes.addAndGet(-bytes.toLong())
                            error("MCP stdio 待处理响应超过总字节预算")
                        }
                        try {
                            queue.channel.send(QueuedLine(line, bytes))
                        } catch (error: Throwable) {
                            queue.queuedBytes.addAndGet(-bytes.toLong())
                            throw error
                        }
                    }
                }
                queue.channel.close(IllegalStateException("MCP stdio 进程已结束"))
            } catch (error: Throwable) {
                queue.channel.close(error)
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
        val queue = lines ?: error("MCP stdio 进程未启动")
        val result = queue.channel.receiveCatching()
        result.getOrNull()?.let { queued ->
            queue.queuedBytes.addAndGet(-queued.utf8Bytes.toLong())
            return queued.value
        }
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
        lines?.channel?.cancel()
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

    private fun readLineBounded(reader: java.io.BufferedReader, maxChars: Int): String? {
        val output = StringBuilder(minOf(maxChars, 8 * 1024))
        while (true) {
            val value = reader.read()
            if (value < 0) return output.takeIf { it.isNotEmpty() }?.toString()
            if (value == '\n'.code) return output.toString()
            if (value == '\r'.code) continue
            if (output.length >= maxChars) {
                throw IllegalStateException("MCP stdio 单行响应超过 ${maxChars} 字符上限")
            }
            output.append(value.toChar())
        }
    }

    private companion object {
        const val STDIO_QUEUE_CAPACITY = 8
        const val MAX_STDIO_LINE_CHARS = 2 * 1024 * 1024
        const val MAX_STDIO_QUEUE_BYTES = 4L * 1024L * 1024L
    }
}

internal fun Response.readMcpBodyBounded(maxBytes: Int = MAX_MCP_HTTP_BODY_BYTES): String {
    val responseBody = body ?: return ""
    val declared = responseBody.contentLength()
    if (declared > maxBytes) throw IllegalStateException("MCP HTTP 响应超过 ${maxBytes} 字节上限")
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    responseBody.byteStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IllegalStateException("MCP HTTP 响应超过 ${maxBytes} 字节上限")
            output.write(buffer, 0, read)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val MAX_MCP_HTTP_BODY_BYTES = 8 * 1024 * 1024

internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val value = block(it)
                            if (continuation.isActive) continuation.resume(value)
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            },
        )
    }

internal suspend fun Call.awaitResponseCancellable(): Response =
    suspendCancellableCoroutine { continuation ->
        var responseRef: Response? = null
        continuation.invokeOnCancellation {
            cancel()
            responseRef?.close()
        }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    responseRef = response
                    if (continuation.isActive) {
                        continuation.resume(response)
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


internal fun parseJsonRpcResponse(body: String, id: Long, json: Json): JsonObject {
    val response = json.parseToJsonElement(body).jsonObject
    require(response["id"]?.jsonPrimitive?.content == id.toString()) {
        "MCP HTTP 响应 id 与请求不匹配"
    }
    return response
}

internal fun encodeHeaderValue(value: String): String =
    if (value.all { it.code in 0x20..0x7E }) value
    else "base64:" + Base64.getEncoder().encodeToString(value.toByteArray())
