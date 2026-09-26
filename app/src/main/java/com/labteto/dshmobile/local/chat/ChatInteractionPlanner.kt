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
    val interactionIntent: String = ChatInteractionIntent.NORMAL.name,
    val interactionIntentStrength: Int = 0,
    /** Per-field age in completed turns. Used to expire short-lived roleplay state. */
    val transientAges: Map<String, Int> = emptyMap(),
    /** Open-thread age keyed by normalized thread text. */
    val unresolvedThreadAges: Map<String, Int> = emptyMap(),
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
        appendLine("更新角色隐藏状态，并生成用户下一句可直接发送的回复建议。只输出 JSON，不解释。")
        appendLine("角色=${persona.name}" +
            persona.personality.takeIf(String::isNotBlank)?.let { "｜性格=$it" }.orEmpty() +
            persona.relationship.takeIf(String::isNotBlank)?.let { "｜关系=$it" }.orEmpty())
        if (persona.coreMotivations.isNotEmpty()) {
            appendLine("动机：${persona.coreMotivations.joinToString("；")}")
        }
        if (persona.behaviorPatterns.isNotEmpty()) {
            appendLine("稳定行为：${persona.behaviorPatterns.joinToString("；")}")
        }

        appendLine(
            "上一状态：情绪=${state.mood}｜关系=${state.relationshipState}｜阶段=${state.dynamics.stage}｜" +
                "温度=${state.dynamics.warmth} 信任=${state.dynamics.trust} 互惠=${state.dynamics.reciprocity} " +
                "张力=${state.dynamics.tension} 稳定=${state.dynamics.stability}｜主动=${state.initiative} 分享=${state.shareDesire}",
        )
        state.currentFocus.takeIf(String::isNotBlank)?.let { appendLine("关注：$it") }
        state.recentImpression.takeIf(String::isNotBlank)?.let { appendLine("近期印象：$it") }
        state.activeGoal.takeIf(String::isNotBlank)?.let { appendLine("目标：$it") }
        state.currentAgenda.takeIf(String::isNotBlank)?.let { appendLine("行动：$it") }
        state.internalConflict.takeIf(String::isNotBlank)?.let { appendLine("内在拉扯：$it") }
        state.immediateConcern.takeIf(String::isNotBlank)?.let { appendLine("在意：$it") }
        if (state.unresolvedThreads.isNotEmpty()) appendLine("未完：${state.unresolvedThreads.joinToString("；")}")
        appendLine(
            "用户习惯：长度=${state.userPattern.replyLength}｜直接=${state.userPattern.directness}｜" +
                "玩笑=${state.userPattern.playfulness}｜主动=${state.userPattern.initiative}",
        )
        if (state.dynamics.facts.isNotEmpty()) {
            appendLine("事实：${state.dynamics.facts.joinToString("；") { it.text }}")
        }
        if (state.dynamics.hypotheses.isNotEmpty()) {
            appendLine("推测：${state.dynamics.hypotheses.joinToString("；") { it.text }}")
        }
        if (state.dynamics.unknowns.isNotEmpty()) appendLine("未知：${state.dynamics.unknowns.joinToString("；")}")
        if (state.dynamics.sharedMoments.isNotEmpty()) {
            appendLine("共同经历：${state.dynamics.sharedMoments.joinToString("；")}")
        }

        appendLine("用户：${userMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine("角色：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
        if (hasAdultIntimacyIntent(userMessage, state)) {
            appendLine("本轮为成年角色亲密互动：建议顺着用户明确方向保持连续，不重复确认，不被普通聊天规则降级或转移。")
        }

        appendLine("输出：{\"state\":{仅写变化字段},\"suggestions\":[{\"label\":\"\",\"style\":\"自然|俏皮|直球|放飞\",\"text\":\"\",\"bold\":false}],\"turnSignificance\":\"NONE|MINOR|MAJOR\"}")
        appendLine("state 可用字段：mood, relationshipState, currentFocus, recentImpression, activeGoal, currentAgenda, internalConflict, immediateConcern, unresolvedThreads, initiative, shareDesire；dynamics(stage,warmth,trust,reciprocity,tension,stability,unresolvedConflict,facts,hypotheses,unknowns,sharedMoments)；userPattern(replyLength,directness,playfulness,initiative,emojiStyle,preferredTone)。短期字段省略时会自然衰减；已经解决时请显式写空字符串或空数组。")
        appendLine("规则：")
        appendLine("1. facts 只放明确事实；hypotheses 放带置信度的暂定解释；证据不足放 unknowns；sharedMoments 只写真正共同经历。")
        appendLine("2. 数值与用户画像渐进变化；stage 仅在明确关系事件或连续强证据下改变。stage 只用 NEW/FAMILIAR/AMBIGUOUS/DATING/COMMITTED/CONFLICT/COOLING/SEPARATED/REPAIRING。")
        appendLine("3. NONE=无新状态，MINOR=短期变化，MAJOR=承诺、关系事件、重大共同经历或稳定人物事实；NONE 时 state 可为空。")
        appendLine("4. 有自然接话空间时给4条明显不同建议，优先自然/俏皮/直球/放飞，至少1条 bold=true；直接回应角色刚才那句并贴合用户真实说话习惯，禁止同义改写凑数。")
        appendLine("5. 用户实际发言和明确纠正优先；不虚构事实、不替用户作重大不可逆决定。现实关系军师场景禁止跟踪、胁迫、欺骗操控或绕过明确拒绝。")
        appendLine("6. activeGoal/currentAgenda/internalConflict/immediateConcern 只写角色当下真实驱动，不凭空制造阴谋、爱意或分析腔。")
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

        val agedPrevious = ageTransientState(previous, userMessage)
        return decoded.copy(
            state = applyInteractionIntent(
                state = if (significance == "NONE") {
                    agedPrevious
                } else {
                    sanitizeState(
                        value = decoded.state,
                        previous = agedPrevious,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        rawState = rawState,
                    )
                },
                previous = agedPrevious,
                userMessage = userMessage,
            ),
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

    private fun applyInteractionIntent(
        state: ChatCharacterState,
        previous: ChatCharacterState,
        userMessage: String,
    ): ChatCharacterState {
        val (intent, strength) = nextInteractionIntentState(userMessage, previous)
        return state.copy(
            interactionIntent = intent,
            interactionIntentStrength = strength,
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
        val merged = value.copy(
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
            transientAges = previous.transientAges,
            unresolvedThreadAges = previous.unresolvedThreadAges,
            updatedAt = System.currentTimeMillis(),
        )
        return resetUpdatedAges(merged, rawState)
    }

    private fun ageTransientState(
        previous: ChatCharacterState,
        userMessage: String,
    ): ChatCharacterState {
        val topicReset = TOPIC_RESET_HINTS.any { userMessage.contains(it) }
        val nextAges = previous.transientAges.toMutableMap()

        fun age(key: String, value: String, ttl: Int, clearOnTopicReset: Boolean = false): String {
            if (value.isBlank()) {
                nextAges.remove(key)
                return ""
            }
            if (topicReset && clearOnTopicReset) {
                nextAges.remove(key)
                return ""
            }
            val next = (nextAges[key] ?: 0) + 1
            return if (next > ttl) {
                nextAges.remove(key)
                ""
            } else {
                nextAges[key] = next
                value
            }
        }

        val nextThreadAges = previous.unresolvedThreadAges.toMutableMap()
        val threads = if (topicReset) {
            nextThreadAges.clear()
            emptyList()
        } else {
            previous.unresolvedThreads.filter { thread ->
                val key = normalize(thread)
                val next = (nextThreadAges[key] ?: 0) + 1
                if (next > THREAD_TTL) {
                    nextThreadAges.remove(key)
                    false
                } else {
                    nextThreadAges[key] = next
                    true
                }
            }
        }

        return previous.copy(
            currentFocus = age("currentFocus", previous.currentFocus, 3, clearOnTopicReset = true),
            recentImpression = age("recentImpression", previous.recentImpression, 5),
            activeGoal = age("activeGoal", previous.activeGoal, 12),
            currentAgenda = age("currentAgenda", previous.currentAgenda, 3, clearOnTopicReset = true),
            internalConflict = age("internalConflict", previous.internalConflict, 6),
            immediateConcern = age("immediateConcern", previous.immediateConcern, 2, clearOnTopicReset = true),
            unresolvedThreads = threads,
            transientAges = nextAges,
            unresolvedThreadAges = nextThreadAges,
        )
    }

    private fun resetUpdatedAges(
        state: ChatCharacterState,
        rawState: JsonObject?,
    ): ChatCharacterState {
        if (rawState == null) return state
        val ages = state.transientAges.toMutableMap()

        fun reset(key: String, value: String) {
            if (!rawState.containsKey(key)) return
            if (value.isBlank()) ages.remove(key) else ages[key] = 0
        }

        reset("currentFocus", state.currentFocus)
        reset("recentImpression", state.recentImpression)
        reset("activeGoal", state.activeGoal)
        reset("currentAgenda", state.currentAgenda)
        reset("internalConflict", state.internalConflict)
        reset("immediateConcern", state.immediateConcern)

        val threadAges = if (rawState.containsKey("unresolvedThreads")) {
            state.unresolvedThreads.associate { normalize(it) to 0 }
        } else {
            state.unresolvedThreadAges
        }
        return state.copy(
            transientAges = ages,
            unresolvedThreadAges = threadAges,
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
        val TOPIC_RESET_HINTS = listOf(
            "换个话题", "先不聊这个", "不聊这个", "别提这个", "别再提", "说正事", "算了", "到此为止",
        )
        const val THREAD_TTL = 6
        val EXPLICIT_STAGE_SIGNAL = Regex(
            """在一起|确定关系|确认关系|正式交往|暧昧|约会中|分手|分开了|复合|冷战|闹矛盾|订婚|结婚|离婚|同居|前任""",
        )
    }
}
