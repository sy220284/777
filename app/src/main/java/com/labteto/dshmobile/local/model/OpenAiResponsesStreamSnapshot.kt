package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Final snapshots replace deltas for the same part; they must never be appended twice. */
internal class OpenAiResponsesStreamSnapshot {
    private val text = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy({ it.first }, { it.second }))
    private val reasoning = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy({ it.first }, { it.second }))
    private val items = sortedMapOf<Int, JsonObject>()

    val content: String get() = text.values.joinToString("")
    val reasoningText: String get() = reasoning.values.joinToString("")

    fun record(event: JsonObject): LocalModelDelta? {
        val type = event.string("type") ?: return null
        if (!type.endsWith(".delta") && !type.endsWith(".done")) return null
        val outputIndex = event.number("output_index")
        val partIndex = event.number("content_index", event.number("summary_index"))
        val key = outputIndex to partIndex
        return when (type) {
            "response.output_text.delta", "response.refusal.delta" -> {
                val delta = event.string("delta") ?: return null
                text.getOrPut(key, ::StringBuilder).append(delta)
                LocalModelDelta(content = delta)
            }
            "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                val delta = event.string("delta") ?: return null
                reasoning.getOrPut(key, ::StringBuilder).append(delta)
                LocalModelDelta(reasoning = delta)
            }
            "response.output_text.done" -> { replace(text, key, event.string("text")); null }
            "response.refusal.done" -> { replace(text, key, event.string("refusal")); null }
            "response.reasoning_text.done", "response.reasoning_summary_text.done" -> {
                replace(reasoning, key, event.string("text")); null
            }
            "response.content_part.done" -> {
                (event["part"] as? JsonObject)?.let { recordPart(outputIndex, partIndex, it) }
                null
            }
            "response.output_item.done" -> {
                val item = event["item"] as? JsonObject ?: return null
                items[outputIndex] = item
                if (item.string("type") == "message") {
                    (item["content"] as? JsonArray).orEmpty().forEachIndexed { index, part ->
                        (part as? JsonObject)?.let { recordPart(outputIndex, index, it) }
                    }
                }
                null
            }
            else -> null
        }
    }

    fun settledResponse(response: JsonObject): JsonObject =
        if ((response["output"] as? JsonArray).isNullOrEmpty() && items.isNotEmpty()) {
            JsonObject(response + ("output" to JsonArray(items.values.toList())))
        } else response

    private fun recordPart(outputIndex: Int, index: Int, part: JsonObject) {
        when (part.string("type")) {
            "output_text" -> replace(text, outputIndex to index, part.string("text"))
            "refusal" -> replace(text, outputIndex to index, part.string("refusal"))
        }
    }

    private fun replace(target: MutableMap<Pair<Int, Int>, StringBuilder>, key: Pair<Int, Int>, value: String?) {
        if (value != null) target[key] = StringBuilder(value)
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw invalidField(key)
    }

    private fun JsonObject.number(key: String, fallback: Int = 0): Int {
        val value = this[key] ?: return fallback
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
            ?: throw invalidField(key)
    }

    private fun invalidField(key: String) = LocalModelException(
        "RESPONSES_PROTOCOL_ERROR", "Responses API 流式字段格式无效：$key", false,
    )
}
