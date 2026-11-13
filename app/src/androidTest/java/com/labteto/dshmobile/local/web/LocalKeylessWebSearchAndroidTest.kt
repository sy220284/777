package com.labteto.dshmobile.local.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labteto.dshmobile.local.LocalWebException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies XML parser compatibility on a real Android runtime, not just desktop JVM. */
@RunWith(AndroidJUnit4::class)
class LocalKeylessWebSearchAndroidTest {
    @Test
    fun legitimateRssParsesOnAndroidEvenWithoutSecureProcessingFeature() {
        val xml = """<?xml version="1.0"?><rss version="2.0"><channel><item>
            <title>今日公告</title><link>https://example.com/notice</link>
            <description><![CDATA[安全的摘要]]></description>
        </item></channel></rss>"""
        val result = parseKeylessSearchRss("今日公告", xml)
        assertTrue(result.contains("[今日公告](https://example.com/notice)"))
        assertTrue(result.contains("安全的摘要"))
    }

    @Test
    fun androidParserRejectsDtdDeclarationsWithoutOpeningEntities() {
        val attack = """<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///proc/version">]>
            <rss><channel><item><title>&xxe;</title></item></channel></rss>"""
        val result = runCatching { parseKeylessSearchRss("test", attack) }.exceptionOrNull()
        assertTrue(result is LocalWebException)
        assertEquals("INVALID_RESPONSE", (result as LocalWebException).code)
        assertTrue(result.message.orEmpty().contains("DTD"))
    }
}
