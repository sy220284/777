package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class ChatInteractionPlanner @Inject constructor(json: Json) {
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
        persona: PersonaProfile, state: ChatCharacterState, userMessage: String,
        assistantMessage: String, recentDialogue: List<Pair<String, String>> = emptyList(),
    ): String = promptBuilder.suggestionsPrompt(persona, state, userMessage, assistantMessage, recentDialogue)

    fun parseSuggestions(text: String): List<ChatReplySuggestion>? = parser.parseSuggestions(text)
    fun parse(
        text: String,
        previous: ChatCharacterState,
        userMessage: String = "",
        assistantMessage: String = "",
        persona: PersonaProfile = PersonaProfile(),
    ): ChatPostTurnPlan? = parser.parsePlan(text)?.let {
        reducer.reduce(it, previous, userMessage, assistantMessage, persona)
    }
    fun applyDeterministicInteractionState(
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState =
        reducer.applyDeterministicInteractionState(previous, userMessage, assistantMessage)
}
