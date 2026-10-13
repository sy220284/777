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
    // Field authorization has already happened in ChatStatePatchAuthority.
    // Do not re-classify the same transition based on diary significance here.
    val impression = if (rawState?.containsKey("currentUserImpression") == true) {
        current.currentUserImpression
    } else previous.currentUserImpression
    val withImpression = current.copy(
        currentUserImpression = impression,
        recentImpression = "",
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
