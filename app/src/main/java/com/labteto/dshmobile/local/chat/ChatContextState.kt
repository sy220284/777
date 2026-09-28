package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

/**
 * Conversation-scoped continuity state.
 *
 * The transcript/event log remains the source of truth. This object only keeps the minimum
 * request-time state needed to avoid scene drift while post-turn consolidation is asynchronous.
 */
@Serializable
data class ChatContextState(
    val scene: ChatSceneState = ChatSceneState(),
    val continuity: ChatContinuityState = ChatContinuityState(),
    val processedThroughSequence: Long = 0L,
    val pendingTurns: List<ChatPendingTurn> = emptyList(),
    /** Recent deterministic hard-scene transitions. Raw transcript remains the canonical history. */
    val sceneEvents: List<ChatSceneEvent> = emptyList(),
    val generation: Long = 0L,
)

@Serializable
data class ChatPendingTurn(
    val sequence: Long = 0L,
    val userMessageId: String = "",
    val assistantMessageId: String = "",
    val branchHeadId: String = "",
    val userMessage: String = "",
    val assistantMessage: String = "",
    val generation: Long = 0L,
)

internal fun ChatContextState.normalized(): ChatContextState {
    val recentEvents = continuity.recentEvents.cleanRecentContextLines(5, 180)
    val decisions = continuity.decisions.cleanRecentContextLines(4, 180)
    val unfinished = continuity.unfinished.cleanRecentContextLines(4, 180)
    val activeEvidenceKeys = buildSet {
        recentEvents.forEach { add(ChatContinuityFactKind.EVENT to normalizeContinuityFact(it)) }
        decisions.forEach { add(ChatContinuityFactKind.DECISION to normalizeContinuityFact(it)) }
        unfinished.forEach { add(ChatContinuityFactKind.OPEN_THREAD to normalizeContinuityFact(it)) }
    }
    val groundedEvidence = continuity.evidence.asSequence()
        .map { item ->
            item.copy(
                text = item.text.trim().take(180),
                sourceUserMessageId = item.sourceUserMessageId.trim().take(160),
                sourceAssistantMessageId = item.sourceAssistantMessageId.trim().take(160),
                evidence = item.evidence.trim().take(360),
            )
        }
        .filter { item ->
            item.text.isNotBlank() &&
                (item.kind to normalizeContinuityFact(item.text)) in activeEvidenceKeys
        }
        .distinctBy { item -> item.kind to normalizeContinuityFact(item.text) }
        .takeLast(MAX_CONTINUITY_EVIDENCE)
        .toList()

    return copy(
        scene = scene.copy(
            sceneTime = scene.sceneTime.trim().take(80),
            location = scene.location.trim().take(120),
            participants = scene.participants.cleanContextLines(8, 80),
            positions = scene.positions.cleanContextLines(8, 120),
            activeActions = scene.activeActions.cleanContextLines(6, 120),
            keyObjects = scene.keyObjects.cleanContextLines(8, 80),
            // Legacy #241 fields are deliberately no longer carried as durable hard state.
            currentEvent = "",
            lastSceneChange = "",
        ),
        continuity = continuity.copy(
            recentEvents = recentEvents,
            // Repeated-event history is folded into the latest decision/event instead of growing forever.
            recurringEvents = emptyList(),
            decisions = decisions,
            unfinished = unfinished,
            evidence = groundedEvidence,
        ),
        sceneEvents = sceneEvents
            .sortedBy(ChatSceneEvent::sequence)
            .distinctBy { event ->
                listOf(event.sequence.toString(), event.kind.name, event.actor, event.to)
            }
            .takeLast(RECENT_SCENE_EVENT_LIMIT),
    )
}

internal fun ChatContextState.enqueuePending(turn: ChatPendingTurn): ChatContextState {
    if (turn.sequence <= processedThroughSequence) return this
    val next = (pendingTurns + turn)
        .distinctBy { pending -> pending.sequence to pending.assistantMessageId }
        .sortedBy(ChatPendingTurn::sequence)
    return copy(pendingTurns = next)
}

internal fun ChatContextState.applySceneTurn(
    userMessage: String,
    assistantMessage: String,
    sequence: Long,
): ChatContextState {
    val events = ChatSceneRuntime.extractTurnEvents(
        previous = scene,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
        sequence = sequence,
    )
    if (events.isEmpty()) return this

    var reducedScene = scene
    val effectiveEvents = mutableListOf<ChatSceneEvent>()
    events.sortedBy(ChatSceneEvent::sequence).forEach { event ->
        val next = ChatSceneRuntime.reduce(reducedScene, listOf(event))
        val changed = next.location != reducedScene.location || next.sceneTime != reducedScene.sceneTime
        if (changed) effectiveEvents += event
        reducedScene = next
    }
    if (effectiveEvents.isEmpty()) return normalized()

    val mergedEvents = (sceneEvents + effectiveEvents)
        .sortedBy(ChatSceneEvent::sequence)
        .distinctBy { event ->
            listOf(event.sequence.toString(), event.kind.name, event.actor, event.to)
        }
        .takeLast(RECENT_SCENE_EVENT_LIMIT)
    return copy(
        scene = reducedScene,
        sceneEvents = mergedEvents,
    ).normalized()
}

internal fun ChatContextState.commitProcessed(
    scene: ChatSceneState,
    continuity: ChatContinuityState,
    throughSequence: Long,
): ChatContextState {
    val through = maxOf(processedThroughSequence, throughSequence)
    return copy(
        scene = scene,
        continuity = continuity,
        processedThroughSequence = through,
        pendingTurns = pendingTurns.filter { it.sequence > through },
    ).normalized()
}

internal fun ChatContextState.invalidateGeneration(): ChatContextState = copy(
    generation = generation + 1L,
)

internal fun ChatContextState.rebaseGeneration(): ChatContextState {
    val nextGeneration = generation + 1L
    return copy(
        generation = nextGeneration,
        pendingTurns = pendingTurns.map { it.copy(generation = nextGeneration) },
    )
}

internal fun restoreBranchContext(
    snapshot: ChatContextState?,
    legacyState: ChatCharacterState?,
    previousGeneration: Long,
): ChatContextState =
    snapshot?.rebaseGeneration()
        ?: legacyState?.let { state ->
            ChatContextState().withLegacyFallback(state).rebaseGeneration()
        }
        ?: ChatContextState(generation = previousGeneration + 1L)

internal fun ChatContextState.pendingForRequest(limit: Int = 6): List<ChatPendingTurn> =
    pendingTurns
        .filter { it.sequence > processedThroughSequence }
        .filter { it.generation == generation }
        .takeLast(limit.coerceAtLeast(1))

internal fun ChatContextState.hasUsefulFacts(): Boolean =
    scene.sceneTime.isNotBlank() ||
        scene.location.isNotBlank() ||
        continuity.recentEvents.isNotEmpty() ||
        continuity.decisions.isNotEmpty() ||
        continuity.unfinished.isNotEmpty() ||
        pendingTurns.isNotEmpty() ||
        sceneEvents.isNotEmpty()

internal fun ChatContextState.withLegacyFallback(state: ChatCharacterState): ChatContextState {
    val current = normalized()
    val legacy = ChatContextState(
        scene = ChatSceneState(
            sceneTime = state.scene.sceneTime,
            location = state.scene.location,
        ),
        continuity = state.continuity,
    ).normalized()

    return current.copy(
        scene = current.scene.copy(
            sceneTime = current.scene.sceneTime.ifBlank { legacy.scene.sceneTime },
            location = current.scene.location.ifBlank { legacy.scene.location },
        ),
        continuity = current.continuity.copy(
            recentEvents = current.continuity.recentEvents.ifEmpty { legacy.continuity.recentEvents },
            decisions = current.continuity.decisions.ifEmpty { legacy.continuity.decisions },
            unfinished = current.continuity.unfinished.ifEmpty { legacy.continuity.unfinished },
            evidence = (current.continuity.evidence + legacy.continuity.evidence)
                .distinctBy { item -> item.kind to normalizeContinuityFact(item.text) },
        ),
    ).normalized()
}

internal fun ChatCharacterState.withContextForPlanner(context: ChatContextState): ChatCharacterState =
    copy(
        scene = context.scene.copy(
            // Planner may read hard time/location, but legacy soft scene details have no
            // deterministic provenance and must not leak back into continuity summaries.
            participants = emptyList(),
            positions = emptyList(),
            activeActions = emptyList(),
            keyObjects = emptyList(),
            currentEvent = "",
            lastSceneChange = "",
        ),
        continuity = context.continuity,
    )

private const val HOT_WINDOW_PENDING_TURNS = 10
private const val PENDING_RAW_FALLBACK_TURNS = 4
private const val RECENT_SCENE_EVENT_LIMIT = 12
private const val MAX_CONTINUITY_EVIDENCE = 13
private const val MIN_CONTINUITY_EVIDENCE_SCORE = 20
private const val DERIVED_CONTINUITY_EVIDENCE_SCORE = 10
private const val MAX_PENDING_CONTEXT_CHARS = 1_800
private const val MAX_SCENE_CONTEXT_CHARS = 700
private const val MAX_SOFT_CONTINUITY_CHARS = 1_300
private const val MAX_RENDERED_CONTEXT_BODY_CHARS = 3_900

private fun List<String>.cleanContextLines(limit: Int, maxChars: Int): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(maxChars) }
        .distinct()
        .take(limit)
        .toList()

private fun List<String>.cleanRecentContextLines(limit: Int, maxChars: Int): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(maxChars) }
        .distinct()
        .toList()
        .takeLast(limit)


/**
 * Accept new soft continuity facts only when they can be tied back to a durable Pending turn.
 *
 * Legacy facts that were already active remain readable without fabricated provenance. As soon as
 * the planner changes a fact, the replacement must be grounded in the raw user/assistant turn.
 */
internal fun groundContinuityEvidence(
    previous: ChatContinuityState,
    candidate: ChatContinuityState,
    pendingTurns: List<ChatPendingTurn>,
): ChatContinuityState {
    val orderedTurns = pendingTurns.sortedBy(ChatPendingTurn::sequence)
    val previousEvidence = previous.evidence.associateBy { item ->
        item.kind to normalizeContinuityFact(item.text)
    }
    val acceptedEvidence = linkedMapOf<Pair<ChatContinuityFactKind, String>, ChatContinuityEvidence>()

    fun ground(
        kind: ChatContinuityFactKind,
        values: List<String>,
        previousValues: List<String>,
        allowDerivedEvidence: Boolean = false,
    ): List<String> {
        if (values.isEmpty()) return emptyList()
        val previousKeys = previousValues.associateBy(::normalizeContinuityFact)
        val accepted = mutableListOf<String>()

        values.forEach { raw ->
            val value = raw.trim()
            if (value.isBlank()) return@forEach
            val key = kind to normalizeContinuityFact(value)
            if (key.second.isBlank()) return@forEach

            if (key.second in previousKeys) {
                accepted += value
                previousEvidence[key]?.let { acceptedEvidence[key] = it.copy(text = value) }
                return@forEach
            }

            val direct = bestContinuitySource(value, orderedTurns)
            if (direct != null) {
                accepted += value
                acceptedEvidence[key] = ChatContinuityEvidence(
                    kind = kind,
                    text = value,
                    sourceSequence = direct.first.sequence,
                    sourceUserMessageId = direct.first.userMessageId,
                    sourceAssistantMessageId = direct.first.assistantMessageId,
                    evidence = direct.first.rawEvidence(),
                )
                return@forEach
            }

            if (allowDerivedEvidence) {
                val inherited = acceptedEvidence.values
                    .map { evidence -> evidence to continuityEvidenceScore(value, evidence.text + " " + evidence.evidence) }
                    .filter { (_, score) -> score >= DERIVED_CONTINUITY_EVIDENCE_SCORE }
                    .maxByOrNull { (_, score) -> score }
                    ?.first
                if (inherited != null) {
                    accepted += value
                    acceptedEvidence[key] = inherited.copy(kind = kind, text = value)
                }
            }
        }

        // A malformed/hallucinated replacement must not silently erase the previous valid state.
        return if (accepted.isEmpty() && previousValues.isNotEmpty()) previousValues else accepted
    }

    val recentEvents = ground(
        kind = ChatContinuityFactKind.EVENT,
        values = candidate.recentEvents,
        previousValues = previous.recentEvents,
    )
    val decisions = ground(
        kind = ChatContinuityFactKind.DECISION,
        values = candidate.decisions,
        previousValues = previous.decisions,
    )
    val unfinished = ground(
        kind = ChatContinuityFactKind.OPEN_THREAD,
        values = candidate.unfinished,
        previousValues = previous.unfinished,
        allowDerivedEvidence = true,
    )

    val activeKeys = buildSet {
        recentEvents.forEach { add(ChatContinuityFactKind.EVENT to normalizeContinuityFact(it)) }
        decisions.forEach { add(ChatContinuityFactKind.DECISION to normalizeContinuityFact(it)) }
        unfinished.forEach { add(ChatContinuityFactKind.OPEN_THREAD to normalizeContinuityFact(it)) }
    }
    val carriedLegacyEvidence = previous.evidence.filter { item ->
        val key = item.kind to normalizeContinuityFact(item.text)
        key in activeKeys && key !in acceptedEvidence
    }

    return candidate.copy(
        recentEvents = recentEvents,
        recurringEvents = emptyList(),
        decisions = decisions,
        unfinished = unfinished,
        evidence = (carriedLegacyEvidence + acceptedEvidence.values)
            .distinctBy { item -> item.kind to normalizeContinuityFact(item.text) }
            .takeLast(MAX_CONTINUITY_EVIDENCE),
    )
}

private fun bestContinuitySource(
    fact: String,
    turns: List<ChatPendingTurn>,
): Pair<ChatPendingTurn, Int>? = turns.asSequence()
    .map { turn -> turn to continuityEvidenceScore(fact, turn.rawEvidence()) }
    .filter { (_, score) -> score >= MIN_CONTINUITY_EVIDENCE_SCORE }
    .maxWithOrNull(
        compareBy<Pair<ChatPendingTurn, Int>> { it.second }
            .thenBy { it.first.sequence },
    )

private fun ChatPendingTurn.rawEvidence(): String =
    listOf(userMessage.trim(), assistantMessage.trim())
        .filter(String::isNotBlank)
        .joinToString(" ")
        .take(360)

private fun continuityEvidenceScore(fact: String, source: String): Int {
    val a = normalizeContinuityFact(fact)
    val b = normalizeContinuityFact(source)
    if (a.length < 2 || b.length < 2) return 0
    if (b.contains(a) || a.contains(b)) return 100
    val aa = continuityBigrams(a)
    val bb = continuityBigrams(b)
    if (aa.isEmpty() || bb.isEmpty()) return 0
    val shared = aa.count(bb::contains)
    if (shared == 0) return 0
    val containment = (shared * 100) / minOf(aa.size, bb.size)
    return if (shared >= 2) containment else containment.coerceAtMost(18)
}

private fun continuityBigrams(text: String): Set<String> =
    if (text.length < 2) emptySet()
    else (0 until text.length - 1).mapTo(linkedSetOf()) { index -> text.substring(index, index + 2) }

private fun normalizeContinuityFact(text: String): String =
    text.trim()
        .lowercase()
        .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
        .take(360)


internal fun ChatContextState.canonicalFactLines(): List<String> {
    val normalized = normalized()
    return buildList {
        normalized.scene.sceneTime.takeIf(String::isNotBlank)?.let { add("时间=$it") }
        normalized.scene.location.takeIf(String::isNotBlank)?.let { add("地点=$it") }
        addAll(normalized.continuity.recentEvents)
        addAll(normalized.continuity.decisions)
        addAll(normalized.continuity.unfinished)
    }.distinct()
}

internal fun renderChatContextForModel(context: ChatContextState): String {
    val normalized = context.normalized()
    if (!normalized.hasUsefulFacts()) return ""

    val pendingSection = buildString {
        val pending = normalized.pendingTurns
            .filter { it.sequence > normalized.processedThroughSequence }
            .filter { it.generation == normalized.generation }
            .sortedBy(ChatPendingTurn::sequence)
        if (pending.isNotEmpty()) {
            appendLine("【尚未归并｜最高连续性优先】")
            appendLine("最近 ${minOf(pending.size, HOT_WINDOW_PENDING_TURNS)} 个未归并回合的原文已在对话热窗口中，直接以原文为准，不重复注入。")
            val overflowFallback = pending
                .dropLast(HOT_WINDOW_PENDING_TURNS.coerceAtMost(pending.size))
                .takeLast(PENDING_RAW_FALLBACK_TURNS)
            if (overflowFallback.isNotEmpty()) {
                appendLine("以下较老 Pending 可能已退出热窗口，补充原文事实：")
                overflowFallback.forEach { turn ->
                    if (turn.userMessage.isNotBlank()) {
                        appendLine("#${turn.sequence} 用户：${turn.userMessage.trim().take(180)}")
                    }
                    if (turn.assistantMessage.isNotBlank()) {
                        appendLine("#${turn.sequence} 角色：${turn.assistantMessage.trim().take(240)}")
                    }
                }
            }
        }
    }.trim().take(MAX_PENDING_CONTEXT_CHARS)

    val sceneSection = buildString {
        val scene = normalized.scene
        if (scene.sceneTime.isNotBlank() || scene.location.isNotBlank()) {
            appendLine("【当前场景｜硬连续性】")
            appendLine(
                "时间=${scene.sceneTime.ifBlank { "未知" }}｜地点=${scene.location.ifBlank { "未知" }}",
            )
            normalized.sceneEvents.lastOrNull()?.let { event ->
                val label = if (event.kind == ChatSceneEventKind.LOCATION) "地点变化" else "时间推进"
                appendLine("最近硬状态事件：$label=${event.to}")
            }
            appendLine("硬状态目前只包含有确定性事件来源的时间与地点；人物位置、进行中动作和物件以最近原始对话为准，不把旧快照当成当前事实。")
            append("若没有明确移动、时间推进或合理叙事跳切，保持当前场景不变。")
        }
    }.trim().take(MAX_SCENE_CONTEXT_CHARS)

    val continuitySection = buildString {
        val continuity = normalized.continuity
        if (
            continuity.recentEvents.isNotEmpty() ||
            continuity.decisions.isNotEmpty() ||
            continuity.unfinished.isNotEmpty()
        ) {
            appendLine("【剧情连续性｜当前有效】")
            if (continuity.recentEvents.isNotEmpty()) appendLine("近期：${continuity.recentEvents.joinToString("；")}")
            if (continuity.decisions.isNotEmpty()) appendLine("已定：${continuity.decisions.joinToString("；")}")
            if (continuity.unfinished.isNotEmpty()) append("待续：${continuity.unfinished.joinToString("；")}")
        }
    }.trim().take(MAX_SOFT_CONTINUITY_CHARS)

    val body = listOf(pendingSection, sceneSection, continuitySection)
        .filter(String::isNotBlank)
        .joinToString("\n\n")
        .take(MAX_RENDERED_CONTEXT_BODY_CHARS)

    return buildString {
        if (body.isNotBlank()) {
            appendLine(body)
        }
        append("事实优先级：当前用户输入 > 尚未归并原文 > 当前场景/连续性 > 历史检查点。")
    }.trim()
}
