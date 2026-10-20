package com.labteto.dshmobile.automation

import android.content.Context
import com.labteto.dshmobile.observability.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

@Serializable(with = WebhookRunStatusSerializer::class)
enum class WebhookRunStatus(val wireValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    /** Previous process ended without a trustworthy result; remote side effects may have happened. */
    INTERRUPTED("interrupted"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed");

    override fun toString(): String = wireValue
}

internal object WebhookRunStatusSerializer : KSerializer<WebhookRunStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WebhookRunStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: WebhookRunStatus) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): WebhookRunStatus {
        val wireValue = decoder.decodeString()
        // Unknown future states cannot prove that an external side effect failed.
        return WebhookRunStatus.entries.firstOrNull { it.wireValue == wireValue }
            ?: throw SerializationException("未知 Webhook 回执状态：$wireValue")
    }
}

@Serializable
data class WebhookRunResult(
    val requestId: String,
    val status: WebhookRunStatus,
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
            try {
                json.decodeFromString(WebhookRunResult.serializer(), raw).also { receipt ->
                    require(receipt.requestId == id) { "Webhook 回执编号与存储键不一致" }
                }
            } catch (error: Exception) {
                AppLog.warn("WebhookResults", "failure=true operation=read_webhook_result " +
                    "request_id=${id.take(80)} cause_type=${error.javaClass.simpleName}")
                unknownReceipt(id)
            }
        }

    private fun unknownReceipt(id: String): WebhookRunResult = WebhookRunResult(
        requestId = id,
        status = WebhookRunStatus.INTERRUPTED,
        updatedAt = System.currentTimeMillis(),
        error = "Webhook 历史回执损坏，执行副作用是否发生未知；请先核实再决定是否重试",
    )

    @Synchronized
    fun put(result: WebhookRunResult) {
        preferences.edit()
            .putString(
                result.requestId,
                json.encodeToString(WebhookRunResult.serializer(), result),
            )
            .commit().also { check(it) { "Webhook 回执持久化失败" } }
        cleanup()
    }

    @Synchronized
    fun update(
        id: String,
        status: WebhookRunStatus,
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

    /** Reconcile nonterminal receipts once at service process start; never replay side effects. */
    @Synchronized
    fun settleInterruptedFromPreviousProcess(): Int {
        val interrupted = preferences.all.mapNotNull { (id, raw) ->
            val previous = try {
                (raw as? String)?.let {
                    json.decodeFromString(WebhookRunResult.serializer(), it).also { receipt ->
                        require(receipt.requestId == id) { "Webhook 回执编号与存储键不一致" }
                    }
                }
            } catch (error: Exception) {
                AppLog.warn("WebhookResults", "failure=true operation=restore_webhook_result " +
                    "request_id=${id.take(80)} cause_type=${error.javaClass.simpleName}")
                null
            }
            if (previous == null) {
                // A corrupted receipt is not evidence that the external action never ran.
                return@mapNotNull id to unknownReceipt(id)
            }
            if (previous.status != WebhookRunStatus.QUEUED &&
                previous.status != WebhookRunStatus.RUNNING) return@mapNotNull null
            id to previous.copy(
                status = WebhookRunStatus.INTERRUPTED,
                updatedAt = System.currentTimeMillis(),
                error = "Webhook 运行被进程中断，副作用结果未知，请核查后决定是否重试",
            )
        }
        if (interrupted.isEmpty()) return 0
        check(preferences.edit().apply {
            interrupted.forEach { (id, result) ->
                putString(id, json.encodeToString(WebhookRunResult.serializer(), result))
            }
        }.commit()) { "Webhook 中断回执持久化失败" }
        return interrupted.size
    }

    private fun cleanup() {
        val entries = preferences.all.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            val parsed = runCatching {
                json.decodeFromString(WebhookRunResult.serializer(), raw)
            }.onFailure { error ->
                AppLog.warn("WebhookResults", "failure=true operation=scan_webhook_result " +
                    "request_id=${key.take(80)} cause_type=${error.javaClass.simpleName}")
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
