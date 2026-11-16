package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.context.LocalContextCheckpointKind
import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
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
    private var latestWorkCheckpointIndex = -1

    private fun isWorkCheckpoint(message: JsonObject): Boolean =
        isTrustedContextCheckpointModelMessage(message, LocalContextCheckpointKind.WORK)

    private fun rebuildWorkCheckpointIndex() {
        latestWorkCheckpointIndex = messages.indices.reversed()
            .firstOrNull { isWorkCheckpoint(messages[it]) } ?: -1
    }

    @Volatile
    var encodedChars: Int = 0
        private set

    @Volatile
    var estimatedTokens: Int = 0
        private set

    val size: Int get() = messages.size

    fun snapshot(): List<JsonObject> = messages.toList()

    /**
     * Read the bounded recent tail and the newest trusted Work checkpoint.
     * Mutation maintains its index so each tool receipt avoids rescanning history.
     */
    fun recentWithLatestWorkCheckpoint(limit: Int): List<JsonObject> {
        require(limit >= 0) { "最近历史窗口长度不能为负" }
        val start = (messages.size - limit).coerceAtLeast(0)
        return buildList {
            if (latestWorkCheckpointIndex in 0 until start) {
                add(messages[latestWorkCheckpointIndex])
            }
            addAll(messages.subList(start, messages.size))
        }
    }

    fun dropLast(count: Int): List<JsonObject> = messages.dropLast(count)

    fun firstOrNull(): JsonObject? = messages.firstOrNull()

    fun lastOrNull(): JsonObject? = messages.lastOrNull()

    fun append(message: JsonObject) {
        messages += message
        if (isWorkCheckpoint(message)) latestWorkCheckpointIndex = messages.lastIndex
        addMetrics(message)
    }

    fun prepend(message: JsonObject) {
        insert(0, message)
    }

    fun insert(index: Int, message: JsonObject) {
        messages.add(index, message)
        if (latestWorkCheckpointIndex >= index) latestWorkCheckpointIndex++
        if (isWorkCheckpoint(message) && index > latestWorkCheckpointIndex) {
            latestWorkCheckpointIndex = index
        }
        addMetrics(message)
    }

    fun replaceSystem(message: JsonObject) {
        require(messages.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            "模型历史首条消息不是 system"
        }
        addMetrics(messages[0], -1)
        messages[0] = message
        rebuildWorkCheckpointIndex()
        addMetrics(message)
    }

    fun reset(values: List<JsonObject> = emptyList()) {
        messages.clear()
        messages += values
        rebuildWorkCheckpointIndex()
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
