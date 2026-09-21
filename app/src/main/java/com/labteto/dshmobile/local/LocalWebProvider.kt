package com.labteto.dshmobile.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.SocketTimeoutException
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

/** Android providers for web search, safe fetch and user-visible network diagnostics. */
@Singleton
class LocalWebProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(input: String): String = withContext(Dispatchers.IO) {
        var current = validateTarget(input)
        try {
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                val request = Request.Builder()
                    .url(current.uri.toString())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.5")
                    .build()
                pinnedClient(current).newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw LocalWebException("HTTP_REDIRECT", "网页重定向次数过多")
                        }
                        val location = response.header("Location")
                            ?: throw LocalWebException("HTTP_REDIRECT", "网页重定向缺少地址")
                        current = validateTarget(current.uri.resolve(location).toString())
                        return@repeat
                    }
                    if (!response.isSuccessful) {
                        val code = if (response.code in 400..499) "HTTP_4XX" else "HTTP_5XX"
                        throw LocalWebException(code, "服务器返回 HTTP ${response.code}：${current.uri}")
                    }
                    val mediaType = response.body?.contentType()?.toString().orEmpty()
                    val body = response.body ?: return@withContext "网页没有响应正文"
                    val text = body.charStream().use(::readBounded)
                    return@withContext "URL: ${current.uri}\nContent-Type: $mediaType\n${normalize(text, mediaType)}"
                }
            }
            throw LocalWebException("HTTP_REDIRECT", "网页重定向次数过多")
        } catch (error: LocalWebException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LocalWebException("TIMEOUT", "连接 ${current.uri.host} 超时。${networkHint(current)}", error)
        } catch (error: UnknownHostException) {
            throw LocalWebException("DNS_FAILED", "无法解析 ${current.uri.host}。${networkHint(current)}", error)
        } catch (error: java.io.IOException) {
            throw LocalWebException(
                "NETWORK_ERROR",
                "访问 ${current.uri.host} 失败：${error.message ?: "网络异常"}。${networkHint(current)}",
                error,
            )
        }
    }

    /** Human-readable diagnosis used by both the UI and the local Harness tool. */
    suspend fun diagnose(input: String): String = withContext(Dispatchers.IO) {
        val normalized = normalizeInput(input)
        val uri = parseUri(normalized)
        val host = requireNotNull(uri.host)
        val proxy = systemHttpProxy()
        val vpn = isVpnActive()
        val interfaces = activeTunnelInterfaces()
        val addresses = resolve(host)
        val blocked = addresses.filterNot { isPublicAddress(it) || isAllowedVpnFakeAddress(host, it, vpn) }
        buildString {
            appendLine("网络诊断")
            appendLine("目标：$normalized")
            appendLine("域名：$host")
            appendLine("解析：${addresses.joinToString { it.hostAddress ?: it.toString() }}")
            appendLine("系统代理：${proxy?.let { "${it.host}:${it.port}" } ?: "未检测到"}")
            appendLine("VPN/TUN：${if (vpn) "已启用" else "未检测到"}${if (interfaces.isNotEmpty()) "（${interfaces.joinToString()}）" else ""}")
            if (blocked.isEmpty()) {
                append("结论：地址通过安全检查，可尝试直接抓取。")
            } else {
                appendLine("命中受保护地址：${blocked.joinToString { it.hostAddress ?: it.toString() }}")
                append("结论：安全策略会主动拦截.${blockedHint(host, blocked, vpn)}")
            }
        }.trimEnd()
    }

    /** Exact DeepSeek auxiliary-search wire format used by the official provider. */
    suspend fun search(apiKey: String, queries: List<String>): String = withContext(Dispatchers.IO) {
        val clean = queries.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_QUERIES)
        require(clean.isNotEmpty()) { "至少需要一个搜索词" }
        val outputs = clean.map { query -> searchOne(apiKey, query) }
        outputs.joinToString("\n\n")
    }

    fun fallbackQuery(input: String): String {
        val normalized = normalizeInput(input)
        return runCatching {
            val uri = URI(normalized)
            if (uri.host.equals("github.com", ignoreCase = true)) {
                uri.path.trim('/').removeSuffix(".git").replace('/', ' ')
            } else normalized
        }.getOrDefault(normalized)
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
        return try {
            runInterruptible { http.newCall(request).execute() }.use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val code = if (response.code in 400..499) "HTTP_4XX" else "HTTP_5XX"
                    throw LocalWebException(code, "网页搜索失败（HTTP ${response.code}）：${body.take(500)}")
                }
                formatSearch(query, json.parseToJsonElement(body).jsonObject)
            }
        } catch (error: LocalWebException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LocalWebException("TIMEOUT", "网页搜索连接超时", error)
        } catch (error: java.io.IOException) {
            throw LocalWebException("NETWORK_ERROR", "网页搜索网络异常：${error.message}", error)
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
        if (sources.isEmpty()) {
            return "搜索：$query\n未找到公开可引用结果。目标可能不存在、属于私有资源，或名称/地址有误；请核对后重试。"
        }
        return buildString {
            append("搜索：").append(query).append('\n')
            if (answer.isNotEmpty()) append(answer.joinToString("\n").take(4_000)).append('\n')
            append(sources.joinToString("\n"))
        }
    }

    private fun validateTarget(input: String): ValidatedTarget {
        val normalized = normalizeInput(input)
        val uri = parseUri(normalized)
        val host = requireNotNull(uri.host)
        val addresses = resolve(host)
        val vpn = isVpnActive()
        val blocked = addresses.filterNot { isPublicAddress(it) || isAllowedVpnFakeAddress(host, it, vpn) }
        if (blocked.isNotEmpty()) {
            throw LocalWebException(
                "SSRF_BLOCKED",
                buildString {
                    append("目标域名被解析为受保护地址：$host → ")
                    append(blocked.joinToString { it.hostAddress ?: it.toString() })
                    append("。")
                    append(blockedHint(host, blocked, vpn))
                },
            )
        }
        return ValidatedTarget(uri, addresses, vpn)
    }

    private fun parseUri(input: String): URI {
        val uri = runCatching { URI(input.trim()) }.getOrElse {
            throw LocalWebException("INVALID_URL", "网址格式不正确")
        }
        if (uri.scheme != "https" && uri.scheme != "http") {
            throw LocalWebException("INVALID_URL", "仅允许 HTTP/HTTPS 地址")
        }
        if (uri.host == null || uri.userInfo != null) {
            throw LocalWebException("INVALID_URL", "网址缺少有效主机或包含用户信息")
        }
        if (uri.port != -1 && uri.port !in setOf(80, 443)) {
            throw LocalWebException("SSRF_BLOCKED", "网页获取只允许 80 或 443 端口")
        }
        return uri
    }

    private fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(candidate)
            if (uri.host.equals("github.com", ignoreCase = true) && uri.path.endsWith(".git")) {
                URI(uri.scheme, uri.authority, uri.path.removeSuffix(".git"), uri.query, uri.fragment).toString()
            } else candidate
        }.getOrDefault(candidate)
    }

    private fun resolve(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).toList().also {
            if (it.isEmpty()) throw UnknownHostException(host)
        }
    } catch (error: UnknownHostException) {
        throw LocalWebException("DNS_FAILED", "无法解析域名 $host", error)
    }

    private fun pinnedClient(target: ValidatedTarget): OkHttpClient {
        val builder = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
        val proxy = systemHttpProxy()
        if (proxy != null) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
        } else {
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(target.uri.host, ignoreCase = true)) {
                        throw UnknownHostException("主机发生变化")
                    }
                    return target.addresses
                }
            })
        }
        return builder.build()
    }

    private fun systemHttpProxy(): ProxyEndpoint? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val proxy = manager.getLinkProperties(network)?.httpProxy ?: return null
        val host = proxy.host ?: return null
        val port = proxy.port
        return if (host.isNotBlank() && port in 1..65535) ProxyEndpoint(host, port) else null
    }

    private fun isVpnActive(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun activeTunnelInterfaces(): List<String> = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && (it.name.startsWith("tun") || it.name.startsWith("wg") || it.name.startsWith("ppp")) }
            .map { it.name }
    }.getOrDefault(emptyList())

    private fun isAllowedVpnFakeAddress(host: String, address: InetAddress, vpn: Boolean): Boolean {
        if (!vpn || looksLikeIpLiteral(host) || address !is Inet4Address) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 198 && second in 18..19
    }

    private fun looksLikeIpLiteral(host: String): Boolean =
        host.contains(':') || host.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.toIntOrNull()?.let { value -> value in 0..255 } == true
            }
        }

    private fun blockedHint(host: String, blocked: List<InetAddress>, vpn: Boolean): String {
        val fakeRange = blocked.any { address ->
            if (address !is Inet4Address) false else {
                val bytes = address.address
                (bytes[0].toInt() and 0xff) == 198 && (bytes[1].toInt() and 0xff) in 18..19
            }
        }
        return when {
            fakeRange && !vpn ->
                "该地址位于 RFC 2544 基准测试保留网段，常见于代理/VPN 的 Fake-IP 模式；当前 App 未检测到活动 VPN，请检查代理/TUN 状态。"
            fakeRange ->
                "检测到 VPN/TUN，但该目标仍未满足安全放行条件；请在“网络诊断”中检查代理与解析状态。"
            else ->
                "这属于安全策略主动拦截，并非普通网络超时。若你确认 $host 是公网域名，请运行“网络诊断”检查 DNS/代理。"
        }
    }

    private fun networkHint(target: ValidatedTarget): String {
        val proxy = systemHttpProxy()
        return buildString {
            append("解析地址：${target.addresses.joinToString { it.hostAddress ?: it.toString() }}")
            if (target.vpn) append("；检测到 VPN/TUN")
            if (proxy != null) append("；系统代理 ${proxy.host}:${proxy.port}")
            append("。可运行“网络诊断”进一步定位。")
        }
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

    private data class ValidatedTarget(
        val uri: URI,
        val addresses: List<InetAddress>,
        val vpn: Boolean,
    )

    private data class ProxyEndpoint(val host: String, val port: Int)

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

class LocalWebException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception("[$code] $message", cause)
