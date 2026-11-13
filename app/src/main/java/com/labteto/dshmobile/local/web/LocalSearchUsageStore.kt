package com.labteto.dshmobile.local.web

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Search/Fetch charge per successful request, never per token or returned hit. */
@Serializable
data class LocalSearchUsageDay(
    val epochDay: Long,
    val bingSearch: Long = 0,
    val deepSeekSearch: Long = 0,
    val deepSeekFetch: Long = 0,
) {
    val requests: Long get() = bingSearch + deepSeekSearch + deepSeekFetch
    val paidRequests: Long get() = deepSeekSearch + deepSeekFetch
}

@Serializable
data class LocalSearchUsageSnapshot(
    val bingSearch: Long = 0,
    val deepSeekSearch: Long = 0,
    val deepSeekFetch: Long = 0,
    val days: List<LocalSearchUsageDay> = emptyList(),
) {
    val requests: Long get() = bingSearch + deepSeekSearch + deepSeekFetch
    val paidRequests: Long get() = deepSeekSearch + deepSeekFetch
    val estimatedCostCny: Double get() = paidRequests * CNY_PER_SUCCESS
    companion object { const val CNY_PER_SUCCESS = 0.005 }
}

/** Device-only ledger, separate from token usage: Search and Fetch do not report tokens. */
@Singleton
class LocalSearchUsageStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val preferences = context.getSharedPreferences("local_web_search_usage", Context.MODE_PRIVATE)
    private val lock = Any()
    private val zone = ZoneId.systemDefault()
    private val _usage = MutableStateFlow(
        preferences.getString("snapshot_v1", null)?.let { json.decodeFromString<LocalSearchUsageSnapshot>(it) }
            ?: LocalSearchUsageSnapshot(),
    )
    val usage: StateFlow<LocalSearchUsageSnapshot> = _usage.asStateFlow()

    /** Invoke after a confirmed usable 200 response, even when displaying the result later fails. */
    fun record(provider: String, operation: String) = synchronized(lock) {
        require(provider in setOf("bing", "deepseek"))
        require(operation in setOf("search", "fetch"))
        val current = _usage.value
        val day = LocalDate.now(zone).toEpochDay()
        val previous = current.days.firstOrNull { it.epochDay == day } ?: LocalSearchUsageDay(day)
        val nextDay = when {
            provider == "bing" -> previous.copy(bingSearch = previous.bingSearch + 1)
            operation == "fetch" -> previous.copy(deepSeekFetch = previous.deepSeekFetch + 1)
            else -> previous.copy(deepSeekSearch = previous.deepSeekSearch + 1)
        }
        val next = current.copy(
            bingSearch = current.bingSearch + if (provider == "bing") 1 else 0,
            deepSeekSearch = current.deepSeekSearch + if (provider == "deepseek" && operation == "search") 1 else 0,
            deepSeekFetch = current.deepSeekFetch + if (provider == "deepseek" && operation == "fetch") 1 else 0,
            days = (current.days.filter { it.epochDay != day && it.epochDay >= day - 89 } + nextDay)
                .sortedBy { it.epochDay },
        )
        check(preferences.edit().putString("snapshot_v1", json.encodeToString(next)).commit()) {
            "网络搜索计费记录未能写入本机"
        }
        _usage.value = next
    }
}
