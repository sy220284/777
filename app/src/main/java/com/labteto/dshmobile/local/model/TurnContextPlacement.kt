package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Feature-neutral placement: stable prefix, dynamic current-turn context, complete required rules. */
internal fun placeTurnContext(
    history: List<JsonObject>,
    stableContext: String,
    dynamicContext: String,
    maxChars: Int,
    dynamicReserveChars: Int,
    completeRules: String = "",
): List<JsonObject> {
    if (stableContext.isBlank() && dynamicContext.isBlank() && completeRules.isBlank()) return history
    require(maxChars >= 0 && dynamicReserveChars >= 0)
    val reserve = minOf(dynamicReserveChars, dynamicContext.length)
    val stable = truncateWithoutSplittingSurrogatePair(stableContext, (maxChars - reserve).coerceAtLeast(0))
    val dynamic = truncateWithoutSplittingSurrogatePair(dynamicContext, (maxChars - stable.length).coerceAtLeast(0))
    val result = history.toMutableList()
    fun message(content: String) = buildJsonObject { put("role", "system"); put("content", content) }
    if (stable.isNotBlank()) {
        val index = if (result.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") 1 else 0
        result.add(index, message(stable))
    }
    for (content in listOf(dynamic, completeRules).filter(String::isNotBlank)) {
        val index = result.indexOfLast { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        result.add(if (index >= 0) index else result.size, message(content))
    }
    return result
}
