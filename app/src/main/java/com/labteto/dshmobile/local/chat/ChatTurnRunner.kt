package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.ChatStyleGuard
import com.labteto.dshmobile.local.DeepSeekTokenUsage
import com.labteto.dshmobile.local.LocalModelReply
import javax.inject.Inject
import javax.inject.Singleton

data class ChatTurnContext(
    val persona: PersonaProfile,
    val stablePrompt: String,
    val dynamicPrompt: String,
) {
    val prompt: String
        get() = listOf(stablePrompt, dynamicPrompt)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
}

@Singleton
class ChatTurnRunner @Inject constructor(
    private val personaStore: ChatPersonaStore,
    relationshipEngine: ChatRelationshipEngine,
    loreEngine: CharacterLoreEngine,
) {
    private val runtimeProjector = CharacterRuntimeProjector(relationshipEngine, loreEngine)
    fun prepare(
        personaId: String,
        state: ChatCharacterState = ChatCharacterState(),
        context: ChatContextState = ChatContextState(),
        userInput: String = "",
        storyContext: String? = null,
    ): ChatTurnContext = prepareProfile(
        persona = personaStore.get(personaId),
        state = state,
        context = context,
        userInput = userInput,
        storyContext = storyContext,
    )

    fun prepareProfile(
        persona: PersonaProfile,
        state: ChatCharacterState = ChatCharacterState(),
        context: ChatContextState = ChatContextState(),
        userInput: String = "",
        storyContext: String? = null,
    ): ChatTurnContext {
        if (persona.isUnboundChatPersona()) {
            val continuationPrompt = storyContext?.takeIf(String::isNotBlank)?.let {
                """
                【对话连续性摘要｜已发生】
                ${it.take(MAX_STORY_CONTEXT_CHARS)}
                以上只用于衔接当前对话；除非用户追问，不主动复述旧内容。
                """.trimIndent()
            }.orEmpty()
            return ChatTurnContext(
                persona = persona,
                stablePrompt = UNBOUND_CHAT_PROMPT,
                dynamicPrompt = listOf(
                    renderChatContextForModel(context),
                    continuationPrompt,
                    renderChatTurnModeForModel(userInput),
                ).filter(String::isNotBlank).joinToString("\n\n"),
            )
        }

        val runtime = runtimeProjector.project(
            persona = persona,
            state = state,
            context = context,
            userInput = userInput,
            storyContext = storyContext,
        )
        return ChatTurnContext(
            persona = persona,
            stablePrompt = runtime.stablePrompt,
            dynamicPrompt = runtime.dynamicPrompt,
        )
    }

    suspend fun finalizeReply(
        persona: PersonaProfile,
        reply: LocalModelReply,
        recordUsage: (LocalModelReply) -> Unit,
        guardEnabled: Boolean = true,
        additionalBannedPhrases: List<String> = emptyList(),
        recentAssistantReplies: List<String> = emptyList(),
        onGuardEvent: (String, List<String>) -> Unit = { _, _ -> },
    ): LocalModelReply {
        recordUsage(reply)
        if (reply.toolCalls.isNotEmpty()) return reply

        var content = reply.content.orEmpty()
        if (guardEnabled) {
            val phrases = ChatStyleGuard.activePhrases(
                customPhrases = additionalBannedPhrases,
                personaPhrases = persona.bannedPhrases,
                enabled = true,
            )
            val violations = ChatStyleGuard.violations(content, phrases)
            if (violations.isNotEmpty()) {
                onGuardEvent("filter", violations)
                content = ChatStyleGuard.filterLiteral(content, phrases)
            }
        }

        val repetition = ChatRepetitionGuard.filter(content, recentAssistantReplies)
        if (repetition.repeatedSegments.isNotEmpty() && repetition.text != content) {
            onGuardEvent("repeat-filter", repetition.repeatedSegments.take(4))
            content = repetition.text
        }

        return if (content == reply.content.orEmpty()) reply else ChatStyleGuard.withContent(reply, content)
    }

    private companion object {
        const val MAX_STORY_CONTEXT_CHARS = 2_500
        const val UNBOUND_CHAT_PROMPT =
            "【聊天模式】当前未选择人物角色。以通用聊天助手身份自然回应，" +
                "不虚构固定人物身份、人物关系、背景或世界观。"
    }}
