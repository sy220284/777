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
