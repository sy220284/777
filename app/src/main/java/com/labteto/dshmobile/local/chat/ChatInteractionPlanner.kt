package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatCharacterState(
    val mood: String = "自然",
    val relationshipState: String = "熟悉中",
    val currentFocus: String = "",
    val recentImpression: String = "",
    val unresolvedThreads: List<String> = emptyList(),
    val initiative: Int = 50,
    val shareDesire: Int = 50,
    val updatedAt: Long = 0L,
)

@Serializable
data class ChatReplySuggestion(
    val label: String,
    val text: String,
)

@Serializable
data class ChatPostTurnPlan(
    val state: ChatCharacterState = ChatCharacterState(),
    val suggestions: List<ChatReplySuggestion> = emptyList(),
)

@Singleton
class ChatInteractionPlanner @Inject constructor(
    private val json: Json,
) {
    fun prompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): String = buildString {
        appendLine("你只负责维护角色聊天的隐藏状态，并给用户生成下一句回复建议。")
        appendLine("不要继续扮演角色，不要输出解释，不要使用 Markdown，只输出一个 JSON 对象。")
        appendLine("角色：${persona.name}")
        if (persona.personality.isNotBlank()) appendLine("性格：${persona.personality}")
        if (persona.relationship.isNotBlank()) appendLine("关系设定：${persona.relationship}")
        appendLine("上一状态：")
        appendLine("情绪=${state.mood}")
        appendLine("关系阶段=${state.relationshipState}")
        appendLine("关注点=${state.currentFocus}")
        appendLine("近期印象=${state.recentImpression}")
        appendLine("未完话题=${state.unresolvedThreads.joinToString("；")}")
        appendLine("主动欲=${state.initiative}")
        appendLine("分享欲=${state.shareDesire}")
        appendLine("用户刚说：${userMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine("角色刚回：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine()
        appendLine("输出结构必须严格为：")
        appendLine("""{"state":{"mood":"简短情绪","relationshipState":"关系阶段","currentFocus":"当前最关注的事","recentImpression":"对用户近期印象","unresolvedThreads":["最多3条未完话题"],"initiative":0,"shareDesire":0},"suggestions":[{"label":"2到4字方向","text":"用户可以直接发送的回复"}]}""")
        appendLine("要求：")
        appendLine("1. suggestions 生成 3 到 4 条，方向明显不同，禁止只是同义改写。")
        appendLine("2. 建议必须是用户对角色说的话，口语自然，不替用户做重大决定。")
        appendLine("3. initiative/shareDesire 是 0 到 100 的整数。")
        appendLine("4. 未完话题只保留真正值得后续继续的内容，最多 3 条。")
        appendLine("5. 状态变化要有惯性，普通一句话不要让关系和情绪突然翻转。")
    }.trim()

    fun parse(text: String, previous: ChatCharacterState): ChatPostTurnPlan? {
        val body = extractJsonObject(text) ?: return null
        val decoded = runCatching {
            json.decodeFromString(ChatPostTurnPlan.serializer(), body)
        }.getOrNull() ?: return null

        return decoded.copy(
            state = sanitizeState(decoded.state, previous),
            suggestions = decoded.suggestions.asSequence()
                .map { suggestion ->
                    ChatReplySuggestion(
                        label = suggestion.label.trim().take(8),
                        text = suggestion.text.trim().take(240),
                    )
                }
                .filter { it.label.isNotBlank() && it.text.isNotBlank() }
                .distinctBy(ChatReplySuggestion::text)
                .take(4)
                .toList(),
        )
    }

    private fun sanitizeState(
        value: ChatCharacterState,
        previous: ChatCharacterState,
    ): ChatCharacterState = value.copy(
        mood = value.mood.trim().take(80).ifBlank { previous.mood },
        relationshipState = value.relationshipState.trim().take(120)
            .ifBlank { previous.relationshipState },
        currentFocus = value.currentFocus.trim().take(240),
        recentImpression = value.recentImpression.trim().take(320),
        unresolvedThreads = value.unresolvedThreads.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.take(200) }
            .distinct()
            .take(3)
            .toList(),
        initiative = value.initiative.coerceIn(0, 100),
        shareDesire = value.shareDesire.coerceIn(0, 100),
        updatedAt = System.currentTimeMillis(),
    )

    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 2_000
    }
}
