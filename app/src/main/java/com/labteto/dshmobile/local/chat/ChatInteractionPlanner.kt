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
data class ChatSceneState(
    val sceneTime: String = "",
    val location: String = "",
    val participants: List<String> = emptyList(),
    val positions: List<String> = emptyList(),
    val activeActions: List<String> = emptyList(),
    val keyObjects: List<String> = emptyList(),
    val currentEvent: String = "",
    val lastSceneChange: String = "",
)

@Serializable
enum class ChatContinuityFactKind {
    EVENT,
    DECISION,
    OPEN_THREAD,
}

@Serializable
data class ChatContinuityEvidence(
    val kind: ChatContinuityFactKind = ChatContinuityFactKind.EVENT,
    val text: String = "",
    val sourceSequence: Long = 0L,
    val sourceUserMessageId: String = "",
    val sourceAssistantMessageId: String = "",
    val evidence: String = "",
)

@Serializable
data class ChatContinuityState(
    val recentEvents: List<String> = emptyList(),
    val recurringEvents: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val unfinished: List<String> = emptyList(),
    /**
     * System-owned provenance for the active soft continuity facts above.
     * Old persisted states decode with an empty list and are gradually grounded by later turns.
     */
    val evidence: List<ChatContinuityEvidence> = emptyList(),
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
    val evolution: CharacterEvolutionState = CharacterEvolutionState(),
    val userPattern: UserChatPattern = UserChatPattern(),
    val scene: ChatSceneState = ChatSceneState(),
    val continuity: ChatContinuityState = ChatContinuityState(),
    val narrativeDirection: ChatNarrativeDirection? = null,
    val interactionIntent: String = ChatInteractionIntent.NORMAL.name,
    val interactionIntentStrength: Int = 0,
    val interactionIntensity: Int = 0,
    val recentActionTags: List<String> = emptyList(),
    val recentPoseTags: List<String> = emptyList(),
    val recentVerbalTags: List<String> = emptyList(),
    val recentAddressTerms: List<String> = emptyList(),
    val interactionCooldowns: Map<String, Int> = emptyMap(),
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

@Serializable
data class ChatReplySuggestionPlan(
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
        appendLine("更新角色隐藏状态，只输出 JSON。")
        appendLine("角色=${persona.name}" +
            persona.personality.takeIf(String::isNotBlank)?.let { "｜性格=$it" }.orEmpty() +
            persona.relationship.takeIf(String::isNotBlank)?.let { "｜关系=$it" }.orEmpty())
        if (persona.coreMotivations.isNotEmpty()) {
            appendLine("动机：${persona.coreMotivations.joinToString("；")}")
        }
        if (persona.behaviorPatterns.isNotEmpty()) {
            appendLine("稳定行为：${persona.behaviorPatterns.joinToString("；")}")
        }
        appendPersonaExpressionContext(persona)
        appendCharacterEvolutionContext(state.evolution)

        appendLine(
            "上一状态：情绪=${state.mood}｜关系=${state.relationshipState}｜阶段=${state.dynamics.stage}｜" +
                "温度=${state.dynamics.warmth} 信任=${state.dynamics.trust} 互惠=${state.dynamics.reciprocity} " +
                "张力=${state.dynamics.tension} 稳定=${state.dynamics.stability}｜主动=${state.initiative} 分享=${state.shareDesire}",
        )
        if (state.interactionIntensity > 0 || state.recentVerbalTags.isNotEmpty() || state.recentActionTags.isNotEmpty()) {
            appendLine(
                "互动：意图=${state.interactionIntent}｜强度=${state.interactionIntensity}/5｜" +
                    "近期动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                    "近期话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}",
            )
        }
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
        if (state.scene.sceneTime.isNotBlank() || state.scene.location.isNotBlank()) {
            appendLine(
                "当前硬场景：时间=${state.scene.sceneTime.ifBlank { "未知" }}｜地点=${state.scene.location.ifBlank { "未知" }}",
            )
        }
        if (state.continuity.recentEvents.isNotEmpty()) {
            appendLine("近期事件：${state.continuity.recentEvents.joinToString("；")}")
        }
        if (state.continuity.decisions.isNotEmpty()) {
            appendLine("已定事项：${state.continuity.decisions.joinToString("；")}")
        }
        if (state.continuity.unfinished.isNotEmpty()) {
            appendLine("待续事项：${state.continuity.unfinished.joinToString("；")}")
        }

        appendLine("用户：${userMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine("角色：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
        if (hasFlirtingOrIntimateIntent(userMessage, state)) {
            appendLine("当前为亲密互动：按上下文保留互动连续性；双关不当作新事实。")
        }
        if (hasAdultIntimacyIntent(userMessage, state)) {
            appendLine("当前意图明确，状态更新按实际互动推进，不重复确认或自行转移。")
        }

        appendLine("输出：{\"state\":{仅写变化字段},\"suggestions\":[],\"turnSignificance\":\"NONE|MINOR|MAJOR\"}")
        appendLine("state字段：mood, relationshipState, currentFocus, recentImpression, activeGoal, currentAgenda, internalConflict, immediateConcern, unresolvedThreads, initiative, shareDesire；dynamics(stage,warmth,trust,reciprocity,tension,stability,unresolvedConflict,facts,hypotheses,unknowns,sharedMoments)；userPattern(replyLength,directness,playfulness,initiative,emojiStyle,preferredTone)；continuity(recentEvents,decisions,unfinished)。")
        appendLine("规则：")
        appendLine("- 以真实对话和用户明确纠正为准；事实、推测、未知分开，不虚构。")
        appendLine("- 角色台词的模糊或习惯性推辞不得单独触发关系降温、冲突或边界状态；结合人设纠正、关系阶段和连续动作判断。针对当前行为的清晰明确停止、退出或拒绝继续除外；此类边界一次表达即生效，不要求重复。")
        appendLine("- 状态与用户画像渐进变化；stage 仅在明确事件或持续强证据下变化，取 NEW/FAMILIAR/AMBIGUOUS/DATING/COMMITTED/CONFLICT/COOLING/SEPARATED/REPAIRING。")
        appendLine("- evolution 为系统维护的长期人物演变层，只由多轮稳定信号渐进更新；模型不得输出、覆盖或重置。")
        appendLine("- 已失效短期状态显式清空；scene 和 interaction* 由系统维护，不输出。")
        appendLine("- continuity 只保留当前有效的关键事件、决定和待续事项，合并重复，不复制旧台词。")
        appendLine("- NONE=无有效变化；MINOR=短期或连续性变化；MAJOR=重大关系事件、共同经历或稳定事实。")
        appendLine("- suggestions 固定为空；不得替用户作重大决定。现实关系建议禁止跟踪、胁迫、欺骗操控或绕过明确拒绝。")
    }.trim()

    fun suggestionsPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
        recentDialogue: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        appendLine("根据最近对话生成用户下一句可直接发送的建议，只输出 JSON。")
        appendLine(
            "角色=${persona.name}" +
                persona.personality.takeIf(String::isNotBlank)?.let { "｜性格=$it" }.orEmpty() +
                persona.relationship.takeIf(String::isNotBlank)?.let { "｜关系=$it" }.orEmpty(),
        )
        appendPersonaExpressionContext(persona)
        appendLine(
            "当前关系=${state.relationshipState}｜阶段=${state.dynamics.stage}｜" +
                "用户习惯：长度=${state.userPattern.replyLength}｜直接=${state.userPattern.directness}｜" +
                "玩笑=${state.userPattern.playfulness}｜主动=${state.userPattern.initiative}",
        )
        if (state.interactionIntensity > 0 || state.recentVerbalTags.isNotEmpty() || state.recentActionTags.isNotEmpty()) {
            appendLine(
                "互动状态：意图=${state.interactionIntent}｜强度=${state.interactionIntensity}/5｜" +
                    "近期动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                    "近期话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}｜" +
                    "近期称呼=${state.recentAddressTerms.joinToString("、").ifBlank { "无" }}",
            )
        }
        val dialogue = recentDialogue
            .filter { (role, content) ->
                (role == "user" || role == "assistant") && content.isNotBlank()
            }
            .takeLast(6)
        if (dialogue.isNotEmpty()) {
            appendLine("最近对话（旧→新）：")
            dialogue.forEach { (role, content) ->
                val speaker = if (role == "user") "用户" else "角色"
                appendLine("${speaker}：${content.take(MAX_MESSAGE_CHARS)}")
            }
        } else {
            appendLine("用户：${userMessage.take(MAX_MESSAGE_CHARS)}")
            appendLine("角色：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
        }
        if (hasFlirtingOrIntimateIntent(userMessage, state)) {
            appendLine("当前为亲密语境：建议承接当前互动，避免复用近期动作、称呼和表达套路。")
        }
        if (hasChineseSuggestiveFlirtingIntent(userMessage)) {
            appendLine("存在中文双关时接住言外之意，不做词义解释。")
        }
        appendLine(
            "输出：{\"suggestions\":[{\"label\":\"2到6字短标签\",\"style\":\"自然|俏皮|直球|放飞\",\"text\":\"用户可直接发送的下一句\",\"bold\":false}]}",
        )
        appendLine("规则：")
        appendLine("- 给4条明显不同的建议，明确覆盖自然、俏皮、直球、放飞；至少1条 bold=true。")
        appendLine("- 最新1～2轮和当前状态优先；已结束、拒绝或被纠正的话题不得复活。")
        appendLine("- 若人设或用户纠正明确角色会嘴硬、害羞、别扭或服软，建议承接这种表达节奏，不把模糊或习惯性推辞自动解释为关系拒绝；针对当前行为的清晰明确停止、退出或拒绝继续一次即生效。")
        appendLine("- 直接承接角色最后一句，贴合用户表达习惯和当前关系。")
        appendLine("- 不用同义改写凑数，不虚构事实，不替用户作重大决定。")
        appendLine("- 现实关系建议不得包含跟踪、胁迫、欺骗操控或绕过明确拒绝。")
    }.trim()

    fun parseSuggestions(text: String): List<ChatReplySuggestion>? {
        val body = extractJsonObject(text) ?: return null
        val decoded = runCatching {
            json.decodeFromString(ChatReplySuggestionPlan.serializer(), body)
        }.getOrNull() ?: return null
        return decoded.suggestions.asSequence()
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
            .toList()
    }

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
        val baseState = if (significance == "NONE") {
            applyExplicitTransientClears(
                value = decoded.state,
                previous = agedPrevious,
                rawState = rawState,
            )
        } else {
            sanitizeState(
                value = decoded.state,
                previous = agedPrevious,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                rawState = rawState,
            )
        }
        val continuityState = mergeSceneAndContinuity(
            value = decoded.state,
            previous = baseState,
            rawState = rawState,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        )
        val evolvedState = continuityState.copy(evolution = evolveCharacterEvolution(agedPrevious, continuityState, significance))
        return decoded.copy(
            state = applyInteractionPerformance(
                state = applyInteractionIntent(
                    state = evolvedState,
                    previous = agedPrevious,
                    userMessage = userMessage,
                ),
                previous = agedPrevious,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
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

    private fun applyExplicitTransientClears(
        value: ChatCharacterState,
        previous: ChatCharacterState,
        rawState: JsonObject?,
    ): ChatCharacterState {
        if (rawState == null) return previous

        val ages = previous.transientAges.toMutableMap()
        var changed = false

        fun shouldClear(key: String, value: String): Boolean =
            rawState.containsKey(key) && value.isBlank()

        val clearCurrentFocus = shouldClear("currentFocus", value.currentFocus)
        val clearRecentImpression = shouldClear("recentImpression", value.recentImpression)
        val clearActiveGoal = shouldClear("activeGoal", value.activeGoal)
        val clearCurrentAgenda = shouldClear("currentAgenda", value.currentAgenda)
        val clearInternalConflict = shouldClear("internalConflict", value.internalConflict)
        val clearImmediateConcern = shouldClear("immediateConcern", value.immediateConcern)
        val clearThreads = rawState.containsKey("unresolvedThreads") && value.unresolvedThreads.isEmpty()

        listOf(
            "currentFocus" to clearCurrentFocus,
            "recentImpression" to clearRecentImpression,
            "activeGoal" to clearActiveGoal,
            "currentAgenda" to clearCurrentAgenda,
            "internalConflict" to clearInternalConflict,
            "immediateConcern" to clearImmediateConcern,
        ).forEach { (key, clear) ->
            if (clear) {
                ages.remove(key)
                changed = true
            }
        }
        if (clearThreads) changed = true
        if (!changed) return previous

        return previous.copy(
            currentFocus = if (clearCurrentFocus) "" else previous.currentFocus,
            recentImpression = if (clearRecentImpression) "" else previous.recentImpression,
            activeGoal = if (clearActiveGoal) "" else previous.activeGoal,
            currentAgenda = if (clearCurrentAgenda) "" else previous.currentAgenda,
            internalConflict = if (clearInternalConflict) "" else previous.internalConflict,
            immediateConcern = if (clearImmediateConcern) "" else previous.immediateConcern,
            unresolvedThreads = if (clearThreads) emptyList() else previous.unresolvedThreads,
            transientAges = ages,
            unresolvedThreadAges = if (clearThreads) emptyMap() else previous.unresolvedThreadAges,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun applyDeterministicInteractionState(
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        val intentState = if (userMessage.isBlank()) {
            previous
        } else {
            applyInteractionIntent(
                state = previous,
                previous = previous,
                userMessage = userMessage,
            )
        }
        return applyInteractionPerformance(
            state = intentState,
            previous = previous,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
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
            interactionIntensity = nextInteractionIntensity(userMessage, previous),
        )
    }

    private fun applyInteractionPerformance(
        state: ChatCharacterState,
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        val signals = extractInteractionPerformanceSignals(assistantMessage)
        val cooldowns = previous.interactionCooldowns
            .mapValues { (_, turns) -> turns - 1 }
            .filterValues { it > 0 }
            .toMutableMap()

        fun cool(prefix: String, tags: List<String>, turns: Int) {
            tags.forEach { tag -> cooldowns["$prefix:$tag"] = turns }
        }

        cool("动作", signals.actionTags, ACTION_COOLDOWN_TURNS)
        cool("姿态", signals.poseTags, POSE_COOLDOWN_TURNS)
        cool("话术", signals.verbalTags, VERBAL_COOLDOWN_TURNS)
        cool("称呼", signals.addressTerms, ADDRESS_COOLDOWN_TURNS)

        val resetRequested = isInteractionResetIntent(userMessage)
        val assistantIntensity = if (resetRequested) 0 else assistantInitiatedInteractionIntensity(assistantMessage)
        val finalIntensity = if (resetRequested) 0 else maxOf(state.interactionIntensity, assistantIntensity)
        val finalIntent = when {
            resetRequested -> ChatInteractionIntent.NORMAL.name
            finalIntensity > 0 && state.interactionIntent == ChatInteractionIntent.NORMAL.name ->
                ChatInteractionIntent.FLIRTING.name
            else -> state.interactionIntent
        }
        val finalStrength = when {
            resetRequested -> 0
            finalIntent == ChatInteractionIntent.FLIRTING.name &&
                state.interactionIntent == ChatInteractionIntent.NORMAL.name ->
                maxOf(state.interactionIntentStrength, 2)
            else -> state.interactionIntentStrength
        }

        return state.copy(
            interactionIntent = finalIntent,
            interactionIntentStrength = finalStrength,
            interactionIntensity = finalIntensity,
            recentActionTags = mergeRecentTags(previous.recentActionTags, signals.actionTags, RECENT_ACTION_LIMIT),
            recentPoseTags = mergeRecentTags(previous.recentPoseTags, signals.poseTags, RECENT_POSE_LIMIT),
            recentVerbalTags = mergeRecentTags(previous.recentVerbalTags, signals.verbalTags, RECENT_VERBAL_LIMIT),
            recentAddressTerms = mergeRecentTags(previous.recentAddressTerms, signals.addressTerms, RECENT_ADDRESS_LIMIT),
            interactionCooldowns = if (finalIntensity == 0) emptyMap() else cooldowns.toMap(),
        )
    }

    private fun mergeRecentTags(
        previous: List<String>,
        incoming: List<String>,
        limit: Int,
    ): List<String> {
        if (incoming.isEmpty()) return previous.takeLast(limit)
        val merged = previous.toMutableList()
        incoming.forEach { tag ->
            merged.remove(tag)
            merged += tag
        }
        return merged.takeLast(limit)
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
            evolution = previous.evolution,
            userPattern = pattern,
            scene = previous.scene,
            continuity = previous.continuity,
            narrativeDirection = null,
            interactionIntent = previous.interactionIntent,
            interactionIntentStrength = previous.interactionIntentStrength,
            interactionIntensity = previous.interactionIntensity,
            recentActionTags = previous.recentActionTags,
            recentPoseTags = previous.recentPoseTags,
            recentVerbalTags = previous.recentVerbalTags,
            recentAddressTerms = previous.recentAddressTerms,
            interactionCooldowns = previous.interactionCooldowns,
            transientAges = previous.transientAges,
            unresolvedThreadAges = previous.unresolvedThreadAges,
            updatedAt = System.currentTimeMillis(),
        )
        return resetUpdatedAges(merged, rawState)
    }

    private fun mergeSceneAndContinuity(
        value: ChatCharacterState,
        previous: ChatCharacterState,
        rawState: JsonObject?,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        if (rawState == null) return previous
        val rawContinuity = rawState["continuity"]?.let { runCatching { it.jsonObject }.getOrNull() }
        if (rawContinuity == null) return previous

        val continuity = sanitizeContinuity(value.continuity, previous.continuity, rawContinuity)
        return previous.copy(
            // Hard scene state is advanced only by ChatSceneRuntime from transcript events.
            scene = previous.scene,
            continuity = continuity,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun sanitizeContinuity(
        value: ChatContinuityState,
        previous: ChatContinuityState,
        raw: JsonObject,
    ): ChatContinuityState = ChatContinuityState(
        recentEvents = if (raw.containsKey("recentEvents")) {
            sanitizeCurrentStrings(value.recentEvents, limit = 5, maxChars = 180)
        } else previous.recentEvents.takeLast(5),
        recurringEvents = emptyList(),
        decisions = if (raw.containsKey("decisions")) {
            sanitizeCurrentStrings(value.decisions, limit = 4, maxChars = 180)
        } else previous.decisions.takeLast(4),
        unfinished = if (raw.containsKey("unfinished")) {
            sanitizeCurrentStrings(value.unfinished, limit = 4, maxChars = 180)
        } else previous.unfinished.takeLast(4),
        // Provenance is derived from durable Pending turns after parsing; the model never owns it.
        evidence = previous.evidence,
    )

    private fun sanitizeCurrentStrings(
        values: List<String>,
        limit: Int,
        maxChars: Int,
    ): List<String> = values.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(maxChars) }
        .distinctBy(::normalize)
        .toList()
        .takeLast(limit)

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
                incoming = value.hypotheses.filter { evidenceGrounded(it, userMessage, assistantMessage) },
                minimumConfidence = 10, maximumConfidence = 85, allowedSources = FACT_SOURCES, limit = 6,
            ) else previous.hypotheses,
            unknowns = if (raw.containsKey("unknowns")) {
                mergeStrings(previous.unknowns, value.unknowns, 6, 160)
            } else previous.unknowns,
            sharedMoments = if (raw.containsKey("sharedMoments")) {
                mergeStrings(previous.sharedMoments, value.sharedMoments.filter { moment ->
                    evidenceGrounded(RelationshipEvidence(moment, "dialogue", 100), userMessage, assistantMessage)
                }, 8, 180)
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
        const val ACTION_COOLDOWN_TURNS = 3
        const val POSE_COOLDOWN_TURNS = 3
        const val VERBAL_COOLDOWN_TURNS = 3
        const val ADDRESS_COOLDOWN_TURNS = 2
        const val RECENT_ACTION_LIMIT = 6
        const val RECENT_POSE_LIMIT = 4
        const val RECENT_VERBAL_LIMIT = 6
        const val RECENT_ADDRESS_LIMIT = 4
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
