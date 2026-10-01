package com.labteto.dshmobile.update

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePayloadTransferTest {
    @Test
    fun cancellationDuringBodyReadNeverRetriesOrCommitsAndCleansStaging() = runBlocking {
        val root = Files.createTempDirectory("update-cancel").toFile()
        val entered = CountDownLatch(1)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(blockingBody(chain.call(), entered)).build()
        }.build()
        val target = root.resolve("app.apk").apply { writeText("existing") }
        val transfer = UpdatePayloadTransfer(client)
        val job = launch(Dispatchers.Default) {
            transfer.downloadFile("http://example.com/apk", target, 1024, null, "APK", "*/*")
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertEquals("existing", target.readText())
            assertEquals(listOf("app.apk"), root.list()!!.toList())
            assertEquals(1, requests.get())
        } finally {
            job.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun checksumCancellationAlsoOwnsBodyRead() = runBlocking {
        val entered = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(blockingBody(chain.call(), entered)).build()
        }.build()
        val job = launch(Dispatchers.Default) { UpdatePayloadTransfer(client).fetchText("http://example.com/sums", 1024) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
        } finally { job.cancel() }
    }

    @Test
    fun successfulDownloadReplacesCompleteFileAndLengthMismatchKeepsOriginal() = runBlocking {
        val root = Files.createTempDirectory("update-transfer").toFile()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body("apk".toResponseBody()).build()
        }.build()
        try {
            val target = root.resolve("app.apk").apply { writeText("old") }
            val transfer = UpdatePayloadTransfer(client)
            transfer.downloadFile("http://example.com/apk", target, 1024, 3, "APK", "*/*")
            assertEquals("apk", target.readText())
            val error = runCatching {
                transfer.downloadFile("http://example.com/apk", target, 1024, 4, "APK", "*/*")
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("apk", target.readText())
            assertEquals(listOf("app.apk"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun updateCheckCancellationDoesNotFallBackToOtherEndpoints() = runBlocking {
        val entered = CountDownLatch(1)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(blockingBody(chain.call(), entered)).build()
        }.build()
        val job = launch(Dispatchers.Default) { UpdateChecker(client).checkNow("0.1.0") }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertEquals(1, requests.get())
        } finally { job.cancel() }
    }

    private fun blockingBody(call: Call, entered: CountDownLatch): ResponseBody {
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
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
