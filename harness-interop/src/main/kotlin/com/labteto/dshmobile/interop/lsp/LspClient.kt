package com.labteto.dshmobile.interop.lsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LspProcessClient(
    private val command: List<String>,
    private val json: Json,
    private val workingDirectory: File? = null,
    private val commandResolver: (List<String>) -> List<String> = { it },
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
) : Closeable {
    private val ids = AtomicLong(1L)
    private val mutex = Mutex()
    private val pendingNotifications = ArrayDeque<JsonObject>()
    private var process: Process? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    suspend fun initialize(rootUri: String?, capabilities: JsonObject = JsonObject(emptyMap())): JsonObject {
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
            LspFraming.write(output!!, json.encodeToString(JsonObject.serializer(), payload))
        }
    }

    fun drainNotifications(): List<JsonObject> = synchronized(pendingNotifications) {
        buildList {
            while (pendingNotifications.isNotEmpty()) add(pendingNotifications.removeFirst())
        }
    }

    @Synchronized override fun close() {
        process?.takeIf(Process::isAlive)?.destroyForcibly()
        runCatching { output?.close() }
        runCatching { input?.close() }
        process = null
        input = null
        output = null
    }

    private suspend fun requestMessage(method: String, params: JsonObject): JsonObject = mutex.withLock {
        boundedIo { requestBlocking(method, params) }
    }

    private suspend fun <T> boundedIo(block: () -> T): T = coroutineScope {
        val pending = async(Dispatchers.IO) { block() }
        try { withTimeout(30_000L) { pending.await() } }
        catch (cancelled: CancellationException) { close(); throw cancelled }
    }

    private fun requestBlocking(method: String, params: JsonObject): JsonObject {
            ensureStarted()
            val id = ids.getAndIncrement()
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            LspFraming.write(output!!, json.encodeToString(JsonObject.serializer(), payload))
            while (true) {
                val message = json.parseToJsonElement(LspFraming.read(input!!)).jsonObject
                val messageId = message["id"]?.jsonPrimitive?.content
                if (messageId == id.toString()) return message
                if (messageId != null && message["method"] != null) {
                    LspFraming.write(output!!, buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", message["id"]!!)
                        put("error", buildJsonObject { put("code", -32601); put("message", "Client method unsupported") })
                    }.toString())
                } else synchronized(pendingNotifications) {
                    if (pendingNotifications.size >= 256) pendingNotifications.removeFirst()
                    pendingNotifications.addLast(message)
                }
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
            .apply { environment().putAll(environmentProvider()) }
            .start()
        process = next
        input = BufferedInputStream(next.inputStream)
        output = BufferedOutputStream(next.outputStream)
    }

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
