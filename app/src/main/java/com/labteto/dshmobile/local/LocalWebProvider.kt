package com.labteto.dshmobile.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
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
    suspend fun fetch(
        input: String,
        maxBytes: Int = DEFAULT_FETCH_BYTES,
        format: String = "text",
        timeoutSeconds: Long = DEFAULT_FETCH_TIMEOUT_SECONDS,
    ): LocalWebFetchResult = withContext(Dispatchers.IO) {
        require(format in setOf("text", "raw")) { "web_fetch format 仅支持 text 或 raw" }
        val byteLimit = maxBytes.coerceIn(MIN_FETCH_BYTES, MAX_FETCH_BYTES)
        var current = validateTarget(input)
        try {
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                val route = requestRoute(current, timeoutSeconds)
                val requestBuilder = Request.Builder()
                    .url(route.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.5")
                route.hostHeader?.let { requestBuilder.header("Host", it) }
                route.client.newCall(requestBuilder.build()).execute().use { response ->
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
                    val body = response.body
                        ?: return@withContext LocalWebFetchResult(
                            url = current.uri.toString(),
                            mediaType = "",
                            content = "",
                            bytesRead = 0,
                            totalBytes = 0,
                            truncated = false,
                        )
                    val mediaType = body.contentType()?.toString().orEmpty()
                    if (mediaType.isNotBlank() && !isTextualWebMediaType(mediaType)) {
                        throw LocalWebException(
                            "UNSUPPORTED_MEDIA",
                            "web_fetch 仅处理文本/JSON/XML；目标返回 $mediaType。请使用文件下载/附件流程处理二进制内容。",
                        )
                    }
                    val totalBytes = body.contentLength().takeIf { it >= 0L }
                    val bounded = body.byteStream().use { readBounded(it, byteLimit) }
                    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val rawText = bounded.bytes.toString(charset)
                    val content = if (format == "raw") rawText else normalize(rawText, mediaType)
                    return@withContext LocalWebFetchResult(
                        url = current.uri.toString(),
                        mediaType = mediaType,
                        content = content,
                        bytesRead = bounded.bytes.size,
                        totalBytes = totalBytes,
                        truncated = bounded.truncated,
                    )
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

        if (blocked.isNotEmpty()) {
            return@withContext buildString {
                appendLine("网络诊断")
                appendLine("目标：$normalized")
                appendLine("域名：$host")
                appendLine("解析：${addresses.joinToString { it.hostAddress ?: it.toString() }}")
                appendLine("系统代理：${proxy?.let { "${it.host}:${it.port}" } ?: "未检测到"}")
                appendLine("VPN/TUN：${if (vpn) "已启用" else "未检测到"}${if (interfaces.isNotEmpty()) "（${interfaces.joinToString()}）" else ""}")
                appendLine("命中受保护地址：${blocked.joinToString { it.hostAddress ?: it.toString() }}")
                append("结论：安全策略会主动拦截。${blockedHint(host, blocked, vpn)}")
            }.trimEnd()
        }

        val target = ValidatedTarget(uri, addresses, vpn)
        val probe = probeConnectivity(target)
        buildString {
            appendLine("网络诊断")
            appendLine("目标：$normalized")
            appendLine("域名：$host")
            appendLine("解析：${addresses.joinToString { it.hostAddress ?: it.toString() }}")
            appendLine("系统代理：${proxy?.let { "${it.host}:${it.port}" } ?: "未检测到"}")
            appendLine("VPN/TUN：${if (vpn) "已启用" else "未检测到"}${if (interfaces.isNotEmpty()) "（${interfaces.joinToString()}）" else ""}")
            appendLine("安全检查：通过")
            appendLine("实际连通性：${probe.detail}")
            append(
                if (probe.reachable) {
                    "结论：DNS、安全策略与实际 HTTP/TLS 连通性均已验证。"
                } else {
                    "结论：DNS 与安全策略检查通过，但实际连接失败；请检查代理分流、TUN 规则、TLS 拦截或目标服务可达性。"
                },
            )
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

    private fun requestRoute(target: ValidatedTarget, timeoutSeconds: Long): RequestRoute {
        val timeout = timeoutSeconds.coerceIn(MIN_FETCH_TIMEOUT_SECONDS, MAX_FETCH_TIMEOUT_SECONDS)
        val proxy = systemHttpProxy()
        val fakeIp = target.addresses.any { isAllowedVpnFakeAddress(target.uri.host, it, target.vpn) }
        if (proxy == null || fakeIp) {
            val client = http.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (!hostname.equals(target.uri.host, ignoreCase = true)) {
                            throw UnknownHostException("主机发生变化")
                        }
                        return target.addresses
                    }
                })
                .connectTimeout(FETCH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout + FETCH_CALL_GRACE_SECONDS, TimeUnit.SECONDS)
                .build()
            return RequestRoute(client, target.uri.toString(), null)
        }

        // 代理仍然使用，但 CONNECT/请求目标固定到已通过安全检查的 IP，
        // 防止代理侧重新解析同一域名后把请求导向 localhost/LAN/保留地址。
        val address = target.addresses.first()
        val pinnedUri = pinUriToAddress(target.uri, address)
        val builder = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
            .connectTimeout(FETCH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .callTimeout(timeout + FETCH_CALL_GRACE_SECONDS, TimeUnit.SECONDS)

        if (target.uri.scheme.equals("https", ignoreCase = true)) {
            val originalHost = target.uri.host
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            builder.sslSocketFactory(
                SniSocketFactory(http.sslSocketFactory, originalHost),
                http.x509TrustManager,
            )
            builder.hostnameVerifier { _, session -> verifier.verify(originalHost, session) }
        }

        return RequestRoute(
            client = builder.build(),
            url = pinnedUri.toString(),
            hostHeader = hostHeader(target.uri),
        )
    }

    private fun hostHeader(uri: URI): String {
        val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
        return if (uri.port == -1 || uri.port == defaultPort) uri.host else "${uri.host}:${uri.port}"
    }

    private fun probeConnectivity(target: ValidatedTarget): ConnectivityProbe {
        return try {
            val route = requestRoute(target, PROBE_CALL_TIMEOUT_SECONDS)
            val builder = Request.Builder()
                .url(route.url)
                .header("User-Agent", USER_AGENT)
                .head()
            route.hostHeader?.let { builder.header("Host", it) }
            route.client.newCall(builder.build()).execute().use { response ->
                val classification = classifyProbeStatus(response.code)
                ConnectivityProbe(classification.first, classification.second)
            }
        } catch (error: SocketTimeoutException) {
            ConnectivityProbe(false, "探测超时（PROBE_TIMEOUT）：${error.message ?: "连接未完成"}")
        } catch (error: java.io.IOException) {
            ConnectivityProbe(false, "探测失败（PROBE_FAILED）：${error.message ?: error::class.java.simpleName}")
        }
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

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): BoundedBytes {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8_192)
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        val all = output.toByteArray()
        val truncated = all.size > maxBytes
        return BoundedBytes(
            bytes = if (truncated) all.copyOf(maxBytes) else all,
            truncated = truncated,
        )
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

    private data class BoundedBytes(val bytes: ByteArray, val truncated: Boolean)

    private data class ConnectivityProbe(val reachable: Boolean, val detail: String)

    private data class RequestRoute(
        val client: OkHttpClient,
        val url: String,
        val hostHeader: String?,
    )

    private data class ValidatedTarget(
        val uri: URI,
        val addresses: List<InetAddress>,
        val vpn: Boolean,
    )

    private data class ProxyEndpoint(val host: String, val port: Int)

    private class SniSocketFactory(
        private val delegate: SSLSocketFactory,
        private val serverName: String,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            delegate.createSocket(socket, serverName, port, autoClose)

        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(serverName, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = delegate.createSocket(serverName, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = delegate.createSocket(address, port, localAddress, localPort)
    }
    private companion object {
        const val SEARCH_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
        const val SEARCH_MODEL = "deepseek-v4-flash"
        const val USER_AGENT = "DSH-Mobile-Android16/0.12.0"
        const val MAX_REDIRECTS = 5
        const val MIN_FETCH_BYTES = 16 * 1024
        const val DEFAULT_FETCH_BYTES = 2 * 1024 * 1024
        const val MAX_FETCH_BYTES = 4 * 1024 * 1024
        const val PROBE_TIMEOUT_SECONDS = 6L
        const val PROBE_CALL_TIMEOUT_SECONDS = 8L
        const val DEFAULT_FETCH_TIMEOUT_SECONDS = 45L
        const val MIN_FETCH_TIMEOUT_SECONDS = 5L
        const val MAX_FETCH_TIMEOUT_SECONDS = 300L
        const val FETCH_CONNECT_TIMEOUT_SECONDS = 10L
        const val FETCH_CALL_GRACE_SECONDS = 10L
        const val MAX_QUERIES = 4
        const val MAX_RESULTS = 10
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
internal fun pinUriToAddress(uri: URI, address: InetAddress): URI =
    URI(
        uri.scheme,
        null,
        address.hostAddress,
        uri.port,
        uri.rawPath?.ifEmpty { "/" } ?: "/",
        uri.rawQuery,
        null,
    )

internal fun classifyProbeStatus(code: Int): Pair<Boolean, String> = when (code) {
    407 -> false to "系统代理可达，但要求代理认证（HTTP 407）"
    502, 503, 504 -> false to "已连接到代理/网关，但其无法正常连接目标（HTTP $code）"
    in 500..599 -> true to "目标 HTTP/TLS 路径已建立，但服务端返回错误（HTTP $code）"
    else -> true to "已建立目标 HTTP/TLS 连接（HTTP $code）"
}

internal fun isTextualWebMediaType(mediaType: String): Boolean {
    val type = mediaType.substringBefore(';').trim().lowercase()
    return type.startsWith("text/") ||
        type in setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/xhtml+xml",
            "application/rss+xml",
            "application/atom+xml",
            "application/javascript",
            "application/x-javascript",
        ) ||
        type.endsWith("+json") ||
        type.endsWith("+xml")
}

data class LocalWebFetchResult(
    val url: String,
    val mediaType: String,
    val content: String,
    val bytesRead: Int,
    val totalBytes: Long?,
    val truncated: Boolean,
)

class LocalWebException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception("[$code] $message", cause)
