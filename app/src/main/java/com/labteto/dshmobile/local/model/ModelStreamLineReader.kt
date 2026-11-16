package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.io.NetworkInputTooLargeException
import com.labteto.dshmobile.local.io.readBoundedLine
import java.io.BufferedReader
import java.io.IOException

/** Stream framing limits only; each provider retains its own event and billing protocol. */
internal enum class ModelStreamLimitKind { LINE, STREAM }

internal class ModelStreamLimitException(
    val kind: ModelStreamLimitKind,
    cause: Throwable? = null,
) : IOException("模型流式响应超过限额", cause)

/** Tracks UTF-8 line bytes plus one delimiter, exactly as the original clients did. */
internal class ModelStreamLineReader(
    private val reader: BufferedReader,
    private val maxLineChars: Int,
    private val maxStreamBytes: Int,
) {
    init { require(maxLineChars > 0 && maxStreamBytes > 0) }

    private var bytesRead = 0L

    fun readLine(): String? {
        val line = try {
            readBoundedLine(reader, maxLineChars)
        } catch (error: NetworkInputTooLargeException) {
            throw ModelStreamLimitException(ModelStreamLimitKind.LINE, error)
        } ?: return null
        val count = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
        if (bytesRead + count > maxStreamBytes.toLong()) {
            throw ModelStreamLimitException(ModelStreamLimitKind.STREAM)
        }
        bytesRead += count
        return line
    }
}


/**
 * Parses SSE records at their blank-line boundary. Also accepts adjacent standalone JSON
 * data records without a blank delimiter, as emitted by some OpenAI-compatible endpoints.
 * Unknown non-SSE content can be passed to a protocol-specific fallback decoder.
 */
internal class ModelSseDataReader(
    private val lines: ModelStreamLineReader,
    private val onNonEventLine: (String) -> Unit = {},
) {
    private var exhausted = false
    private var pendingLine: String? = null

    fun readData(): String? {
        if (exhausted) return null
        val data = StringBuilder()
        while (true) {
            val line = pendingLine?.also { pendingLine = null } ?: lines.readLine()
            if (line == null) {
                exhausted = true
                return data.toString().takeIf(String::isNotEmpty)
            }
            if (line.isEmpty()) {
                if (data.isNotEmpty()) return data.toString()
                continue
            }
            if (!line.startsWith("data:")) {
                // Preserve legacy non-SSE JSON payloads in Chat Completions.
                if (line.isNotBlank() && !line.startsWith(":") &&
                    !line.startsWith("event:") && !line.startsWith("id:") &&
                    !line.startsWith("retry:")) onNonEventLine(line)
                continue
            }
            if (data.isNotEmpty() && isCompleteModelEvent(data.toString())) {
                // Some provider proxies omit the mandatory blank line between events.
                pendingLine = line
                return data.toString()
            }
            if (data.isNotEmpty()) data.append('\n')
            val value = line.substring(5)
            data.append(if (value.startsWith(' ')) value.substring(1) else value)
        }
    }

    private fun isCompleteModelEvent(data: String): Boolean =
        data == "[DONE]" ||
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data) }.isSuccess
}
