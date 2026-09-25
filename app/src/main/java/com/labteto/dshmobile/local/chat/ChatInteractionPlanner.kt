package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    val activeGoal: String = "",
    val currentAgenda: String = "",
    val internalConflict: String = "",
    val immediateConcern: String = "",
    val unresolvedThreads: List<String> = emptyList(),
    val initiative: Int = 50,
    val shareDesire: Int = 50,
    val dynamics: RelationshipDynamics = RelationshipDynamics(),
    val userPattern: UserChatPattern = UserChatPattern(),
    val narrativeDirection: ChatNarrativeDirection? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class ChatNarrativeDirection(
    val label: String,
    val guidance: String,
)

@Serializable
data class ChatReplySuggestion(
    val label: String,
    // Directly sendable user draft. Defaults keep older saved sessions decodable.
    val text: String = "",
    val style: String = "",
    val bold: Boolean = false,
    // Legacy story-direction fields are retained only for session compatibility.
    val direction: String = "",
    val impact: String = "",
)

@Serializable
data class ChatPostTurnPlan(
    val state: ChatCharacterState = ChatCharacterState(),
    val suggestions: List<ChatReplySuggestion> = emptyList(),
    val turnSignificance: String = "MINOR",
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
        appendLine("你负责维护角色聊天的隐藏关系状态、证据账本、用户沟通习惯，并为用户生成下一句可直接编辑发送的回复建议。")
        appendLine("不要继续扮演角色，不要解释过程，不要使用 Markdown，只输出一个 JSON 对象。")
        appendLine("角色：${persona.name}")
        if (persona.personality.isNotBlank()) appendLine("性格：${persona.personality}")
        if (persona.coreMotivations.isNotEmpty()) appendLine("核心动机：${persona.coreMotivations.joinToString("；")}")
        if (persona.behaviorPatterns.isNotEmpty()) appendLine("稳定行为：${persona.behaviorPatterns.joinToString("；")}")
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
        appendLine("当前目标=${state.activeGoal}")
        appendLine("行动倾向=${state.currentAgenda}")
        appendLine("内在矛盾=${state.internalConflict}")
        appendLine("眼下最在意=${state.immediateConcern}")
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
            """{"state":{"mood":"简短情绪","relationshipState":"自然语言关系描述","currentFocus":"当前关注","recentImpression":"近期印象","activeGoal":"角色眼下真正想达成什么","currentAgenda":"准备如何行动或回应","internalConflict":"当前内在拉扯，没有则空字符串","immediateConcern":"眼下最在意的人或事","unresolvedThreads":["最多3条"],"initiative":0,"shareDesire":0,"dynamics":{"stage":"FAMILIAR","warmth":0,"trust":0,"reciprocity":0,"tension":0,"stability":0,"unresolvedConflict":"","facts":[{"text":"明确事实","confidence":95,"source":"user"}],"hypotheses":[{"text":"暂定推测","confidence":60,"source":"inference"}],"unknowns":["关键未知"],"sharedMoments":["真实共同经历"]},"userPattern":{"replyLength":"mixed","directness":50,"playfulness":50,"initiative":50,"emojiStyle":"","preferredTone":""}},"suggestions":[{"label":"2到6字短标签","style":"自然/俏皮/直球/放飞","text":"用户可以直接发送的下一句","bold":false}],"turnSignificance":"NONE|MINOR|MAJOR"}""",
        )
        appendLine("要求：")
        appendLine("1. stage 只能取 NEW / FAMILIAR / AMBIGUOUS / DATING / COMMITTED / CONFLICT / COOLING / SEPARATED / REPAIRING。")
        appendLine("2. facts 只记录本轮明确说出或明确发生、以后仍有价值的事实；尽量保留原话关键短语，便于证据核对；不得把动机、爱意、依恋类型、人格猜测写进 facts。")
        appendLine("3. hypotheses 专门放暂定解释，必须给置信度；证据不足就放 unknowns，不为完整感硬猜。")
        appendLine("4. warmth/trust/reciprocity/tension/stability 每轮通常只小幅变化；普通一句话禁止关系数值剧烈跳变。")
        appendLine("5. stage 只有出现明确关系事件或连续强证据时才建议变化；一次回复慢、一个表情、一次冷淡都不足以改阶段。")
        appendLine("6. sharedMoments 只保留双方真实发生且以后值得自然提起的共同经历，禁止虚构。")
        appendLine("7. userPattern 只从用户真实表达习惯渐进学习，不因单轮异常表达彻底改画像。")
        appendLine("8. 角色本轮回复只要存在自然接话空间，就生成4条建议；极少数确实无话可接的收尾场景允许返回空数组。")
        appendLine("9. 4条建议必须明显不同，优先覆盖自然、俏皮、直球、放飞四种风格；禁止只是换几个词的同义改写。")
        appendLine("10. 每组建议至少保留1条 bold=true 的放飞选项。它可以明显大胆、夸张、荒诞、反常规、突然直球或带梗；关系允许时可以带暧昧挑逗，关系不熟时优先用反差和脑洞制造趣味。始终贴合当前情景，不能凭空捏造事实、替用户作重大或不可逆决定。")
        appendLine("11. text 必须是用户对角色说的话，并直接回应角色刚刚那句；尽量抓住最近对话里的具体物件、动作、称呼或情绪，不写放到任何聊天都成立的万能句。")
        appendLine("12. 根据 userPattern 控制长度、直接度、玩笑感和表情习惯；四条可以有不同力度，但都要像同一个用户在不同心情下会说的话。")
        appendLine("13. label 只概括这一条的感觉或打法，style 只用自然/俏皮/直球/放飞；bold 只表示这条更出格有趣，不代表自动发送。")
        appendLine("14. 用户实际发言和明确纠正始终优先；不得虚构已发生的事实或强制关系升级。现实关系军师/代回场景中，不生成跟踪、胁迫、欺骗性操控或绕过明确拒绝的建议。")
        appendLine("15. turnSignificance 只能是 NONE / MINOR / MAJOR。寒暄、纯表情、重复问候或没有新信息通常为 NONE；短期情绪/关注变化为 MINOR；承诺、关系事件、重大共同经历、稳定人物事实才可为 MAJOR。")
        appendLine("16. NONE 时人物状态保持原样，不为完整感编造新的情绪、关系、目标或记忆；但只要角色回复仍有自然接话空间，回复建议照常生成。")
        appendLine("17. activeGoal/currentAgenda/internalConflict/immediateConcern 描述角色此刻的内在驱动，不凭空制造阴谋、爱意或模型分析口吻。")
    }.trim()

    fun parse(
        text: String,
        previous: ChatCharacterState,
        userMessage: String = "",
        assistantMessage: String = "",
    ): ChatPostTurnPlan? {
        val body = extractJsonObject(text) ?: return null
        val root = runCatching {
            json.parseToJsonElement(body).jsonObject
        }.getOrNull() ?: return null
        val decoded = runCatching {
            json.decodeFromString(ChatPostTurnPlan.serializer(), body)
        }.getOrNull() ?: return null
        val rawState = root["state"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val significance = normalizeSignificance(decoded.turnSignificance)

        return decoded.copy(
            state = if (significance == "NONE") {
                previous
            } else {
                sanitizeState(
                    value = decoded.state,
                    previous = previous,
                    userMessage = userMessage,
                    assistantMessage = assistantMessage,
                    rawState = rawState,
                )
            },
            suggestions = decoded.suggestions.asSequence()
                .map { suggestion ->
                    val style = suggestion.style.trim().take(12)
                    ChatReplySuggestion(
                        label = suggestion.label.trim().take(12),
                        text = suggestion.text.trim().take(320),
                        style = style,
                        bold = suggestion.bold || style == "放飞",
                        direction = suggestion.direction.trim().take(200),
                        impact = suggestion.impact.trim().take(120),
                    )
                }
                .filter { it.label.isNotBlank() && it.text.isNotBlank() }
                .distinctBy { normalize(it.text) }
                .take(4)
                .toList(),
            turnSignificance = significance,
        )
    }

    private fun sanitizeState(
        value: ChatCharacterState,
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
        rawState: JsonObject?,
    ): ChatCharacterState {
        val rawDynamics = rawState?.get("dynamics")?.let { runCatching { it.jsonObject }.getOrNull() }
        val rawPattern = rawState?.get("userPattern")?.let { runCatching { it.jsonObject }.getOrNull() }
        val dynamics = if (rawDynamics == null) {
            previous.dynamics
        } else {
            sanitizeDynamics(
                value = value.dynamics,
                previous = previous.dynamics,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                raw = rawDynamics,
            )
        }
        val pattern = if (rawPattern == null && userMessage.isBlank()) {
            previous.userPattern
        } else {
            sanitizeUserPattern(
                value = value.userPattern,
                previous = previous.userPattern,
                userMessage = userMessage,
                raw = rawPattern,
            )
        }
        val requestedStage = if (rawDynamics?.containsKey("stage") == true) {
            normalizeStage(value.dynamics.stage)
        } else previous.dynamics.stage
        val relationshipDescription = when {
            dynamics.stage != previous.dynamics.stage -> stageLabel(dynamics.stage)
            requestedStage != previous.dynamics.stage -> previous.relationshipState
            rawState?.containsKey("relationshipState") == true ->
                value.relationshipState.trim().take(120).ifBlank { previous.relationshipState }
            else -> previous.relationshipState
        }
        return value.copy(
            mood = if (rawState?.containsKey("mood") == true) {
                value.mood.trim().take(80).ifBlank { previous.mood }
            } else previous.mood,
            relationshipState = relationshipDescription,
            currentFocus = if (rawState?.containsKey("currentFocus") == true) {
                value.currentFocus.trim().take(240)
            } else previous.currentFocus,
            recentImpression = if (rawState?.containsKey("recentImpression") == true) {
                value.recentImpression.trim().take(320)
            } else previous.recentImpression,
            activeGoal = if (rawState?.containsKey("activeGoal") == true) {
                value.activeGoal.trim().take(240)
            } else previous.activeGoal,
            currentAgenda = if (rawState?.containsKey("currentAgenda") == true) {
                value.currentAgenda.trim().take(240)
            } else previous.currentAgenda,
            internalConflict = if (rawState?.containsKey("internalConflict") == true) {
                value.internalConflict.trim().take(240)
            } else previous.internalConflict,
            immediateConcern = if (rawState?.containsKey("immediateConcern") == true) {
                value.immediateConcern.trim().take(240)
            } else previous.immediateConcern,
            unresolvedThreads = if (rawState?.containsKey("unresolvedThreads") == true) {
                value.unresolvedThreads.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map { it.take(200) }
                    .distinct()
                    .take(3)
                    .toList()
            } else previous.unresolvedThreads,
            initiative = if (rawState?.containsKey("initiative") == true) {
                bounded(value.initiative, previous.initiative, 15)
            } else previous.initiative,
            shareDesire = if (rawState?.containsKey("shareDesire") == true) {
                bounded(value.shareDesire, previous.shareDesire, 15)
            } else previous.shareDesire,
            dynamics = dynamics,
            userPattern = pattern,
            narrativeDirection = null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun sanitizeDynamics(
        value: RelationshipDynamics,
        previous: RelationshipDynamics,
        userMessage: String,
        assistantMessage: String,
        raw: JsonObject,
    ): RelationshipDynamics {
        val candidateStage = if (raw.containsKey("stage")) normalizeStage(value.stage) else previous.stage
        val explicitStageEvidence = EXPLICIT_STAGE_SIGNAL.containsMatchIn(userMessage)
        val stage = when {
            candidateStage == previous.stage -> previous.stage
            explicitStageEvidence -> candidateStage
            else -> previous.stage
        }

        return RelationshipDynamics(
            stage = stage,
            warmth = if (raw.containsKey("warmth")) bounded(value.warmth, previous.warmth, 10) else previous.warmth,
            trust = if (raw.containsKey("trust")) bounded(value.trust, previous.trust, 8) else previous.trust,
            reciprocity = if (raw.containsKey("reciprocity")) bounded(value.reciprocity, previous.reciprocity, 8) else previous.reciprocity,
            tension = if (raw.containsKey("tension")) bounded(value.tension, previous.tension, 12) else previous.tension,
            stability = if (raw.containsKey("stability")) bounded(value.stability, previous.stability, 8) else previous.stability,
            unresolvedConflict = if (raw.containsKey("unresolvedConflict")) {
                value.unresolvedConflict.trim().take(240)
            } else previous.unresolvedConflict,
            facts = if (raw.containsKey("facts")) mergeEvidence(
                previous = previous.facts,
                incoming = value.facts.filter {
                    evidenceGrounded(
                        evidence = it,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                    )
                },
                minimumConfidence = 80,
                maximumConfidence = 100,
                allowedSources = FACT_SOURCES,
                limit = 12,
            ) else previous.facts,
            hypotheses = if (raw.containsKey("hypotheses")) mergeEvidence(
                previous = previous.hypotheses,
                incoming = value.hypotheses,
                minimumConfidence = 10,
                maximumConfidence = 85,
                allowedSources = null,
                limit = 6,
            ) else previous.hypotheses,
            unknowns = if (raw.containsKey("unknowns")) {
                mergeStrings(previous.unknowns, value.unknowns, 6, 160)
            } else previous.unknowns,
            sharedMoments = if (raw.containsKey("sharedMoments")) {
                mergeStrings(previous.sharedMoments, value.sharedMoments, 8, 180)
            } else previous.sharedMoments,
        )
    }

    private fun sanitizeUserPattern(
        value: UserChatPattern,
        previous: UserChatPattern,
        userMessage: String,
        raw: JsonObject?,
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
            .takeIf { raw?.containsKey("replyLength") == true && it in ALLOWED_REPLY_LENGTHS }
        val length = measuredReplyLength ?: modelLength ?: previous.replyLength

        return value.copy(
            replyLength = length,
            directness = if (raw?.containsKey("directness") == true) {
                bounded(value.directness, previous.directness, 10)
            } else previous.directness,
            playfulness = if (raw?.containsKey("playfulness") == true) {
                bounded(value.playfulness, previous.playfulness, 10)
            } else previous.playfulness,
            initiative = if (raw?.containsKey("initiative") == true) {
                bounded(value.initiative, previous.initiative, 10)
            } else previous.initiative,
            emojiStyle = if (raw?.containsKey("emojiStyle") == true) {
                value.emojiStyle.trim().take(120)
            } else previous.emojiStyle,
            preferredTone = if (raw?.containsKey("preferredTone") == true) {
                value.preferredTone.trim().take(120)
            } else previous.preferredTone,
            observedTurns = if (hasObservation) (previous.observedTurns + 1).coerceAtMost(1000)
                else previous.observedTurns,
            averageMessageChars = averageChars.coerceIn(0, 2_000),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun evidenceGrounded(
        evidence: RelationshipEvidence,
        userMessage: String,
        assistantMessage: String,
    ): Boolean {
        val source = evidence.source.trim().lowercase()
        val evidenceText = normalize(evidence.text)
        if (evidenceText.length < 2) return false

        val sourceText = when (source) {
            "user", "explicit" -> normalize(userMessage)
            "observed", "dialogue" -> normalize(userMessage + assistantMessage)
            else -> return false
        }
        if (sourceText.length < 2) return false
        if (sourceText.contains(evidenceText) || evidenceText.contains(sourceText)) return true

        val evidenceBigrams = bigrams(evidenceText)
        val sourceBigrams = bigrams(sourceText)
        if (evidenceBigrams.isEmpty() || sourceBigrams.isEmpty()) return false
        val shared = evidenceBigrams.count(sourceBigrams::contains)
        val ratio = shared.toDouble() / evidenceBigrams.size
        return shared >= 2 && ratio >= 0.25
    }

    private fun bigrams(text: String): Set<String> =
        if (text.length < 2) emptySet()
        else (0 until text.length - 1).mapTo(linkedSetOf()) { index ->
            text.substring(index, index + 2)
        }

    private fun mergeEvidence(
        previous: List<RelationshipEvidence>,
        incoming: List<RelationshipEvidence>,
        minimumConfidence: Int,
        maximumConfidence: Int,
        allowedSources: Set<String>?,
        limit: Int,
    ): List<RelationshipEvidence> {
        val merged = linkedMapOf<String, RelationshipEvidence>()
        (previous + incoming).forEach { item ->
            val text = item.text.trim().take(220)
            if (text.isBlank()) return@forEach
            val source = item.source.trim().lowercase().take(40)
            if (allowedSources != null && source !in allowedSources) return@forEach
            val confidence = item.confidence.coerceIn(minimumConfidence, maximumConfidence)
            if (item.confidence < minimumConfidence) return@forEach
            val clean = item.copy(
                text = text,
                confidence = confidence,
                source = source,
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

    private fun normalizeSignificance(raw: String): String =
        raw.trim().uppercase().takeIf { it in ALLOWED_SIGNIFICANCE } ?: "MINOR"

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
        val ALLOWED_SIGNIFICANCE = setOf("NONE", "MINOR", "MAJOR")
        val FACT_SOURCES = setOf("user", "observed", "dialogue", "explicit")
        val ALLOWED_STAGES = setOf(
            "NEW", "FAMILIAR", "AMBIGUOUS", "DATING", "COMMITTED",
            "CONFLICT", "COOLING", "SEPARATED", "REPAIRING",
        )
        val EXPLICIT_STAGE_SIGNAL = Regex(
            """在一起|确定关系|确认关系|正式交往|暧昧|约会中|分手|分开了|复合|冷战|闹矛盾|订婚|结婚|离婚|同居|前任""",
        )
    }
}
