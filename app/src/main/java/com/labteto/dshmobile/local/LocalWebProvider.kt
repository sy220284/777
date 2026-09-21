package com.labteto.dshmobile.local

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Android providers for the official Harness web search and anonymous fetch seams. */
@Singleton
class LocalWebProvider @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(input: String): String = withContext(Dispatchers.IO) {
        var current = validatePublicUrl(input)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val client = pinnedClient(current.host)
            val request = Request.Builder()
                .url(current.toString())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.5")
                .build()
            runInterruptible { client.newCall(request).execute() }.use { response ->
                if (response.isRedirect) {
                    require(redirectCount < MAX_REDIRECTS) { "网页重定向次数过多" }
                    val location = response.header("Location") ?: error("网页重定向缺少地址")
                    current = validatePublicUrl(current.resolve(location).toString())
                    return@repeat
                }
                require(response.isSuccessful) { "网页请求失败：HTTP ${response.code}" }
                val mediaType = response.body?.contentType()?.toString().orEmpty()
                val body = response.body ?: return@withContext "网页没有响应正文"
                val text = body.charStream().use(::readBounded)
                return@withContext "URL: $current\nContent-Type: $mediaType\n${normalize(text, mediaType)}"
            }
        }
        error("网页重定向次数过多")
    }

    /** Exact DeepSeek auxiliary-search wire format used by the official provider. */
    suspend fun search(apiKey: String, queries: List<String>): String = withContext(Dispatchers.IO) {
        val clean = queries.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_QUERIES)
        require(clean.isNotEmpty()) { "至少需要一个搜索词" }
        val outputs = clean.map { query -> searchOne(apiKey, query) }
        outputs.joinToString("\n\n")
    }

    private suspend fun searchOne(apiKey: String, query: String): String {
        val payload = buildJsonObject {
            put("model", SEARCH_MODEL)
            put("max_tokens", 4096)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Perform a web search for the query: $query")
                        })
                    })
                })
            })
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                    put("max_uses", 5)
                })
            })
        }
        val request = Request.Builder()
            .url(SEARCH_ENDPOINT)
            .header("x-api-key", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("anthropic-version", "2023-06-01")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return runInterruptible { http.newCall(request).execute() }.use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "网页搜索失败（HTTP ${response.code}）：${body.take(500)}" }
            formatSearch(query, json.parseToJsonElement(body).jsonObject)
        }
    }

    private fun formatSearch(query: String, root: JsonObject): String {
        val blocks = root["content"]?.jsonArray ?: JsonArray(emptyList())
        val snippets = linkedMapOf<String, String>()
        val answer = mutableListOf<String>()
        blocks.forEach { element ->
            val block = element.jsonObject
            if (block["type"]?.jsonPrimitive?.contentOrNull == "text") {
                block["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let(answer::add)
                block["citations"]?.jsonArray.orEmpty().forEach { citationElement ->
                    val citation = citationElement.jsonObject
                    val url = citation["url"]?.jsonPrimitive?.contentOrNull
                    val text = citation["cited_text"]?.jsonPrimitive?.contentOrNull
                    if (!url.isNullOrBlank() && !text.isNullOrBlank()) snippets.putIfAbsent(url, text)
                }
            }
        }
        val seen = linkedSetOf<String>()
        val sources = mutableListOf<String>()
        blocks.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "web_search_tool_result" }
            .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
            .forEach { resultElement ->
                val result = resultElement.jsonObject
                if (result["type"]?.jsonPrimitive?.contentOrNull != "web_search_result") return@forEach
                val url = result["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                if (!seen.add(url) || sources.size >= MAX_RESULTS) return@forEach
                val title = result["title"]?.jsonPrimitive?.contentOrNull ?: url
                val age = result["page_age"]?.jsonPrimitive?.contentOrNull?.let { " · $it" }.orEmpty()
                val snippet = snippets[url]?.replace(Regex("\\s+"), " ")?.take(500)?.let { "\n  $it" }.orEmpty()
                sources += "- [$title]($url)$age$snippet"
            }
        require(sources.isNotEmpty()) { "DeepSeek 搜索没有返回可引用结果" }
        return buildString {
            append("搜索：").append(query).append('\n')
            if (answer.isNotEmpty()) append(answer.joinToString("\n").take(4_000)).append('\n')
            append(sources.joinToString("\n"))
        }
    }

    private fun validatePublicUrl(input: String): URI {
        val uri = runCatching { URI(input.trim()) }.getOrElse { error("网址格式不正确") }
        require(uri.scheme == "https" || uri.scheme == "http") { "仅允许 HTTP/HTTPS 地址" }
        require(uri.host != null && uri.userInfo == null) { "网址缺少有效主机或包含用户信息" }
        require(uri.port == -1 || uri.port in setOf(80, 443)) { "网页获取只允许 80 或 443 端口" }
        val addresses = InetAddress.getAllByName(uri.host)
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "拒绝访问本机、局域网或保留地址" }
        return uri
    }

    private fun pinnedClient(host: String): OkHttpClient {
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) throw UnknownHostException("非公网地址")
        return http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(host, ignoreCase = true)) throw UnknownHostException("主机发生变化")
                    return addresses.toList()
                }
            })
            .build()
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        if (address is Inet4Address) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false
            if (first == 100 && second in 64..127) return false
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 168) return false
            if (first == 198 && second in 18..19) return false
        }
        if (address is Inet6Address) {
            val first = bytes[0].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return false
        }
        return true
    }

    private fun readBounded(reader: java.io.Reader): String {
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (output.length < MAX_FETCH_CHARS) {
            val read = reader.read(buffer, 0, minOf(buffer.size, MAX_FETCH_CHARS - output.length))
            if (read < 0) break
            output.append(buffer, 0, read)
        }
        return output.toString()
    }

    private fun normalize(text: String, mediaType: String): String {
        if (!mediaType.contains("html", ignoreCase = true)) return text
        return text
            .replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
        const val SEARCH_MODEL = "deepseek-v4-flash"
        const val USER_AGENT = "DSH-Mobile-Android16/0.12.0"
        const val MAX_REDIRECTS = 5
        const val MAX_FETCH_CHARS = 200_000
        const val MAX_QUERIES = 4
        const val MAX_RESULTS = 10
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
