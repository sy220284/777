package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pure subagent context inheritance policy kept outside the execution runner. */
internal fun boundedSubagentContext(context: String, maxChars: Int = 10_000): String? =
    context.trim().takeIf(String::isNotEmpty)?.let {
        truncateWithoutSplittingSurrogatePair(it, maxChars.coerceAtLeast(1))
    }

internal fun inheritedHistoryBeforeToolCall(
    history: List<JsonObject>,
    parentCallId: String?,
): MutableList<JsonObject> {
    if (parentCallId.isNullOrBlank()) return history.toMutableList()
    val boundary = history.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "assistant" &&
            LocalCanonicalModelCodec.canonicalToolCalls(message).any { call ->
                call.id == parentCallId
            }
    }
    return if (boundary >= 0) history.take(boundary).toMutableList() else history.toMutableList()
}
