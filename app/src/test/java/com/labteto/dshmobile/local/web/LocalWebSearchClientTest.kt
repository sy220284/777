package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.local.LocalWebException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebSearchClientTest {
    @Test
    fun nativeSearchUsesOfficialEndpointAndReturnsAttribution() = runBlocking {
        val client = LocalWebSearchClient(http { request ->
            assertEquals("https://api.deepseek.com/v1/tools/search", request.url.toString())
            assertEquals("Bearer official-key", request.header("Authorization"))
            assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val fields = Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            assertEquals("最新技术", fields["query"]?.let { it.toString().trim('"') })
            assertEquals("30", fields["count"].toString())
            assertEquals("true", fields["need_content"].toString())
            response(request, 200, """
                {"results":[
                  {"title":"官网公告","url":"https://example.org/news",
                   "summary":"新版本已发布","content":"# 新版本完整正文",
                   "site_name":"官方","date":"2026-10-24T00:00:00Z",
                   "authority":4,"rerank_score":2.9},
                  {"title":"无效地址","url":"javascript:alert(1)","summary":"危险链接"}
                ]}
            """.trimIndent())
        }, Json)
        val actual = client.search("official-key", listOf("最新技术"), count = 30, needContent = true)
        assertTrue("实际搜索内容：$actual", actual.contains("来源：DeepSeek Search API"))
        assertTrue("实际搜索内容：$actual", actual.contains("[官网公告](https://example.org/news)"))
        assertTrue("实际搜索内容：$actual", actual.contains("权威度 4"))
        assertTrue("实际搜索内容：$actual", actual.contains("相关分 2.9"))
        assertTrue("实际搜索内容：$actual", actual.contains("正文摘录：# 新版本完整正文"))
        assertFalse("实际搜索内容：$actual", actual.contains("javascript:"))
        assertFalse("实际搜索内容：$actual", actual.contains("official-key"))
    }

    @Test
    fun emptyResultsAreSuccessfulButUnverifiedSources() = runBlocking {
        val client = LocalWebSearchClient(http { request ->
            response(request, 200, """{"results":[]}""")
        }, Json)
        val actual = client.search("key", listOf("不存在的关键词"))
        assertTrue(actual.contains("未找到可引用网页结果"))
    }

    @Test
    fun nativeFetchUsesOfficialEndpointAndReturnsMarkdown() = runBlocking {
        val client = LocalWebSearchClient(http { request ->
            assertEquals("https://api.deepseek.com/v1/tools/fetch", request.url.toString())
            assertEquals("Bearer official-key", request.header("Authorization"))
            val data = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(data.contains("\"url\":\"https://example.org/a\""))
            response(request, 200, """{"title":"文章标题","content":"# 正文\n\n内容"}""")
        }, Json)
        val result = client.fetch("official-key", "https://example.org/a")
        assertEquals("text/markdown; charset=utf-8", result.mediaType)
        assertTrue(result.content.contains("文章标题"))
        assertTrue(result.content.contains("# 正文"))
        assertFalse(result.truncated)
    }

    @Test
    fun invalidCredentialDoesNotLeakKeyOrImitateSuccess() = runBlocking {
        val client = LocalWebSearchClient(http { request ->
            response(request, 401, """{"error":{"message":"invalid token","code":"invalid_api_key"}}""")
        }, Json)
        val failure = runCatching { client.search("super-secret-key", listOf("test")) }.exceptionOrNull()
        assertTrue(failure is LocalWebException)
        assertEquals("AUTH_FAILED", (failure as LocalWebException).code)
        assertFalse(failure.message.orEmpty().contains("super-secret-key"))
    }

    @Test
    fun queryLimitPreservesUnicodeCodePoints() = runBlocking {
        val requests = mutableListOf<String>()
        val client = LocalWebSearchClient(http { request ->
            val text = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val query = (Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)["query"]
                ?.toString()?.trim('"').orEmpty()
            requests += query
            response(request, 200, """{"results":[]}""")
        }, Json)
        client.search("key", listOf("😀".repeat(150)))
        assertEquals(1, requests.size)
        assertEquals(128, requests.single().codePointCount(0, requests.single().length))
    }

    @Test
    fun billingOnlyCountsNonEmptyResultsAndValidFetchContent() = runBlocking {
        val charges = mutableListOf<String>()
        val client = LocalWebSearchClient(http { request ->
            if (request.url.encodedPath.endsWith("/search")) {
                response(request, 200, """{"results":[]}""")
            } else {
                response(request, 200, """{"title":"案例","content":"# 正文"}""")
            }
        }, Json) { charges += it }
        client.search("key", listOf("没有结果"))
        assertTrue(charges.isEmpty())
        client.fetch("key", "https://example.com")
        assertEquals(listOf("fetch"), charges)
    }

    @Test
    fun retriesRateLimitWithoutDoubleBillingAndForwardsOfficialFields() = runBlocking {
        var attempts = 0
        var charges = 0
        val client = LocalWebSearchClient(http { request ->
            attempts += 1
            if (attempts == 1) {
                response(request, 429, """{"error":{"code":"rate_limit_exceeded"}}""")
            } else {
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("\"ori_question\":\"用户原始提问\""))
                assertTrue(body.contains("\"localization\":\"8.8.8.8\""))
                response(request, 200,
                    """{"results":[{"title":"事实","url":"https://example.com","summary":"摘要"}]}""")
            }
        }, Json) { if (it == "search") charges++ }
        client.search(
            "key", listOf("查询"), oriQuestion = "用户原始提问", localization = "8.8.8.8",
        )
        assertEquals(2, attempts)
        assertEquals(1, charges)
    }

    @Test
    fun fetchRespectsRequestedSizeWithoutClaimingCompleteContent() = runBlocking {
        val client = LocalWebSearchClient(http { request ->
            val body = buildJsonObject {
                put("title", "")
                put("content", "a".repeat(20000))
            }.toString()
            response(request, 200, body)
        }, Json)
        val result = client.fetch("key", "https://example.com/long", maxBytes = 16 * 1024)
        assertTrue(result.truncated)
        assertEquals(16 * 1024, result.bytesRead)
        assertEquals(20_000L, result.totalBytes)
    }

    private fun http(reply: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request()) }.build()

    private fun response(request: okhttp3.Request, status: Int, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(status).message("mock").body(body.toResponseBody()).build()
}
