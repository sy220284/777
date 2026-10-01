package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class ChatInteractionPlanner @Inject constructor(
    private val json: Json,
) {
    private val promptBuilder = ChatInteractionPromptBuilder()
    private val parser = ChatInteractionPlanParser(json)
    private val reducer = ChatInteractionStateReducer()

    fun prompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): String = promptBuilder.prompt(persona, state, userMessage, assistantMessage)

    fun suggestionsPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
        recentDialogue: List<Pair<String, String>> = emptyList(),
    ): String = promptBuilder.suggestionsPrompt(
        persona = persona,
        state = state,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
        recentDialogue = recentDialogue,
    )

    fun parseSuggestions(text: String): List<ChatReplySuggestion>? =
        parser.parseSuggestions(text)

    fun parse(
        text: String,
        previous: ChatCharacterState,
        userMessage: String = "",
        assistantMessage: String = "",
    ): ChatPostTurnPlan? {
        val parsed = parser.parsePlan(text) ?: return null
        return reducer.reduce(
            parsed = parsed,
            previous = previous,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        )
    }

    fun applyDeterministicInteractionState(
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState =
        reducer.applyDeterministicInteractionState(previous, userMessage, assistantMessage)
}
