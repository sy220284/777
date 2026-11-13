package com.labteto.dshmobile.local.usage

import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
        // Raw Base64 media is not natural-language prompt text. Never retain giant media
        // strings as LRU keys or interpret encoded bytes as millions of prompt tokens.
        // Server-reported usage always supersedes these conservative local estimates.
        var images = 0
        var videos = 0
        var files = 0
        val sourceParts = value["content"] as? JsonArray
        val cleanParts = sourceParts?.map { part: JsonElement ->
            val obj = part as? JsonObject ?: return@map part
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@map part
            if (type == "input_file") {
                val data = obj["file_data"] as? JsonPrimitive
                if (data?.contentOrNull?.startsWith("data:") != true) return@map part
                files++
                return@map JsonObject(obj.toMutableMap().apply {
                    put("file_data", JsonPrimitive("[inline file bytes omitted]"))
                })
            }
            if (type !in setOf("image_url", "video_url")) return@map part
            val url = (obj[type] as? JsonObject)?.get("url") as? JsonPrimitive
            if (url?.contentOrNull?.startsWith("data:") != true) return@map part
            if (type == "video_url") videos++ else images++
            JsonObject(mapOf(
                "type" to JsonPrimitive(type),
                type to JsonObject(mapOf("url" to JsonPrimitive("[inline media bytes omitted]"))),
            ))
        }
        if (images > 0 || videos > 0 || files > 0) {
            val lightweight = JsonObject(value.toMutableMap().apply {
                put("content", JsonArray(requireNotNull(cleanParts)))
            })
            val content = lightweight["content"].toString()
            return MessageEstimate(
                tokens = (estimateRequestTokens(lightweight.toString()) +
                    images * 1_024 + videos * 16_384 + files * 2_048).coerceAtMost(Int.MAX_VALUE),
                memory = memoryClassifier(content),
                persona = personaClassifier(content),
            )
        }
        messageCache[value]?.let { return it }
        val encoded = value.toString()
        val content = value["content"]?.toString().orEmpty()
        return MessageEstimate(
            tokens = estimateRequestTokens(encoded),
            memory = memoryClassifier(content),
            persona = personaClassifier(content),
        ).also { messageCache[value] = it }
    }

    @Synchronized
    fun tools(value: JsonArray): Int {
        if (value.isEmpty()) return 0
        toolCache[value]?.let { return it }
        return estimateRequestTokens(value.toString()).also { toolCache[value] = it }
    }

    private const val MAX_MESSAGE_ESTIMATES = 2_048
    private const val MAX_TOOL_ESTIMATES = 64
}
