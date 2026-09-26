package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal const val LOCAL_SUBAGENT_CHECKPOINT_EVENT = "subagent/checkpoint"

internal data class LocalSubagentContinuationCheckpoint(
    val agentId: String,
    val model: String,
    val maxSteps: Int,
    val allowMutation: Boolean,
    val virtualScreen: Boolean,
    val history: List<JsonObject>,
)

internal fun encodeSubagentContinuationCheckpoint(
    agentId: String,
    model: String,
    maxSteps: Int,
    allowMutation: Boolean,
    virtualScreen: Boolean,
    history: List<JsonObject>,
    compactor: LocalHistoryCompactor,
): JsonObject {
    val sanitized = history.map(::sanitizeSubagentContinuationMessage)
    val budget = LocalHistoryBudget(
        maxHistoryChars = 96_000,
        tailChars = 56_000,
        maxSummaryChars = 8_000,
        maxToolResultChars = 8_000,
        maxHistoryTokens = null,
        tailTokens = null,
        maxToolResultTokens = 4_000,
    )
    val bounded = compactor.compact(
        history = sanitized,
        budget = budget,
        summaryMode = LocalHistorySummaryMode.WORK,
    )?.messages ?: sanitized

    // Final hard bound protects the JSONL event segment even when one atomic message cannot be
    // semantically compacted. Keep the system prefix and newest complete messages.
    val finalHistory = if (bounded.sumOf { it.toString().length } <= MAX_CHECKPOINT_CHARS) {
        bounded
    } else {
        buildList {
            val head = bounded.firstOrNull()
            head?.let(::add)
            var retainedChars = head?.toString()?.length ?: 0
            val tail = ArrayDeque<JsonObject>()
            for (message in bounded.asReversed()) {
                if (message === head) continue
                val size = message.toString().length
                if (retainedChars + size > MAX_CHECKPOINT_CHARS) break
                tail.addFirst(message)
                retainedChars += size
            }
            addAll(tail)
        }
    }

    return buildJsonObject {
        put("version", CHECKPOINT_VERSION)
        put("agent_id", agentId)
        put("model", model)
        put("max_steps", maxSteps.coerceIn(1, 128))
        put("allow_mutation", allowMutation)
        put("virtual_screen", virtualScreen)
        put("history", JsonArray(finalHistory))
    }
}

internal fun decodeSubagentContinuationCheckpoint(
    data: JsonObject,
): LocalSubagentContinuationCheckpoint? {
    if ((data["version"] as? JsonPrimitive)?.intOrNull != CHECKPOINT_VERSION) return null
    val agentId = (data["agent_id"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: return null
    val model = (data["model"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: return null
    val maxSteps = (data["max_steps"] as? JsonPrimitive)?.intOrNull
        ?.takeIf { it in 1..128 } ?: return null
    val allowMutation = (data["allow_mutation"] as? JsonPrimitive)?.booleanOrNull ?: false
    val virtualScreen = (data["virtual_screen"] as? JsonPrimitive)?.booleanOrNull ?: false
    val history = (data["history"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.takeIf { it.isNotEmpty() && it.size <= MAX_CHECKPOINT_MESSAGES }
        ?: return null
    return LocalSubagentContinuationCheckpoint(
        agentId = agentId,
        model = model,
        maxSteps = maxSteps,
        allowMutation = allowMutation,
        virtualScreen = virtualScreen,
        history = history,
    )
}

private fun sanitizeSubagentContinuationMessage(message: JsonObject): JsonObject {
    val content = message["content"]
    val sanitizedContent = sanitizeSubagentContent(content)
    val entries = message.toMutableMap()
    if (sanitizedContent != null) entries["content"] = sanitizedContent

    val calls = message["tool_calls"] as? JsonArray
    if (calls != null) {
        entries["tool_calls"] = buildJsonArray {
            calls.take(16).forEach { raw ->
                val call = raw as? JsonObject ?: return@forEach
                val callEntries = call.toMutableMap()
                val function = call["function"] as? JsonObject
                if (function != null) {
                    val fnEntries = function.toMutableMap()
                    val arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull
                    if (arguments != null) {
                        fnEntries["arguments"] = JsonPrimitive(
                            truncateWithoutSplittingSurrogatePair(arguments, MAX_ARGUMENT_CHARS),
                        )
                    }
                    callEntries["function"] = JsonObject(fnEntries)
                }
                add(JsonObject(callEntries))
            }
        }
    }
    return JsonObject(entries)
}

private fun sanitizeSubagentContent(content: JsonElement?): JsonElement? = when (content) {
    null -> null
    is JsonPrimitive -> if (content.isString) {
        JsonPrimitive(truncateWithoutSplittingSurrogatePair(content.content, MAX_CONTENT_CHARS))
    } else {
        content
    }
    is JsonArray -> buildJsonArray {
        content.take(32).forEach { part ->
            when (part) {
                is JsonObject -> {
                    val type = (part["type"] as? JsonPrimitive)?.contentOrNull
                    when (type) {
                        "image_url", "input_image", "image" -> add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", "[图片内容已从子智能体续接检查点省略]")
                            },
                        )
                        else -> {
                            val entries = part.toMutableMap()
                            val text = (part["text"] as? JsonPrimitive)?.contentOrNull
                            if (text != null) {
                                entries["text"] = JsonPrimitive(
                                    truncateWithoutSplittingSurrogatePair(text, MAX_CONTENT_CHARS),
                                )
                            }
                            add(JsonObject(entries))
                        }
                    }
                }
                is JsonPrimitive -> add(
                    if (part.isString) JsonPrimitive(
                        truncateWithoutSplittingSurrogatePair(part.content, MAX_CONTENT_CHARS),
                    ) else part,
                )
                else -> Unit
            }
        }
    }
    else -> content
}

private const val CHECKPOINT_VERSION = 1
private const val MAX_CHECKPOINT_CHARS = 120_000
private const val MAX_CHECKPOINT_MESSAGES = 96
private const val MAX_CONTENT_CHARS = 12_000
private const val MAX_ARGUMENT_CHARS = 4_000
