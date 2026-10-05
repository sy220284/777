package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.MAX_PENDING_INPUTS
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.send.prepareLocalSend
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.coordinateOwnedLocalSend
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job

/** Work-owned product execution entry. */
@Singleton
internal class LocalWorkExecutionCoordinator @Inject constructor(
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val turn: LocalWorkTurnPort,
) : LocalWorkExecutionPort {
    override fun send(
        text: String,
        attachments: List<LocalImportedAttachment>,
    ): LocalSendResult {
        val prepared = prepareLocalSend(text, attachments) ?: return LocalSendResult.Empty
        workRunRegistry.enqueueIntoLiveRun(prepared)?.let { return it }

        var started: Job? = null
        val result = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            workRunRegistry.enqueueIntoLiveRun(prepared)?.let { return@synchronized it }

            val snapshot = runtimeStateStore.state.value
            if (snapshot.usageMode != LocalUsageMode.WORK) {
                return@synchronized reject(
                    snapshot.sessionId,
                    LocalSendResult.rejected(LocalSendRejectReason.SESSION_TRANSITION),
                )
            }

            val sessionId = snapshot.sessionId
            val pending = runtimeStateStore.foregroundRunHandle.pendingInputs
            val queuedInput = QueuedAgentInput(
                prepared.content,
                prepared.memoryInput,
                prepared.modelMessage,
                UUID.randomUUID().toString(),
            )
            coordinateOwnedLocalSend(
                usageMode = LocalUsageMode.WORK,
                sessionId = sessionId,
                workBindingActive = false,
                visibleJobActive = runtimeStateStore.foregroundRunHandle.hasLiveJob(),
                configured = snapshot.modelState.configured,
                loading = snapshot.loading,
                sessionTransitioning = runtimeStateStore.sessionTransitioning,
                pendingCount = pending.size(),
                pendingLimit = MAX_PENDING_INPUTS,
                onRejected = { rejected -> reject(sessionId, rejected) },
                onAccepted = runtimeStateStore::clearSendFeedback,
                enqueue = {
                    pending.offer(queuedInput) {
                        val transcript = LocalHarnessMessage(
                            id = queuedInput.id,
                            role = "user",
                            content = prepared.content,
                            createdAt = System.currentTimeMillis(),
                        )
                        val event = sessionStorage.eventLogs.get(sessionId).append(
                            LOCAL_AGENT_INBOX_EVENT_TYPE,
                            encodeLocalAgentInboxEvent(
                                action = "queued",
                                pending = pending.snapshot(),
                                affected = listOf(queuedInput),
                                transcript = listOf(transcript),
                            ),
                        )
                        runtimeStateStore.projection.appendForegroundTranscript(
                            sessionId = sessionId,
                            messages = listOf(transcript),
                        )
                        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
                            maxOf(
                                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                                event.sequence,
                            )
                    }
                },
                onQueued = {
                    runtimeStateStore.projection.setForegroundQueuedInputCount(
                        sessionId = sessionId,
                        count = pending.size(),
                    )
                    check(sessionStorage.enqueueCurrentSnapshot(sessionId)) {
                        "Work 排队保存时前台会话已切换"
                    }
                },
                onStart = { reservedLease ->
                    started = turn.startPrepared(
                        prepared = prepared,
                        sessionLease = requireNotNull(reservedLease) {
                            "Work 首轮启动前必须持有前台 Session 租约"
                        },
                    )
                },
            )
        }
        started?.start()
        return result
    }

    override fun regenerateReply(messageId: String): Boolean =
        turn.regenerateReply(messageId)

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
