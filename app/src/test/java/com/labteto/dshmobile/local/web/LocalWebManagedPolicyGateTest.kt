package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.di.enforceManagedNetworkDestinations
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search adapters reuse the shared OkHttp admission instead of adding another gate. */
class LocalWebManagedPolicyGateTest {
    @Test fun paidSearchPolicyDenialHappensBeforeAnyHttpRequest() = runBlocking {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder()
            .enforceManagedNetworkDestinations { _, host ->
                check(host != "api.deepseek.com") { "管理策略拒绝DeepSeek" }
            }
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
        val client = LocalWebSearchClient(http, Json)
        val rejected = runCatching { client.search("secret", listOf("example")) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        assertEquals(0, attempts.get())
    }

    @Test fun paidSearchRejectsRedirectWithoutRetryingOrFollowing() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "https://untrusted.example/mcp")
                .body("".toResponseBody())
                .build()
        }.build()
        val client = LocalWebSearchClient(http, Json)
        val failure = runCatching { client.search("test-key", listOf("test")) }.exceptionOrNull()
        assertTrue("拒绝重定向须返回受控错误，实际类型：" +
            (failure?.javaClass?.name ?: "无异常") + "；请求次数：" + calls.get(),
            failure is com.labteto.dshmobile.local.LocalWebException)
        assertEquals("敏感搜索不得自动重放到重定向地址", 1, calls.get())
        assertEquals("UNSAFE_REDIRECT", (failure as com.labteto.dshmobile.local.LocalWebException).code)
    }

    @Test fun freeSearchPolicyDenialHappensBeforeAnyHttpRequest() = runBlocking {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder()
            .enforceManagedNetworkDestinations { _, host ->
                check(host != "www.bing.com") { "管理策略拒绝公共搜索" }
            }
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
        val client = LocalKeylessWebSearchClient(http)
        val rejected = runCatching { client.search(listOf("example")) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        assertEquals(0, attempts.get())
    }
}
