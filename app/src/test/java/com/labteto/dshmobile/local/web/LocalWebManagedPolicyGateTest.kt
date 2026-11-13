package com.labteto.dshmobile.local.web

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every outbound search request must recheck the active managed policy before OkHttp. */
class LocalWebManagedPolicyGateTest {
    @Test fun paidSearchPolicyDenialHappensBeforeAnyHttpRequest() = runBlocking {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        val client = LocalWebSearchClient(
            http, Json, verifyEndpoint = { endpoint ->
                assertTrue(endpoint.startsWith("https://api.deepseek.com/"))
                throw IllegalStateException("管理策略拒绝DeepSeek")
            },
        )
        val rejected = runCatching { client.search("secret", listOf("example")) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        assertEquals(0, attempts.get())
    }

    @Test fun freeSearchPolicyDenialHappensBeforeAnyHttpRequest() = runBlocking {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        val client = LocalKeylessWebSearchClient(http, verifyEndpoint = { endpoint ->
            assertTrue(endpoint.startsWith("https://www.bing.com/search"))
            throw IllegalStateException("管理策略拒绝公共搜索")
        })
        val rejected = runCatching { client.search(listOf("example")) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        assertEquals(0, attempts.get())
    }
}
