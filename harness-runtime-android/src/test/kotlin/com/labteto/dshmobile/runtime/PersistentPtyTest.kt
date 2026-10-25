package com.labteto.dshmobile.runtime

import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPtyTest {
    @Test fun cancellingBlockedPipeWriteClosesItsProcessInsteadOfHanging() = runBlocking {
        val provider = PersistentPipeTerminalProvider()
        try {
            val id = provider.open(listOf("sh", "-c", "sleep 60"), null)
            try {
                withTimeout(100) { provider.write(id, "x".repeat(2 * 1024 * 1024)) }
                throw AssertionError("write should block until cancellation")
            } catch (_: TimeoutCancellationException) {
                assertFalse(provider.isAlive(id))
            }
        } finally {
            provider.closeAll()
        }
    }

    @Test fun decodingPreservesCharactersAcrossEveryByteBoundary() {
        val expected = "中😀文\r\n"
        val decoder = TerminalUtf8Decoder()
        val text = buildString {
            expected.toByteArray().forEach { append(decoder.decode(byteArrayOf(it))) }
            append(decoder.decode(byteArrayOf(), end = true))
        }
        assertEquals(expected, text)
        assertFalse(decoder.hasPendingBytes())
        assertEquals("", decoder.decode(byteArrayOf(0xe4.toByte())))
        assertEquals("�", decoder.decode(byteArrayOf(), end = true))
    }

    @Test fun unreadOutputIsBoundedAndReportsDroppedPrefix() {
        val buffer = TerminalOutputBuffer(ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }))
        buffer.awaitClosed()
        assertTrue(buffer.finished)
        val text = buildString { while (!buffer.isDrained()) append(buffer.read()) }
        assertTrue(text.startsWith("[终端输出超过 1 MiB"))
        assertEquals(1024 * 1024, text.count { it == 'x' })
    }

    @Test fun defaultPtyHasControllingTerminalResizesAndPreservesFinalOutput() = runBlocking {
        val provider = PersistentPipeTerminalProvider()
        try {
            val id = provider.openPty(listOf("sh", "-c", "test -t 0 && test -t 1 && test -t 2; stty size; read answer; stty size; printf '尾部'; exit 9"))
            assertTrue(provider.isPty(id))
            var output = ""
            withTimeout(5_000) {
                while (!output.contains("24 80")) { output += provider.read(id); delay(10) }
            }
            provider.resize(id, 43, 132)
            provider.write(id, "continue\n")
            withTimeout(5_000) {
                while (true) {
                    val read = provider.readResult(id)
                    output += read.output
                    if (read.exited) { assertEquals(9, read.exitCode); break }
                    delay(10)
                }
            }
            assertTrue(output.contains("43 132"))
            assertTrue(output.contains("尾部"))
        } finally {
            provider.closeAll()
        }
    }
}
