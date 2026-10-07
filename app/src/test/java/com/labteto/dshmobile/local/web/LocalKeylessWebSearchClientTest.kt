package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.local.LocalWebException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKeylessWebSearchClientTest {
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
