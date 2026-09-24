package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RelationshipEvidence(
    val text: String = "",
    val confidence: Int = 50,
    val source: String = "",
)

@Serializable
data class RelationshipDynamics(
    val stage: String = "FAMILIAR",
    val warmth: Int = 50,
    val trust: Int = 50,
    val reciprocity: Int = 50,
    val tension: Int = 10,
    val stability: Int = 50,
    val unresolvedConflict: String = "",
    val facts: List<RelationshipEvidence> = emptyList(),
    val hypotheses: List<RelationshipEvidence> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val sharedMoments: List<String> = emptyList(),
)

@Serializable
data class UserChatPattern(
    val replyLength: String = "mixed",
    val directness: Int = 50,
    val playfulness: Int = 50,
    val initiative: Int = 50,
    val emojiStyle: String = "",
    val preferredTone: String = "",
    val observedTurns: Int = 0,
    val averageMessageChars: Int = 0,
    val updatedAt: Long = 0L,
)

@Serializable
data class ChatCharacterState(
    val mood: String = "自然",
    val relationshipState: String = "熟悉中",
    val currentFocus: String = "",
    val recentImpression: String = "",
    val unresolvedThreads: List<String> = emptyList(),
    val initiative: Int = 50,
    val shareDesire: Int = 50,
    val dynamics: RelationshipDynamics = RelationshipDynamics(),
    val userPattern: UserChatPattern = UserChatPattern(),
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
        appendLine("你负责维护角色聊天的隐藏关系状态、证据账本、用户沟通习惯，并生成用户下一句回复建议。")
        appendLine("不要继续扮演角色，不要解释过程，不要使用 Markdown，只输出一个 JSON 对象。")
        appendLine("角色：${persona.name}")
        if (persona.personality.isNotBlank()) appendLine("性格：${persona.personality}")
        if (persona.relationship.isNotBlank()) appendLine("关系设定：${persona.relationship}")
        appendLine()
        appendLine("上一状态：")
        appendLine("情绪=${state.mood}")
        appendLine("关系描述=${state.relationshipState}")
        appendLine(
            "关系动力：stage=${state.dynamics.stage}, warmth=${state.dynamics.warmth}, " +
                "trust=${state.dynamics.trust}, reciprocity=${state.dynamics.reciprocity}, " +
                "tension=${state.dynamics.tension}, stability=${state.dynamics.stability}",
        )
        appendLine("关注点=${state.currentFocus}")
        appendLine("近期印象=${state.recentImpression}")
        appendLine("未完话题=${state.unresolvedThreads.joinToString("；")}")
        appendLine("角色主动欲=${state.initiative}；分享欲=${state.shareDesire}")
        appendLine(
            "用户习惯：长度=${state.userPattern.replyLength}；直接度=${state.userPattern.directness}；" +
                "玩笑接受度=${state.userPattern.playfulness}；主动倾向=${state.userPattern.initiative}",
        )
        appendLine("已有事实=${state.dynamics.facts.joinToString("；") { it.text }}")
        appendLine("已有推测=${state.dynamics.hypotheses.joinToString("；") { it.text }}")
        appendLine("仍未知=${state.dynamics.unknowns.joinToString("；")}")
        appendLine("共同经历=${state.dynamics.sharedMoments.joinToString("；")}")
        appendLine()
        appendLine("用户刚说：${userMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine("角色刚回：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine()
        appendLine("输出结构必须严格为：")
        appendLine(
            """{"state":{"mood":"简短情绪","relationshipState":"自然语言关系描述","currentFocus":"当前关注","recentImpression":"近期印象","unresolvedThreads":["最多3条"],"initiative":0,"shareDesire":0,"dynamics":{"stage":"FAMILIAR","warmth":0,"trust":0,"reciprocity":0,"tension":0,"stability":0,"unresolvedConflict":"","facts":[{"text":"明确事实","confidence":95,"source":"user"}],"hypotheses":[{"text":"暂定推测","confidence":60,"source":"inference"}],"unknowns":["关键未知"],"sharedMoments":["真实共同经历"]},"userPattern":{"replyLength":"mixed","directness":50,"playfulness":50,"initiative":50,"emojiStyle":"","preferredTone":""}},"suggestions":[{"label":"2到4字方向","text":"用户可以直接发送的回复"}]}""",
        )
        appendLine("要求：")
        appendLine("1. stage 只能取 NEW / FAMILIAR / AMBIGUOUS / DATING / COMMITTED / CONFLICT / COOLING / SEPARATED / REPAIRING。")
        appendLine("2. facts 只记录本轮明确说出或明确发生、以后仍有价值的事实；不得把动机、爱意、依恋类型、人格猜测写进 facts。")
        appendLine("3. hypotheses 专门放暂定解释，必须给置信度；证据不足就放 unknowns，不为完整感硬猜。")
        appendLine("4. warmth/trust/reciprocity/tension/stability 每轮通常只小幅变化；普通一句话禁止关系数值剧烈跳变。")
        appendLine("5. stage 只有出现明确关系事件或连续强证据时才建议变化；一次回复慢、一个表情、一次冷淡都不足以改阶段。")
        appendLine("6. sharedMoments 只保留双方真实发生且以后值得自然提起的共同经历，禁止虚构。")
        appendLine("7. userPattern 只从用户真实表达习惯渐进学习，不因单轮异常表达彻底改画像。")
        appendLine("8. suggestions 生成3到4条，方向明显不同，禁止同义改写；必须贴合用户已形成的表达习惯。")
        appendLine("9. 建议是用户对角色说的话，口语自然，不替用户做重大决定，不使用操控、羞辱、欺骗或绕过边界的策略。")
    }.trim()

    fun parse(
        text: String,
        previous: ChatCharacterState,
        userMessage: String = "",
        assistantMessage: String = "",
    ): ChatPostTurnPlan? {
        val body = extractJsonObject(text) ?: return null
        val decoded = runCatching {
            json.decodeFromString(ChatPostTurnPlan.serializer(), body)
        }.getOrNull() ?: return null

        return decoded.copy(
            state = sanitizeState(
                value = decoded.state,
                previous = previous,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
            ),
            suggestions = decoded.suggestions.asSequence()
                .map { suggestion ->
                    ChatReplySuggestion(
                        label = suggestion.label.trim().take(8),
                        text = suggestion.text.trim().take(240),
                    )
                }
                .filter { it.label.isNotBlank() && it.text.isNotBlank() }
                .distinctBy { normalize(it.text) }
                .take(4)
                .toList(),
        )
    }

    private fun sanitizeState(
        value: ChatCharacterState,
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        val dynamics = sanitizeDynamics(
            value = value.dynamics,
            previous = previous.dynamics,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        )
        val pattern = sanitizeUserPattern(
            value = value.userPattern,
            previous = previous.userPattern,
            userMessage = userMessage,
        )
        val requestedStage = normalizeStage(value.dynamics.stage)
        val relationshipDescription = when {
            dynamics.stage != previous.dynamics.stage -> stageLabel(dynamics.stage)
            requestedStage != previous.dynamics.stage -> previous.relationshipState
            else -> value.relationshipState.trim().take(120).ifBlank { previous.relationshipState }
        }
        return value.copy(
            mood = value.mood.trim().take(80).ifBlank { previous.mood },
            relationshipState = relationshipDescription,
            currentFocus = value.currentFocus.trim().take(240),
            recentImpression = value.recentImpression.trim().take(320),
            unresolvedThreads = value.unresolvedThreads.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { it.take(200) }
                .distinct()
                .take(3)
                .toList(),
            initiative = bounded(value.initiative, previous.initiative, 15),
            shareDesire = bounded(value.shareDesire, previous.shareDesire, 15),
            dynamics = dynamics,
            userPattern = pattern,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun sanitizeDynamics(
        value: RelationshipDynamics,
        previous: RelationshipDynamics,
        userMessage: String,
        assistantMessage: String,
    ): RelationshipDynamics {
        val candidateStage = normalizeStage(value.stage)
        val explicitStageEvidence = EXPLICIT_STAGE_SIGNAL.containsMatchIn(userMessage)
        val stage = when {
            candidateStage == previous.stage -> previous.stage
            explicitStageEvidence -> candidateStage
            else -> previous.stage
        }

        return RelationshipDynamics(
            stage = stage,
            warmth = bounded(value.warmth, previous.warmth, 10),
            trust = bounded(value.trust, previous.trust, 8),
            reciprocity = bounded(value.reciprocity, previous.reciprocity, 8),
            tension = bounded(value.tension, previous.tension, 12),
            stability = bounded(value.stability, previous.stability, 8),
            unresolvedConflict = value.unresolvedConflict.trim().take(240),
            facts = mergeEvidence(
                previous = previous.facts,
                incoming = value.facts,
                minimumConfidence = 80,
                maximumConfidence = 100,
                limit = 12,
            ),
            hypotheses = mergeEvidence(
                previous = previous.hypotheses,
                incoming = value.hypotheses,
                minimumConfidence = 10,
                maximumConfidence = 85,
                limit = 6,
            ),
            unknowns = mergeStrings(previous.unknowns, value.unknowns, 6, 160),
            sharedMoments = mergeStrings(previous.sharedMoments, value.sharedMoments, 8, 180),
        )
    }

    private fun sanitizeUserPattern(
        value: UserChatPattern,
        previous: UserChatPattern,
        userMessage: String,
    ): UserChatPattern {
        val measuredLength = userMessage.trim().length
        val hasObservation = measuredLength > 0
        val previousWeight = previous.observedTurns.coerceIn(0, 19)
        val averageChars = if (hasObservation) {
            if (previousWeight == 0) {
                measuredLength
            } else {
                ((previous.averageMessageChars * previousWeight) + measuredLength) / (previousWeight + 1)
            }
        } else {
            previous.averageMessageChars
        }
        val measuredReplyLength = when {
            !hasObservation -> null
            averageChars < 20 -> "short"
            averageChars < 80 -> "medium"
            else -> "long"
        }
        val modelLength = value.replyLength.trim().lowercase()
            .takeIf { it in ALLOWED_REPLY_LENGTHS }
        val length = measuredReplyLength ?: modelLength ?: previous.replyLength

        return value.copy(
            replyLength = length,
            directness = bounded(value.directness, previous.directness, 10),
            playfulness = bounded(value.playfulness, previous.playfulness, 10),
            initiative = bounded(value.initiative, previous.initiative, 10),
            emojiStyle = value.emojiStyle.trim().take(120),
            preferredTone = value.preferredTone.trim().take(120),
            observedTurns = if (hasObservation) (previous.observedTurns + 1).coerceAtMost(1000)
                else previous.observedTurns,
            averageMessageChars = averageChars.coerceIn(0, 2_000),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun mergeEvidence(
        previous: List<RelationshipEvidence>,
        incoming: List<RelationshipEvidence>,
        minimumConfidence: Int,
        maximumConfidence: Int,
        limit: Int,
    ): List<RelationshipEvidence> {
        val merged = linkedMapOf<String, RelationshipEvidence>()
        (previous + incoming).forEach { item ->
            val text = item.text.trim().take(220)
            if (text.isBlank()) return@forEach
            val confidence = item.confidence.coerceIn(minimumConfidence, maximumConfidence)
            if (item.confidence < minimumConfidence) return@forEach
            val clean = item.copy(
                text = text,
                confidence = confidence,
                source = item.source.trim().take(40),
            )
            merged[normalize(text)] = clean
        }
        return merged.values.toList().takeLast(limit)
    }

    private fun mergeStrings(
        previous: List<String>,
        incoming: List<String>,
        limit: Int,
        maxChars: Int,
    ): List<String> {
        val merged = linkedMapOf<String, String>()
        (previous + incoming).forEach { raw ->
            val text = raw.trim().take(maxChars)
            if (text.isNotBlank()) merged[normalize(text)] = text
        }
        return merged.values.toList().takeLast(limit)
    }

    private fun bounded(value: Int, previous: Int, maxDelta: Int): Int =
        value.coerceIn(previous - maxDelta, previous + maxDelta).coerceIn(0, 100)

    private fun normalizeStage(raw: String): String {
        val stage = raw.trim().uppercase()
        return stage.takeIf { it in ALLOWED_STAGES } ?: "FAMILIAR"
    }

    private fun stageLabel(stage: String): String = when (stage) {
        "NEW" -> "刚认识"
        "FAMILIAR" -> "熟悉中"
        "AMBIGUOUS" -> "暧昧期"
        "DATING" -> "约会中"
        "COMMITTED" -> "稳定关系"
        "CONFLICT" -> "矛盾期"
        "COOLING" -> "降温期"
        "SEPARATED" -> "已分开"
        "REPAIRING" -> "修复中"
        else -> "熟悉中"
    }

    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()
            .removePrefix("~~~json")
            .removePrefix("~~~")
            .removeSuffix("~~~")
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")

    private companion object {
        const val MAX_MESSAGE_CHARS = 2_000
        val ALLOWED_REPLY_LENGTHS = setOf("short", "medium", "long", "mixed")
        val ALLOWED_STAGES = setOf(
            "NEW", "FAMILIAR", "AMBIGUOUS", "DATING", "COMMITTED",
            "CONFLICT", "COOLING", "SEPARATED", "REPAIRING",
        )
        val EXPLICIT_STAGE_SIGNAL = Regex(
            """在一起|确定关系|确认关系|正式交往|暧昧|约会中|分手|分开了|复合|冷战|闹矛盾|订婚|结婚|离婚|同居|前任""",
        )
    }
}
