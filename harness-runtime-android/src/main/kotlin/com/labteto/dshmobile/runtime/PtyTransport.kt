package com.labteto.dshmobile.runtime

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Frames are app-to-bridge only. Terminal output is never interpreted as control data. */
internal fun writePtyFrame(output: OutputStream, operation: Char, payload: ByteArray = byteArrayOf()) {
    output.write(ByteBuffer.allocate(5).put(operation.code.toByte()).putInt(payload.size).array())
    output.write(payload)
    output.flush()
}

internal suspend fun awaitPtyChild(managed: ManagedProcess): Long {
    val line = StringBuilder()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline && line.length < 80) {
        if (managed.process.inputStream.available() == 0) {
            check(managed.process.isAlive) { "PTY 创建失败：内置 Python 或系统 PTY 不可用" }
            delay(2)
            continue
        }
        val byte = managed.process.inputStream.read()
        if (byte == 10) {
            require(line.startsWith("DSH_PTY_V1:")) { "PTY 握手无效" }
            return line.toString().removePrefix("DSH_PTY_V1:").toLong().also { require(it > 1) }
        }
        check(byte >= 0) { "PTY 握手中断" }
        line.append(byte.toChar())
    }
    error("PTY 启动握手超时")
}

/** Preserve a UTF-8 character split across two nonblocking terminal reads. */
internal class TerminalUtf8Decoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var tail = byteArrayOf()
    @Synchronized fun hasPendingBytes(): Boolean = tail.isNotEmpty()

    @Synchronized fun decode(bytes: ByteArray, end: Boolean = false): String {
        val input = ByteBuffer.wrap(tail + bytes)
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, end).throwExceptionIfError()
        tail = ByteArray(input.remaining()).also { input.get(it) }
        if (end) {
            decoder.flush(output).throwExceptionIfError()
            decoder.reset()
            tail = byteArrayOf()
        }
        return output.flip().toString()
    }

    private fun java.nio.charset.CoderResult.throwExceptionIfError() {
        if (isError) throwException()
    }
}
