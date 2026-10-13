package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextAssembler
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatInteractionPlanner
import com.labteto.dshmobile.local.chat.ChatPostTurnPlan
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.ChatTurnContext
import com.labteto.dshmobile.local.chat.ChatTurnRunner
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.isUnboundChatPersona
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.json.JsonObject
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
    private val persistence: LocalChatPersistence? = null,
) {
    internal fun chatStablePrompt(snapshot: LocalHarnessState, base: String): String =
        listOf(base, renderChatExpressionPreferences(
            snapshot.chat.chatStyleGuardCustomPhrases, snapshot.chat.chatStyleGuardEnabled,
        )).filter(String::isNotBlank).joinToString("\n\n")

    internal fun prepare(
        snapshot: LocalHarnessState,
        input: String,
        relationshipMemory: String = "",
    ): LocalPreparedChatTurn {
        val sharedContext = if (snapshot.chat.groupChat.enabled) {
            snapshot.chat.groupChat.context
        } else {
            snapshot.chat.chatContext
        }
        val decisions = persistence?.diaryStore?.decisionsForExperience(
            chatRelationshipSubjectKey(snapshot.chat.galleryId, snapshot.chat.personaId),
            sharedContext.withExperience(snapshot.sessionId, listOf(snapshot.sessionId)),
        ).orEmpty()
        val correctedState = reconcileDiaryState(snapshot.chat.chatState, decisions)
        val correctedContext = reconcileDiaryContext(sharedContext, decisions)
        val context = if (snapshot.chat.chatPersona.isUnboundChatPersona()) {
            runner.prepareProfile(
                persona = snapshot.chat.chatPersona,
                state = correctedState,
                context = correctedContext,
                userInput = input,
                storyContext = snapshot.handoffSummary,
            )
        } else {
            runner.prepare(
                personaId = snapshot.chat.personaId,
                state = correctedState,
                context = correctedContext,
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
            context = context.copy(stablePrompt = chatStablePrompt(snapshot, context.stablePrompt)),
            dynamicContext = ChatContextAssembler.assemble(
                dynamicPrompt = listOf(context.dynamicPrompt, renderDiaryDecisions(decisions))
                    .filter(String::isNotBlank).joinToString("\n\n"),
                relationshipMemory = relationshipMemory,
                userInput = input,
                recentAssistantReplies = recentAssistantReplies,
            ),
        )
    }

    internal fun prepareProfile(
        persona: PersonaProfile,
        state: ChatCharacterState,
        context: ChatContextState = ChatContextState(),
        userInput: String,
        storyContext: String?,
        subjectKey: String? = null,
    ): ChatTurnContext {
        val decisions = subjectKey?.let { persistence?.diaryStore?.decisionsForExperience(it, context) }.orEmpty()
        val effectiveContext = reconcileDiaryContext(context, decisions)
        val prepared = runner.prepareProfile(
            persona = persona, state = reconcileDiaryState(state, decisions),
            context = effectiveContext, userInput = userInput, storyContext = storyContext,
        )
        val parts = prepared.dynamicParts + ChatContextPart(
            ChatContextLayer.HISTORICAL, renderDiaryDecisions(decisions),
        )
        return prepared.copy(
            dynamicPrompt = ChatUnifiedTurnContext.compose(userInput, effectiveContext, parts),
            dynamicParts = parts,
        )
    }

    internal suspend fun finalize(
        snapshot: LocalHarnessState,
        persona: PersonaProfile,
        reply: LocalModelReply,
        recordUsage: (LocalModelReply) -> Unit,
        onGuardEvent: (String, List<String>) -> Unit,
    ): LocalModelReply = runner.finalizeReply(
        persona = persona,
        reply = reply,
        recordUsage = recordUsage,
        guardEnabled = snapshot.chat.chatStyleGuardEnabled,
        additionalBannedPhrases = snapshot.chat.chatStyleGuardCustomPhrases,
        recentAssistantReplies = recentRoleReplies(
            messages = snapshot.messages,
            groupEnabled = snapshot.chat.groupChat.enabled,
            persona = persona,
        ),
        onGuardEvent = onGuardEvent,
    )

    internal fun persona(snapshot: LocalHarnessState): PersonaProfile =
        runner.prepare(snapshot.chat.personaId, snapshot.chat.chatState).persona

    internal fun postTurnPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
        context: ChatContextState = ChatContextState(),
    ): String = interactionPlanner.prompt(
        persona = persona,
        state = state,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
        context = context,
    )

    internal fun postTurnParseFailureKind(text: String): String =
        interactionPlanner.postTurnParseFailureKind(text)

    internal fun parsePostTurnEnvelope(text: String): JsonObject? =
        interactionPlanner.parseJsonObjectEnvelope(text)

    internal fun parsePostTurn(
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

    internal fun applyDeterministicInteractionState(
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState = interactionPlanner.applyDeterministicInteractionState(
        previous = previous,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
    )

    internal fun replySuggestionsPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        messages: List<LocalHarnessMessage>,
        latestAssistantMessageId: String,
        context: ChatContextState = ChatContextState(),
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
            context = context,
        ).let(::enhanceReplySuggestionPromptForScheduling)
    }

    internal fun parseReplySuggestions(text: String): List<ChatReplySuggestion>? =
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


/** Scheduling ideas share the existing user-invoked next-reply request; no background model call. */
internal fun enhanceReplySuggestionPromptForScheduling(prompt: String): String = prompt + """

如果最近双方真实谈到未来的共同计划、提醒、下次互动或待续约定，可以在原有下一句建议中自然加入一条关于后续时间或主动联系的用户表达；没有相关语境则保持普通接话建议。建议仍必须是用户可以直接发给当前人物的文字，不要虚构约定的时间或事件，不要把角色的反应写死，也不要宣称已经创建自动任务。实际定时执行需用户明确进入自动任务并确认。
""".trimIndent()
