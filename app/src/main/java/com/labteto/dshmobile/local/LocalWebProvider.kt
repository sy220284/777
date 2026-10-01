package com.labteto.dshmobile.local

import com.labteto.dshmobile.core.wire.withCancellableHttpResponse
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.security.MessageDigest
import javax.net.ssl.SSLPeerUnverifiedException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Android providers for web search, safe fetch and user-visible network diagnostics. */
@Singleton
class LocalWebProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
    private val usageTracker: DeepSeekUsageTracker,
) {
    private val targetResolver = com.labteto.dshmobile.local.web.LocalWebTargetResolver(context, http)

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
                val result = withCancellableHttpResponse(route.client.newCall(requestBuilder.build())) { response ->
                    if (response.isRedirect) {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw LocalWebException("HTTP_REDIRECT", "网页重定向次数过多")
                        }
                        val location = response.header("Location")
                            ?: throw LocalWebException("HTTP_REDIRECT", "网页重定向缺少地址")
                        current = validateTarget(current.uri.resolve(location).toString())
                        return@withCancellableHttpResponse null
                    }
                    if (!response.isSuccessful) {
                        val code = if (response.code in 400..499) "HTTP_4XX" else "HTTP_5XX"
                        throw LocalWebException(code, "服务器返回 HTTP ${response.code}：${current.uri}")
                    }
                    val body = response.body
                        ?: return@withCancellableHttpResponse LocalWebFetchResult(
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
                    return@withCancellableHttpResponse LocalWebFetchResult(
                        url = current.uri.toString(),
                        mediaType = mediaType,
                        content = content,
                        bytesRead = bounded.bytes.size,
                        totalBytes = totalBytes,
                        truncated = bounded.truncated,
                    )
                }
                result?.let { return@withContext it }
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

    suspend fun request(
        method: String,
        input: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        maxBytes: Int = DEFAULT_FETCH_BYTES,
        timeoutSeconds: Long = DEFAULT_FETCH_TIMEOUT_SECONDS,
    ): LocalHttpResponse = withContext(Dispatchers.IO) {
        val verb = method.trim().uppercase()
        require(verb in HTTP_METHODS) { "HTTP 方法仅支持 GET/HEAD/POST/PUT/PATCH/DELETE" }
        require(body == null || body.toByteArray().size <= MAX_REQUEST_BODY_BYTES) {
            "HTTP 请求体超过 ${MAX_REQUEST_BODY_BYTES / 1024} KiB"
        }
        val safeHeaders = validateRequestHeaders(headers)
        val byteLimit = maxBytes.coerceIn(MIN_FETCH_BYTES, MAX_FETCH_BYTES)
        var current = validateTarget(input)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val route = requestRoute(current, timeoutSeconds)
            val media = safeHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                ?.toMediaType()
                ?: if (body?.trimStart()?.startsWith("{") == true || body?.trimStart()?.startsWith("[") == true) {
                    JSON_MEDIA
                } else {
                    "text/plain; charset=utf-8".toMediaType()
                }
            val requestBody = when {
                verb in setOf("GET", "HEAD") -> null
                body != null -> body.toRequestBody(media)
                else -> ByteArray(0).toRequestBody(null)
            }
            val builder = Request.Builder()
                .url(route.url)
                .header("User-Agent", USER_AGENT)
                .method(verb, requestBody)
            safeHeaders.forEach { (name, value) -> builder.header(name, value) }
            route.hostHeader?.let { builder.header("Host", it) }
            val result = executeWithTransientRetry(
                client = route.client,
                request = builder.build(),
                attempts = if (verb in SAFE_RETRY_METHODS) SAFE_HTTP_RETRY_ATTEMPTS else 1,
            ) { response ->
                if (response.isRedirect) {
                    if (verb !in setOf("GET", "HEAD")) {
                        throw LocalWebException("HTTP_REDIRECT", "会改变远端状态的 HTTP 请求拒绝自动跟随重定向")
                    }
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw LocalWebException("HTTP_REDIRECT", "HTTP 请求重定向次数过多")
                    }
                    val location = response.header("Location")
                        ?: throw LocalWebException("HTTP_REDIRECT", "HTTP 重定向缺少地址")
                    current = validateTarget(current.uri.resolve(location).toString())
                    return@executeWithTransientRetry null
                }
                val responseBody = response.body
                val mediaType = responseBody?.contentType()?.toString().orEmpty()
                if (verb != "HEAD" && mediaType.isNotBlank() && !isTextualWebMediaType(mediaType)) {
                    throw LocalWebException(
                        "UNSUPPORTED_MEDIA",
                        "http_request 仅返回文本/JSON/XML；二进制响应请使用 download_file",
                    )
                }
                val bounded = responseBody?.byteStream()?.use { readBounded(it, byteLimit) }
                    ?: BoundedBytes(ByteArray(0), false)
                return@executeWithTransientRetry LocalHttpResponse(
                    url = current.uri.toString(),
                    status = response.code,
                    mediaType = mediaType,
                    headers = safeResponseHeaders(response),
                    content = bounded.bytes.toString(responseBody?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8),
                    bytesRead = bounded.bytes.size,
                    truncated = bounded.truncated,
                )
            }
            result?.let { return@withContext it }
        }
        throw LocalWebException("HTTP_REDIRECT", "HTTP 请求重定向次数过多")
    }

    suspend fun downloadTo(
        input: String,
        destination: File,
        maxBytes: Long = DEFAULT_DOWNLOAD_BYTES,
        timeoutSeconds: Long = DEFAULT_FETCH_TIMEOUT_SECONDS,
    ): LocalDownloadResult = withContext(Dispatchers.IO) {
        val boundedMax = maxBytes.coerceIn(MIN_DOWNLOAD_BYTES, MAX_DOWNLOAD_BYTES)
        var current = validateTarget(input)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val route = requestRoute(current, timeoutSeconds)
            val builder = Request.Builder()
                .url(route.url)
                .header("User-Agent", USER_AGENT)
                .get()
            route.hostHeader?.let { builder.header("Host", it) }
            val result = withCancellableHttpResponse(route.client.newCall(builder.build())) { response ->
                if (response.isRedirect) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw LocalWebException("HTTP_REDIRECT", "下载重定向次数过多")
                    }
                    val location = response.header("Location")
                        ?: throw LocalWebException("HTTP_REDIRECT", "下载重定向缺少地址")
                    current = validateTarget(current.uri.resolve(location).toString())
                    return@withCancellableHttpResponse null
                }
                if (!response.isSuccessful) {
                    throw LocalWebException(
                        if (response.code in 400..499) "HTTP_4XX" else "HTTP_5XX",
                        "下载失败（HTTP ${response.code}）：${current.uri}",
                    )
                }
                val body = response.body ?: throw LocalWebException("EMPTY_BODY", "下载响应为空")
                val declared = body.contentLength()
                if (declared > boundedMax) {
                    throw LocalWebException("DOWNLOAD_TOO_LARGE", "下载文件超过 ${boundedMax} 字节上限")
                }
                destination.parentFile?.mkdirs()
                val temporary = File.createTempFile("web-download-", ".part", destination.absoluteFile.parentFile)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    body.byteStream().use { inputStream ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = inputStream.read(buffer)
                                currentCoroutineContext().ensureActive()
                                if (read < 0) break
                                total += read
                                if (total > boundedMax) {
                                    throw LocalWebException(
                                        "DOWNLOAD_TOO_LARGE",
                                        "下载文件超过 ${boundedMax} 字节上限",
                                    )
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    require(total > 0L) { "下载文件为空" }
                    currentCoroutineContext().ensureActive()
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
                return@withCancellableHttpResponse LocalDownloadResult(
                    url = current.uri.toString(),
                    bytes = total,
                    mediaType = body.contentType()?.toString().orEmpty(),
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                )
            }
            result?.let { return@withContext it }
        }
        throw LocalWebException("HTTP_REDIRECT", "下载重定向次数过多")
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
                    "结论：DNS 与安全策略检查通过，但连续探测仍未建立稳定连接；这可能是链路抖动、代理/网关、TLS 或目标服务问题，单凭该结果不能判定 VPN/代理存在规则性阻断。"
                },
            )
        }.trimEnd()
    }

    /** Exact DeepSeek auxiliary-search wire format used by the official provider. */
    suspend fun search(
        apiKey: String,
        queries: List<String>,
        usageContext: TokenUsageContext? = null,
    ): String = withContext(Dispatchers.IO) {
        val clean = queries.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_QUERIES)
        require(clean.isNotEmpty()) { "至少需要一个搜索词" }
        val outputs = clean.map { query -> searchOne(apiKey, query, usageContext) }
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

    private suspend fun searchOne(
        apiKey: String,
        query: String,
        usageContext: TokenUsageContext?,
    ): String {
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
            withCancellableHttpResponse(http.newCall(request)) { response ->
                val bounded = response.body?.byteStream()?.use { readBounded(it, MAX_FETCH_BYTES) }
                require(bounded?.truncated != true) { "网页搜索响应超过大小上限" }
                val body = bounded?.bytes?.toString(Charsets.UTF_8).orEmpty()
                if (!response.isSuccessful) {
                    val code = if (response.code in 400..499) "HTTP_4XX" else "HTTP_5XX"
                    throw LocalWebException(code, "网页搜索失败（HTTP ${response.code}）：${body.take(500)}")
                }
                val root = json.parseToJsonElement(body).jsonObject
                usageTracker.record(
                    model = SEARCH_MODEL,
                    usage = parseDeepSeekAnthropicUsage(root),
                    requestId = UUID.randomUUID().toString(),
                    context = usageContext?.copy(
                        action = TokenUsageAction.WEB_SEARCH,
                        taskLabel = usageContext.taskLabel ?: query.take(120),
                    ) ?: TokenUsageContext(
                        mode = LocalUsageMode.WORK,
                        action = TokenUsageAction.WEB_SEARCH,
                        taskLabel = query.take(120),
                    ),
                    route = LocalModelRouteIdentity(
                        provider = "DeepSeek",
                        model = SEARCH_MODEL,
                        baseUrl = "https://api.deepseek.com",
                        authKind = LocalModelAuthKind.API_KEY.name,
                        protocol = LocalModelProtocol.ANTHROPIC_MESSAGES.name,
                    ),
                )
                formatSearch(query, root)
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

    private fun validateRequestHeaders(headers: Map<String, String>): Map<String, String> {
        require(headers.size <= 16) { "HTTP 请求头最多 16 项" }
        return headers.mapKeys { (name, _) ->
            val clean = name.trim()
            require(clean.lowercase() in SAFE_REQUEST_HEADERS) {
                "请求头 $clean 未获允许；认证信息请通过受保护的连接/凭据机制配置，不能写入工具参数"
            }
            clean
        }.mapValues { (_, value) ->
            require(value.length <= 4_096 && '\n' !in value && '\r' !in value) { "HTTP 请求头值无效" }
            value
        }
    }

    private fun validateTarget(input: String): com.labteto.dshmobile.local.web.ValidatedTarget = targetResolver.validateTarget(input)
    private fun parseUri(input: String): URI = targetResolver.parseUri(input)
    private fun normalizeInput(input: String): String = targetResolver.normalizeInput(input)
    private fun resolve(host: String): List<InetAddress> = targetResolver.resolve(host)
    private fun requestRoute(target: com.labteto.dshmobile.local.web.ValidatedTarget, timeoutSeconds: Long): com.labteto.dshmobile.local.web.RequestRoute =
        targetResolver.requestRoute(target, timeoutSeconds)
    private suspend fun probeConnectivity(target: com.labteto.dshmobile.local.web.ValidatedTarget): ConnectivityProbe {
        val failures = mutableListOf<String>()
        repeat(PROBE_ATTEMPTS) { index ->
            val attempt = probeConnectivityOnce(target)
            if (attempt.reachable) {
                return if (index == 0) {
                    attempt
                } else {
                    attempt.copy(detail = "第 ${index + 1}/$PROBE_ATTEMPTS 次探测成功：${attempt.detail}")
                }
            }
            failures += "${index + 1}/$PROBE_ATTEMPTS ${attempt.detail}"
            if (!attempt.retryable || index == PROBE_ATTEMPTS - 1) {
                return if (index == 0) {
                    attempt
                } else {
                    attempt.copy(
                        detail = "连续 ${index + 1} 次探测未建立稳定连接；${failures.joinToString("；")}",
                    )
                }
            }
            delay(NETWORK_RETRY_BACKOFF_MS * (index + 1))
        }
        error("网络探测重试循环异常结束")
    }

    private suspend fun probeConnectivityOnce(target: com.labteto.dshmobile.local.web.ValidatedTarget): ConnectivityProbe {
        return try {
            val route = requestRoute(target, PROBE_CALL_TIMEOUT_SECONDS)
            val builder = Request.Builder()
                .url(route.url)
                .header("User-Agent", USER_AGENT)
                .head()
            route.hostHeader?.let { builder.header("Host", it) }
            withCancellableHttpResponse(route.client.newCall(builder.build())) { response ->
                val classification = classifyProbeStatus(response.code)
                ConnectivityProbe(
                    reachable = classification.first,
                    detail = classification.second,
                    retryable = shouldRetryProbeStatus(response.code),
                )
            }
        } catch (error: SocketTimeoutException) {
            ConnectivityProbe(
                reachable = false,
                detail = "探测超时（PROBE_TIMEOUT）：${error.message ?: "连接未完成"}",
                retryable = true,
            )
        } catch (error: java.io.IOException) {
            ConnectivityProbe(
                reachable = false,
                detail = "探测失败（PROBE_FAILED）：${error.message ?: error::class.java.simpleName}",
                retryable = isRetryableTransportFailure(error),
            )
        }
    }

    private suspend fun <T> executeWithTransientRetry(
        client: OkHttpClient,
        request: Request,
        attempts: Int,
        read: suspend (Response) -> T,
    ): T {
        val boundedAttempts = attempts.coerceIn(1, SAFE_HTTP_RETRY_ATTEMPTS)
        repeat(boundedAttempts) { index ->
            try {
                val result = withCancellableHttpResponse(client.newCall(request)) { response ->
                    if (index + 1 < boundedAttempts && shouldRetryProbeStatus(response.code)) null
                    else Result.success(read(response))
                }
                if (result != null) return result.getOrThrow()
            } catch (error: java.io.IOException) {
                if (!isRetryableTransportFailure(error) || index + 1 >= boundedAttempts) throw error
            }
            delay(NETWORK_RETRY_BACKOFF_MS * (index + 1))
        }
        error("HTTP 重试循环异常结束")
    }

    private fun isRetryableTransportFailure(error: java.io.IOException): Boolean =
        error !is UnknownHostException && error !is SSLPeerUnverifiedException

    private fun safeResponseHeaders(response: Response): Map<String, String> = buildMap {
        SAFE_RESPONSE_HEADERS.forEach { name ->
            response.header(name)?.let { value -> put(name, value) }
        }
    }
    private fun systemHttpProxy(): com.labteto.dshmobile.local.web.ProxyEndpoint? = targetResolver.systemHttpProxy()
    private fun isVpnActive(): Boolean = targetResolver.isVpnActive()
    private fun activeTunnelInterfaces(): List<String> = targetResolver.activeTunnelInterfaces()
    private fun isAllowedVpnFakeAddress(host: String, address: InetAddress, vpn: Boolean): Boolean =
        targetResolver.isAllowedVpnFakeAddress(host, address, vpn)
    private fun blockedHint(host: String, blocked: List<InetAddress>, vpn: Boolean): String =
        targetResolver.blockedHint(host, blocked, vpn)
    private fun networkHint(target: com.labteto.dshmobile.local.web.ValidatedTarget): String = targetResolver.networkHint(target)
    private fun isPublicAddress(address: InetAddress): Boolean = targetResolver.isPublicAddress(address)
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

    private data class ConnectivityProbe(
        val reachable: Boolean,
        val detail: String,
        val retryable: Boolean = false,
    )

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
        const val SEARCH_MODEL = "deepseek-flash"
        const val USER_AGENT = "DSH-Mobile-Android16/0.12.0"
        const val MAX_REDIRECTS = 5
        const val MIN_FETCH_BYTES = 16 * 1024
        const val DEFAULT_FETCH_BYTES = 2 * 1024 * 1024
        const val MAX_FETCH_BYTES = 4 * 1024 * 1024
        const val PROBE_TIMEOUT_SECONDS = 6L
        const val PROBE_CALL_TIMEOUT_SECONDS = 8L
        const val PROBE_ATTEMPTS = 3
        const val SAFE_HTTP_RETRY_ATTEMPTS = 3
        const val NETWORK_RETRY_BACKOFF_MS = 200L
        const val DEFAULT_FETCH_TIMEOUT_SECONDS = 45L
        const val MAX_QUERIES = 4
        const val MAX_RESULTS = 10
        const val MAX_REQUEST_BODY_BYTES = 1024 * 1024
        const val MIN_DOWNLOAD_BYTES = 1024L
        const val DEFAULT_DOWNLOAD_BYTES = 20L * 1024L * 1024L
        const val MAX_DOWNLOAD_BYTES = 100L * 1024L * 1024L
        val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        val SAFE_RETRY_METHODS = setOf("GET", "HEAD")
        val SAFE_REQUEST_HEADERS = setOf("accept", "content-type", "if-none-match", "if-modified-since")
        val SAFE_RESPONSE_HEADERS = listOf(
            "X-RateLimit-Limit",
            "X-RateLimit-Remaining",
            "X-RateLimit-Reset",
            "X-RateLimit-Resource",
            "Retry-After",
            "ETag",
            "Last-Modified",
            "X-GitHub-Request-Id",
        )
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
data class LocalHttpResponse(
    val url: String,
    val status: Int,
    val mediaType: String,
    val headers: Map<String, String> = emptyMap(),
    val content: String,
    val bytesRead: Int,
    val truncated: Boolean,
)

data class LocalDownloadResult(
    val url: String,
    val bytes: Long,
    val mediaType: String,
    val sha256: String,
)

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
