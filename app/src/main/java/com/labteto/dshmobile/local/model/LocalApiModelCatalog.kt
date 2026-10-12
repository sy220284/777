package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** This endpoint is entered or confirmed by the person editing the route, not by an agent. */
internal fun apiModelCatalogEndpoint(baseUrl: String): HttpUrl {
    val normalized = normalizeModelBaseUrl(baseUrl)
    val base = normalized.toHttpUrl()
    require(base.query == null) { "模型接口地址不能包含查询参数" }
    val path = base.encodedPath.trimEnd('/')
    return if (path.endsWith("/models")) base else base.newBuilder().addPathSegment("models").build()
}

internal fun parseApiModelCatalogPage(raw: String): Pair<List<String>, String?> {
    val root = Json.parseToJsonElement(raw).jsonObject
    val entries = root["data"]?.jsonArray
        ?: error("接口没有返回标准模型目录 data 数组")
    val models = entries.mapNotNull { item ->
        (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotBlank() && it.length <= 256 }
    }
    val hasMore = root["has_more"]?.jsonPrimitive?.booleanOrNull == true
    val cursor = if (hasMore) root["last_id"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) else null
    if (hasMore && cursor == null) error("服务商模型目录分页信息不完整")
    return models to cursor
}

/** Read-only model discovery. A successful catalog response is not an inference capability test. */
internal class LocalApiModelCatalog @Inject constructor(private val http: OkHttpClient) {
    suspend fun query(apiKey: String, baseUrl: String, protocol: LocalModelProtocol): List<String> =
        withTimeout(30_000L) {
            withContext(Dispatchers.IO) {
                require(apiKey.isNotBlank()) { "请填写 API 密钥后查询模型" }
                val endpoint = apiModelCatalogEndpoint(baseUrl)
                val client = http.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                val all = linkedSetOf<String>()
                var cursor: String? = null
                val visited = hashSetOf<String>()
                // Anthropic's native /models response is paginated; other compatible services
                // commonly return the entire list in one response.
                repeat(50) {
                    val target = if (cursor == null) endpoint else endpoint.newBuilder()
                        .addQueryParameter("after_id", cursor).build()
                    val requestBuilder = Request.Builder()
                        .url(target)
                        .header("Accept", "application/json")
                    if (protocol == LocalModelProtocol.ANTHROPIC_MESSAGES) {
                        requestBuilder.header("x-api-key", apiKey)
                            .header("anthropic-version", "2023-06-01")
                    } else {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                    val request = requestBuilder.get().build()
                    val page = withCancellableHttpResponse(client.newCall(request)) { response ->
                        check(response.isSuccessful) {
                            "模型目录查询失败（HTTP ${response.code}）；请检查密钥、地址或服务商是否支持 /models"
                        }
                        val raw = response.peekBody(2_097_153L).string()
                        check(raw.length <= 2_097_152) { "服务商模型目录响应过大" }
                        parseApiModelCatalogPage(raw)
                    }
                    all.addAll(page.first)
                    cursor = page.second
                    if (cursor == null) return@withContext all.sorted()
                    check(visited.add(cursor!!)) { "服务商模型目录分页游标重复" }
                }
                error("服务商模型目录超过 50 页；为避免返回不完整列表，已停止查询")
            }
        }
}
