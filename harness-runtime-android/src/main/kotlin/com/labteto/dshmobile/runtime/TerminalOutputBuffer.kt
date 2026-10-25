package com.labteto.dshmobile.runtime

import java.io.InputStream
import java.util.ArrayDeque

/** Drain the OS pipe even between reads, so an interactive writer cannot deadlock on stdout. */
internal class TerminalOutputBuffer(input: InputStream) {
    private val chunks = ArrayDeque<ByteArray>()
    private var size = 0
    private var dropped = false
    private var failure: String? = null
    private val decoder = TerminalUtf8Decoder()
    @Volatile var finished: Boolean = false
        private set

    private val reader = Thread({
        try {
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                if (count == 0) continue
                synchronized(this) {
                    chunks.addLast(bytes.copyOf(count))
                    size += count
                    while (size > MAX_BUFFER_BYTES) {
                        size -= chunks.removeFirst().size
                        dropped = true
                    }
                }
            }
        } catch (error: java.io.IOException) {
            synchronized(this) { failure = error.message ?: "终端输出读取中断" }
        } finally {
            finished = true
        }
    }, "terminal-output").apply { isDaemon = true; start() }

    @Synchronized fun isDrained(): Boolean = finished && size == 0 && !decoder.hasPendingBytes() && failure == null

    @Synchronized fun read(): String {
        val prefix = if (dropped) {
            dropped = false
            // Dropping a prefix can leave a partial UTF-8 character at the new boundary.
            decoder.decode(byteArrayOf(), end = true)
            "[终端输出超过 1 MiB，已丢弃最早部分]\n"
        } else ""
        val bytes = chunks.pollFirst() ?: byteArrayOf()
        size -= bytes.size
        val text = decoder.decode(bytes, end = finished && size == 0)
        val diagnostic = if (finished && size == 0) failure?.let {
            failure = null
            "\n[TERMINAL_OUTPUT_READ_FAILED] $it"
        }.orEmpty() else ""
        return prefix + text + diagnostic
    }

    fun awaitClosed() { reader.join(1_000) }

    private companion object { const val MAX_BUFFER_BYTES = 1024 * 1024 }
}
