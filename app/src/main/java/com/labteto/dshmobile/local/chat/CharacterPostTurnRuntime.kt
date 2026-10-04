package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonObject

/**
 * Applies system-owned V3 state after the model-authored candidate has been sanitized.
 *
 * The model may propose a subjective impression, but life progression and long-term evolution
 * remain runtime-owned so the reducer does not become a second character runtime.
 */
internal fun applyCharacterPostTurnRuntime(
    persona: PersonaProfile,
    previous: ChatCharacterState,
    current: ChatCharacterState,
    candidate: ChatCharacterState,
    rawState: JsonObject?,
    significance: String,
    userMessage: String,
    assistantMessage: String,
): ChatCharacterState {
    val impression = if (significance == "NONE") {
        previous.currentUserImpression.ifBlank { previous.recentImpression }
    } else {
        when {
            rawState?.containsKey("currentUserImpression") == true ->
                candidate.currentUserImpression.trim().take(320)
            rawState?.containsKey("recentImpression") == true ->
                candidate.recentImpression.trim().take(320)
            else -> previous.currentUserImpression.ifBlank { previous.recentImpression }
        }
    }
    val withImpression = current.copy(
        currentUserImpression = impression,
        recentImpression = impression,
    )
    return withImpression.copy(
        lifeState = advanceCharacterLife(persona, withImpression),
        evolution = evolveCharacterEvolution(
            persona = persona,
            previous = previous,
            current = withImpression,
            significance = significance,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        ),
    )
}
