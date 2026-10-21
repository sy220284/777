package com.labteto.dshmobile.automation

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal data class WebhookRequestHead(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
)

/** Absolute monotonic deadline for the entire request; caller authenticates before reading body. */
internal class WebhookHttpRequestReader(
    private val socket: Socket,
    private val deadlineNanos: Long,
) {
    fun readHead(maxHeaderBytes: Int): WebhookRequestHead? {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        var state = 0
        while (header.size() < maxHeaderBytes) {
            applyReadDeadline()
            val value = input.read()
            if (value < 0) return null
            header.write(value)
            state = when {
                state == 0 && value == '\r'.code -> 1
                state == 1 && value == '\n'.code -> 2
                state == 2 && value == '\r'.code -> 3
                state == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        if (state != 4) return null
        val lines = header.toString(Charsets.US_ASCII.name()).split("\r\n")
        val request = lines.firstOrNull()?.split(' ') ?: return null
        if (request.size < 2 || request[0].isBlank() || request[1].isBlank()) return null
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val index = line.indexOf(':')
            if (index <= 0) return null
            val name = line.substring(0, index).trim().lowercase()
            if (name.isBlank()) return null
            // Multiple framing headers create request-smuggling ambiguity.
            if (name in headers && (name == "content-length" || name == "transfer-encoding")) return null
            headers[name] = line.substring(index + 1).trim()
        }
        return WebhookRequestHead(request[0].uppercase(), request[1], headers)
    }

    fun readBody(length: Int): ByteArray? {
        require(length >= 0)
        val body = ByteArray(length)
        var offset = 0
        val input = socket.getInputStream()
        while (offset < length) {
            applyReadDeadline()
            val count = input.read(body, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return body
    }

    private fun applyReadDeadline() {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw SocketTimeoutException("Webhook request deadline exceeded")
        socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceIn(1L, 10_000L).toInt()
    }
}
