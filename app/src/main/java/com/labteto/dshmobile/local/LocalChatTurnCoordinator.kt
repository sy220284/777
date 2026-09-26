package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatInteractionPlanner
import com.labteto.dshmobile.local.chat.ChatPostTurnPlan
import com.labteto.dshmobile.local.chat.ChatTurnContext
import com.labteto.dshmobile.local.chat.ChatTurnRunner
import com.labteto.dshmobile.local.chat.PersonaProfile

internal data class LocalPreparedChatTurn(
    val context: ChatTurnContext,
    val dynamicContext: String,
)

/**
 * Owns chat-only semantic preparation/finalization while leaving session persistence and UI state
 * projection to the engine/session coordinators.
 */
internal class LocalChatTurnCoordinator(
    private val runner: ChatTurnRunner,
    private val interactionPlanner: ChatInteractionPlanner,
) {
    fun prepare(
        snapshot: LocalHarnessState,
        input: String,
        relationshipMemory: String = "",
    ): LocalPreparedChatTurn {
        val context = runner.prepare(
            personaId = snapshot.personaId,
            state = snapshot.chatState,
            userInput = input,
            storyContext = snapshot.handoffSummary,
        )
        return LocalPreparedChatTurn(
            context = context,
            dynamicContext = listOf(context.dynamicPrompt, relationshipMemory)
                .filter(String::isNotBlank)
                .joinToString("\n\n"),
        )
    }

    fun prepareProfile(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userInput: String,
        storyContext: String?,
    ): ChatTurnContext = runner.prepareProfile(
        persona = persona,
        state = state,
        userInput = userInput,
        storyContext = storyContext,
    )

    suspend fun finalize(
        snapshot: LocalHarnessState,
        persona: PersonaProfile,
        reply: LocalModelReply,
        recordUsage: (DeepSeekTokenUsage) -> Unit,
        onGuardEvent: (String, List<String>) -> Unit,
    ): LocalModelReply = runner.finalizeReply(
        persona = persona,
        reply = reply,
        recordUsage = recordUsage,
        guardEnabled = snapshot.chatStyleGuardEnabled,
        additionalBannedPhrases = snapshot.chatStyleGuardCustomPhrases,
        onGuardEvent = onGuardEvent,
    )

    fun persona(snapshot: LocalHarnessState): PersonaProfile =
        runner.prepare(snapshot.personaId, snapshot.chatState).persona

    fun postTurnPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): String = interactionPlanner.prompt(
        persona = persona,
        state = state,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
    )

    fun parsePostTurn(
        text: String,
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatPostTurnPlan? = interactionPlanner.parse(
        text = text,
        previous = previous,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
    )
}
