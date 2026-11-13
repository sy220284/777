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
        previous: ChatCharacterState,
        significance: String,
        quotations: Map<String, String>,
        userMessage: String,
        assistantMessage: String,
    ): JsonObject? {
        if (raw == null) return null
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
                "continuity" -> true // Validated against durable Pending at commit time.
                // A durable impression has no special bypass for MINOR/MAJOR. It is a
                // separate fact from transient mood and always needs an actual quote.
                "currentUserImpression", "relationshipState" -> evidenced(key)
                "unresolvedThreads" -> significance != "NONE" ||
                    state.unresolvedThreads.isEmpty() || evidenced(key)
                in clearedScalar -> {
                    if (significance != "NONE") return@filter true
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
                else -> significance != "NONE" || evidenced(key)
            }
        }.toMutableMap()
        val rawDynamics = raw["dynamics"] as? JsonObject
        if (rawDynamics != null) {
            val accepted = rawDynamics.filter { (field, _) ->
                when (field) {
                    // An empty unknown set is a semantic claim that questions were answered,
                    // not a generic list clear. Only the user's transcript can authorize it.
                    "unknowns" -> previous.dynamics.unknowns.isEmpty() ||
                        previous.dynamics.unknowns.toSet() == state.dynamics.unknowns.toSet() ||
                        evidenced("dynamics.unknowns")
                    "stage" -> true // Stage has its own event-level validation in the reducer.
                    "unresolvedConflict" -> significance != "NONE" ||
                        state.dynamics.unresolvedConflict.isBlank() || evidenced("dynamics.$field")
                    "facts", "hypotheses", "sharedObjects", "sharedMoments" -> true
                    else -> significance != "NONE" || evidenced("dynamics.$field")
                }
            }
            if (accepted.isEmpty()) top.remove("dynamics")
            else top["dynamics"] = JsonObject(accepted)
        }
        return JsonObject(top)
    }
}
