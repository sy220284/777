package com.labteto.dshmobile.interop.mcp

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

/** MCP 2025-06-18 Streamable HTTP lifecycle and optional session-id support. */
class McpLegacyStreamableHttpTransport(
    private val endpoint: String,
    private val http: OkHttpClient,
    private val json: Json,
    private val clientName: String = "777-android",
    private val clientVersion: String = "1",
    private val clientCapabilities: JsonObject = JsonObject(emptyMap()),
) : McpTransport {
    private val ids = AtomicLong(1L)
    private val mutex = Mutex()
    private var initialized = false
    private var sessionId: String? = null

    override suspend fun request(method: String, params: JsonObject): JsonObject = mutex.withLock {
        initializeIfNeeded()
        try {
            sendRequest(method, params)
        } catch (error: McpHttpException) {
            if (error.statusCode == 404 && sessionId != null) {
                initialized = false
                sessionId = null
                initializeIfNeeded()
                sendRequest(method, params)
            } else {
                throw error
            }
        }
    }

    private suspend fun initializeIfNeeded() {
        if (initialized) return
        val id = ids.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", LEGACY_MCP_PROTOCOL_VERSION)
                put("capabilities", clientCapabilities)
                put("clientInfo", buildJsonObject {
                    put("name", clientName)
                    put("version", clientVersion)
                })
            })
        }
        val response = post(payload, includeProtocol = false)
        val result = response.body["result"]?.jsonObject
            ?: error("MCP 旧协议 initialize 缺少 result")
        val negotiated = result["protocolVersion"]?.jsonPrimitive?.content
            ?: error("MCP 旧协议 initialize 缺少 protocolVersion")
        require(negotiated == LEGACY_MCP_PROTOCOL_VERSION) {
            "MCP 服务端协商到不支持的版本：$negotiated"
        }
        sessionId = response.sessionId
        postNotification("notifications/initialized")
        initialized = true
    }

    private suspend fun sendRequest(method: String, params: JsonObject): JsonObject {
        val id = ids.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return post(payload, includeProtocol = true).body
    }

    private suspend fun postNotification(method: String) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        post(payload, includeProtocol = true, notification = true)
    }

    private suspend fun post(
        payload: JsonObject,
        includeProtocol: Boolean,
        notification: Boolean = false,
    ): LegacyHttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(endpoint)
            .post(
                json.encodeToString(JsonObject.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (includeProtocol) builder.header("MCP-Protocol-Version", LEGACY_MCP_PROTOCOL_VERSION)
        sessionId?.let { builder.header("Mcp-Session-Id", it) }

        http.newCall(builder.build()).executeCancellable { response ->
            val bodyText = response.readMcpBodyBounded()
            if (!response.isSuccessful) throw McpHttpException(response.code, bodyText)
            if (notification || response.code == 202 || bodyText.isBlank()) {
                return@executeCancellable LegacyHttpResponse(
                    JsonObject(emptyMap()),
                    response.header("Mcp-Session-Id"),
                )
            }
            val id = payload["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val body = if (
                response.header("Content-Type").orEmpty()
                    .startsWith("text/event-stream", ignoreCase = true)
            ) {
                parseSseResponse(bodyText, requireNotNull(id), json)
            } else {
                json.parseToJsonElement(bodyText).jsonObject
            }
            LegacyHttpResponse(body, response.header("Mcp-Session-Id"))
        }
    }

    override fun close() = Unit

    private data class LegacyHttpResponse(
        val body: JsonObject,
        val sessionId: String?,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Deprecated MCP 2024-11-05 HTTP+SSE transport. */
class McpLegacyHttpSseTransport(
    private val sseUrl: String,
    private val http: OkHttpClient,
    private val json: Json,
    private val clientName: String = "777-android",
    private val clientVersion: String = "1",
) : McpTransport {
    private val ids = AtomicLong(1L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val requestMutex = Mutex()
    private var endpoint = CompletableDeferred<String>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    @Volatile private var sseResponse: Response? = null
    @Volatile private var initialized = false

    override suspend fun request(method: String, params: JsonObject): JsonObject =
        requestMutex.withLock {
            ensureInitialized()
            rawRequest(method, params)
        }

    private suspend fun ensureInitialized() {
        if (initialized) return
        ensureConnected()
        val response = rawRequest(
            "initialize",
            buildJsonObject {
                put("protocolVersion", LEGACY_SSE_MCP_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", clientName)
                    put("version", clientVersion)
                })
            },
        )
        val negotiated = response["result"]?.jsonObject
            ?.get("protocolVersion")?.jsonPrimitive?.content
            ?: error("MCP SSE initialize 缺少 protocolVersion")
        require(negotiated == LEGACY_SSE_MCP_PROTOCOL_VERSION) {
            "MCP SSE 服务端协商到不支持的版本：$negotiated"
        }
        rawNotification("notifications/initialized")
        initialized = true
    }

    private suspend fun ensureConnected() = connectMutex.withLock {
        if (sseResponse != null && endpoint.isCompleted && !endpoint.isCancelled) return@withLock
        endpoint = CompletableDeferred()
        val response = http.newCall(
            Request.Builder()
                .url(sseUrl)
                .get()
                .header("Accept", "text/event-stream")
                .build(),
        ).awaitResponseCancellable()
        if (!response.isSuccessful) {
            val body = response.readMcpBodyBounded()
            val code = response.code
            response.close()
            throw McpHttpException(code, body)
        }
        require(
            response.header("Content-Type").orEmpty()
                .startsWith("text/event-stream", ignoreCase = true),
        ) { "旧 MCP SSE 端点未返回 text/event-stream" }
        sseResponse = response
        scope.launch {
            try {
                readSse(response.body?.charStream() ?: error("MCP SSE 响应为空"))
            } catch (error: Exception) {
                if (!endpoint.isCompleted) endpoint.completeExceptionally(error)
                pending.values.forEach { it.completeExceptionally(error) }
                pending.clear()
            } finally {
                response.close()
                sseResponse = null
                initialized = false
            }
        }
        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) { endpoint.await() }
        } catch (error: Exception) {
            response.close()
            sseResponse = null
            throw error
        }
    }

    private suspend fun rawRequest(method: String, params: JsonObject): JsonObject {
        ensureConnected()
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            postMessage(payload)
            return withTimeout(REQUEST_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun rawNotification(method: String) {
        ensureConnected()
        postMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
            },
        )
    }

    private suspend fun postMessage(payload: JsonObject) = withContext(Dispatchers.IO) {
        val url = withTimeout(CONNECT_TIMEOUT_MILLIS) { endpoint.await() }
        val request = Request.Builder()
            .url(url)
            .post(
                json.encodeToString(JsonObject.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .header("Content-Type", "application/json")
            .build()
        http.newCall(request).executeCancellable { response ->
            val body = response.readMcpBodyBounded()
            if (!response.isSuccessful) throw McpHttpException(response.code, body)
            if (
                body.isNotBlank() &&
                response.header("Content-Type").orEmpty().contains("application/json")
            ) {
                val direct = json.parseToJsonElement(body).jsonObject
                direct["id"]?.jsonPrimitive?.content?.toLongOrNull()?.let { id ->
                    pending[id]?.complete(direct)
                }
            }
        }
    }

    private fun readSse(reader: java.io.Reader) {
        BufferedReader(reader).use { input ->
            var event: String? = null
            val data = StringBuilder()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) {
                    dispatchSse(event, data.toString())
                    event = null
                    data.setLength(0)
                    continue
                }
                if (line.startsWith(":")) continue
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
            if (data.isNotEmpty()) dispatchSse(event, data.toString())
        }
    }

    private fun dispatchSse(event: String?, data: String) {
        if (data.isBlank()) return
        if (event == "endpoint") {
            if (!endpoint.isCompleted) {
                val resolved = sseUrl.toHttpUrl().resolve(data)
                    ?: error("MCP SSE endpoint 无法解析：$data")
                endpoint.complete(resolved.toString())
            }
            return
        }
        val message = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: return
        val id = message["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        pending[id]?.complete(message)
    }

    override fun close() {
        scope.cancel()
        sseResponse?.close()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val REQUEST_TIMEOUT_MILLIS = 60_000L
    }
}

/** Current HTTP first, then 2025 lifecycle, then deprecated 2024 HTTP+SSE. */
class McpNegotiatingHttpTransport(
    endpoint: String,
    http: OkHttpClient,
    json: Json,
) : McpTransport {
    private val modern = McpStreamableHttpTransport(endpoint, http, json)
    private val legacy = McpLegacyStreamableHttpTransport(endpoint, http, json)
    private val legacySse = McpLegacyHttpSseTransport(endpoint, http, json)
    private val selectionMutex = Mutex()
    @Volatile private var selected: McpTransport? = null

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        selected?.let { return it.request(method, params) }
        return selectionMutex.withLock {
            selected?.let { return@withLock it.request(method, params) }
            try {
                modern.request(method, params).also { selected = modern }
            } catch (modernError: McpHttpException) {
                if (modernError.statusCode !in 400..499) throw modernError
                try {
                    legacy.request(method, params).also { selected = legacy }
                } catch (legacyError: McpHttpException) {
                    if (legacyError.statusCode !in 400..499) throw legacyError
                    legacySse.request(method, params).also { selected = legacySse }
                }
            }
        }
    }

    override fun close() {
        modern.close()
        legacy.close()
        legacySse.close()
    }
}

/** Legacy stdio lifecycle for MCP servers that still require initialize/initialized. */
class McpLegacyStdioTransport(
    private val command: List<String>,
    private val json: Json,
    private val workingDirectory: File? = null,
    private val clientName: String = "777-android",
    private val clientVersion: String = "1",
    private val protocolVersion: String = LEGACY_MCP_PROTOCOL_VERSION,
    private val commandResolver: (List<String>) -> List<String> = { it },
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
) : McpTransport {
    private val ids = AtomicLong(1L)
    private val mutex = Mutex()
    private val lineProcess = McpLineProcess(
        command = command,
        workingDirectory = workingDirectory,
        commandResolver = commandResolver,
        environmentProvider = environmentProvider,
    )
    private var initialized = false

    override suspend fun request(method: String, params: JsonObject): JsonObject = mutex.withLock {
        try {
            val restarted = lineProcess.ensureStarted()
            if (restarted) initialized = false
            ensureInitialized()
            requestRaw(method, params)
        } catch (error: kotlinx.coroutines.CancellationException) {
            initialized = false
            lineProcess.abort()
            throw error
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        val response = requestRaw(
            "initialize",
            buildJsonObject {
                put("protocolVersion", protocolVersion)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", clientName)
                    put("version", clientVersion)
                })
            },
        )
        val negotiated = response["result"]?.jsonObject
            ?.get("protocolVersion")?.jsonPrimitive?.content
            ?: error("MCP stdio initialize 缺少 protocolVersion")
        require(negotiated == protocolVersion) {
            "MCP stdio 服务端协商到不支持的版本：$negotiated；请求版本：$protocolVersion"
        }
        writeMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
        )
        initialized = true
    }

    private suspend fun requestRaw(method: String, params: JsonObject): JsonObject {
        val id = ids.getAndIncrement()
        writeMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        while (true) {
            val line = lineProcess.readLine()
            if (line.isBlank()) continue
            val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: continue
            if (message["id"]?.jsonPrimitive?.content == id.toString()) return message
        }
    }

    private suspend fun writeMessage(message: JsonObject) {
        lineProcess.writeLine(json.encodeToString(JsonObject.serializer(), message))
    }

    override fun close() {
        initialized = false
        lineProcess.close()
    }
}


/** Current stdio lifecycle first, then the 2025 lifecycle used by older MCP servers. */
class McpNegotiatingStdioTransport(
    command: List<String>,
    json: Json,
    workingDirectory: File? = null,
    commandResolver: (List<String>) -> List<String> = { it },
    environmentProvider: () -> Map<String, String> = { emptyMap() },
) : McpTransport {
    private val current = McpLegacyStdioTransport(
        command = command,
        json = json,
        workingDirectory = workingDirectory,
        protocolVersion = CURRENT_MCP_PROTOCOL_VERSION,
        commandResolver = commandResolver,
        environmentProvider = environmentProvider,
    )
    private val legacy = McpLegacyStdioTransport(
        command = command,
        json = json,
        workingDirectory = workingDirectory,
        protocolVersion = LEGACY_MCP_PROTOCOL_VERSION,
        commandResolver = commandResolver,
        environmentProvider = environmentProvider,
    )
    private val selectionMutex = Mutex()
    @Volatile private var selected: McpTransport? = null

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        selected?.let { return it.request(method, params) }
        return selectionMutex.withLock {
            selected?.let { return@withLock it.request(method, params) }
            try {
                current.request(method, params).also { selected = current }
            } catch (currentError: Exception) {
                current.close()
                try {
                    legacy.request(method, params).also { selected = legacy }
                } catch (legacyError: Exception) {
                    legacy.close()
                    legacyError.addSuppressed(currentError)
                    throw legacyError
                }
            }
        }
    }

    override fun close() {
        current.close()
        legacy.close()
    }
}
