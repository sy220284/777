package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.model.chatSystemPrompt
import com.labteto.dshmobile.local.model.groupChatSystemPrompt
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.buildLocalTranscriptRuntimeIndex
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chat-owned timeline mutation and regeneration entry.
 *
 * Historical edit commits the durable timeline rewrite while holding Session MAINTENANCE ownership,
 * then updates Chat/model-history projections and releases maintenance before starting a new turn.
 * Regeneration owns Chat branch rollback/admission and starts the Chat-owned direct executor.
 */
@Singleton
internal class LocalChatTimelineCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val chatState: LocalChatStatePort,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelHistory: LocalForegroundModelHistoryRuntime,
    private val persistence: LocalChatPersistence,
    private val memoryStore: MemoryStore,
    private val userActivity: LocalChatUserActivityPort,
    private val turn: LocalChatTurnPort,
    private val directTurn: LocalChatDirectTurnExecutor,
    private val json: Json,
) {
    internal fun editAndResendUserMessage(
        messageId: String,
        replacement: String,
    ): LocalChatUserEditResult {
        val requestedText = replacement.trim()
        var started: Job? = null
        val handle = runtimeStateStore.foregroundRunHandle

        val result = synchronized(handle.lock) {
            val before = runtimeStateStore.state.value
            if (!before.modelState.configured || before.usageMode != LocalUsageMode.CHAT) {
                return@synchronized LocalChatUserEditResult.UNAVAILABLE
            }
            if (
                before.loading ||
                before.kernel.running ||
                runtimeStateStore.sessionTransitioning ||
                handle.hasLiveJob() ||
                handle.pendingInputs.size() != 0
            ) return@synchronized LocalChatUserEditResult.BUSY

            val lease = LocalSessionRuntimeRegistry.tryAcquire(
                before.sessionId,
                LocalSessionRuntimeKind.MAINTENANCE,
            ) ?: return@synchronized LocalChatUserEditResult.BUSY

            var committed = false
            try {
                val state = runtimeStateStore.state.value
                if (
                    state.sessionId != before.sessionId ||
                    state.usageMode != LocalUsageMode.CHAT ||
                    state.loading ||
                    state.kernel.running ||
                    runtimeStateStore.sessionTransitioning ||
                    handle.hasLiveJob() ||
                    handle.pendingInputs.size() != 0
                ) return@synchronized LocalChatUserEditResult.BUSY

                val eventLog = sessionStorage.eventLogs.get(state.sessionId)
                recoverPendingTimelineRewriteProjection(
                    eventLog,
                    memoryStore,
                    persistence.galleryStore,
                    persistence.diaryStore,
                )

                val activeTranscript = activeTranscriptForUserEdit(
                    messageId = messageId,
                    activeBranch = activeChatBranchMessages(state.chat.chatBranches),
                    hotMessages = state.messages,
                    loadDurableTranscript = { LocalSessionTranscriptPager(eventLog).all() },
                )
                val originalIndex = activeTranscript.indexOfFirst { message -> message.id == messageId }
                if (originalIndex < 0) return@synchronized LocalChatUserEditResult.MESSAGE_MISSING
                val original = activeTranscript[originalIndex]
                if (original.role != "user") return@synchronized LocalChatUserEditResult.MESSAGE_MISSING

                val content = withEditedChatUserText(original, requestedText)
                if (content.isBlank()) return@synchronized LocalChatUserEditResult.EMPTY
                if (editableChatUserText(original).trim() == requestedText) {
                    return@synchronized LocalChatUserEditResult.UNCHANGED
                }
                LocalChatPostTurnJobOwner.cancel()

                val sourceSequence = sourceEventSequenceForMessage(eventLog, messageId)
                val branchParentNode = state.chat.chatBranches.nodes
                    .firstOrNull { node -> node.message.id == messageId }
                    ?.parentId
                    ?.let { parentId ->
                        state.chat.chatBranches.nodes.firstOrNull { node -> node.message.id == parentId }
                    }
                val branchParentState = branchParentNode?.chatStateAfter
                val branchParentContext = branchParentNode?.chatContextAfter
                val baseState = branchParentState
                    ?: restoreChatStateBefore(eventLog, json, sourceSequence, original.createdAt)
                    ?: ChatCharacterState()
                val baseGroupState = if (state.chat.groupChat.enabled) {
                    restoreGroupStateBefore(eventLog, json, sourceSequence, original.createdAt)
                        ?: state.chat.groupChat.copy(
                            members = state.chat.groupChat.members.map { member ->
                                member.copy(chatState = ChatCharacterState())
                            },
                            turnCursor = 0,
                        )
                } else {
                    state.chat.groupChat
                }

                val discarded = activeTranscript.drop(originalIndex)
                val edited = LocalHarnessMessage(
                    id = UUID.randomUUID().toString(),
                    role = "user",
                    content = content,
                    createdAt = System.currentTimeMillis(),
                )
                val rewritten = rewriteChatTranscriptFromUserEdit(
                    activeMessages = activeTranscript,
                    originalMessageId = messageId,
                    editedMessage = edited,
                ) ?: return@synchronized LocalChatUserEditResult.MESSAGE_MISSING
                val retainedPrefix = rewritten.dropLast(1)

                val previousGeneration = if (state.chat.groupChat.enabled) {
                    state.chat.groupChat.context.generation
                } else {
                    state.chat.chatContext.generation
                }
                val replayedContext = replayHardChatContextFromTranscript(
                    messages = retainedPrefix,
                    generation = previousGeneration + 1L,
                )
                val recoveredBaseContext = restoreBranchContext(
                    snapshot = branchParentContext,
                    legacyState = baseState,
                    previousGeneration = previousGeneration,
                ).boundDurablePending(
                    eventLog,
                    if (state.chat.groupChat.enabled) "group" else "direct",
                )
                val baseContext = replayedContext.copy(continuity = recoveredBaseContext.continuity)
                val editedModelMessage = editedChatUserModelMessage(
                    eventLog = eventLog,
                    originalMessageId = messageId,
                    content = content,
                )
                val rewrittenHistory = buildEditedChatModelHistory(
                    eventLog = eventLog,
                    messages = rewritten,
                    groupMode = state.chat.groupChat.enabled,
                    editedMessageId = edited.id,
                    editedModelMessage = editedModelMessage,
                    systemPrompt = if (state.chat.groupChat.enabled) {
                        groupChatSystemPrompt()
                    } else {
                        chatSystemPrompt()
                    },
                )

                val restoredGroupState = if (state.chat.groupChat.enabled) {
                    baseGroupState.copy(context = baseContext)
                } else {
                    baseGroupState
                }
                val committedChatState = baseState.withoutLegacyConversationContext()
                val committedChatContext =
                    if (state.chat.groupChat.enabled) state.chat.chatContext else baseContext

                persistChatTimelineBaseline(
                    eventLog,
                    json,
                    state.copy(
                        chat = state.chat.copy(
                            chatState = committedChatState,
                            chatContext = committedChatContext,
                            groupChat = restoredGroupState,
                        ),
                    ),
                )
                val rewrite = appendTimelineRewriteCommit(
                    eventLog = eventLog,
                    reason = "user-edited",
                    activeTranscript = rewritten,
                    modelHistory = rewrittenHistory,
                    state = LocalTimelineRewriteState(
                        chatState = committedChatState,
                        chatContext = committedChatContext,
                        chatBranches = LocalChatBranchState(),
                        groupChat = restoredGroupState,
                    ),
                    projection = LocalTimelineRewriteProjectionInput(
                        sourceSessionId = state.sessionId,
                        createdAtInclusive = original.createdAt,
                        discardedMessageIds = discarded.map(LocalHarnessMessage::id),
                        directGalleryId = state.chat.galleryId.takeIf {
                            !state.chat.groupChat.enabled && state.chat.galleryStoryId != null
                        },
                        directStoryId = state.chat.galleryStoryId.takeIf {
                            !state.chat.groupChat.enabled && state.chat.galleryId != null
                        },
                        directMessageKeys = if (state.chat.groupChat.enabled) {
                            emptyList()
                        } else {
                            discarded.map(::galleryMessageArchiveKey)
                        },
                        directReplacementChatState = committedChatState.takeIf {
                            !state.chat.groupChat.enabled &&
                                state.chat.galleryId != null &&
                                state.chat.galleryStoryId != null
                        },
                        groupGalleryStates = if (state.chat.groupChat.enabled) {
                            baseGroupState.members.map { it.galleryId to it.chatState }
                        } else {
                            emptyList()
                        },
                    ),
                    editedMessageId = edited.id,
                    editedModelMessage = editedModelMessage,
                )
                committed = true

                check(modelHistory.reset(state.sessionId, rewrittenHistory)) {
                    "Chat 编辑提交后前台模型历史会话已切换"
                }
                chatState.update { current ->
                    if (current.sessionId != state.sessionId) current else current.copy(
                        messages = rewritten.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                        transcriptIndex = buildLocalTranscriptRuntimeIndex(rewritten),
                        chat = current.chat.copy(
                            chatState = committedChatState,
                            chatContext = committedChatContext,
                            groupChat = restoredGroupState,
                            replySuggestions = emptyList(),
                            chatBranches = LocalChatBranchState(),
                            groupActiveSpeakerName = null,
                            personaCorrectionNotice = null,
                        ),
                        error = null,
                    )
                }
                handle.transcriptProjectionCursor = maxOf(
                    handle.transcriptProjectionCursor ?: -1L,
                    rewrite.sequence,
                )

                val projectionFailures = mutableListOf<String>()
                runCatching {
                    recoverPendingTimelineRewriteProjection(
                        eventLog,
                        memoryStore,
                        persistence.galleryStore,
                        persistence.diaryStore,
                    )
                }.onFailure { error ->
                    projectionFailures += "历史投影恢复失败：${error.message ?: error::class.java.simpleName}"
                }
                runCatching {
                    check(modelHistory.checkpoint(
                        state.sessionId,
                        if (state.chat.groupChat.enabled) "group/user-edited" else "chat/user-edited",
                    ))
                }.onFailure { error ->
                    projectionFailures += "模型历史检查点失败：${error.message ?: error::class.java.simpleName}"
                }
                if (!sessionStorage.enqueueCurrentSnapshot(state.sessionId)) {
                    projectionFailures += "Session 快照排队失败"
                }
                userActivity.record(state.sessionId, edited.createdAt)
                if (projectionFailures.isNotEmpty()) {
                    chatState.update { current ->
                        if (current.sessionId != state.sessionId) current else current.copy(
                            error = "历史编辑已提交；" + projectionFailures.joinToString("；"),
                        )
                    }
                }

                started = turn.start(
                    content = content,
                    memoryInput = requestedText,
                    sourceMessageId = edited.id,
                ).also { handle.job = it }
                LocalChatUserEditResult.SENT
            } catch (error: Throwable) {
                if (committed) {
                    chatState.update { current ->
                        if (current.sessionId != before.sessionId) current else current.copy(
                            error = "历史编辑已提交，后续投影失败：${error.message ?: error::class.java.simpleName}",
                        )
                    }
                    LocalChatUserEditResult.SENT
                } else {
                    throw error
                }
            } finally {
                lease.close()
            }
        }

        started?.start()
        return result
    }

    internal fun regenerateReply(messageId: String): Boolean {
        var started: Job? = null
        val handle = runtimeStateStore.foregroundRunHandle
        val accepted = synchronized(handle.lock) {
            val before = runtimeStateStore.state.value
            if (
                before.usageMode != LocalUsageMode.CHAT ||
                !before.modelState.configured ||
                before.loading ||
                before.chat.groupChat.enabled ||
                before.kernel.running ||
                runtimeStateStore.sessionTransitioning ||
                handle.hasLiveJob() ||
                handle.pendingInputs.size() != 0
            ) return@synchronized false

            val lease = LocalSessionRuntimeRegistry.tryAcquire(
                before.sessionId,
                LocalSessionRuntimeKind.MAINTENANCE,
            ) ?: return@synchronized false
            try {
                val state = runtimeStateStore.state.value
                if (
                    state.sessionId != before.sessionId ||
                    state.usageMode != LocalUsageMode.CHAT ||
                    !state.modelState.configured ||
                    state.loading ||
                    state.chat.groupChat.enabled ||
                    state.kernel.running ||
                    runtimeStateStore.sessionTransitioning ||
                    handle.hasLiveJob() ||
                    handle.pendingInputs.size() != 0
                ) return@synchronized false

                val last = state.messages.lastOrNull() ?: return@synchronized false
                if (last.id != messageId || last.role != "assistant") return@synchronized false
                val promptMessage = state.messages.dropLast(1).lastOrNull { it.role == "user" }
                    ?: return@synchronized false
                if (
                    modelHistory.history.lastOrNull()
                        ?.get("role")
                        ?.jsonPrimitive
                        ?.contentOrNull != "assistant"
                ) return@synchronized false

                LocalChatPostTurnJobOwner.cancel()
                if (state.transcriptIndex.branchingEligible) {
                    val branches = if (state.chat.chatBranches.nodes.isNotEmpty()) {
                        state.chat.chatBranches
                    } else {
                        syncChatBranchState(
                            current = LocalChatBranchState(),
                            activeMessages = transcriptForBranchMaterialization(state.sessionId),
                            chatState = state.chat.chatState,
                            chatContext = state.chat.chatContext,
                            replySuggestions = state.chat.replySuggestions,
                        )
                    }
                    val eventLog = sessionStorage.eventLogs.get(state.sessionId)
                    val branchParentState = chatBranchParentState(branches, messageId)
                    val sourceSequence = sourceEventSequenceForMessage(eventLog, promptMessage.id)
                    val baseState = branchParentState
                        ?: restoreChatStateBefore(
                            eventLog,
                            json,
                            sourceSequence,
                            promptMessage.createdAt,
                        )
                        ?: ChatCharacterState()
                    val baseContext = restoreBranchContext(
                        snapshot = chatBranchParentContext(branches, messageId),
                        legacyState = baseState,
                        previousGeneration = state.chat.chatContext.generation,
                    ).boundDurablePending(eventLog, "direct")
                    chatState.update { current ->
                        if (current.sessionId != state.sessionId) current else current.copy(
                            chat = current.chat.copy(
                                chatState = baseState.withoutLegacyConversationContext(),
                                chatContext = baseContext,
                                replySuggestions = emptyList(),
                                chatBranches = branches,
                            ),
                        )
                    }
                }

                started = directTurn.start(
                    input = promptMessage.content,
                    replacingMessageId = messageId,
                ).also { handle.job = it }
                true
            } finally {
                lease.close()
            }
        }
        started?.start()
        return accepted
    }

    private fun transcriptForBranchMaterialization(sessionId: String): List<LocalHarnessMessage> {
        val state = runtimeStateStore.state.value
        if (state.sessionId != sessionId) return emptyList()
        val activeBranch = activeChatBranchMessages(state.chat.chatBranches)
        if (activeBranch.isNotEmpty()) return activeBranch
        if (state.transcriptIndex.totalMessageCount <= state.messages.size.toLong()) {
            return state.messages
        }
        return LocalSessionTranscriptPager(sessionStorage.eventLogs.get(sessionId)).all()
    }
}
