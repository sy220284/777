package com.labteto.dshmobile.local.files

import com.labteto.dshmobile.local.io.NetworkInputTooLargeException
import java.io.BufferedReader
import java.io.InterruptedIOException

/**
 * Reusable bounded line scanner for workspace grep. BufferedReader.read() one
 * character at a time cost tens of seconds on 128 MiB generated source.
 *
 * Keep unread characters within a fixed buffer between lines. Never call the
 * unbounded BufferedReader.readLine(), and preserve CRLF and EOF behavior of
 * readBoundedLine exactly.
 */
internal class LocalSourceLineScanner(
    private val input: BufferedReader,
    private val maxChars: Int,
) {
    private val buffer = CharArray(16 * 1024)
    private var offset = 0
    private var available = 0

    init {
        require(maxChars > 0) { "源码搜索行上限必须大于零" }
    }

    fun readLine(): String? {
        val result = StringBuilder(160)
        while (true) {
            if (offset >= available) {
                if (Thread.currentThread().isInterrupted)
                    throw InterruptedIOException("源码搜索已取消")
                available = input.read(buffer)
                offset = 0
                if (available < 0) return if (result.isEmpty()) null else result.toString()
                if (available == 0) continue
            }
            var end = offset
            while (end < available && buffer[end] != '\n') end++
            val chars = end - offset
            if (chars > maxChars - result.length) {
                throw NetworkInputTooLargeException("单行超过 $maxChars 字符")
            }
            result.append(buffer, offset, chars)
            offset = end
            if (offset < available) {
                offset++
                return result.toString().removeSuffix("\r")
            }
        }
    }
}
