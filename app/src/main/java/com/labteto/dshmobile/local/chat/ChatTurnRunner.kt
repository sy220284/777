package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.ChatStyleGuard
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.DeepSeekTokenUsage
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import javax.inject.Singleton

data class ChatTurnContext(
    val persona: PersonaProfile,
    val prompt: String,
)

@Singleton
class ChatTurnRunner @Inject constructor(
    private val personaStore: ChatPersonaStore,
) {
    fun prepare(
        personaId: String,
        state: ChatCharacterState = ChatCharacterState(),
    ): ChatTurnContext {
        val persona = personaStore.get(personaId)
        return ChatTurnContext(
            persona = persona,
            prompt = composePersonaPrompt(persona, state),
        )
    }

    suspend fun finalizeReply(
        persona: PersonaProfile,
        reply: LocalModelReply,
        rewrite: suspend (String, List<String>) -> LocalModelReply,
        recordUsage: (DeepSeekTokenUsage) -> Unit,
        onGuardEvent: (String, List<String>) -> Unit = { _, _ -> },
    ): LocalModelReply {
        val extraBanned = persona.bannedPhrases
        val firstViolations = ChatStyleGuard.violations(reply.content.orEmpty(), extraBanned)
        if (firstViolations.isEmpty()) {
            recordUsage(reply.usage)
            return reply
        }

        onGuardEvent("rewrite", firstViolations)
        recordUsage(reply.usage)
        val repaired = try {
            rewrite(reply.content.orEmpty(), firstViolations)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onGuardEvent("rewrite-failed", firstViolations)
            return ChatStyleGuard.withContent(
                reply,
                ChatStyleGuard.scrub(reply.content.orEmpty(), extraBanned),
            )
        }

        recordUsage(repaired.usage)
        val remaining = ChatStyleGuard.violations(repaired.content.orEmpty(), extraBanned)
        if (remaining.isEmpty()) return repaired

        onGuardEvent("scrub", remaining)
        return ChatStyleGuard.withContent(
            repaired,
            ChatStyleGuard.scrub(repaired.content.orEmpty(), extraBanned),
        )
    }

    private fun composePersonaPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
    ): String = buildString {
        appendLine("【当前角色】")
        appendLine("名称：${persona.name}")
        if (persona.identity.isNotBlank()) appendLine("身份：${persona.identity}")
        if (persona.background.isNotBlank()) appendLine("背景：${persona.background}")
        if (persona.personality.isNotBlank()) appendLine("核心性格：${persona.personality}")
        if (persona.speechStyle.isNotBlank()) appendLine("说话方式：${persona.speechStyle}")
        if (persona.relationship.isNotBlank()) appendLine("与用户的关系：${persona.relationship}")
        if (persona.worldSetting.isNotBlank()) appendLine("世界设定：${persona.worldSetting}")

        if (persona.hardConstraints.isNotEmpty()) {
            appendLine("【不可违反的人设】")
            persona.hardConstraints.forEach { appendLine("- $it") }
        }
        if (persona.signaturePhrases.isNotEmpty()) {
            appendLine("【常用表达】")
            persona.signaturePhrases.forEach { appendLine("- $it") }
        }
        if (persona.bannedPhrases.isNotEmpty()) {
            appendLine("【这个角色禁止使用的表达】")
            persona.bannedPhrases.forEach { appendLine("- $it") }
        }
        if (persona.exampleDialogues.isNotEmpty()) {
            appendLine("【对白参考】")
            persona.exampleDialogues.forEach { appendLine("- $it") }
        }

        appendLine("【当前动态状态】")
        appendLine("情绪：${state.mood}")
        appendLine("关系阶段：${state.relationshipState}")
        state.currentFocus.takeIf(String::isNotBlank)?.let { appendLine("当前关注：$it") }
        state.recentImpression.takeIf(String::isNotBlank)?.let { appendLine("对用户近期印象：$it") }
        if (state.unresolvedThreads.isNotEmpty()) {
            appendLine("还没聊完的事：${state.unresolvedThreads.joinToString("；")}")
        }
        appendLine("主动倾向：${state.initiative}/100；分享欲：${state.shareDesire}/100")
        appendLine("这些动态状态有惯性。延续当前情绪和关系，不要每轮重置，也不要因为一句普通对话突然大幅改变。")

        appendLine("始终以这个角色继续当前聊天。角色设定的优先级高于普通聊天习惯；不要解释角色卡，也不要说自己正在扮演角色。")
    }.trim()
}
