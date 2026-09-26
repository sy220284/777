package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextAssembler
import com.labteto.dshmobile.local.chat.ChatInteractionPlanner
import com.labteto.dshmobile.local.chat.ChatPostTurnPlan
import com.labteto.dshmobile.local.chat.ChatTurnContext
import com.labteto.dshmobile.local.chat.ChatTurnRunner
import com.labteto.dshmobile.local.chat.PersonaProfile

internal data class LocalPreparedChatTurn(
    val context: ChatTurnContext,
    val dynamicContext: String,
)

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
        val recentAssistantReplies = recentRoleReplies(
            messages = snapshot.messages,
            groupEnabled = snapshot.groupChat.enabled,
            persona = context.persona,
        )
        return LocalPreparedChatTurn(
            context = context,
            dynamicContext = ChatContextAssembler.assemble(
                dynamicPrompt = context.dynamicPrompt,
                relationshipMemory = relationshipMemory,
                userInput = input,
                recentAssistantReplies = recentAssistantReplies,
            ),
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
        recentAssistantReplies = recentRoleReplies(
            messages = snapshot.messages,
            groupEnabled = snapshot.groupChat.enabled,
            persona = persona,
        ),
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


internal fun recentRoleReplies(
    messages: List<LocalHarnessMessage>,
    groupEnabled: Boolean,
    persona: PersonaProfile,
    limit: Int = 4,
): List<String> = messages.asReversed()
    .asSequence()
    .filter { message ->
        message.role == "assistant" &&
            (
                !groupEnabled ||
                    message.speakerId == persona.id ||
                    message.speakerName == persona.name
            )
    }
    .map { it.content }
    .filter(String::isNotBlank)
    .take(limit.coerceAtLeast(1))
    .toList()
    .asReversed()
