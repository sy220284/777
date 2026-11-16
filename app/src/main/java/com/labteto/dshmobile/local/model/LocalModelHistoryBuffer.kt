package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the mutable model-visible history and its cached size metrics.
 *
 * Keeping the collection and counters together prevents the Runtime Kernel from having to update
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
        addMetrics(message)
    }

    fun prepend(message: JsonObject) {
        insert(0, message)
    }

    fun insert(index: Int, message: JsonObject) {
        messages.add(index, message)
        addMetrics(message)
    }

    fun replaceSystem(message: JsonObject) {
        require(messages.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            "模型历史首条消息不是 system"
        }
        addMetrics(messages[0], -1)
        messages[0] = message
        addMetrics(message)
    }

    fun reset(values: List<JsonObject> = emptyList()) {
        messages.clear()
        messages += values
        recalculateMetrics()
    }

    private fun recalculateMetrics() {
        var chars = 0
        var tokens = 0
        messages.forEach { message ->
            val encoded = message.toString()
            chars += encoded.length
            tokens += estimateModelTokens(encoded)
        }
        encodedChars = chars
        estimatedTokens = tokens
    }

    private fun addMetrics(message: JsonObject, sign: Int = 1) {
        // Serialize once, not once for the character count and again for tokens.
        val encoded = message.toString()
        encodedChars += sign * encoded.length
        estimatedTokens += sign * estimateModelTokens(encoded)
    }
}
