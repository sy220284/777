package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.harness.state.RuntimeStateTransitionPolicy
import com.labteto.dshmobile.harness.state.acceptRuntimeStateTransition
import com.labteto.dshmobile.harness.state.rejectRuntimeStateTransition
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
    val currentImpression = previous.currentUserImpression
    val proposedImpression = if (rawState?.containsKey("currentUserImpression") == true) {
        candidate.currentUserImpression
    } else {
        currentImpression
    }
    val impression = RuntimeStateTransitionPolicy<String> { runtimeOwned, proposed ->
        if (significance == "NONE") {
            rejectRuntimeStateTransition(runtimeOwned, "本轮证据不足，不改写长期主观印象")
        } else {
            acceptRuntimeStateTransition(proposed.trim().take(320))
        }
    }.resolve(currentImpression, proposedImpression).value
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
