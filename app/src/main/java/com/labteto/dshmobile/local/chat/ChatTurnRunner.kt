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
    private val relationshipEngine: ChatRelationshipEngine,
    private val loreEngine: CharacterLoreEngine,
) {
    fun prepare(
        personaId: String,
        state: ChatCharacterState = ChatCharacterState(),
        userInput: String = "",
        storyContext: String? = null,
    ): ChatTurnContext = prepareProfile(
        persona = personaStore.get(personaId),
        state = state,
        userInput = userInput,
        storyContext = storyContext,
    )

    fun prepareProfile(
        persona: PersonaProfile,
        state: ChatCharacterState = ChatCharacterState(),
        userInput: String = "",
        storyContext: String? = null,
    ): ChatTurnContext {
        val lorePrompt = loreEngine.prompt(persona, userInput)
        val backgroundPrompt = composeRelevantBackgroundPrompt(persona, userInput)
        val storyPrompt = storyContext?.takeIf { it.isNotBlank() }?.let {
            """
            【连续性摘要｜已发生】
            ${it.take(MAX_STORY_CONTEXT_CHARS)}
            以上只用于保持连续；除非用户追问，不主动复述其中背景、旧事件或旧对白。
            """.trimIndent()
        }.orEmpty()
        return ChatTurnContext(
            persona = persona,
            stablePrompt = composeStablePersonaPrompt(persona),
            dynamicPrompt = listOf(
                composeDynamicPersonaPrompt(persona, state),
                backgroundPrompt,
                storyPrompt,
                lorePrompt,
                relationshipEngine.prompt(userInput, state),
            ).filter(String::isNotBlank).joinToString("\n\n"),
        )
    }

    suspend fun finalizeReply(
        persona: PersonaProfile,
        reply: LocalModelReply,
        recordUsage: (DeepSeekTokenUsage) -> Unit,
        guardEnabled: Boolean = true,
        additionalBannedPhrases: List<String> = emptyList(),
        recentAssistantReplies: List<String> = emptyList(),
        onGuardEvent: (String, List<String>) -> Unit = { _, _ -> },
    ): LocalModelReply {
        recordUsage(reply.usage)
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

    private fun composeStablePersonaPrompt(persona: PersonaProfile): String = buildString {
        appendLine("【角色】${persona.name}")
        if (persona.identity.isNotBlank()) appendLine("身份：${persona.identity.take(600)}")
        if (persona.personality.isNotBlank()) appendLine("性格：${persona.personality.take(600)}")
        if (persona.speechStyle.isNotBlank()) appendLine("说话：${persona.speechStyle.take(600)}")
        if (persona.relationship.isNotBlank()) appendLine("关系：${persona.relationship.take(600)}")

        fun section(title: String, values: List<String>, limit: Int) {
            val selected = values.asSequence().map(String::trim).filter(String::isNotBlank).take(limit).toList()
            if (selected.isEmpty()) return
            appendLine("【$title】")
            selected.forEach { appendLine("- ${it.take(240)}") }
        }
        section("核心动机", persona.coreMotivations, 4)
        section("稳定行为", persona.behaviorPatterns, 8)
        section("知识边界", persona.knowledgeBoundary, 6)
        section("硬约束", persona.hardConstraints, 8)
        append("固定人设只约束角色如何行动和说话，不主动背诵设定。")
    }.trim()

    private fun composeDynamicPersonaPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
    ): String = buildString {
        if (persona.corrections.isNotEmpty()) {
            appendLine("【用户纠正｜最高优先】")
            persona.corrections.takeLast(6).forEach { appendLine("- ${it.take(240)}") }
        }
        appendLine("【当前状态】情绪=${state.mood}｜关系=${state.relationshipState}｜阶段=${state.dynamics.stage}")
        state.activeGoal.takeIf(String::isNotBlank)?.let { appendLine("目标：$it") }
        state.currentAgenda.takeIf(String::isNotBlank)?.let { appendLine("行动：$it") }
        state.currentFocus.takeIf(String::isNotBlank)?.let { appendLine("关注：$it") }
        state.immediateConcern.takeIf(String::isNotBlank)?.let { appendLine("在意：$it") }
        state.internalConflict.takeIf(String::isNotBlank)?.let { appendLine("内在拉扯：$it") }
        state.recentImpression.takeIf(String::isNotBlank)?.let { appendLine("近期印象：$it") }
        state.dynamics.unresolvedConflict.takeIf(String::isNotBlank)?.let { appendLine("未解冲突：$it") }
        if (state.unresolvedThreads.isNotEmpty()) {
            appendLine("未完话题：${state.unresolvedThreads.joinToString("；")}")
        }
        if (state.userPattern.observedTurns >= 3) {
            appendLine(
                "表达偏好：长度=${state.userPattern.replyLength}" +
                    state.userPattern.preferredTone.takeIf(String::isNotBlank)?.let { "｜语气=$it" }.orEmpty(),
            )
        }
        append("状态用于决定本轮反应；已经结束或无关的旧事件不要重新提起。")
    }.trim()

    private fun composeRelevantBackgroundPrompt(persona: PersonaProfile, userInput: String): String {
        if (userInput.isBlank()) return ""
        val anchors = listOf(
            "背景" to persona.background,
            "世界" to persona.worldSetting,
            "时间线" to persona.timelinePosition,
            "来源" to persona.franchise,
        ).filter { (_, value) -> value.isNotBlank() && relevantTo(value, userInput) }
        if (anchors.isEmpty()) return ""
        return buildString {
            appendLine("【本轮相关背景】仅在当前问题需要时使用，不主动扩写。")
            anchors.forEach { (label, value) -> appendLine("$label：${value.take(900)}") }
        }.trim()
    }

    private fun relevantTo(source: String, query: String): Boolean {
        val a = normalize(source)
        val b = normalize(query)
        if (a.isBlank() || b.length < 2) return false
        val pairs = if (b.length == 2) setOf(b) else b.windowed(2).toSet()
        val hits = pairs.count(a::contains)
        return hits >= minOf(2, pairs.size)
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")

    private companion object {
        const val MAX_STORY_CONTEXT_CHARS = 2_500
    }
}
