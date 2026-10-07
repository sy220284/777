package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.core.wire.withCancellableHttpResponse
import com.labteto.dshmobile.local.LocalWebException
import java.io.StringReader
import java.net.URI
import java.net.SocketTimeoutException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.InputSource

/**
 * Keyless public-web search, independent of the selected LLM account.
 * Only the pinned Bing RSS endpoint is queried; no HTML SERP scraping or hosted app backend.
 */
internal class LocalKeylessWebSearchClient(private val http: OkHttpClient) {
    suspend fun search(queries: List<String>): String = withContext(Dispatchers.IO) {
        val clean = queries.map(String::trim).filter(String::isNotBlank).distinct().take(4)
        require(clean.isNotEmpty()) { "至少需要一个搜索词" }
        clean.joinToString("\n\n") { query -> searchOne(query.take(400)) }
    }

    private suspend fun searchOne(query: String): String {
        val url = "https://www.bing.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "rss")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LOCAL_WEB_USER_AGENT)
            .header("Accept", "application/rss+xml, application/xml;q=0.9")
            .get()
            .build()
        return try {
            withCancellableHttpResponse(http.newCall(request)) { response ->
                if (!response.isSuccessful) {
                    throw LocalWebException("SEARCH_HTTP", "公共网页搜索返回 HTTP " + response.code)
                }
                val bounded = response.body?.byteStream()?.use {
                    readBoundedWebBody(it, MAX_RSS_BYTES)
                } ?: throw LocalWebException("INVALID_RESPONSE", "公共搜索未返回内容")
                if (bounded.truncated) {
                    throw LocalWebException("RESPONSE_TOO_LARGE", "公共搜索响应超过大小上限")
                }
                parseKeylessSearchRss(query, bounded.bytes.toString(Charsets.UTF_8))
            }
        } catch (error: LocalWebException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LocalWebException("TIMEOUT", "公共搜索连接超时", error)
        } catch (error: java.io.IOException) {
            throw LocalWebException("NETWORK_ERROR", "公共搜索网络异常：" + error.message, error)
        }
    }

    private companion object {
        const val MAX_RSS_BYTES = 512 * 1024
    }
}

/** Harden XML before reading remote snippets. An HTML/blocked response must fail, not look empty. */
internal fun parseKeylessSearchRss(query: String, xml: String): String {
    val document = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (error: Exception) {
        throw LocalWebException("INVALID_RESPONSE", "公共搜索 RSS 响应格式无效", error)
    }
    if (!document.documentElement.tagName.equals("rss", ignoreCase = true)) {
        throw LocalWebException("INVALID_RESPONSE", "公共搜索返回了非 RSS 内容")
    }
    val entries = document.getElementsByTagName("item")
    val results = linkedSetOf<String>()
    for (index in 0 until minOf(entries.length, 24)) {
        val item = entries.item(index) as? org.w3c.dom.Element ?: continue
        fun field(name: String): String = item.getElementsByTagName(name)
            .item(0)?.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val title = field("title").take(180)
        val url = field("link")
        val validLink = runCatching {
            val uri = URI(url)
            (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
        if (!validLink || title.isBlank()) continue
        val excerpt = field("description").take(450)
        results += "- [" + title.replace("[", "［").replace("]", "］") + "](" + url + ")" +
            if (excerpt.isBlank()) "" else "\n  " + excerpt
        if (results.size >= 8) break
    }
    return if (results.isEmpty()) {
        "搜索：" + query + "\n公共搜索没有找到可引用结果。请更换搜索词或检查网络状态。"
    } else {
        "搜索：" + query + "\n来源：Bing RSS（公开搜索结果）\n" + results.joinToString("\n")
    }
}
