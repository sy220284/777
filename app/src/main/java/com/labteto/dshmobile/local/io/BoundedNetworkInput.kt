package com.labteto.dshmobile.local.io

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.ResponseBody

/** Network input must be bounded during consumption, before allocation. */
internal class NetworkInputTooLargeException(message: String) : IOException(message)

internal fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
    require(maxChars > 0)
    val line = StringBuilder(minOf(maxChars, 8 * 1024))
    while (true) {
        val value = reader.read()
        if (value == -1) return if (line.isEmpty()) null else line.toString()
        if (value == '\n'.code) return line.toString().removeSuffix("\r")
        if (line.length >= maxChars) throw NetworkInputTooLargeException("单行超过 $maxChars 字符")
        line.append(value.toChar())
    }
}

internal fun readBoundedBody(body: ResponseBody?, maxBytes: Int): String {
    require(maxBytes > 0)
    if (body == null) return ""
    val input = body.byteStream()
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (read > maxBytes - total) throw NetworkInputTooLargeException("响应超过 $maxBytes 字节")
        output.write(buffer, 0, read)
        total += read
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}
