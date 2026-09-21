package com.labteto.dshmobile.automation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WebhookRunResult(
    val requestId: String,
    val status: String,
    val updatedAt: Long,
    val result: String? = null,
    val error: String? = null,
)

@Singleton
class WebhookResultStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val preferences =
        context.getSharedPreferences("local_harness_webhook_results", Context.MODE_PRIVATE)

    @Synchronized
    fun get(id: String): WebhookRunResult? =
        preferences.getString(id, null)?.let { raw ->
            runCatching { json.decodeFromString(WebhookRunResult.serializer(), raw) }.getOrNull()
        }

    @Synchronized
    fun put(result: WebhookRunResult) {
        preferences.edit()
            .putString(
                result.requestId,
                json.encodeToString(WebhookRunResult.serializer(), result),
            )
            .apply()
        cleanup()
    }

    @Synchronized
    fun update(
        id: String,
        status: String,
        result: String? = null,
        error: String? = null,
    ) {
        put(
            WebhookRunResult(
                requestId = id,
                status = status,
                updatedAt = System.currentTimeMillis(),
                result = result?.take(MAX_RESULT_CHARS),
                error = error?.take(MAX_ERROR_CHARS),
            ),
        )
    }

    private fun cleanup() {
        val entries = preferences.all.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            val parsed = runCatching {
                json.decodeFromString(WebhookRunResult.serializer(), raw)
            }.getOrNull() ?: return@mapNotNull null
            Triple(key, parsed.updatedAt, parsed.status)
        }
        if (entries.size <= MAX_RESULTS) return
        val remove = entries
            .sortedBy { it.second }
            .take(entries.size - MAX_RESULTS)
            .map { it.first }
        preferences.edit().apply {
            remove.forEach(::remove)
        }.apply()
    }

    companion object {
        private const val MAX_RESULTS = 100
        private const val MAX_RESULT_CHARS = 20_000
        private const val MAX_ERROR_CHARS = 4_000
    }
}
