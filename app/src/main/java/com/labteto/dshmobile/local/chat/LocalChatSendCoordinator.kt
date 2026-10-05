package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.automation.LocalChatUserActivityPort
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.MAX_PENDING_INPUTS
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.send.prepareLocalSend
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.coordinateOwnedLocalSend
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat-owned user-send transaction.
 *
 * Durable user input, branch projection, bounded transcript/history and foreground admission commit
 * together before the Chat turn is started. Shared Runtime owns the one foreground queue/job.
 */
@Singleton
internal class LocalChatSendCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val chatState: LocalChatStatePort,
    private val userActivity: LocalChatUserActivityPort,
    private val turn: LocalChatTurnPort,
    private val json: Json,
) {
    internal fun send(
        text: String,
        attachments: List<LocalImportedAttachment>,
    ): LocalSendResult {
        val prepared = prepareLocalSend(text, attachments) ?: return LocalSendResult.Empty
        var started: Job? = null
        val handle = runtimeStateStore.foregroundRunHandle

        val result = synchronized(handle.lock) {
            val snapshot = runtimeStateStore.state.value
            if (snapshot.usageMode != LocalUsageMode.CHAT) {
                return@synchronized reject(
                    snapshot.sessionId,
                    LocalSendResult.rejected(LocalSendRejectReason.SESSION_TRANSITION),
                )
            }
            val sessionId = snapshot.sessionId
            val pending = handle.pendingInputs
            val queuedInput = QueuedAgentInput(
                prepared.content,
                prepared.memoryInput,
                prepared.modelMessage,
                UUID.randomUUID().toString(),
            )
            coordinateOwnedLocalSend(
                usageMode = LocalUsageMode.CHAT,
                sessionId = sessionId,
                workBindingActive = false,
                visibleJobActive = handle.hasLiveJob(),
                configured = snapshot.modelState.configured,
                loading = snapshot.loading,
                sessionTransitioning = runtimeStateStore.sessionTransitioning,
                pendingCount = pending.size(),
                pendingLimit = MAX_PENDING_INPUTS,
                onRejected = { rejected -> reject(sessionId, rejected) },
                onAccepted = {
                    runtimeStateStore.clearSendFeedback()
                    LocalChatPostTurnJobOwner.cancel()
                },
                enqueue = {
                    pending.offer(queuedInput) {
                        recordUserTranscript(
                            prepared = prepared,
                            queuedInput = queuedInput,
                        )
                    }
                },
                onQueued = {
                    runtimeStateStore.projection.setForegroundQueuedInputCount(
                        sessionId = sessionId,
                        count = pending.size(),
                    )
                    chatState.update { current -> current.copy(error = null) }
                    check(sessionStorage.enqueueCurrentSnapshot(sessionId)) {
                        "Chat 排队保存时前台会话已切换"
                    }
                },
                onStart = {
                    val sourceMessageId = recordUserTranscript(prepared)
                    appendUserHistory(sessionId, prepared.modelMessage)
                    check(sessionStorage.enqueueCurrentSnapshot(sessionId)) {
                        "Chat 首轮保存时前台会话已切换"
                    }
                    started = turn.start(
                        content = prepared.content,
                        memoryInput = prepared.memoryInput,
                        sourceMessageId = sourceMessageId,
                    ).also { handle.job = it }
                },
            )
        }
        started?.start()
        return result
    }

    private fun recordUserTranscript(
        prepared: LocalPreparedSend,
        queuedInput: QueuedAgentInput? = null,
    ): String {
        val before = runtimeStateStore.state.value
        check(before.usageMode == LocalUsageMode.CHAT) {
            "Chat 用户消息提交时会话模式已改变"
        }
        val sessionId = before.sessionId
        val eventLog = sessionStorage.eventLogs.get(sessionId)
        persistChatTimelineBaseline(eventLog, json, before)

        val transcriptMessage = LocalHarnessMessage(
            id = queuedInput?.id ?: UUID.randomUUID().toString(),
            role = "user",
            content = prepared.content,
            createdAt = System.currentTimeMillis(),
        )
        val userEvent = if (queuedInput != null) {
            eventLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "queued",
                    pending = runtimeStateStore.foregroundRunHandle.pendingInputs.snapshot(),
                    affected = listOf(queuedInput),
                    transcript = listOf(transcriptMessage),
                ),
            )
        } else {
            eventLog.append("user/message", buildJsonObject {
                put("content", prepared.content)
                put("model_message", prepared.modelMessage)
                put("queued", false)
                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
            })
        }

        runtimeStateStore.projection.appendForegroundTranscript(
            sessionId = sessionId,
            messages = listOf(transcriptMessage),
        )
        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
            maxOf(
                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                userEvent.sequence,
            )

        if (!before.chat.groupChat.enabled) {
            userActivity.record(sessionId, transcriptMessage.createdAt)
        }

        if (
            before.chat.chatBranches.nodes.isNotEmpty() &&
            before.transcriptIndex.branchingEligible
        ) {
            val branches = appendMaterializedChatBranchMessage(
                current = before.chat.chatBranches,
                activeMessages = before.messages,
                message = transcriptMessage,
                parentId = before.transcriptIndex.latestDialogueMessageId,
                chatState = before.chat.chatState,
                chatContext = before.chat.chatContext,
                replySuggestions = before.chat.replySuggestions,
            )
            chatState.update { current ->
                current.copy(
                    chat = current.chat.copy(
                        replySuggestions = emptyList(),
                        chatBranches = branches,
                    ),
                )
            }
        } else {
            chatState.update { current ->
                current.copy(chat = current.chat.copy(replySuggestions = emptyList()))
            }
        }
        return transcriptMessage.id
    }

    private fun appendUserHistory(
        sessionId: String,
        message: JsonObject,
    ) {
        val history = runtimeStateStore.foregroundRunHandle.modelHistory
        history.append(message)
        val resources = runtimeStateStore.resourceSnapshot()
        runtimeStateStore.projection.updateContextMetrics(
            sessionId = sessionId,
            contextChars = history.encodedChars,
            contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(
                runtimeStateStore.state.value,
                resources,
            ),
        )
    }

    private fun reject(
        sessionId: String,
        result: LocalSendResult,
    ): LocalSendResult {
        runtimeStateStore.publishSendFeedback(
            LocalSendFeedbackState(
                sessionId = sessionId,
                rejectReason = result.rejectReason,
                rejectLimit = result.rejectLimit,
            ),
        )
        return result
    }
}
