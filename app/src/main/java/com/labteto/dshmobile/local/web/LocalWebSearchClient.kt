package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.core.wire.withCancellableHttpResponse
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.LocalWebException
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelRouteIdentity
import com.labteto.dshmobile.local.model.parseDeepSeekAnthropicUsage
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

/** DeepSeek auxiliary search wire format, result projection and actual API usage attribution. */
internal class LocalWebSearchClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val usageTracker: DeepSeekUsageTracker,
) {
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
            .header("User-Agent", LOCAL_WEB_USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return try {
            withCancellableHttpResponse(http.newCall(request)) { response ->
                val bounded = response.body?.byteStream()?.use { readBoundedWebBody(it, LOCAL_WEB_MAX_RESPONSE_BYTES) }
                if (bounded?.truncated == true) throw LocalWebException("RESPONSE_TOO_LARGE", "网页搜索响应超过 ${LOCAL_WEB_MAX_RESPONSE_BYTES} 字节上限")
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

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
        const val SEARCH_MODEL = "deepseek-flash"
        const val MAX_QUERIES = 4
        const val MAX_RESULTS = 10
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
