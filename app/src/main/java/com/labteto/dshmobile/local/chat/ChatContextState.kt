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
    val assistantMessageId: String = "",
    val branchHeadId: String = "",
    val userMessage: String = "",
    val assistantMessage: String = "",
    val generation: Long = 0L,
)

internal fun ChatContextState.normalized(): ChatContextState = copy(
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
        recentEvents = continuity.recentEvents.cleanContextLines(5, 180),
        // Repeated-event history is folded into the latest decision/event instead of growing forever.
        recurringEvents = emptyList(),
        decisions = continuity.decisions.cleanContextLines(4, 180),
        unfinished = continuity.unfinished.cleanContextLines(4, 180),
    ),
    sceneEvents = sceneEvents
        .sortedBy(ChatSceneEvent::sequence)
        .distinctBy { event ->
            listOf(event.sequence.toString(), event.kind.name, event.actor, event.to)
        }
        .takeLast(RECENT_SCENE_EVENT_LIMIT),
)

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
    val mergedEvents = (sceneEvents + events)
        .sortedBy(ChatSceneEvent::sequence)
        .distinctBy { event ->
            listOf(event.sequence.toString(), event.kind.name, event.actor, event.to)
        }
        .takeLast(RECENT_SCENE_EVENT_LIMIT)
    return copy(
        scene = ChatSceneRuntime.reduce(scene, events),
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
    if (hasUsefulFacts()) return normalized()
    val legacy = copy(scene = state.scene, continuity = state.continuity).normalized()
    return if (legacy.hasUsefulFacts()) legacy else this
}

internal fun ChatCharacterState.withContextForPlanner(context: ChatContextState): ChatCharacterState =
    copy(
        scene = context.scene,
        continuity = context.continuity,
    )

private const val HOT_WINDOW_PENDING_TURNS = 10
private const val PENDING_RAW_FALLBACK_TURNS = 6
private const val RECENT_SCENE_EVENT_LIMIT = 12

private fun List<String>.cleanContextLines(limit: Int, maxChars: Int): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(maxChars) }
        .distinct()
        .take(limit)
        .toList()

internal fun renderChatContextForModel(context: ChatContextState): String {
    val normalized = context.normalized()
    if (!normalized.hasUsefulFacts()) return ""
    return buildString {
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
            appendLine("若没有明确移动、时间推进或合理叙事跳切，保持当前场景不变。")
        }

        val continuity = normalized.continuity
        if (
            continuity.recentEvents.isNotEmpty() ||
            continuity.decisions.isNotEmpty() ||
            continuity.unfinished.isNotEmpty()
        ) {
            appendLine("【剧情连续性｜当前有效】")
            if (continuity.recentEvents.isNotEmpty()) appendLine("近期：${continuity.recentEvents.joinToString("；")}")
            if (continuity.decisions.isNotEmpty()) appendLine("已定：${continuity.decisions.joinToString("；")}")
            if (continuity.unfinished.isNotEmpty()) appendLine("待续：${continuity.unfinished.joinToString("；")}")
        }

        val pending = normalized.pendingTurns
            .filter { it.sequence > normalized.processedThroughSequence }
            .filter { it.generation == normalized.generation }
            .sortedBy(ChatPendingTurn::sequence)
        if (pending.isNotEmpty()) {
            appendLine("【尚未归并｜优先于上方状态】")
            appendLine("最近 ${minOf(pending.size, HOT_WINDOW_PENDING_TURNS)} 个未归并回合的原文已在对话热窗口中，直接以原文为准，不重复注入。")
            val overflowFallback = pending
                .dropLast(HOT_WINDOW_PENDING_TURNS.coerceAtMost(pending.size))
                .takeLast(PENDING_RAW_FALLBACK_TURNS)
            if (overflowFallback.isNotEmpty()) {
                appendLine("以下较老 Pending 可能已退出热窗口，补充原文事实：")
                overflowFallback.forEach { turn ->
                    if (turn.userMessage.isNotBlank()) {
                        appendLine("#${turn.sequence} 用户：${turn.userMessage.trim().take(240)}")
                    }
                    if (turn.assistantMessage.isNotBlank()) {
                        appendLine("#${turn.sequence} 角色：${turn.assistantMessage.trim().take(320)}")
                    }
                }
            }
        }
        append("事实优先级：当前用户输入 > 尚未归并原文 > 当前场景/连续性 > 历史检查点。")
    }.trim().take(2_600)
}
