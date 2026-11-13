package com.labteto.dshmobile.interop.mcp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

internal fun Response.readMcpRpcResponse(id: Long, json: Json): JsonObject =
    if (header("Content-Type").orEmpty().startsWith("text/event-stream", ignoreCase = true)) {
        readSseResponse(body?.byteStream() ?: error("MCP SSE 响应为空"), id, json)
    } else {
        parseJsonRpcResponse(readMcpBodyBounded(), id, json)
    }

internal fun parseSseResponse(body: String, id: Long, json: Json): JsonObject =
    readSseResponse(body.byteInputStream(), id, json)

/** Return on the matching complete response, without waiting for the server to close its stream. */
internal fun readSseResponse(
    input: InputStream,
    id: Long,
    json: Json,
    maxBytes: Int = 8 * 1024 * 1024,
): JsonObject {
    var matching: JsonObject? = null
    readMcpSseEvents(input, maxEventBytes = maxBytes, maxTotalBytes = maxBytes.toLong()) { _, data ->
        val value = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
        if (value?.get("id")?.jsonPrimitive?.content == id.toString()) matching = value
        matching != null
    }
    return matching ?: error("MCP SSE 未返回请求 $id 的最终响应")
}

/** Shared framing for finite RPC replies and the deprecated persistent HTTP+SSE transport. */
internal fun readMcpSseEvents(
    input: InputStream,
    maxEventBytes: Int = 8 * 1024 * 1024,
    maxTotalBytes: Long? = null,
    onEvent: (event: String?, data: String) -> Boolean,
) {
    require(maxEventBytes > 0 && (maxTotalBytes == null || maxTotalBytes > 0))
    val stream = input.buffered()
    val line = ByteArrayOutputStream()
    val data = StringBuilder()
    var event: String? = null
    var eventBytes = 0
    var totalBytes = 0L
    var firstLine = true
    var previousCr = false
    var dataLines = 0

    fun dispatch(): Boolean {
        val stop = if (dataLines > 0) onEvent(event, data.toString()) else false
        event = null
        eventBytes = 0
        dataLines = 0
        data.setLength(0)
        return stop
    }

    fun acceptLine(): Boolean {
        var text = line.toString(Charsets.UTF_8.name())
        line.reset()
        if (firstLine) {
            text = text.removePrefix("\uFEFF")
            firstLine = false
        }
        if (text.isEmpty()) return dispatch()
        if (text.startsWith(":")) return false
        val value = text.substringAfter(':', "").removePrefix(" ")
        when (text.substringBefore(':')) {
            "event" -> event = value
            "data" -> {
                if (dataLines++ > 0) data.append('\n')
                data.append(value)
            }
        }
        return false
    }

    while (true) {
        val next = stream.read()
        if (next < 0) break
        totalBytes++
        require(maxTotalBytes == null || totalBytes <= maxTotalBytes) { "MCP SSE 响应超过字节上限" }
        require(++eventBytes <= maxEventBytes) { "MCP SSE 事件超过 $maxEventBytes 字节上限" }
        if (next == '\n'.code && previousCr) {
            previousCr = false
            continue
        }
        previousCr = next == '\r'.code
        if (next == '\n'.code || previousCr) {
            if (acceptLine()) return
        } else {
            line.write(next)
        }
    }
    // Preserve compatibility with finite replies omitting their final blank line.
    if (line.size() > 0 && acceptLine()) return
    dispatch()
}
