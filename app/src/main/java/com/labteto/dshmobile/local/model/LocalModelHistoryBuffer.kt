package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the mutable model-visible history and its cached size metrics.
 *
 * Keeping the collection and counters together prevents LocalHarnessEngine from having to update
 * three pieces of state for every append/reset/compaction operation.
 */
internal class LocalModelHistoryBuffer {
    private val messages = mutableListOf<JsonObject>()

    @Volatile
    var encodedChars: Int = 0
        private set

    @Volatile
    var estimatedTokens: Int = 0
        private set

    fun snapshot(): List<JsonObject> = messages.toList()

    fun dropLast(count: Int): List<JsonObject> = messages.dropLast(count)

    fun firstOrNull(): JsonObject? = messages.firstOrNull()

    fun lastOrNull(): JsonObject? = messages.lastOrNull()

    fun append(message: JsonObject) {
        messages += message
        encodedChars += measureChars(message)
        estimatedTokens += measureTokens(message)
    }

    fun prepend(message: JsonObject) {
        insert(0, message)
    }

    fun insert(index: Int, message: JsonObject) {
        messages.add(index, message)
        encodedChars += measureChars(message)
        estimatedTokens += measureTokens(message)
    }

    fun replaceSystem(message: JsonObject) {
        require(messages.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            "模型历史首条消息不是 system"
        }
        encodedChars -= measureChars(messages[0])
        estimatedTokens -= measureTokens(messages[0])
        messages[0] = message
        encodedChars += measureChars(message)
        estimatedTokens += measureTokens(message)
    }

    fun reset(values: List<JsonObject> = emptyList()) {
        messages.clear()
        messages += values
        recalculateMetrics()
    }

    private fun recalculateMetrics() {
        encodedChars = messages.sumOf(::measureChars)
        estimatedTokens = messages.sumOf(::measureTokens)
    }

    private fun measureChars(message: JsonObject): Int = message.toString().length

    private fun measureTokens(message: JsonObject): Int = estimateModelTokens(message.toString())
}
