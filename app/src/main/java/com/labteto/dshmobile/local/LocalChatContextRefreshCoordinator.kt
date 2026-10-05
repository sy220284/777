package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.ChatDiarySourceMode
import com.labteto.dshmobile.local.chat.ChatDiaryStore
import com.labteto.dshmobile.local.chat.ChatDiaryWriteRequest
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.LocalChatPostTurnJobOwner
import com.labteto.dshmobile.local.chat.activeChatBranchMessages
import com.labteto.dshmobile.local.chat.applySceneTurn
import com.labteto.dshmobile.local.chat.chatRelationshipSubjectKey
import com.labteto.dshmobile.local.chat.commitProcessed
import com.labteto.dshmobile.local.chat.enqueuePendingDurably
import com.labteto.dshmobile.local.chat.groundContinuityEvidence
import com.labteto.dshmobile.local.chat.hasChatBranchAlternatives
import com.labteto.dshmobile.local.chat.loadPendingBatch
import com.labteto.dshmobile.local.chat.updateChatBranchNodeSnapshot
import com.labteto.dshmobile.local.chat.withContextForPlanner
import com.labteto.dshmobile.local.chat.withoutLegacyConversationContext
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.decodeTranscriptMessages
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val merged = mergeGroupContinuity(context.continuity, statesInReplyOrder)
    val grounded = groundContinuityEvidence(
        previous = context.continuity,
        candidate = merged,
        pendingTurns = processedPending,
    )
    return context.commitProcessed(
        scene = context.scene,
        continuity = grounded,
        throughSequence = through,
    )
}


internal fun findChatContinuitySourceUserMessageId(
    eventLog: LocalSessionEventLog,
    beforeSequenceExclusive: Long,
    expectedContent: String,
    pageSize: Int = 200,
): String? {
    val expected = expectedContent.trim()
    if (expected.isBlank()) return null
    var before = beforeSequenceExclusive
    val boundedPageSize = pageSize.coerceIn(1, 500)
    while (true) {
        val page = eventLog.pageBefore(
            sequenceExclusive = before,
            limit = boundedPageSize,
        )
        if (page.isEmpty()) return null

        page.asReversed().forEach { event ->
            decodeTranscriptMessages(event.data).orEmpty()
                .asReversed()
                .firstOrNull { message ->
                    message.role == "user" && message.content.trim() == expected
                }
                ?.let { return it.id }
        }

        val oldestSequence = page.minOf(LocalSessionEventLog.Event::sequence)
        if (page.size < boundedPageSize || oldestSequence <= 0L) return null
        before = oldestSequence
    }
}

internal fun shouldRetryChatPostTurnRequest(error: Throwable): Boolean =
    when (error) {
        is LocalModelException -> error.retryable
        is IOException -> true
        else -> false
    }

internal class LocalChatContextRefreshCoordinator(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val scope: CoroutineScope,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val diaryStore: ChatDiaryStore,
    private val requestPlanner: suspend (
        snapshot: LocalHarnessState,
        prompt: String,
        eventLog: LocalSessionEventLog,
        profile: LocalModelProfile,
    ) -> LocalModelReply?,
    private val recordUsage: (LocalHarnessState, LocalModelReply) -> Unit,
    private val persistBranchState: (String) -> Unit,
    private val persist: () -> Unit,
) {
    fun cancelScheduledRefresh() {
        LocalChatPostTurnJobOwner.cancel()
    }

    fun schedule(
        userMessage: String,
        assistantMessage: String,
        persona: PersonaProfile,
        expectedSessionId: String,
        expectedAssistantMessageId: String,
        expectedBaseState: ChatCharacterState,
        boundEventLog: LocalSessionEventLog,
        profile: LocalModelProfile,
        sourceUserMessageId: String? = null,
        assistantEventSequence: Long? = null,
    ) {
        cancelScheduledRefresh()
        val generation = enqueue(
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            expectedSessionId = expectedSessionId,
            expectedAssistantMessageId = expectedAssistantMessageId,
            boundEventLog = boundEventLog,
            sourceUserMessageId = sourceUserMessageId,
            assistantEventSequence = assistantEventSequence,
        ) ?: return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            refresh(
                persona = persona,
                expectedSessionId = expectedSessionId,
                expectedBaseState = expectedBaseState,
                expectedGeneration = generation,
                boundEventLog = boundEventLog,
                profile = profile,
            )
        }
        LocalChatPostTurnJobOwner.replace(job)
        job.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(job) }
        job.start()
    }

    private fun scheduleRetry(
        persona: PersonaProfile,
        expectedSessionId: String,
        expectedGeneration: Long,
        boundEventLog: LocalSessionEventLog,
        profile: LocalModelProfile,
        retryAttempt: Int,
        reason: String,
    ) {
        if (retryAttempt >= MAX_REFRESH_RETRIES) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(RETRY_BASE_DELAY_MS * (retryAttempt + 1L))
            val latest = state.value
            val stillPending = latest.chat.chatContext.pendingTurns.any { pending ->
                pending.sequence > latest.chat.chatContext.processedThroughSequence &&
                    pending.generation == expectedGeneration
            }
            if (
                latest.usageMode != LocalUsageMode.CHAT ||
                latest.sessionId != expectedSessionId ||
                latest.chat.chatContext.generation != expectedGeneration ||
                !stillPending
            ) return@launch

            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "retrying")
                put("reason", reason)
                put("attempt", retryAttempt + 1)
            })
            refresh(
                persona = persona,
                expectedSessionId = expectedSessionId,
                expectedBaseState = latest.chat.chatState,
                expectedGeneration = expectedGeneration,
                boundEventLog = boundEventLog,
                profile = profile,
                retryAttempt = retryAttempt + 1,
            )
        }
        LocalChatPostTurnJobOwner.replace(job)
        job.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(job) }
        job.start()
    }

    fun enqueue(
        userMessage: String,
        assistantMessage: String,
        expectedSessionId: String,
        expectedAssistantMessageId: String,
        boundEventLog: LocalSessionEventLog,
        sourceUserMessageId: String? = null,
        assistantEventSequence: Long? = null,
    ): Long? {
        if (state.value.sessionId != expectedSessionId) return null
        val sequence = assistantEventSequence ?: findTranscriptEventSequence(
            eventLog = boundEventLog,
            type = "assistant/message",
            messageId = expectedAssistantMessageId,
        ) ?: return null
        val resolvedSourceUserMessageId = sourceUserMessageId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: if (userMessage.isBlank()) {
                ""
            } else {
                // Compatibility fallback for older callers that do not yet carry the durable user
                // message id. Normal foreground turns pass it directly and avoid this log scan.
                findChatContinuitySourceUserMessageId(
                    eventLog = boundEventLog,
                    beforeSequenceExclusive = sequence,
                    expectedContent = userMessage,
                ).orEmpty()
            }

        var generation: Long? = null
        var branchSnapshotUpdated = false
        state.update { current ->
            generation = null
            branchSnapshotUpdated = false
            if (current.sessionId != expectedSessionId) {
                current
            } else {
                val baseContext = current.chat.chatContext
                    .applySceneTurn(
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        sequence = sequence,
                    )
                val nextContext = baseContext.enqueuePendingDurably(
                    ChatPendingTurn(
                        sequence = sequence,
                        userMessageId = resolvedSourceUserMessageId,
                        assistantMessageId = expectedAssistantMessageId,
                        branchHeadId = expectedAssistantMessageId,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        generation = baseContext.generation,
                    ),
                    boundEventLog,
                )
                generation = nextContext.generation
                val nextBranches = if (current.transcriptIndex.branchingEligible) {
                    updateChatBranchNodeSnapshot(
                        state = current.chat.chatBranches,
                        messageId = expectedAssistantMessageId,
                        chatState = current.chat.chatState,
                        replySuggestions = current.chat.replySuggestions,
                        chatContext = nextContext,
                    ).also { updated ->
                        branchSnapshotUpdated = updated != current.chat.chatBranches
                    }
                } else {
                    current.chat.chatBranches
                }
                current.copy(
                    chat = current.chat.copy(
                        chatContext = nextContext,
                        chatBranches = nextBranches,
                    ),
                )
            }
        }
        if (generation != null) {
            if (branchSnapshotUpdated && hasChatBranchAlternatives(state.value.chat.chatBranches)) {
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
        profile: LocalModelProfile,
        retryAttempt: Int = 0,
    ) {
        val before = state.value
        if (before.usageMode != LocalUsageMode.CHAT || before.sessionId != expectedSessionId) return
        val baseContext = before.chat.chatContext
        if (baseContext.generation != expectedGeneration) return

        val pending = baseContext.loadPendingBatch(
            boundEventLog, PENDING_BATCH,
            activeBranchMessageIds = if (hasChatBranchAlternatives(before.chat.chatBranches)) {
                activeChatBranchMessages(before.chat.chatBranches).mapTo(hashSetOf()) { it.id }
            } else null,
        )
        if (pending.isEmpty()) return

        val plannerState = before.chat.chatState.withContextForPlanner(baseContext)
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
            requestPlanner(before, prompt, boundEventLog, profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val retryable = shouldRetryChatPostTurnRequest(error)
            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
                put("pending_count", pending.size)
                put("retryable", retryable)
            })
            if (retryable) {
                scheduleRetry(
                    persona, expectedSessionId, expectedGeneration, boundEventLog, profile, retryAttempt, "request-failed",
                )
            }
            return
        }
        if (plannerReply == null) {
            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "empty-reply")
                put("pending_count", pending.size)
            })
            scheduleRetry(
                persona, expectedSessionId, expectedGeneration, boundEventLog, profile, retryAttempt, "empty-reply",
            )
            return
        }
        recordUsage(before, plannerReply)

        val plan = chatTurnCoordinator.parsePostTurn(
            text = plannerReply.content.orEmpty(),
            previous = plannerState,
            userMessage = userEvidenceBatch,
            assistantMessage = assistantEvidenceBatch,
            persona = persona,
        )
        if (plan == null) {
            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "parse-failed")
                put("content", plannerReply.content.orEmpty().take(2_000))
                put("pending_count", pending.size)
            })
            scheduleRetry(
                persona, expectedSessionId, expectedGeneration, boundEventLog, profile, retryAttempt, "parse-failed",
            )
            return
        }

        val throughSequence = pending.maxOf(ChatPendingTurn::sequence)
        val groundedContinuity = groundContinuityEvidence(
            previous = baseContext.continuity,
            candidate = plan.state.continuity,
            pendingTurns = pending,
        )
        val deterministicState = plan.state.withoutLegacyConversationContext()
        var nextContext = baseContext
        var applied = false
        state.update { current ->
            applied = false
            val currentContext = current.chat.chatContext
            if (
                current.sessionId != expectedSessionId ||
                currentContext.generation != expectedGeneration ||
                current.chat.chatState != expectedBaseState ||
                currentContext.processedThroughSequence != baseContext.processedThroughSequence
            ) {
                current
            } else {
                applied = true
                nextContext = currentContext.commitProcessed(
                    scene = currentContext.scene,
                    continuity = groundedContinuity,
                    throughSequence = throughSequence,
                )
                current.copy(
                    chat = current.chat.copy(
                        // The legacy mirror keeps old gallery/session data readable. Generation uses
                        // conversation-scoped chatContext as the canonical continuity state.
                        chatState = deterministicState,
                        chatContext = nextContext,
                        chatBranches = if (current.transcriptIndex.branchingEligible) {
                            updateChatBranchNodeSnapshot(
                                state = current.chat.chatBranches,
                                messageId = pending.last().assistantMessageId,
                                chatState = deterministicState,
                                replySuggestions = current.chat.replySuggestions,
                                chatContext = nextContext,
                            )
                        } else {
                            current.chat.chatBranches
                        },
                    ),
                )
            }
        }
        if (!applied) {
            boundEventLog.append("chat/post-turn", buildJsonObject {
                put("status", "stale-discarded")
                put("through_sequence", throughSequence)
                put("pending_preserved", true)
            })
            scheduleRetry(
                persona, expectedSessionId, expectedGeneration, boundEventLog, profile, retryAttempt, "stale-discarded",
            )
            return
        }

        if (before.autoMemory) chatRelationshipSubjectKey(before.chat.galleryId, before.chat.personaId)?.let { subjectKey ->
            runCatching {
                diaryStore.record(
                    ChatDiaryWriteRequest(
                        subjectKey = subjectKey,
                        personaName = persona.name,
                        delta = plan.diaryDelta,
                        turnSignificance = plan.turnSignificance,
                        sourceMode = ChatDiarySourceMode.DIRECT,
                        sourceSessionId = expectedSessionId,
                        sourceUserMessageIds = pending.map(ChatPendingTurn::userMessageId),
                        sourceAssistantMessageIds = pending.map(ChatPendingTurn::assistantMessageId),
                        evidenceText = pending.joinToString("\n") { turn ->
                            listOf(turn.userMessage, turn.assistantMessage)
                                .filter(String::isNotBlank)
                                .joinToString(" ")
                        },
                        generation = expectedGeneration,
                    ),
                )
            }.onSuccess { diary ->
                if (diary != null) {
                    boundEventLog.append("chat/diary", buildJsonObject {
                        put("status", "recorded")
                        put("diary_id", diary.id)
                        put("importance", diary.importance)
                        put("source_mode", diary.sourceMode.name.lowercase())
                    })
                }
            }.onFailure { error ->
                boundEventLog.append("chat/diary", buildJsonObject {
                    put("status", "failed")
                    put("detail", error.message.orEmpty().take(800))
                })
            }
        }

        boundEventLog.append("chat/post-turn", buildJsonObject {
            put("status", "updated")
            put("mood", deterministicState.mood)
            put("relationship_state", deterministicState.relationshipState)
            put("processed_through_sequence", throughSequence)
            put("remaining_pending", nextContext.pendingTurns.size)
        })
        if (hasChatBranchAlternatives(state.value.chat.chatBranches)) {
            persistBranchState("chat/post-turn-updated")
        }
        persist()
        if (nextContext.pendingTurns.any { pending ->
                pending.sequence > nextContext.processedThroughSequence &&
                    pending.generation == expectedGeneration
            }) {
            scheduleRetry(
                persona, expectedSessionId, expectedGeneration, boundEventLog, profile, retryAttempt, "remaining-pending",
            )
        }
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
        const val MAX_REFRESH_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 1_000L
    }
}
