package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContextAssembler
import com.labteto.dshmobile.local.chat.ChatInteractionPlanner
import com.labteto.dshmobile.local.chat.ChatPostTurnPlan
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.ChatTurnContext
import com.labteto.dshmobile.local.chat.ChatTurnRunner
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.isUnboundChatPersona
import javax.inject.Inject
import javax.inject.Singleton

internal data class LocalPreparedChatTurn(
    val context: ChatTurnContext,
    val dynamicContext: String,
)

@Singleton
internal class LocalChatTurnCoordinator @Inject constructor(
    private val runner: ChatTurnRunner,
    private val interactionPlanner: ChatInteractionPlanner,
) {
    fun prepare(
        snapshot: LocalHarnessState,
        input: String,
        relationshipMemory: String = "",
    ): LocalPreparedChatTurn {
        val sharedContext = if (snapshot.chat.groupChat.enabled) {
            snapshot.chat.groupChat.context
        } else {
            snapshot.chat.chatContext
        }
        val context = if (snapshot.chat.chatPersona.isUnboundChatPersona()) {
            runner.prepareProfile(
                persona = snapshot.chat.chatPersona,
                state = snapshot.chat.chatState,
                context = sharedContext,
                userInput = input,
                storyContext = snapshot.handoffSummary,
            )
        } else {
            runner.prepare(
                personaId = snapshot.chat.personaId,
                state = snapshot.chat.chatState,
                context = sharedContext,
                userInput = input,
                storyContext = snapshot.handoffSummary,
            )
        }
        val recentAssistantReplies = recentRoleReplies(
            messages = snapshot.messages,
            groupEnabled = snapshot.chat.groupChat.enabled,
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
        context: ChatContextState = ChatContextState(),
        userInput: String,
        storyContext: String?,
    ): ChatTurnContext = runner.prepareProfile(
        persona = persona,
        state = state,
        context = context,
        userInput = userInput,
        storyContext = storyContext,
    )

    suspend fun finalize(
        snapshot: LocalHarnessState,
        persona: PersonaProfile,
        reply: LocalModelReply,
        recordUsage: (LocalModelReply) -> Unit,
        onGuardEvent: (String, List<String>) -> Unit,
    ): LocalModelReply = runner.finalizeReply(
        persona = persona,
        reply = reply,
        recordUsage = recordUsage,
        guardEnabled = snapshot.chatStyleGuardEnabled,
        additionalBannedPhrases = snapshot.chatStyleGuardCustomPhrases,
        recentAssistantReplies = recentRoleReplies(
            messages = snapshot.messages,
            groupEnabled = snapshot.chat.groupChat.enabled,
            persona = persona,
        ),
        onGuardEvent = onGuardEvent,
    )

    fun persona(snapshot: LocalHarnessState): PersonaProfile =
        runner.prepare(snapshot.chat.personaId, snapshot.chat.chatState).persona

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
        persona: PersonaProfile = PersonaProfile(),
    ): ChatPostTurnPlan? = interactionPlanner.parse(
        text = text,
        previous = previous,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
        persona = persona,
    )

    fun applyDeterministicInteractionState(
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState = interactionPlanner.applyDeterministicInteractionState(
        previous = previous,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
    )

    fun replySuggestionsPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        messages: List<LocalHarnessMessage>,
        latestAssistantMessageId: String,
    ): String {
        val recentDialogue = recentReplySuggestionDialogue(
            messages = messages,
            latestAssistantMessageId = latestAssistantMessageId,
        )
        val userMessage = recentDialogue.lastOrNull { it.first == "user" }?.second.orEmpty()
        val assistantMessage = recentDialogue.lastOrNull { it.first == "assistant" }?.second.orEmpty()
        return interactionPlanner.suggestionsPrompt(
            persona = persona,
            state = state,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            recentDialogue = recentDialogue,
        )
    }

    fun parseReplySuggestions(text: String): List<ChatReplySuggestion>? =
        interactionPlanner.parseSuggestions(text)
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

internal fun recentReplySuggestionDialogue(
    messages: List<LocalHarnessMessage>,
    latestAssistantMessageId: String,
    limit: Int = 6,
): List<Pair<String, String>> {
    val latestAssistantIndex = messages.indexOfLast { message ->
        message.id == latestAssistantMessageId &&
            message.role == "assistant" &&
            message.content.isNotBlank()
    }
    if (latestAssistantIndex < 0) return emptyList()

    return messages
        .subList(0, latestAssistantIndex + 1)
        .asSequence()
        .filter { message ->
            (message.role == "user" || message.role == "assistant") &&
                message.content.isNotBlank()
        }
        .map { message -> message.role to message.content.trim() }
        .toList()
        .takeLast(limit.coerceAtLeast(2))
}

