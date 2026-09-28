package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.applySceneTurn
import com.labteto.dshmobile.local.chat.commitProcessed
import com.labteto.dshmobile.local.chat.enqueuePending
import com.labteto.dshmobile.local.chat.withContextForPlanner
import com.labteto.dshmobile.local.chat.withLegacyFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns asynchronous consolidation of durable pending Chat facts.
 *
 * Foreground generation never depends on this job finishing: raw pending turns are already
 * persisted and injected near the request tail. This coordinator only folds those facts into the
 * small Scene/Continuity checkpoint and advances processedThroughSequence.
 */
internal fun renderPendingTurnsForPlanner(pending: List<ChatPendingTurn>): String = buildString {
    appendLine("【待归并回合｜严格按真实顺序】")
    pending.sortedBy(ChatPendingTurn::sequence).forEachIndexed { index, turn ->
        if (index > 0) appendLine()
        appendLine("#${turn.sequence}")
        appendLine("用户：${turn.userMessage}")
        appendLine("角色：${turn.assistantMessage}")
    }
    append("按上述 用户→角色 的逐回合顺序理解状态变化；后面的回合可以纠正、结束或覆盖前面的临时状态。")
}.trim()

internal fun mergeGroupContinuity(
    base: ChatContinuityState,
    statesInReplyOrder: List<ChatCharacterState>,
): ChatContinuityState {
    var merged = base.copy(recurringEvents = emptyList())
    statesInReplyOrder.forEach { state ->
        val incoming = state.continuity
        merged = merged.copy(
            recentEvents = if (incoming.recentEvents != base.recentEvents) {
                incoming.recentEvents
            } else {
                merged.recentEvents
            },
            decisions = if (incoming.decisions != base.decisions) {
                incoming.decisions
            } else {
                merged.decisions
            },
            unfinished = if (incoming.unfinished != base.unfinished) {
                incoming.unfinished
            } else {
                merged.unfinished
            },
            recurringEvents = emptyList(),
        )
    }
    return merged
}

internal fun finalizeGroupContextAfterRefresh(
    context: ChatContextState,
    statesInReplyOrder: List<ChatCharacterState>,
    processedPending: List<ChatPendingTurn>,
    complete: Boolean,
): ChatContextState {
    if (!complete || processedPending.isEmpty()) return context
    val through = processedPending.maxOf(ChatPendingTurn::sequence)
    return context.commitProcessed(
        scene = context.scene,
        continuity = mergeGroupContinuity(context.continuity, statesInReplyOrder),
        throughSequence = through,
    )
}

internal class LocalChatContextRefreshCoordinator(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val requestPlanner: suspend (
        snapshot: LocalHarnessState,
        prompt: String,
        eventLog: LocalSessionEventLog,
    ) -> LocalModelReply?,
    private val recordUsage: (LocalHarnessState, LocalModelReply) -> Unit,
    private val persistBranchState: (String) -> Unit,
    private val persist: () -> Unit,
) {
    fun enqueue(
        userMessage: String,
        assistantMessage: String,
        expectedSessionId: String,
        expectedAssistantMessageId: String,
        boundEventLog: LocalSessionEventLog,
    ): Long? {
        if (state.value.sessionId != expectedSessionId) return null
        val sequence = findTranscriptEventSequence(
            eventLog = boundEventLog,
            type = "assistant/message",
            messageId = expectedAssistantMessageId,
        ) ?: return null

        var generation: Long? = null
        var branchSnapshotUpdated = false
        state.update { current ->
            if (current.sessionId != expectedSessionId) {
                current
            } else {
                val baseContext = current.chatContext
                    .withLegacyFallback(current.chatState)
                    .applySceneTurn(
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        sequence = sequence,
                    )
                val nextContext = baseContext.enqueuePending(
                    ChatPendingTurn(
                        sequence = sequence,
                        assistantMessageId = expectedAssistantMessageId,
                        branchHeadId = expectedAssistantMessageId,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        generation = baseContext.generation,
                    ),
                )
                generation = nextContext.generation
                val nextBranches = if (current.transcriptIndex.branchingEligible) {
                    updateChatBranchNodeSnapshot(
                        state = current.chatBranches,
                        messageId = expectedAssistantMessageId,
                        chatState = current.chatState,
                        replySuggestions = current.replySuggestions,
                        chatContext = nextContext,
                    ).also { updated ->
                        branchSnapshotUpdated = updated != current.chatBranches
                    }
                } else {
                    current.chatBranches
                }
                current.copy(
                    chatContext = nextContext,
                    chatBranches = nextBranches,
                )
            }
        }
        if (generation != null) {
            if (branchSnapshotUpdated && hasChatBranchAlternatives(state.value.chatBranches)) {
                persistBranchState("chat/pending-enqueued")
            }
            persist()
        }
        return generation
    }

    suspend fun refresh(
        persona: PersonaProfile,
        expectedSessionId: String,
        expectedBaseState: ChatCharacterState,
        expectedGeneration: Long,
        boundEventLog: LocalSessionEventLog,
    ) {
        val before = state.value
        if (before.usageMode != LocalUsageMode.CHAT || before.sessionId != expectedSessionId) return
        val baseContext = before.chatContext.withLegacyFallback(before.chatState)
        if (baseContext.generation != expectedGeneration) return

        val pending = baseContext.pendingTurns.asSequence()
            .filter { it.sequence > baseContext.processedThroughSequence }
            .filter { it.generation == expectedGeneration }
            .sortedBy(ChatPendingTurn::sequence)
            .take(PENDING_BATCH)
            .toList()
        if (pending.isEmpty()) return

        val plannerState = before.chatState.withContextForPlanner(baseContext)
        val orderedTranscript = renderPendingTurnsForPlanner(pending)
        val userEvidenceBatch = pending.joinToString("\n") { turn -> turn.userMessage }
        val assistantEvidenceBatch = pending.joinToString("\n") { turn -> turn.assistantMessage }
        val prompt = chatTurnCoordinator.postTurnPrompt(
            persona = persona,
            state = plannerState,
            userMessage = orderedTranscript,
            assistantMessage = "",
        )
        val plannerReply = try {
            requestPlanner(before, prompt, boundEventLog) ?: return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
                put("pending_count", pending.size)
            })
            return
        }
        recordUsage(before, plannerReply)

        val plan = chatTurnCoordinator.parsePostTurn(
            text = plannerReply.content.orEmpty(),
            previous = plannerState,
            userMessage = userEvidenceBatch,
            assistantMessage = assistantEvidenceBatch,
        )
        if (plan == null) {
            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "parse-failed")
                put("content", plannerReply.content.orEmpty().take(2_000))
                put("pending_count", pending.size)
            })
            return
        }

        val throughSequence = pending.maxOf(ChatPendingTurn::sequence)
        val deterministicState = plan.state.copy(
            scene = baseContext.scene,
            continuity = plan.state.continuity,
        )
        val nextContext = baseContext.commitProcessed(
            scene = baseContext.scene,
            continuity = deterministicState.continuity,
            throughSequence = throughSequence,
        )
        var applied = false
        state.update { current ->
            val currentContext = current.chatContext.withLegacyFallback(current.chatState)
            if (
                current.sessionId != expectedSessionId ||
                currentContext.generation != expectedGeneration ||
                current.chatState != expectedBaseState
            ) {
                current
            } else {
                applied = true
                current.copy(
                    // The legacy mirror keeps old gallery/session data readable. Generation uses
                    // conversation-scoped chatContext as the canonical continuity state.
                    chatState = deterministicState,
                    chatContext = nextContext,
                    chatBranches = if (current.transcriptIndex.branchingEligible) {
                        updateChatBranchNodeSnapshot(
                            state = current.chatBranches,
                            messageId = pending.last().assistantMessageId,
                            chatState = deterministicState,
                            replySuggestions = current.replySuggestions,
                            chatContext = nextContext,
                        )
                    } else {
                        current.chatBranches
                    },
                )
            }
        }
        if (!applied) {
            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "stale-discarded")
                put("through_sequence", throughSequence)
                put("pending_preserved", true)
            })
            return
        }

        boundEventLog.append("chat/post-turn", buildJsonObject {
            put("status", "updated")
            put("mood", deterministicState.mood)
            put("relationship_state", deterministicState.relationshipState)
            put("processed_through_sequence", throughSequence)
            put("remaining_pending", nextContext.pendingTurns.size)
        })
        if (hasChatBranchAlternatives(state.value.chatBranches)) {
            persistBranchState("chat/post-turn-updated")
        }
        persist()
    }

    private fun findTranscriptEventSequence(
        eventLog: LocalSessionEventLog,
        type: String,
        messageId: String,
    ): Long? {
        var beforeSequenceExclusive = Long.MAX_VALUE
        while (true) {
            val page = eventLog.pageBefore(
                sequenceExclusive = beforeSequenceExclusive,
                limit = EVENT_SCAN_PAGE_SIZE,
            )
            if (page.isEmpty()) return null
            page.asReversed().firstOrNull { event ->
                event.type == type &&
                    decodeTranscriptMessages(event.data).orEmpty()
                        .any { message -> message.id == messageId }
            }?.let { return it.sequence }

            val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
            if (page.size < EVENT_SCAN_PAGE_SIZE || oldestSequence <= 0L) return null
            beforeSequenceExclusive = oldestSequence
        }
    }

    private companion object {
        const val PENDING_BATCH = 8
        const val EVENT_SCAN_PAGE_SIZE = 200
    }
}
