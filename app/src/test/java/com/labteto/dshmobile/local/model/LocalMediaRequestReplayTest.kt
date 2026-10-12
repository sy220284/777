package com.labteto.dshmobile.local.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaRequestReplayTest {
    private fun probe(body: RequestBody): Pair<Int, Int> {
        val submissions = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/billable") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val first = submissions.incrementAndGet() == 1
            if (first) exchange.responseHeaders.set("Retry-After", "0")
            val response = if (first) "temporary failure" else "second submission"
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(if (first) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        return try {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/billable").post(body).build()
            client.newCall(request).execute().use { response -> response.code to submissions.get() }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.stop(0) }
    }

    @Test fun ordinaryRepeatablePostDemonstrates503AutomaticReplay() {
        val (status, count) = probe("billable prompt".toRequestBody("text/plain".toMediaType()))
        assertEquals(200, status)
        assertEquals(2, count)
    }

    @Test fun chargedMediaPostReturnsOriginalFailureWithoutAnotherSubmission() {
        val (status, count) = probe(localMediaOneShotBody("billable prompt".toRequestBody("text/plain".toMediaType())))
        assertEquals(503, status)
        assertEquals(1, count)
    }
}
