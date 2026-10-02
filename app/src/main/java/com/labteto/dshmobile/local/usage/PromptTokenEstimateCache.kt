package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.estimateModelTokens

import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Reuses prompt-estimation work for immutable history objects across model requests.
 *
 * Canonical/legacy adapters may rebuild equal JSON objects for the unchanged prefix, so immutable
 * structural keys avoid re-serializing and re-tokenizing hundreds of old messages on every step. The cache is
 * deliberately bounded because histories from closed sessions must not be retained indefinitely.
 */
internal object PromptTokenEstimateCache {
    internal data class MessageEstimate(
        val tokens: Int,
        val memory: Boolean,
        val persona: Boolean,
    )

    private val messageCache = object : LinkedHashMap<JsonObject, MessageEstimate>(
        256,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<JsonObject, MessageEstimate>?,
        ): Boolean = size > MAX_MESSAGE_ESTIMATES
    }

    private val toolCache = object : LinkedHashMap<JsonArray, Int>(
        16,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<JsonArray, Int>?,
        ): Boolean = size > MAX_TOOL_ESTIMATES
    }

    @Synchronized
    fun message(
        value: JsonObject,
        memoryClassifier: (String) -> Boolean,
        personaClassifier: (String) -> Boolean,
    ): MessageEstimate {
        messageCache[value]?.let { return it }
        val encoded = value.toString()
        val content = value["content"]?.toString().orEmpty()
        return MessageEstimate(
            tokens = estimateModelTokens(encoded),
            memory = memoryClassifier(content),
            persona = personaClassifier(content),
        ).also { messageCache[value] = it }
    }

    @Synchronized
    fun tools(value: JsonArray): Int {
        if (value.isEmpty()) return 0
        toolCache[value]?.let { return it }
        return estimateModelTokens(value.toString()).also { toolCache[value] = it }
    }

    private const val MAX_MESSAGE_ESTIMATES = 2_048
    private const val MAX_TOOL_ESTIMATES = 64
}
