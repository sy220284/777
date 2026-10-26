package com.labteto.dshmobile.local.files

import com.labteto.dshmobile.local.runtime.MAX_TOOL_RESULT_CHARS
import java.io.File
import java.io.InterruptedIOException

/** Bound allocation while reading, and expose the exact next character instead of losing a long line. */
internal fun readLocalTextPage(file: File, startLine: Int, endLine: Int, startChar: Int): String {
    val output = StringBuilder()
    // Reserve room inside the existing tool result budget for line labels and the continuation.
    val contentLimit = MAX_TOOL_RESULT_CHARS - 512
    val lastLine = minOf(endLine.toLong(), startLine.toLong() + 399L)
    file.bufferedReader().use { reader ->
        var line = 1L
        while (line < startLine) {
            val char = reader.read()
            if (char < 0) return ""
            if (char == '\n'.code) line++
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("文件读取已取消")
        }
        var column = 0
        while (column < startChar) {
            if (column % 8_192 == 0 && Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("文件读取已取消")
            }
            val char = reader.read()
            require(char >= 0 && char != '\n'.code) { "行内字符偏移已失效，请从当前行开头重读" }
            column++
        }
        fun continuation(): String = output.toString().trimEnd('\n') +
            "\n[读取未完：保持 path/end_line，使用 start_line=$line, start_char=$column 继续]"
        while (line <= lastLine) {
            reader.mark(2)
            if (reader.read() < 0) return output.toString().trimEnd('\n')
            reader.reset()
            if (output.length + 32 >= contentLimit) return continuation()
            output.append(line).append(": ")
            while (true) {
                if (column % 8_192 == 0 && Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("文件读取已取消")
                }
                reader.mark(2)
                val first = reader.read()
                if (first < 0) return output.toString().trimEnd('\n')
                var newline = first == '\n'.code
                if (first == '\r'.code) {
                    val next = reader.read()
                    newline = next == '\n'.code
                    if (!newline) {
                        reader.reset()
                        reader.read()
                    }
                }
                if (newline) {
                    output.append('\n')
                    line++
                    column = 0
                    break
                }
                // Do not split a surrogate pair across model-visible pages.
                val second = if (first.toChar().isHighSurrogate()) reader.read() else -1
                val pair = second >= 0 && second.toChar().isLowSurrogate()
                val width = if (pair) 2 else 1
                if (output.length + width > contentLimit) {
                    reader.reset()
                    return continuation()
                }
                if (second >= 0 && !pair) {
                    reader.reset()
                    reader.read()
                }
                output.append(first.toChar())
                if (pair) output.append(second.toChar())
                column += width
            }
        }
        if (line <= endLine && reader.read() >= 0) return continuation()
    }
    return output.toString().trimEnd('\n')
}
