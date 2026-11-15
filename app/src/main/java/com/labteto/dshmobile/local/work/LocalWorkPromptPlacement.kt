package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.placeTurnContext
import com.labteto.dshmobile.local.runtime.MAX_EPHEMERAL_CONTEXT_CHARS
import kotlinx.serialization.json.JsonObject

/** Work passes explicitly selected skill rules intact; Model owns the real context budget. */
internal fun withWorkTurnContext(
    history: List<JsonObject>,
    stableContext: String,
    dynamicContext: String,
    selectedSkillContext: String = "",
): List<JsonObject> {
     return placeTurnContext(history, stableContext, dynamicContext, MAX_EPHEMERAL_CONTEXT_CHARS,
        WORK_DYNAMIC_CONTEXT_RESERVE_CHARS, selectedSkillContext)
}

private const val WORK_DYNAMIC_CONTEXT_RESERVE_CHARS = 4_000
