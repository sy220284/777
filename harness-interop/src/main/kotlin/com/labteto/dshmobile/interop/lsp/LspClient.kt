package com.labteto.dshmobile.interop.lsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Advertise push diagnostics; TypeScript Language Server suppresses them otherwise. */
internal val DEFAULT_LSP_CLIENT_CAPABILITIES: JsonObject = buildJsonObject {
    put("textDocument", buildJsonObject {
        put("publishDiagnostics", buildJsonObject { })
    })
}

class LspProcessClient(
    private val command: List<String>,
    private val json: Json,
    private val workingDirectory: File? = null,
    private val commandResolver: (List<String>) -> List<String> = { it },
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
    private val replaceEnvironment: Boolean = false,
) : Closeable {
    private val ids = AtomicLong(1L)
    private val mutex = Mutex()
    private val pendingNotifications = ArrayDeque<JsonObject>()
    private val messagesLock = Object()
    private val writeLock = Any()
    private val pendingReplies = mutableMapOf<String, CompletableFuture<JsonObject>>()
    private val latestDiagnostics = mutableMapOf<String, Pair<Long, JsonObject>>()
    private var diagnosticSerial = 0L
    private var readerFailure: Throwable? = null
    private var readerThread: Thread? = null
    private var process: Process? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    suspend fun initialize(rootUri: String?, capabilities: JsonObject = DEFAULT_LSP_CLIENT_CAPABILITIES): JsonObject {
        val params = buildJsonObject {
            put("processId", JsonNull)
            if (rootUri != null) put("rootUri", rootUri) else put("rootUri", JsonNull)
            put("capabilities", capabilities)
        }
        val result = request("initialize", params)
        notify("initialized", JsonObject(emptyMap()))
        return result
    }

    suspend fun definition(uri: String, line: Int, character: Int): JsonElement =
        requestElement("textDocument/definition", positionParams(uri, line, character))

    suspend fun references(
        uri: String,
        line: Int,
        character: Int,
        includeDeclaration: Boolean = true,
    ): JsonElement = requestElement(
        "textDocument/references",
        buildJsonObject {
            positionParams(uri, line, character).forEach { (key, value) -> put(key, value) }
            put("context", buildJsonObject { put("includeDeclaration", includeDeclaration) })
        },
    )

    suspend fun hover(uri: String, line: Int, character: Int): JsonElement =
        requestElement("textDocument/hover", positionParams(uri, line, character))

    suspend fun implementation(uri: String, line: Int, character: Int): JsonElement =
        requestElement("textDocument/implementation", positionParams(uri, line, character))

    suspend fun documentSymbols(uri: String): JsonElement =
        requestElement(
            "textDocument/documentSymbol",
            buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri) }) },
        )

    suspend fun workspaceSymbols(query: String): JsonElement =
        requestElement("workspace/symbol", buildJsonObject { put("query", query) })

    suspend fun rename(uri: String, line: Int, character: Int, newName: String): JsonElement =
        requestElement(
            "textDocument/rename",
            buildJsonObject {
                positionParams(uri, line, character).forEach { (key, value) -> put(key, value) }
                put("newName", newName)
            },
        )

    suspend fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        notify(
            "textDocument/didOpen",
            buildJsonObject {
                put("textDocument", buildJsonObject {
                    put("uri", uri)
                    put("languageId", languageId)
                    put("version", version)
                    put("text", text)
                })
            },
        )
    }

    suspend fun didChange(uri: String, version: Int, text: String) {
        notify(
            "textDocument/didChange",
            buildJsonObject {
                put("textDocument", buildJsonObject {
                    put("uri", uri)
                    put("version", version)
                })
                put("contentChanges", buildJsonArray {
                    add(buildJsonObject { put("text", text) })
                })
            },
        )
    }

    suspend fun request(method: String, params: JsonObject): JsonObject =
        requestMessage(method, params).let { response ->
            response["error"]?.let { error -> throw IllegalStateException("LSP 返回错误：$error") }
            response
        }

    suspend fun requestElement(method: String, params: JsonObject): JsonElement {
        val response = request(method, params)
        return response["result"] ?: JsonNull
    }

    suspend fun notify(method: String, params: JsonObject) = mutex.withLock {
        boundedIo {
            ensureStarted()
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
            val writer = checkNotNull(output) { "LSP 输出流未就绪" }
            synchronized(writeLock) {
                LspFraming.write(writer, json.encodeToString(JsonObject.serializer(), payload))
            }
        }
    }

    suspend fun shutdown(timeoutMillis: Long = 1_500L) {
        try {
            withTimeoutOrNull(timeoutMillis) {
                try {
                    request("shutdown", JsonObject(emptyMap()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A broken server still needs a best-effort exit/close below.
                }
                try {
                    notify("exit", JsonObject(emptyMap()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Force-close remains the final fallback.
                }
            }
        } finally {
            close()
        }
    }

    fun drainNotifications(): List<JsonObject> = synchronized(messagesLock) {
        buildList {
            while (pendingNotifications.isNotEmpty()) add(pendingNotifications.removeFirst())
        }
    }

    /** Record a revision before didOpen/didChange so an old push cannot masquerade as new. */
    fun isLive(): Boolean = process?.isAlive == true &&
        synchronized(messagesLock) { readerFailure == null }

    fun diagnosticRevision(uri: String): Long = synchronized(messagesLock) {
        latestDiagnostics[diagnosticUriKey(uri)]?.first ?: 0L
    }

    /**
     * Responses and server notifications have a single reader. Notifications can arrive while
     * no request is pending; await the relevant URI/version instead of pumping unrelated requests.
     */
    suspend fun awaitPublishedDiagnostics(
        uri: String,
        sinceRevision: Long,
        version: Int,
        timeoutMillis: Long = 4_000L,
    ): JsonObject? = boundedIo {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(messagesLock) {
            while (true) {
                val pair = latestDiagnostics[diagnosticUriKey(uri)]
                if (pair != null && pair.first > sinceRevision) {
                    val publishedVersion = pair.second["version"]?.jsonPrimitive?.intOrNull
                    if (publishedVersion == null || publishedVersion >= version) return@boundedIo pair.second
                }
                readerFailure?.let { throw IllegalStateException("语言服务消息流中断", it) }
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) return@boundedIo null
                messagesLock.wait(maxOf(1L, remaining))
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    @Synchronized override fun close() {
        process?.takeIf(Process::isAlive)?.destroyForcibly()
        runCatching { output?.close() }
        runCatching { input?.close() }
        process = null
        input = null
        output = null
        synchronized(messagesLock) {
            val stopped = IllegalStateException("LSP 连接已关闭")
            readerFailure = stopped
            pendingReplies.values.forEach { it.completeExceptionally(stopped) }
            pendingReplies.clear()
            latestDiagnostics.clear()
            messagesLock.notifyAll()
        }
        readerThread?.interrupt()
        readerThread = null
    }

    private suspend fun requestMessage(method: String, params: JsonObject): JsonObject = mutex.withLock {
        boundedIo { requestBlocking(method, params) }
    }

    private suspend fun <T> boundedIo(block: () -> T): T = withTimeout(30_000L) {
        suspendCancellableCoroutine { continuation ->
            val worker = blockingIo.submit {
                try {
                    continuation.resume(block())
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                // A pipe read can ignore Thread.interrupt. Close the process from the cancelling
                // coroutine so neither the caller nor the test runner waits for the blocked read.
                worker.cancel(true)
                close()
            }
        }
    }

    private companion object {
        val blockingIo = Executors.newCachedThreadPool { task ->
            Thread(task, "lsp-blocking-io").apply { isDaemon = true }
        }
    }

    private fun requestBlocking(method: String, params: JsonObject): JsonObject {
        ensureStarted()
        val id = ids.getAndIncrement().toString()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id.toLong())
            put("method", method)
            put("params", params)
        }
        val future = CompletableFuture<JsonObject>()
        synchronized(messagesLock) {
            readerFailure?.let { throw IllegalStateException("语言服务已断开", it) }
            // Register before writing. Any response without this outstanding request
            // is unsolicited: caching it would let a server forge a future call's
            // result by predicting the monotonic JSON-RPC request ID.
            pendingReplies[id] = future
        }
        try {
            synchronized(writeLock) {
                LspFraming.write(checkNotNull(output) { "LSP 输出流未就绪" },
                    json.encodeToString(JsonObject.serializer(), payload))
            }
            return try {
                future.get()
            } catch (error: ExecutionException) {
                throw IllegalStateException("LSP 请求失败：$method", error.cause ?: error)
            }
        } finally {
            synchronized(messagesLock) { pendingReplies.remove(id) }
        }
    }

    @Synchronized private fun ensureStarted() {
        if (process?.isAlive == true) return
        require(command.isNotEmpty()) { "语言服务器命令不能为空" }
        val resolvedCommand = commandResolver(command)
        require(resolvedCommand.isNotEmpty()) { "语言服务器解析后的命令不能为空" }
        val next = ProcessBuilder(resolvedCommand)
            .directory(workingDirectory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { if (replaceEnvironment) environment().clear(); environment().putAll(environmentProvider()) }
            .start()
        process = next
        val reader = BufferedInputStream(next.inputStream)
        input = reader
        output = BufferedOutputStream(next.outputStream)
        synchronized(messagesLock) { readerFailure = null }
        readerThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val message = json.parseToJsonElement(LspFraming.read(reader)).jsonObject
                    val id = message["id"]?.jsonPrimitive?.content
                    if (id != null && message["method"] == null) {
                        // A response can only settle a request that has already been
                        // sent/registered. Drop unsolicited future or duplicate IDs.
                        val reply = synchronized(messagesLock) { pendingReplies.remove(id) }
                        reply?.complete(message)
                    } else if (id != null && message["method"] != null) {
                        // A server-initiated request must not wedge the JSON-RPC transport.
                        val unsupported = buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", message["id"] ?: JsonNull)
                            put("error", buildJsonObject {
                                put("code", -32601)
                                put("message", "Client method unsupported")
                            })
                        }
                        synchronized(writeLock) {
                            LspFraming.write(checkNotNull(output), unsupported.toString())
                        }
                    } else {
                        synchronized(messagesLock) {
                            if (pendingNotifications.size >= 256) pendingNotifications.removeFirst()
                            pendingNotifications.addLast(message)
                            if (message["method"]?.jsonPrimitive?.content == "textDocument/publishDiagnostics") {
                                val params = message["params"] as? JsonObject
                                val uri = params?.get("uri")?.jsonPrimitive?.content
                                if (uri != null && params != null) {
                                    diagnosticSerial++
                                    latestDiagnostics[diagnosticUriKey(uri)] = diagnosticSerial to params
                                    messagesLock.notifyAll()
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                synchronized(messagesLock) {
                    readerFailure = error
                    pendingReplies.values.forEach { it.completeExceptionally(error) }
                    pendingReplies.clear()
                    messagesLock.notifyAll()
                }
            }
        }, "lsp-json-rpc-reader").apply { isDaemon = true; start() }
    }

    /**
     * Some language servers normalize Java file:/path URIs into file:///path when publishing
     * diagnostics. The document identity must survive that equivalent serialization.
     */
    private fun diagnosticUriKey(value: String): String = runCatching {
        val parsed = URI(value).normalize()
        if (parsed.scheme.equals("file", ignoreCase = true) &&
            parsed.host.isNullOrEmpty() && parsed.path.startsWith("/")
        ) {
            URI("file", "", parsed.path, null).toASCIIString()
        } else {
            parsed.toASCIIString()
        }
    }.getOrDefault(value)

    private fun positionParams(uri: String, line: Int, character: Int): JsonObject =
        buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            put("position", buildJsonObject {
                put("line", line.coerceAtLeast(0))
                put("character", character.coerceAtLeast(0))
            })
        }
}

internal object LspFraming {
    fun write(output: BufferedOutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    fun read(input: BufferedInputStream): String {
        var contentLength: Int? = null
        while (true) {
            val line = readHeaderLine(input)
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                contentLength = value.toIntOrNull()
            }
        }
        val length = contentLength ?: error("LSP 消息缺少 Content-Length")
        require(length in 0..MAX_MESSAGE_BYTES) { "LSP 消息过大：$length" }
        val bytes = input.readNBytes(length)
        require(bytes.size == length) { "LSP 消息提前结束" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readHeaderLine(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) error("LSP 流已结束")
            if (value == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) break
                bytes += value.toByte()
                if (next >= 0) bytes += next.toByte()
            } else {
                bytes += value.toByte()
            }
            require(bytes.size <= MAX_HEADER_BYTES) { "LSP 头部过大" }
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
}
