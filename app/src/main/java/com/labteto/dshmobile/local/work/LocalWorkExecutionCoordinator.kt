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
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.coordinateOwnedLocalSend
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Work-owned product execution entry. */
@Singleton
internal class LocalWorkExecutionCoordinator internal constructor(
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val enqueueSnapshot: (String) -> Boolean,
    private val startPreparedTurn: (LocalPreparedSend, com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease) -> Job,
    private val startRegeneration: (String) -> Job,
) : LocalWorkExecutionPort {
    @Inject
    internal constructor(
        workRunRegistry: LocalWorkRunRegistry,
        runtimeStateStore: LocalRuntimeStateStore,
        sessionStorage: LocalSessionStorageRuntime,
        turn: LocalWorkTurnPort,
        regenerator: LocalWorkReplyRegenerator,
    ) : this(
        workRunRegistry = workRunRegistry,
        runtimeStateStore = runtimeStateStore,
        eventLogFor = sessionStorage.eventLogs::get,
        enqueueSnapshot = sessionStorage::enqueueCurrentSnapshot,
        startPreparedTurn = turn::startPrepared,
        startRegeneration = regenerator::start,
    )

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
                        val event = eventLogFor(sessionId).append(
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
                    check(enqueueSnapshot(sessionId)) {
                        "Work 排队保存时前台会话已切换"
                    }
                },
                onStart = { reservedLease ->
                    started = startPreparedTurn(
                        prepared,
                        requireNotNull(reservedLease) {
                            "Work 首轮启动前必须持有前台 Session 租约"
                        },
                    )
                },
            )
        }
        started?.start()
        return result
    }

    override fun regenerateReply(messageId: String): Boolean {
        var started: Job? = null
        val handle = runtimeStateStore.foregroundRunHandle
        val accepted = synchronized(handle.lock) {
            val state = runtimeStateStore.state.value
            if (
                state.usageMode != LocalUsageMode.WORK ||
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
            if (state.messages.dropLast(1).none { it.role == "user" }) return@synchronized false
            if (
                handle.modelHistory.lastOrNull()
                    ?.get("role")
                    ?.jsonPrimitive
                    ?.contentOrNull != "assistant"
            ) return@synchronized false

            started = startRegeneration(messageId).also { handle.job = it }
            true
        }
        started?.start()
        return accepted
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
