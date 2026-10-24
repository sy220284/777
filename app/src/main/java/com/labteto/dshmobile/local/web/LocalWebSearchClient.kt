package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import com.labteto.dshmobile.local.LocalWebException
import com.labteto.dshmobile.local.LocalWebFetchResult
import com.labteto.dshmobile.local.TokenUsageContext
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Native DeepSeek Search/Fetch APIs; no model completion or token usage. */
internal class LocalWebSearchClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val onBillableResult: (String) -> Unit = {},
) {
    suspend fun search(
        apiKey: String,
        queries: List<String>,
        @Suppress("UNUSED_PARAMETER") usageContext: TokenUsageContext? = null,
        count: Int = 10,
        needContent: Boolean = false,
        oriQuestion: String? = null,
        localization: String? = null,
    ): String = withContext(Dispatchers.IO) {
        require(count in 1..50) { "DeepSeek 搜索结果数量必须为 1～50" }
        val clean = queries.map(String::trim).filter(String::isNotBlank).distinct().take(4)
        require(clean.isNotEmpty()) { "至少需要一个搜索词" }
        clean.map { query ->
            val normalized = if (query.codePointCount(0, query.length) > 128) {
                query.substring(0, query.offsetByCodePoints(0, 128))
            } else query
            val payload = buildJsonObject {
                put("query", normalized)
                put("count", count)
                put("need_content", needContent)
                oriQuestion?.trim()?.takeIf(String::isNotBlank)?.let { original ->
                    require(original.codePointCount(0, original.length) <= 128) {
                        "用户原始问题 ori_question 超出官方 128 字符限制"
                    }
                    put("ori_question", original)
                }
                localization?.trim()?.takeIf(String::isNotBlank)?.let { put("localization", it) }
            }
            val result = postJson(apiKey, SEARCH_ENDPOINT, payload)
            val rows = result["results"] as? JsonArray
                ?: throw LocalWebException("INVALID_RESPONSE", "DeepSeek Search 缺少 results 数组")
            if (rows.isNotEmpty()) onBillableResult("search")
            val rendered = formatDeepSeekSearch(normalized, result, needContent)
            if (normalized == query) rendered else "搜索词超过 DeepSeek 128 字符限制，已截取前 128 字符。\n" + rendered
        }.joinToString("\n\n")
    }

    suspend fun fetch(apiKey: String, url: String, maxBytes: Int = LOCAL_WEB_MAX_RESPONSE_BYTES, timeoutSeconds: Long = 45L): LocalWebFetchResult = withContext(Dispatchers.IO) {
        val root = postJson(apiKey, FETCH_ENDPOINT, buildJsonObject { put("url", url) }, timeoutSeconds)
        val body = root["content"]?.jsonPrimitive?.contentOrNull
            ?: throw LocalWebException("INVALID_RESPONSE", "DeepSeek Fetch 缺少网页正文")
        if (body.isBlank()) throw LocalWebException("EMPTY_BODY", "DeepSeek Fetch 未返回可读正文")
        onBillableResult("fetch")
        val title = root["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val markdown = if (title.isBlank()) body else "标题：" + title + "\n\n" + body
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        val limit = maxBytes.coerceIn(16 * 1024, LOCAL_WEB_MAX_RESPONSE_BYTES)
        val truncated = bytes.size > limit
        val visible = if (truncated) bytes.copyOf(limit).toString(Charsets.UTF_8) else markdown
        LocalWebFetchResult(
            url = url,
            mediaType = "text/markdown; charset=utf-8",
            content = visible,
            bytesRead = minOf(bytes.size, limit),
            totalBytes = bytes.size.toLong(),
            truncated = truncated,
        )
    }

    private suspend fun postJson(
        apiKey: String,
        endpoint: String,
        payload: JsonObject,
        timeoutSeconds: Long = 45L,
    ): JsonObject {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer " + apiKey)
            .header("User-Agent", LOCAL_WEB_USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val timeout = timeoutSeconds.coerceIn(15L, 900L)
        val client = http.newBuilder()
            .callTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        var attempt = 0
        while (true) {
            try {
                return withCancellableHttpResponse(client.newCall(request)) { response ->
                    val bounded = response.body?.byteStream()?.use {
                        readBoundedWebBody(it, LOCAL_WEB_MAX_RESPONSE_BYTES)
                    } ?: throw LocalWebException("INVALID_RESPONSE", "DeepSeek 未返回 JSON 数据")
                    if (bounded.truncated) {
                        throw LocalWebException("RESPONSE_TOO_LARGE", "DeepSeek 搜索/抓取响应超过大小上限")
                    }
                    if (!response.isSuccessful) {
                        val code = when (response.code) {
                            401 -> "AUTH_FAILED"
                            402 -> "INSUFFICIENT_BALANCE"
                            403 -> "ACCESS_DENIED"
                            404 -> "NOT_FOUND"
                            408 -> "TIMEOUT"
                            413 -> "REQUEST_TOO_LARGE"
                            429 -> "RATE_LIMITED"
                            in 400..499 -> "HTTP_4XX"
                            else -> "HTTP_5XX"
                        }
                        throw LocalWebException(code, "DeepSeek " +
                            (if (endpoint == SEARCH_ENDPOINT) "Search" else "Fetch") +
                            " 返回 HTTP " + response.code)
                    }
                    try {
                        json.parseToJsonElement(bounded.bytes.toString(Charsets.UTF_8)) as? JsonObject
                            ?: throw LocalWebException("INVALID_RESPONSE", "DeepSeek 响应不是 JSON 对象")
                    } catch (error: LocalWebException) {
                        throw error
                    } catch (error: Exception) {
                        throw LocalWebException("INVALID_RESPONSE", "DeepSeek 响应不是有效的 JSON", error)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LocalWebException) {
                if (error.code !in setOf("TIMEOUT", "RATE_LIMITED", "HTTP_5XX") || attempt >= 2) {
                    throw error
                }
                attempt += 1
                delay(attempt * 400L)
            } catch (error: SocketTimeoutException) {
                throw LocalWebException("TIMEOUT", "DeepSeek 搜索/抓取超时", error)
            } catch (error: java.io.IOException) {
                throw LocalWebException("NETWORK_ERROR", "DeepSeek 搜索/抓取网络异常：" + error.message, error)
            }
        }
    }

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.deepseek.com/v1/tools/search"
        const val FETCH_ENDPOINT = "https://api.deepseek.com/v1/tools/fetch"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Preserve URL provenance and rankings; page content is untrusted reference data. */
internal fun formatDeepSeekSearch(query: String, root: JsonObject, needContent: Boolean): String {
    val entries = root["results"] as? JsonArray
        ?: throw LocalWebException("INVALID_RESPONSE", "DeepSeek Search 缺少 results 数组")
    val seen = linkedSetOf<String>()
    val sources = mutableListOf<String>()
    for (item in entries) {
        val result = item as? JsonObject ?: continue
        val url = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = result["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val validUrl = runCatching {
            val uri = URI(url)
            (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
        if (!validUrl || title.isBlank() || !seen.add(url)) continue
        val date = result["date"]?.jsonPrimitive?.contentOrNull?.take(25)
        val site = result["site_name"]?.jsonPrimitive?.contentOrNull?.take(80)
        val authority = result["authority"]?.jsonPrimitive?.contentOrNull
        val score = result["rerank_score"]?.jsonPrimitive?.contentOrNull
        val summary = result["summary"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: result["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val metadata = listOfNotNull(site, date, authority?.let { "权威度 " + it }, score?.let { "相关分 " + it })
        val safeTitle = title.take(180).replace("[", "［").replace("]", "］")
        sources += buildString {
            append("- [").append(safeTitle).append("](").append(url).append(")")
            if (metadata.isNotEmpty()) append(" · ").append(metadata.joinToString(" · "))
            if (summary.isNotBlank()) append("\n  ").append(summary.replace(Regex("\\s+"), " ").take(850))
            if (needContent) {
                result["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                    append("\n  正文摘录：").append(it.take(1600))
                }
            }
        }
    }
    return if (sources.isEmpty()) {
        "搜索：" + query + "\n来源：DeepSeek Search API\n未找到可引用网页结果。"
    } else {
        "搜索：" + query + "\n来源：DeepSeek Search API（网页内容未经信任）\n" + sources.joinToString("\n")
    }
}
