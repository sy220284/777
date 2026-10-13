package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.attachment.copyAttachmentCancellably
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAttachmentImporterTest {
    @Test
    fun attachmentLargerThanOldTwentyMegabyteLimitCopiesWithoutRejection() = runBlocking {
        val size = 21L * 1024L * 1024L
        val source = object : InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining > 0L) {
                remaining -= 1
                0
            } else -1

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                bytes.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        val output = object : OutputStream() {
            var written = 0L
            override fun write(value: Int) { written++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                written += length
            }
        }

        val copied = copyAttachmentCancellably(
            source = source,
            output = output,
            digest = MessageDigest.getInstance("SHA-256"),
        )

        assertEquals(size, copied)
        assertEquals(size, output.written)
    }

    @Test
    fun cancellationInterruptsBlockedAttachmentCopy() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val source = object : InputStream() {
            private fun blockingRead(): Int {
                started.countDown()
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
                return -1
            }

            override fun read(): Int = blockingRead()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = blockingRead()
        }
        val job = launch(Dispatchers.Default) {
            copyAttachmentCancellably(
                source = source,
                output = ByteArrayOutputStream(),
                digest = MessageDigest.getInstance("SHA-256"),
            )
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        withTimeout(3_000L) {
            job.cancelAndJoin()
        }

        assertTrue(interrupted.get())
    }
}
