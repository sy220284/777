package com.labteto.dshmobile.core.wire

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcTransferCancellationTest {
    @Test
    fun downloadCancellationClosesSocketDuringBodyReadAndPreventsConsumerCompletion() = runBlocking {
        BlockingReplyServer().use { server ->
            val consumed = AtomicBoolean(false)
            val reading = CountDownLatch(1)
            val transport = OkHttpRpcTransport(server.url)
            val job = launch(Dispatchers.Default) {
                transport.download("/export") { _, _, input ->
                    reading.countDown()
                    input.readBytes()
                    consumed.set(true)
                }
            }
            try {
                assertTrue(reading.await(3, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertFalse(consumed.get())
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun uploadCancellationAlsoOwnsResponseBodyLifetime() = runBlocking {
        BlockingReplyServer().use { server ->
            val completed = AtomicBoolean(false)
            val transport = OkHttpRpcTransport(server.url)
            val job = launch(Dispatchers.Default) {
                transport.upload("/upload", "application/octet-stream", 3, "abc".byteInputStream())
                completed.set(true)
            }
            try {
                assertTrue(server.replyStarted.await(3, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertFalse(completed.get())
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun uploadCancellationClosesABlockedProviderInputAndDoesNotReportMoreBytes() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val progressed = AtomicBoolean()
        val source = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return 1
            }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                read()
                buffer[off] = 1
                return 1
            }
            override fun close() { closed.set(true); release.countDown() }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().body!!.writeTo(Buffer())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body("ok".toResponseBody()).build()
        }.build()
        val transport = OkHttpRpcTransport("http://example.com", client)
        val job = launch(Dispatchers.Default) {
            transport.upload("/upload", "application/octet-stream", -1, source) { progressed.set(true) }
        }
        try {
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(closed.get())
            assertFalse(progressed.get())
        } finally { release.countDown(); job.cancel() }
    }

    @Test
    fun downloadAndUploadKeepCarrierErrorsAndSuccessContract() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (chain.request().url.encodedPath == "/missing") 404 else 200)
                .message("test").body("abc".toResponseBody()).build()
        }.build()
        val transport = OkHttpRpcTransport("http://example.com", client)
        assertEquals("abc", transport.download("/ok") { _, _, input -> input.readBytes().decodeToString() })
        assertEquals("abc", transport.upload("/ok", "text/plain", 0, "".byteInputStream()).body)
        for (upload in listOf(false, true)) {
            val failure = runCatching {
                if (upload) transport.upload("/missing", "text/plain", 0, "".byteInputStream())
                else transport.download("/missing") { _, _, input -> input.readBytes() }
            }.exceptionOrNull()
            assertTrue(failure is RpcTransportException)
            assertEquals(404, (failure as RpcTransportException).status)
        }
    }

    /** Sends headers, then deliberately keeps a chunked response body idle. */
    private class BlockingReplyServer : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val release = CountDownLatch(1)
        val replyStarted = CountDownLatch(1)
        val url = "http://127.0.0.1:${socket.localPort}"

        init {
            executor.submit {
                runCatching {
                    socket.accept().use { client ->
                        val input = client.getInputStream()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            if (byte < 0) return@submit
                            headers.append(byte.toChar())
                        }
                        val length = Regex("(?i)Content-Length: (\\d+)")
                            .find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(length) { input.read() }
                        val out = client.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                        out.flush()
                        replyStarted.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        }

        override fun close() {
            release.countDown()
            socket.close()
            executor.shutdownNow()
        }
    }
}
