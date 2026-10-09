package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.runtime.MAX_EPHEMERAL_CONTEXT_CHARS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Work keeps stable runtime/rules near the immutable system prefix while current-query recall and
 * handoff stay next to the current user turn. This preserves the largest provider-cache prefix.
 */
internal fun withWorkTurnContext(
    history: List<JsonObject>,
    stableContext: String,
    dynamicContext: String,
    selectedSkillContext: String = "",
): List<JsonObject> {
    if (stableContext.isBlank() && dynamicContext.isBlank() && selectedSkillContext.isBlank()) return history
    require(selectedSkillContext.length <= MAX_SELECTED_SKILL_CHARS) { "所选技能规则超过 24,000 字符，请缩短后重试" }

    val dynamicReserve = minOf(WORK_DYNAMIC_CONTEXT_RESERVE_CHARS, dynamicContext.length)
    val stableBudget = (MAX_EPHEMERAL_CONTEXT_CHARS - dynamicReserve).coerceAtLeast(0)
    val stable = truncateWithoutSplittingSurrogatePair(stableContext, stableBudget)
    val dynamicBudget = (MAX_EPHEMERAL_CONTEXT_CHARS - stable.length).coerceAtLeast(0)
    val dynamic = truncateWithoutSplittingSurrogatePair(dynamicContext, dynamicBudget)
    val result = history.toMutableList()

    if (stable.isNotBlank()) {
        val stableMessage = buildJsonObject {
            put("role", "system")
            put("content", stable)
        }
        val stableIndex = if (
            result.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
        ) 1 else 0
        result.add(stableIndex, stableMessage)
    }
    if (dynamic.isNotBlank()) {
        val dynamicMessage = buildJsonObject {
            put("role", "system")
            put("content", dynamic)
        }
        val currentUserIndex = result.indexOfLast { message ->
            message["role"]?.jsonPrimitive?.contentOrNull == "user"
        }
        result.add(if (currentUserIndex >= 0) currentUserIndex else result.size, dynamicMessage)
    }
    if (selectedSkillContext.isNotBlank()) {
        val skillMessage = buildJsonObject {
            put("role", "system")
            put("content", selectedSkillContext)
        }
        val currentUserIndex = result.indexOfLast { message ->
            message["role"]?.jsonPrimitive?.contentOrNull == "user"
        }
        result.add(if (currentUserIndex >= 0) currentUserIndex else result.size, skillMessage)
    }
    return result
}

private const val WORK_DYNAMIC_CONTEXT_RESERVE_CHARS = 4_000
private const val MAX_SELECTED_SKILL_CHARS = 24_000
