package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.placeTurnContext
import com.labteto.dshmobile.local.runtime.MAX_EPHEMERAL_CONTEXT_CHARS
import kotlinx.serialization.json.JsonObject

/** Work owns complete skill rules and rejects over-budget rules before shared placement. */
internal fun withWorkTurnContext(
    history: List<JsonObject>,
    stableContext: String,
    dynamicContext: String,
    selectedSkillContext: String = "",
): List<JsonObject> {
    require(selectedSkillContext.length <= MAX_SELECTED_SKILL_CHARS) { "所选技能规则超过 24,000 字符，请缩短后重试" }
    return placeTurnContext(history, stableContext, dynamicContext, MAX_EPHEMERAL_CONTEXT_CHARS,
        WORK_DYNAMIC_CONTEXT_RESERVE_CHARS, selectedSkillContext)
}

private const val WORK_DYNAMIC_CONTEXT_RESERVE_CHARS = 4_000
private const val MAX_SELECTED_SKILL_CHARS = 24_000
