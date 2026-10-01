package com.labteto.dshmobile.local

import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Reuses prompt-estimation work for immutable history objects across model requests.
 *
 * Model history reuses the same JsonObject instances for the unchanged prefix, so identity keys
 * avoid re-serializing and re-tokenizing hundreds of old messages on every step. The cache is
 * deliberately bounded because histories from closed sessions must not be retained indefinitely.
 */
internal object PromptTokenEstimateCache {
    internal data class MessageEstimate(
        val tokens: Int,
        val memory: Boolean,
        val persona: Boolean,
    )

    private class IdentityKey<T : Any>(val value: T) {
        override fun hashCode(): Int = System.identityHashCode(value)
        override fun equals(other: Any?): Boolean =
            other is IdentityKey<*> && other.value === value
    }

    private val messageCache = object : LinkedHashMap<IdentityKey<JsonObject>, MessageEstimate>(
        256,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<IdentityKey<JsonObject>, MessageEstimate>?,
        ): Boolean = size > MAX_MESSAGE_ESTIMATES
    }

    private val toolCache = object : LinkedHashMap<IdentityKey<JsonArray>, Int>(
        16,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<IdentityKey<JsonArray>, Int>?,
        ): Boolean = size > MAX_TOOL_ESTIMATES
    }

    @Synchronized
    fun message(
        value: JsonObject,
        memoryClassifier: (String) -> Boolean,
        personaClassifier: (String) -> Boolean,
    ): MessageEstimate {
        val key = IdentityKey(value)
        messageCache[key]?.let { return it }
        val encoded = value.toString()
        val content = value["content"]?.toString().orEmpty()
        return MessageEstimate(
            tokens = estimateModelTokens(encoded),
            memory = memoryClassifier(content),
            persona = personaClassifier(content),
        ).also { messageCache[key] = it }
    }

    @Synchronized
    fun tools(value: JsonArray): Int {
        if (value.isEmpty()) return 0
        val key = IdentityKey(value)
        toolCache[key]?.let { return it }
        return estimateModelTokens(value.toString()).also { toolCache[key] = it }
    }

    private const val MAX_MESSAGE_ESTIMATES = 2_048
    private const val MAX_TOOL_ESTIMATES = 64
}
