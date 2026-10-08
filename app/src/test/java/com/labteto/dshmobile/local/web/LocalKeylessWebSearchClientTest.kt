package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.local.LocalWebException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKeylessWebSearchClientTest {
    @Test
    fun keylessRequestUsesPinnedPublicSearchWithoutSendingModelCredentials() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https", request.url.scheme)
            assertEquals("www.bing.com", request.url.host)
            assertEquals("rss", request.url.queryParameter("format"))
            assertEquals("model switching", request.url.queryParameter("q"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("x-api-key"))
            val rss = "<rss version=\"2.0\"><channel><item><title>Search hit</title>" +
                "<link>https://example.org/hit</link></item></channel></rss>"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(rss.toResponseBody("application/rss+xml".toMediaType())).build()
        }.build()

        val result = LocalKeylessWebSearchClient(http).search(listOf("model switching"))
        assertTrue(result.contains("[Search hit](https://example.org/hit)"))
    }

    @Test
    fun rssProvidesCitationLinksWithoutCredential() {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel>
            <item><title>发布公告</title><link>https://example.org/news</link>
            <description>官方 &amp; 新消息</description></item>
            <item><title>不安全链接</title><link>javascript:alert(1)</link></item>
        </channel></rss>"""
        val formatted = parseKeylessSearchRss("今天发布什么", rss)
        assertTrue(formatted.contains("[发布公告](https://example.org/news)"))
        assertTrue(formatted.contains("官方 & 新消息"))
        assertFalse(formatted.contains("javascript:"))
    }

    @Test
    fun blockedHtmlDoesNotLookLikeEmptySearch() {
        val failure = runCatching {
            parseKeylessSearchRss("a", "<html><title>blocked</title></html>")
        }.exceptionOrNull()
        assertTrue(failure is LocalWebException)
        assertTrue((failure as LocalWebException).code == "INVALID_RESPONSE")
    }

    @Test
    fun externalEntitiesCannotBeUsedToReadLocalFiles() {
        val failure = runCatching {
            parseKeylessSearchRss("a", """<!DOCTYPE rss [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><title>&leak;</title></item></channel></rss>""")
        }.exceptionOrNull()
        assertTrue(failure is LocalWebException)
    }
}
