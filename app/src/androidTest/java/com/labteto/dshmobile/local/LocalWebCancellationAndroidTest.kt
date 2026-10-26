package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalWebCancellationAndroidTest {
    @Test
    fun cancelledDownloadKeepsDestinationAndRemovesItsTemporaryFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.resolve("web-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean()
        val http = blockedClient(entered, closed)
        val provider = provider(http)
        val target = root.resolve("file.txt").apply { writeText("original") }
        val job = launch(Dispatchers.Default) {
            provider.downloadTo("http://93.184.216.34/file", target)
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertEquals("original", target.readText())
            assertEquals(listOf("file.txt"), root.list()!!.toList())
            assertTrue(closed.get())
        } finally {
            job.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadLargerThanOneHundredMiBStreamsFullyAndVerifiesHash() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.resolve("web-large-${UUID.randomUUID()}").apply { mkdirs() }
        val totalBytes = 101L * 1024L * 1024L + 4096L
        val pattern = ByteArray(32 * 1024) { (it % 251).toByte() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(streamingBody(totalBytes, pattern)).build()
        }.build()
        try {
            val target = root.resolve("large.tar.gz")
            val receipt = provider(client).downloadTo("http://93.184.216.34/file", target)
            assertEquals(totalBytes, receipt.bytes)
            assertEquals(totalBytes, target.length())
            assertEquals(expectedRepeatedSha256(totalBytes, pattern), receipt.sha256)
            assertEquals(listOf(target.name), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitDownloadLimitPreservesExistingDestination() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.resolve("web-limit-${UUID.randomUUID()}").apply { mkdirs() }
        val pattern = ByteArray(32 * 1024) { (it % 251).toByte() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(streamingBody(65_536L, pattern)).build()
        }.build()
        try {
            val target = root.resolve("saved.bin").apply { writeText("original") }
            val failed = runCatching {
                provider(client).downloadTo("http://93.184.216.34/file", target, maxBytes = 16_384L)
            }.exceptionOrNull()
            assertTrue(failed is LocalWebException)
            assertEquals("DOWNLOAD_TOO_LARGE", (failed as LocalWebException).code)
            assertEquals("original", target.readText())
            assertEquals(listOf(target.name), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun streamingBody(totalBytes: Long, chunk: ByteArray): ResponseBody {
        val source = object : Source {
            var remaining = totalBytes
            override fun timeout() = Timeout.NONE
            override fun close() {}
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1L
                val count = minOf(remaining, byteCount, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, count)
                remaining -= count
                return count.toLong()
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = totalBytes
            override fun source() = source
        }
    }

    private fun expectedRepeatedSha256(totalBytes: Long, chunk: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = totalBytes
        while (remaining > 0L) {
            val count = minOf(remaining, chunk.size.toLong()).toInt()
            digest.update(chunk, 0, count)
            remaining -= count
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Test
    fun fetchHttpRequestAndAuxiliarySearchKeepCancellationThroughBodyRead() = runBlocking {
        for (operation in listOf("fetch", "request", "search")) {
            val entered = CountDownLatch(1)
            val closed = AtomicBoolean()
            val provider = provider(blockedClient(entered, closed))
            val job = launch(Dispatchers.Default) {
                when (operation) {
                    "fetch" -> provider.fetch("http://93.184.216.34/file")
                    "request" -> provider.request("GET", "http://93.184.216.34/file")
                    else -> provider.search("test-key", listOf("test"))
                }
            }
            try {
                assertTrue("$operation did not enter body read", entered.await(3, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(closed.get())
            } finally { job.cancel() }
        }
    }

    private fun provider(http: OkHttpClient): LocalWebProvider {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return LocalWebProvider(
            context, http, Json,
            com.labteto.dshmobile.local.web.LocalSearchUsageStore(context, Json),
            com.labteto.dshmobile.local.tools.LocalNetworkSearchSettings(context),
        )
    }

    private fun blockedClient(entered: CountDownLatch, closed: AtomicBoolean): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(blockedBody(chain.call(), entered, closed)).build()
        }.build()

    private fun blockedBody(call: Call, entered: CountDownLatch, closed: AtomicBoolean): ResponseBody {
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun close() { closed.set(true) }
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!call.isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                throw IOException(if (call.isCanceled()) "cancelled" else "test timeout")
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source
        }
    }
}
