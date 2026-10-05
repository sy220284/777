package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.model.DeepSeekPricingRepository
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import java.io.IOException
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
        val usage = DeepSeekUsageTracker(context, DeepSeekPricingRepository(context, http), TokenUsageAnalyticsStore(context, Json))
        return LocalWebProvider(context, http, Json, usage)
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
