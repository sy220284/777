package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonObject

/**
 * One field-level authority policy for all post-turn updates.
 *
 * Event significance classifies a diary/long-term memory; it is not a switch disabling
 * legitimate state changes. When a turn is not significant, *new* affirmative state
 * claims require an actual quote in the public exchange. Explicit empty patches still
 * clear settled transient states and answered questions.
 */
internal object ChatStatePatchAuthority {
    fun effectivePatch(
        raw: JsonObject?,
        state: ChatCharacterState,
        significance: String,
        quotations: Map<String, String>,
        userMessage: String,
        assistantMessage: String,
    ): JsonObject? {
        if (raw == null || significance != "NONE") return raw
        val exchange = listOf(userMessage, assistantMessage).joinToString("\n")
        fun evidenced(field: String): Boolean {
            val quote = quotations[field].orEmpty().trim()
            return quote.length >= 3 && exchange.contains(quote)
        }
        val clearedScalar = setOf(
            "physicalState", "currentFocus", "activeGoal", "currentAgenda",
            "internalConflict", "immediateConcern", "mood",
        )
        val top = raw.filter { (key, _) ->
            when (key) {
                "dynamics" -> true
                "continuity" -> true // Grounded against real pending evidence at commit time.
                "unresolvedThreads" -> state.unresolvedThreads.isEmpty() || evidenced(key)
                in clearedScalar -> {
                    val value = when (key) {
                        "physicalState" -> state.physicalState
                        "currentFocus" -> state.currentFocus
                        "activeGoal" -> state.activeGoal
                        "currentAgenda" -> state.currentAgenda
                        "internalConflict" -> state.internalConflict
                        "immediateConcern" -> state.immediateConcern
                        else -> state.mood
                    }
                    value.isBlank() || key == "mood" && value == "自然" || evidenced(key)
                }
                else -> evidenced(key)
            }
        }.toMutableMap()
        val rawDynamics = raw["dynamics"] as? JsonObject
        if (rawDynamics != null) {
            val accepted = rawDynamics.filter { (field, _) ->
                when (field) {
                    "unknowns" -> true // Complete still-unanswered set, including [].
                    "unresolvedConflict" ->
                        state.dynamics.unresolvedConflict.isBlank() || evidenced("dynamics.$field")
                    else -> evidenced("dynamics.$field")
                }
            }
            if (accepted.isEmpty()) top.remove("dynamics")
            else top["dynamics"] = JsonObject(accepted)
        }
        return JsonObject(top)
    }
}
