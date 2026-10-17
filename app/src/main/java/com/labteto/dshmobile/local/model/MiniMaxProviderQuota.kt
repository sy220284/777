package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

/** Provider quota is an external account fact. It must never be mixed into the local token ledger. */
/** The older API calls usage_count "remaining"; some new versions use it as "used".
 * Prefer the server's remaining-percent field; never invent a count when they disagree.
 */
internal object MiniMaxProviderQuotaParser {
    fun parse(root: JsonObject, balance: Boolean): MiniMaxProviderUsage {
        LocalMiniMaxPolicy.requireSuccessfulResponse(root)
        if (balance) {
            check(root["available_amount"] != null) { "MiniMax 账户余额响应缺少可用金额" }
            return MiniMaxProviderUsage.AccountBalance(
                available = root.text("available_amount").orEmpty(),
                cash = root.text("cash_balance").orEmpty(),
                voucher = root.text("voucher_balance").orEmpty(),
                credit = root.text("credit_balance").orEmpty(),
            )
        }
        val array = root["model_remains"]?.jsonArray
            ?: error("MiniMax 套餐额度响应缺少 model_remains")
        return MiniMaxProviderUsage.TokenPlan(array.map { value ->
            val model = value.jsonObject
            val absent = model.number("current_interval_total_count") == 0L &&
                model.number("current_weekly_total_count") == 0L &&
                model.int("current_interval_status") == 3 &&
                model.int("current_weekly_status") == 3
            MiniMaxProviderUsage.ModelQuota(
                name = model.text("model_name").orEmpty().ifBlank { "未命名额度" },
                interval = window(model, "current_interval", absent),
                weekly = window(model, "current_weekly", absent),
                intervalResetMillis = model.number("remains_time"),
                weeklyResetMillis = model.number("weekly_remains_time"),
            )
        })
    }

    private fun window(root: JsonObject, prefix: String, absent: Boolean): MiniMaxProviderUsage.Window {
        val total = root.number("${prefix}_total_count")
        val reported = root.number("${prefix}_usage_count")
        val percent = root.decimal("${prefix}_remaining_percent")
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val status = root.int("${prefix}_status")
        val multiplier = if (prefix == "current_weekly") {
            (root.number("weekly_boost_permille") ?: 1000L).coerceAtLeast(0L) / 1000.0
        } else 1.0
        val remaining = when {
            absent || status == 3 || total == null || reported == null ||
                total <= 0 || reported !in 0..total -> null
            percent == null -> reported // legacy contract: count means remaining
            abs(reported * 100.0 / total - percent) <= 1.0 -> reported
            abs((total - reported) * 100.0 / total - percent) <= 1.0 -> total - reported
            else -> null
        }
        return MiniMaxProviderUsage.Window(
            remaining = remaining,
            total = total?.takeIf { it > 0 },
            remainingPercent = if (status == 2) 0.0 else percent?.times(multiplier),
            unlimited = status == 3 && !absent,
            notInPlan = absent,
        )
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.number(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.decimal(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull
}

/** Resolves the exact selected profile's credential without exposing it to Settings or the UI. */
@Singleton
internal class MiniMaxProviderQuotaClient @Inject constructor(
    private val credentials: LocalModelCredentialResolver,
    private val http: OkHttpClient,
    private val json: Json,
) {
    suspend fun query(profile: LocalModelProfile): MiniMaxProviderUsage = withContext(Dispatchers.IO) {
        require(profile.authKind == LocalModelAuthKind.API_KEY &&
            profile.model.startsWith("MiniMax-", ignoreCase = true) &&
            LocalMiniMaxPolicy.isOfficial(profile.baseUrl)
        ) { "当前所选模型不支持 MiniMax 官方额度查询" }
        val uri = URI(normalizeModelBaseUrl(profile.baseUrl))
        require(uri.scheme.equals("https", ignoreCase = true) && uri.port == -1) {
            "额度查询仅支持 MiniMax 官方 HTTPS 端点"
        }
        val credential = credentials.resolve(profile.model, profile.baseUrl, profile).bearerToken
        val balance = credential.startsWith("sk-api-")
        val path = if (balance) "/account/query_balance" else "/v1/token_plan/remains"
        // The endpoint is assembled from an allow-listed official host, never the profile's path.
        val request = Request.Builder()
            .url("https://${uri.host}$path")
            .header("Authorization", "Bearer $credential")
            .header("Accept", "application/json")
            .get()
            .build()
        withCancellableHttpResponse(http.newCall(request)) { response ->
            if (!response.isSuccessful) {
                error("MiniMax 额度查询失败（HTTP ${response.code}）")
            }
            val body = response.body?.string() ?: error("MiniMax 未返回额度数据")
            val root = json.parseToJsonElement(body).jsonObject
            MiniMaxProviderQuotaParser.parse(root, balance)
        }
    }
}
