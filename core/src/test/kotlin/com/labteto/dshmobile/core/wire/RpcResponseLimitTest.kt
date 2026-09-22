package com.labteto.dshmobile.core.wire

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RpcResponseLimitTest {
    @Test fun bodyReadFailureCompletesCallerInsteadOfHanging() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long = throw java.io.IOException("broken stream")
                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                }).build()
        }.build()
        val error = runCatching {
            kotlinx.coroutines.withTimeout(2_000) { OkHttpRpcTransport("http://stub", http).post("/api/test", "{}") }
        }.exceptionOrNull()
        assertTrue(error is RpcTransportException)
        assertTrue(error!!.message!!.contains("broken stream"))
    }

    private fun transport(size: Int, declared: Long, status: Int = 200): OkHttpRpcTransport {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(object : ResponseBody() {
                    private val buffer = Buffer().write(ByteArray(size) { 'x'.code.toByte() })
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = declared
                    override fun source(): BufferedSource = buffer
                }).build()
        }.build()
        return OkHttpRpcTransport("http://stub", http)
    }

    @Test fun acceptsExactLimit() = runBlocking {
        val size = 8 * 1024 * 1024
        assertEquals(size, transport(size, size.toLong()).post("/api/test", "{}").body.length)
    }

    @Test fun rejectsDeclaredAndUndeclaredOversizeOnPostAndUpload() = runBlocking {
        for (status in listOf(200, 500)) {
            val size = (if (status == 200) 8 * 1024 * 1024 else 256 * 1024) + 1
            for (declared in listOf(size.toLong(), -1L)) {
                val transport = transport(size, declared, status)
                val post = runCatching { transport.post("/api/test", "{}") }.exceptionOrNull()
                assertTrue(post is RpcTransportException)
                assertTrue(post!!.message!!.contains("exceeds"))
                val upload = runCatching {
                    transport.upload("/api/upload", "application/octet-stream", 0, ByteArrayInputStream(byteArrayOf()))
                }.exceptionOrNull()
                assertTrue(upload is RpcTransportException)
                assertTrue(upload!!.message!!.contains("exceeds"))
            }
        }
    }
}
