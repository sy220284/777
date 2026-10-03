package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.web.LocalWebSearchClient
import com.labteto.dshmobile.local.web.LocalWebDiagnostics
import com.labteto.dshmobile.local.web.LOCAL_WEB_USER_AGENT
import com.labteto.dshmobile.local.web.LOCAL_WEB_MAX_RESPONSE_BYTES
import com.labteto.dshmobile.local.web.BoundedWebBody
import com.labteto.dshmobile.local.web.readBoundedWebBody
import com.labteto.dshmobile.local.web.isRetryableWebTransportFailure
import com.labteto.dshmobile.core.wire.withCancellableHttpResponse
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Android providers for web search, safe fetch and user-visible network diagnostics. */
@Singleton
class LocalWebProvider @Inject constructor(
    @ApplicationContext context: Context,
    http: OkHttpClient,
    json: Json,
    usageTracker: DeepSeekUsageTracker,
) {
    private val searchClient = LocalWebSearchClient(http, json, usageTracker)
    private val targetResolver = com.labteto.dshmobile.local.web.LocalWebTargetResolver(context, http)
    private val diagnostics = LocalWebDiagnostics(targetResolver)

    suspend fun fetch(
        input: String,
        maxBytes: Int = DEFAULT_FETCH_BYTES,
        format: String = "text",
        timeoutSeconds: Long = DEFAULT_FETCH_TIMEOUT_SECONDS,
    ): LocalWebFetchResult = withContext(Dispatchers.IO) {
        require(format in setOf("text", "raw")) { "web_fetch format 仅支持 text 或 raw" }
        val byteLimit = maxBytes.coerceIn(MIN_FETCH_BYTES, LOCAL_WEB_MAX_RESPONSE_BYTES)
        var current = validateTarget(input)
        try {
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                val route = requestRoute(current, timeoutSeconds)
                val requestBuilder = Request.Builder()
                    .url(route.url)
                    .header("User-Agent", LOCAL_WEB_USER_AGENT)
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
                    val bounded = body.byteStream().use { readBoundedWebBody(it, byteLimit) }
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
        val byteLimit = maxBytes.coerceIn(MIN_FETCH_BYTES, LOCAL_WEB_MAX_RESPONSE_BYTES)
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
                .header("User-Agent", LOCAL_WEB_USER_AGENT)
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
                val bounded = responseBody?.byteStream()?.use { readBoundedWebBody(it, byteLimit) }
                    ?: BoundedWebBody(ByteArray(0), false)
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
                .header("User-Agent", LOCAL_WEB_USER_AGENT)
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
    suspend fun diagnose(input: String): String = diagnostics.diagnose(input)

    suspend fun search(
        apiKey: String,
        queries: List<String>,
        usageContext: TokenUsageContext? = null,
    ): String = searchClient.search(apiKey, queries, usageContext)

    fun fallbackQuery(input: String): String {
        val normalized = normalizeInput(input)
        return runCatching {
            val uri = URI(normalized)
            if (uri.host.equals("github.com", ignoreCase = true)) {
                uri.path.trim('/').removeSuffix(".git").replace('/', ' ')
            } else normalized
        }.getOrDefault(normalized)
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

    private fun validateTarget(input: String): com.labteto.dshmobile.local.web.LocalWebTargetResolver.ValidatedTarget = targetResolver.validateTarget(input)
    private fun normalizeInput(input: String): String = targetResolver.normalizeInput(input)
    private fun requestRoute(target: com.labteto.dshmobile.local.web.LocalWebTargetResolver.ValidatedTarget, timeoutSeconds: Long): com.labteto.dshmobile.local.web.LocalWebTargetResolver.RequestRoute =
        targetResolver.requestRoute(target, timeoutSeconds)
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
                if (!isRetryableWebTransportFailure(error) || index + 1 >= boundedAttempts) throw error
            }
            delay(NETWORK_RETRY_BACKOFF_MS * (index + 1))
        }
        error("HTTP 重试循环异常结束")
    }

    private fun safeResponseHeaders(response: Response): Map<String, String> = buildMap {
        SAFE_RESPONSE_HEADERS.forEach { name ->
            response.header(name)?.let { value -> put(name, value) }
        }
    }
    private fun networkHint(target: com.labteto.dshmobile.local.web.LocalWebTargetResolver.ValidatedTarget): String = targetResolver.networkHint(target)

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
        const val MAX_REDIRECTS = 5
        const val MIN_FETCH_BYTES = 16 * 1024
        const val DEFAULT_FETCH_BYTES = 2 * 1024 * 1024
        const val SAFE_HTTP_RETRY_ATTEMPTS = 3
        const val NETWORK_RETRY_BACKOFF_MS = 200L
        const val DEFAULT_FETCH_TIMEOUT_SECONDS = 45L
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
