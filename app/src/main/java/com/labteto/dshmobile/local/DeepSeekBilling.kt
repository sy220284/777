package com.labteto.dshmobile.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class DeepSeekPricePeriod {
    OFF_PEAK,
    PEAK,
}

data class DeepSeekPriceTier(
    val cacheHitCnyPerMillion: Double,
    val cacheMissCnyPerMillion: Double,
    val outputCnyPerMillion: Double,
)

data class DeepSeekModelPricing(
    val modelId: String,
    val displayName: String,
    val version: String,
    val offPeak: DeepSeekPriceTier,
    val peak: DeepSeekPriceTier,
    val aliases: Set<String> = emptySet(),
) {
    fun tier(period: DeepSeekPricePeriod): DeepSeekPriceTier =
        if (period == DeepSeekPricePeriod.PEAK) peak else offPeak

    fun matches(model: String): Boolean {
        val normalized = model.trim().lowercase()
        return normalized == modelId || normalized in aliases
    }
}

data class DeepSeekPricingState(
    val models: List<DeepSeekModelPricing> = defaultDeepSeekPricing(),
    val lastUpdatedAt: Long = 0L,
    val refreshing: Boolean = false,
    val error: String? = null,
    val sourceUrl: String = DeepSeekPricingRepository.OFFICIAL_PRICING_URL,
)

data class DeepSeekTokenUsage(
    val promptTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val reported: Boolean = false,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

data class DeepSeekUsageSnapshot(
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val requestCount: Long = 0L,
    val estimatedCostCny: Double = 0.0,
    val unpricedTokens: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val cacheMeasuredTokens: Long get() = cacheHitTokens + cacheMissTokens
    val cacheHitRate: Double
        get() = if (cacheMeasuredTokens <= 0L) 0.0 else cacheHitTokens.toDouble() / cacheMeasuredTokens.toDouble()
}

/**
 * DeepSeek currently bills peak/off-peak by Beijing time. The official page also says statutory
 * holidays are off-peak; this local classifier intentionally does not embed a holiday calendar, so
 * holiday usage is a conservative estimate until DeepSeek exposes the billed tier in API usage.
 */
object DeepSeekBillingSchedule {
    private val BEIJING = ZoneId.of("Asia/Shanghai")

    fun periodAt(epochMillis: Long): DeepSeekPricePeriod {
        val time = Instant.ofEpochMilli(epochMillis).atZone(BEIJING)
        if (time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY) {
            return DeepSeekPricePeriod.OFF_PEAK
        }
        val minute = time.hour * 60 + time.minute
        val peak = minute in (9 * 60) until (12 * 60) ||
            minute in (14 * 60) until (18 * 60)
        return if (peak) DeepSeekPricePeriod.PEAK else DeepSeekPricePeriod.OFF_PEAK
    }
}

object DeepSeekCostCalculator {
    fun estimateCny(
        model: String,
        usage: DeepSeekTokenUsage,
        pricing: DeepSeekPricingState,
        epochMillis: Long,
    ): Double? {
        val modelPricing = pricing.models.firstOrNull { it.matches(model) } ?: return null
        val tier = modelPricing.tier(DeepSeekBillingSchedule.periodAt(epochMillis))
        val miss = usage.cacheMissTokens.takeIf { it > 0L }
            ?: (usage.promptTokens - usage.cacheHitTokens).coerceAtLeast(0L)
        return (
            usage.cacheHitTokens * tier.cacheHitCnyPerMillion +
                miss * tier.cacheMissCnyPerMillion +
                usage.completionTokens * tier.outputCnyPerMillion
            ) / 1_000_000.0
    }
}

@Singleton
class DeepSeekPricingRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadPersistedState())
    val state: StateFlow<DeepSeekPricingState> = _state.asStateFlow()

    fun pricingFor(model: String): DeepSeekModelPricing? =
        _state.value.models.firstOrNull { it.matches(model) }

    suspend fun refreshFromOfficial() {
        _state.update { it.copy(refreshing = true, error = null) }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(OFFICIAL_PRICING_URL)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("User-Agent", "DSH-Mobile/777 DeepSeek-Pricing")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "官网返回 HTTP ${response.code}" }
                    val html = response.body?.string().orEmpty()
                    check(html.isNotBlank()) { "官网价格页为空" }
                    check(html.length <= MAX_PRICING_HTML_CHARS) { "官网价格页异常过大" }
                    parseDeepSeekPricingPage(html)
                }
            }
        }
        result.onSuccess { models ->
            val now = System.currentTimeMillis()
            persist(models, now)
            _state.value = DeepSeekPricingState(
                models = models,
                lastUpdatedAt = now,
                refreshing = false,
                error = null,
            )
        }.onFailure { error ->
            _state.update {
                it.copy(
                    refreshing = false,
                    error = error.message ?: "无法解析 DeepSeek 官网价格",
                )
            }
        }
    }

    private fun loadPersistedState(): DeepSeekPricingState {
        val defaults = defaultDeepSeekPricing()
        val models = defaults.map { fallback ->
            val prefix = "model.${fallback.modelId}."
            DeepSeekModelPricing(
                modelId = fallback.modelId,
                displayName = fallback.displayName,
                version = fallback.version,
                offPeak = DeepSeekPriceTier(
                    cacheHitCnyPerMillion = preferences.readDouble(prefix + "off.hit", fallback.offPeak.cacheHitCnyPerMillion),
                    cacheMissCnyPerMillion = preferences.readDouble(prefix + "off.miss", fallback.offPeak.cacheMissCnyPerMillion),
                    outputCnyPerMillion = preferences.readDouble(prefix + "off.output", fallback.offPeak.outputCnyPerMillion),
                ),
                peak = DeepSeekPriceTier(
                    cacheHitCnyPerMillion = preferences.readDouble(prefix + "peak.hit", fallback.peak.cacheHitCnyPerMillion),
                    cacheMissCnyPerMillion = preferences.readDouble(prefix + "peak.miss", fallback.peak.cacheMissCnyPerMillion),
                    outputCnyPerMillion = preferences.readDouble(prefix + "peak.output", fallback.peak.outputCnyPerMillion),
                ),
                aliases = fallback.aliases,
            )
        }
        return DeepSeekPricingState(
            models = models,
            lastUpdatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    private fun persist(models: List<DeepSeekModelPricing>, updatedAt: Long) {
        preferences.edit().apply {
            putLong(KEY_UPDATED_AT, updatedAt)
            models.forEach { model ->
                val prefix = "model.${model.modelId}."
                putString(prefix + "off.hit", model.offPeak.cacheHitCnyPerMillion.toString())
                putString(prefix + "off.miss", model.offPeak.cacheMissCnyPerMillion.toString())
                putString(prefix + "off.output", model.offPeak.outputCnyPerMillion.toString())
                putString(prefix + "peak.hit", model.peak.cacheHitCnyPerMillion.toString())
                putString(prefix + "peak.miss", model.peak.cacheMissCnyPerMillion.toString())
                putString(prefix + "peak.output", model.peak.outputCnyPerMillion.toString())
            }
        }.apply()
    }

    companion object {
        const val OFFICIAL_PRICING_URL = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"
        private const val PREFS = "deepseek_pricing"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val MAX_PRICING_HTML_CHARS = 2_000_000
    }
}

@Singleton
class DeepSeekUsageTracker @Inject constructor(
    @ApplicationContext context: Context,
    private val pricingRepository: DeepSeekPricingRepository,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<DeepSeekUsageSnapshot> = _state.asStateFlow()

    fun record(
        model: String,
        usage: DeepSeekTokenUsage,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (!usage.reported) return
        synchronized(lock) {
            val current = _state.value
            val miss = usage.cacheMissTokens.takeIf { it > 0L }
                ?: (usage.promptTokens - usage.cacheHitTokens).coerceAtLeast(0L)
            val cost = DeepSeekCostCalculator.estimateCny(
                model = model,
                usage = usage.copy(cacheMissTokens = miss),
                pricing = pricingRepository.state.value,
                epochMillis = epochMillis,
            )
            val next = current.copy(
                inputTokens = current.inputTokens + usage.promptTokens,
                cacheHitTokens = current.cacheHitTokens + usage.cacheHitTokens,
                cacheMissTokens = current.cacheMissTokens + miss,
                outputTokens = current.outputTokens + usage.completionTokens,
                reasoningTokens = current.reasoningTokens + usage.reasoningTokens,
                requestCount = current.requestCount + 1L,
                estimatedCostCny = current.estimatedCostCny + (cost ?: 0.0),
                unpricedTokens = current.unpricedTokens + if (cost == null) usage.totalTokens else 0L,
                updatedAt = epochMillis,
            )
            persist(next)
            _state.value = next
        }
    }

    private fun load(): DeepSeekUsageSnapshot = DeepSeekUsageSnapshot(
        inputTokens = preferences.getLong(KEY_INPUT, 0L),
        cacheHitTokens = preferences.getLong(KEY_CACHE_HIT, 0L),
        cacheMissTokens = preferences.getLong(KEY_CACHE_MISS, 0L),
        outputTokens = preferences.getLong(KEY_OUTPUT, 0L),
        reasoningTokens = preferences.getLong(KEY_REASONING, 0L),
        requestCount = preferences.getLong(KEY_REQUESTS, 0L),
        estimatedCostCny = preferences.getString(KEY_COST, null)?.toDoubleOrNull() ?: 0.0,
        unpricedTokens = preferences.getLong(KEY_UNPRICED, 0L),
        updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
    )

    private fun persist(value: DeepSeekUsageSnapshot) {
        preferences.edit()
            .putLong(KEY_INPUT, value.inputTokens)
            .putLong(KEY_CACHE_HIT, value.cacheHitTokens)
            .putLong(KEY_CACHE_MISS, value.cacheMissTokens)
            .putLong(KEY_OUTPUT, value.outputTokens)
            .putLong(KEY_REASONING, value.reasoningTokens)
            .putLong(KEY_REQUESTS, value.requestCount)
            .putString(KEY_COST, value.estimatedCostCny.toString())
            .putLong(KEY_UNPRICED, value.unpricedTokens)
            .putLong(KEY_UPDATED_AT, value.updatedAt)
            .apply()
    }

    companion object {
        private const val PREFS = "deepseek_usage"
        private const val KEY_INPUT = "input_tokens"
        private const val KEY_CACHE_HIT = "cache_hit_tokens"
        private const val KEY_CACHE_MISS = "cache_miss_tokens"
        private const val KEY_OUTPUT = "output_tokens"
        private const val KEY_REASONING = "reasoning_tokens"
        private const val KEY_REQUESTS = "request_count"
        private const val KEY_COST = "estimated_cost_cny"
        private const val KEY_UNPRICED = "unpriced_tokens"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}

internal fun parseDeepSeekPricingPage(html: String): List<DeepSeekModelPricing> {
    val text = html
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&#x20;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
    val start = text.indexOf("价格").takeIf { it >= 0 } ?: error("未找到价格表")
    val end = text.indexOf("并发限制", start).takeIf { it > start } ?: error("价格表结构已变化")
    val section = text.substring(start, end)
    val values = Regex("""([0-9]+(?:\.[0-9]+)?)\s*元""")
        .findAll(section)
        .map { it.groupValues[1].toDouble() }
        .take(12)
        .toList()
    check(values.size == 12) { "价格字段数量异常：${values.size}" }

    fun nearDouble(off: Double, peak: Double): Boolean =
        kotlin.math.abs(off * 2.0 - peak) < 0.000_001

    check(nearDouble(values[0], values[2]))
    check(nearDouble(values[1], values[3]))
    check(nearDouble(values[4], values[6]))
    check(nearDouble(values[5], values[7]))
    check(nearDouble(values[8], values[10]))
    check(nearDouble(values[9], values[11]))

    return listOf(
        DeepSeekModelPricing(
            modelId = "deepseek-flash",
            displayName = "DeepSeek Flash",
            version = "DeepSeek-V4.1-Flash",
            offPeak = DeepSeekPriceTier(values[0], values[4], values[8]),
            peak = DeepSeekPriceTier(values[2], values[6], values[10]),
            aliases = setOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp"),
        ),
        DeepSeekModelPricing(
            modelId = "deepseek-v4-pro",
            displayName = "DeepSeek V4 Pro",
            version = "DeepSeek-V4-Pro-0813",
            offPeak = DeepSeekPriceTier(values[1], values[5], values[9]),
            peak = DeepSeekPriceTier(values[3], values[7], values[11]),
        ),
    )
}

fun defaultDeepSeekPricing(): List<DeepSeekModelPricing> = listOf(
    DeepSeekModelPricing(
        modelId = "deepseek-flash",
        displayName = "DeepSeek Flash",
        version = "DeepSeek-V4.1-Flash",
        offPeak = DeepSeekPriceTier(
            cacheHitCnyPerMillion = 0.02,
            cacheMissCnyPerMillion = 1.0,
            outputCnyPerMillion = 4.0,
        ),
        peak = DeepSeekPriceTier(
            cacheHitCnyPerMillion = 0.04,
            cacheMissCnyPerMillion = 2.0,
            outputCnyPerMillion = 8.0,
        ),
        aliases = setOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp"),
    ),
    DeepSeekModelPricing(
        modelId = "deepseek-v4-pro",
        displayName = "DeepSeek V4 Pro",
        version = "DeepSeek-V4-Pro-0813",
        offPeak = DeepSeekPriceTier(
            cacheHitCnyPerMillion = 0.15,
            cacheMissCnyPerMillion = 4.5,
            outputCnyPerMillion = 13.5,
        ),
        peak = DeepSeekPriceTier(
            cacheHitCnyPerMillion = 0.30,
            cacheMissCnyPerMillion = 9.0,
            outputCnyPerMillion = 27.0,
        ),
    ),
)

private fun android.content.SharedPreferences.readDouble(key: String, fallback: Double): Double =
    getString(key, null)?.toDoubleOrNull() ?: fallback
