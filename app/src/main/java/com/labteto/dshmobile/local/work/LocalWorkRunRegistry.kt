package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import android.content.Context
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.syncForegroundJobs
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.runtime.MAX_PENDING_INPUTS
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.toLocalHarnessResourceState
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.coordinateOwnedLocalSend
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Work-owned registry for session-bound foreground runs.
 *
 * The registry is the single in-memory owner of active Work bindings. It observes shared Runtime
 * resource and background-job facts in the allowed Work -> Shared Capability direction; Runtime
 * never imports Work.
 */
@Singleton
class LocalWorkRunRegistry internal constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val notifyJobs: (List<LocalJobInfo>, (String) -> Unit) -> Unit = { _, _ -> },
    private val persistBinding: (LocalWorkRunBinding) -> Unit = {},
) {
    @Inject
    internal constructor(
        runtimeStateStore: LocalRuntimeStateStore,
        @ApplicationContext context: Context,
        preferences: LocalApprovalPreferences,
        sessionStorage: LocalSessionStorageRuntime,
    ) : this(
        runtimeStateStore = runtimeStateStore,
        notifyJobs = { jobs, onFailure ->
            syncForegroundJobs(context.applicationContext, jobs, onFailure)
        },
        persistBinding = { binding ->
            sessionStorage.enqueueSnapshot(binding.persistenceSnapshot())
        },
    ) { bindApprovalPreferences(preferences) }

    private val bindings = java.util.concurrent.ConcurrentHashMap<String, LocalWorkRunBinding>()
    private val jobProjectionLock = Any()
    private val resourceProjectionLock = Any()
    private var approvalPreferences: LocalApprovalPreferences? = null
    private val approvalProjectionLock = Any()

    internal fun bindApprovalPreferences(preferences: LocalApprovalPreferences) =
        synchronized(approvalProjectionLock) {
            if (approvalPreferences === preferences) return@synchronized
            check(approvalPreferences == null) { "Approval authority cannot be replaced" }
            approvalPreferences = preferences
            bindings.values.forEach { it.observeApprovalMode(preferences) }
        }


    init {
        runtimeStateStore.observeResourceSnapshots(::projectResourceSnapshot)
        runtimeStateStore.observeJobSnapshots(::projectJobSnapshot)
    }

    internal operator fun get(sessionId: String): LocalWorkRunBinding? = bindings[sessionId]

    internal fun state(sessionId: String): LocalHarnessState? = bindings[sessionId]?.aggregateSnapshot()

    internal fun live(sessionId: String): LocalWorkRunBinding? =
        bindings[sessionId]?.takeIf { it.runHandle.hasLiveJob() }

    /**
     * Queue an additional user input directly into an already-live Work binding.
     *
     * Returns null when no live Work binding exists so the caller can use the first-turn bridge.
     */
    internal fun enqueueIntoLiveRun(prepared: LocalPreparedSend): LocalSendResult? =
        synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            val snapshot = runtimeStateStore.state.value
            if (snapshot.usageMode != LocalUsageMode.WORK) return@synchronized null
            val binding = live(snapshot.sessionId) ?: return@synchronized null
            if (binding.runHandle.cancellationRequested) {
                return@synchronized LocalSendResult.rejected(LocalSendRejectReason.SESSION_TRANSITION)
            }
            val pending = binding.runHandle.pendingInputs
            val queuedInput = QueuedAgentInput(
                prepared.content,
                prepared.memoryInput,
                prepared.modelMessage,
                UUID.randomUUID().toString(),
            )
            coordinateOwnedLocalSend(
                usageMode = LocalUsageMode.WORK,
                sessionId = snapshot.sessionId,
                workBindingActive = true,
                visibleJobActive = runtimeStateStore.foregroundRunHandle.hasLiveJob(),
                configured = snapshot.modelState.configured,
                loading = snapshot.loading,
                sessionTransitioning = runtimeStateStore.sessionTransitioning,
                pendingCount = pending.size(),
                pendingLimit = MAX_PENDING_INPUTS,
                onRejected = { rejected ->
                    runtimeStateStore.publishSendFeedback(
                        LocalSendFeedbackState(
                            sessionId = snapshot.sessionId,
                            rejectReason = rejected.rejectReason,
                            rejectLimit = rejected.rejectLimit,
                        ),
                    )
                },
                onAccepted = runtimeStateStore::clearSendFeedback,
                enqueue = {
                    pending.offer(queuedInput) {
                        val transcriptMessage = binding.transcriptRuntime
                            .newMessage(
                                role = "user",
                                content = prepared.visibleContent,
                                blocks = prepared.blocks,
                            )
                            .copy(id = queuedInput.id)

                        val event = binding.eventLog.append(
                            LOCAL_AGENT_INBOX_EVENT_TYPE,
                            encodeLocalAgentInboxEvent(
                                action = "queued",
                                pending = pending.snapshot(),
                                affected = listOf(queuedInput),
                                transcript = listOf(transcriptMessage),
                            ),
                        )
                        binding.transcriptRuntime.applyMessages(
                            listOf(transcriptMessage),
                            event.sequence,
                        )
                    }
                },
                onQueued = {
                    binding.state.update { current ->
                        current.copy(
                            kernel = current.kernel.copy(
                                queuedInputCount = pending.size(),
                            ),
                            error = null,
                        )
                    }
                    persistBinding(binding)
                },
                onStart = {
                    error("已有 Work binding 的追加输入不得重新启动首轮运行")
                },
            )
        }

    /**
     * Finalize one Work-owned turn and either detach the completed binding or claim its next input.
     *
     * The registry owns binding lifecycle, durable checkpointing, queue hand-off and visible
     * projection. The caller supplies only a lazy execution factory; the Agent loop remains
     * Work-owned with no process-level fallback.
     */
    internal fun finishTurn(
        binding: LocalWorkRunBinding,
        completedJob: Job?,
        startNext: (QueuedAgentInput, LocalWorkRunBinding) -> Job,
    ): Job? = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
        if (binding.runHandle.job !== completedJob) return@synchronized null

        var successor: Job? = null
        try {
            try {
                checkpointModelHistory(binding, "work/background-turn-end")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportPersistenceFailure(binding, "Work 回合检查点保存失败", error)
                return@synchronized null
            }
            persistDerivedBinding(binding)
            if (binding.runHandle.cancellationRequested) return@synchronized null

            val next = binding.runHandle.pendingInputs.pollCommitted { input, remaining ->
                val message = input.modelMessage ?: buildJsonObject {
                    put("role", "user")
                    put("content", input.content)
                }
                binding.eventLog.append(
                    LOCAL_AGENT_INBOX_EVENT_TYPE,
                    encodeLocalAgentInboxEvent(
                        action = "resumed",
                        pending = remaining,
                        affected = listOf(input),
                        modelMessages = listOf(message),
                    ),
                )
            }
            if (next == null) return@synchronized null
            // A separate user input is a fresh task allowance. Only internal continuation
            // inputs retain the bounded count for their original interrupted task.
            if (!next.id.startsWith("continuation-")) {
                binding.automaticContinuationCount = 0
                binding.continuationParentRunId = null
            }

            val durableMessage = next.modelMessage ?: buildJsonObject {
                put("role", "user")
                put("content", next.content)
            }
            binding.state.update { current ->
                current.copy(
                    kernel = current.kernel.copy(
                        queuedInputCount = binding.runHandle.pendingInputs.size(),
                    ),
                )
            }
            binding.runHandle.modelHistory.append(durableMessage)
            refreshBindingContextMetrics(binding)
            persistDerivedBinding(binding)
            successor = startNext(next, binding)
            binding.runHandle.job = successor
            successor
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportPersistenceFailure(binding, "Work 续轮失败，已有输入保留在会话日志", error)
            null
        } finally {
            if (successor == null) releaseFinishedBinding(binding)
        }
    }

    /** Also covers cancellation before a lazy turn starts and failures during turn setup. */
    internal fun releaseCompletedTurn(binding: LocalWorkRunBinding, completedJob: Job) {
        synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            if (binding.runHandle.job === completedJob) releaseFinishedBinding(binding)
        }
    }

    private fun persistDerivedBinding(binding: LocalWorkRunBinding) {
        try {
            persistBinding(binding)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportPersistenceFailure(binding, "Work 已提交，快照保存失败", error)
        }
    }

    private fun reportPersistenceFailure(binding: LocalWorkRunBinding, message: String, error: Throwable) {
        binding.state.update { current -> current.copy(error = "$message：${error.message}") }
        mirrorVisible(binding)
    }

    private fun releaseFinishedBinding(binding: LocalWorkRunBinding) {
        binding.runHandle.job = null
        detach(binding)
        binding.runHandle.projectionJob?.cancel()
        binding.runHandle.projectionJob = null
        binding.state.update { current -> current.copy(kernel = current.kernel.copy(running = false)) }
        if (
            runtimeStateStore.currentSessionId == binding.sessionId &&
            runtimeStateStore.state.value.sessionId == binding.sessionId
        ) {
            val foreground = runtimeStateStore.foregroundRunHandle
            foreground.modelHistory.reset(binding.runHandle.modelHistory.snapshot())
            foreground.transcriptProjectionCursor = binding.runHandle.transcriptProjectionCursor
            foreground.cancellationRequested = binding.runHandle.cancellationRequested
            binding.runHandle.pendingInputs.transferTo(foreground.pendingInputs)
            mirrorVisible(binding)
        }
    }

    private fun checkpointModelHistory(binding: LocalWorkRunBinding, reason: String) {
        val codec = ModelHistoryCheckpointCodec()
        binding.eventLog.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            codec.encode(
                messages = durableModelHistorySnapshot(binding.runHandle.modelHistory.snapshot()),
                reason = reason,
                asOfSequence = binding.eventLog.latestSequence(),
            ),
        )
        binding.runHandle.turnsSinceModelHistoryCheckpoint = 0
    }

    private fun refreshBindingContextMetrics(binding: LocalWorkRunBinding) {
        val resourceSnapshot = runtimeStateStore.resourceSnapshot()
        binding.state.update { current ->
            current.copy(
                kernel = current.kernel.copy(
                    contextChars = binding.runHandle.modelHistory.encodedChars,
                    contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(
                        current.modelState,
                        resourceSnapshot,
                    ),
                ),
            )
        }
    }

    internal fun attach(binding: LocalWorkRunBinding): LocalWorkRunBinding? {
        val previous = synchronized(approvalProjectionLock) {
            bindings.put(binding.sessionId, binding).also {
                it?.stopApprovalProjection()
                approvalPreferences?.let(binding::observeApprovalMode)
            }
        }
        synchronized(resourceProjectionLock) {
            projectResourceSnapshot(binding, runtimeStateStore.resourceSnapshot())
        }
        refreshJobProjection(notify = false)
        return previous
    }

    internal fun detach(sessionId: String): LocalWorkRunBinding? =
        synchronized(approvalProjectionLock) {
            bindings.remove(sessionId)?.also { it.stopApprovalProjection() }
        }

    internal fun detach(binding: LocalWorkRunBinding): Boolean =
        synchronized(approvalProjectionLock) {
            bindings.remove(binding.sessionId, binding).also { removed ->
                if (removed) binding.stopApprovalProjection()
            }
        }

    internal fun requestCancel(sessionId: String): Boolean =
        bindings[sessionId]?.requestCancel() == true

    internal fun detachAll(sessionIds: Set<String>): List<LocalWorkRunBinding> =
        sessionIds.mapNotNull(::detach)

    internal fun anyLive(): Boolean = bindings.values.any { it.runHandle.hasLiveJob() }

    internal fun mirrorVisible(binding: LocalWorkRunBinding) {
        if (
            runtimeStateStore.currentSessionId != binding.sessionId ||
            runtimeStateStore.state.value.sessionId != binding.sessionId
        ) return
        runtimeStateStore.projection.projectVisibleWorkRun(
            sessionId = binding.sessionId,
            snapshot = binding.aggregateSnapshot(),
        )
    }

    internal fun forEachBinding(block: (LocalWorkRunBinding) -> Unit) {
        bindings.values.toList().forEach(block)
    }

    internal fun forEachEntry(block: (String, LocalWorkRunBinding) -> Unit) {
        bindings.entries.toList().forEach { (sessionId, binding) -> block(sessionId, binding) }
    }

    private fun projectJobSnapshot(@Suppress("UNUSED_PARAMETER") snapshot: List<LocalJobInfo>) {
        refreshJobProjection()
    }

    private fun refreshJobProjection(notify: Boolean = true) = synchronized(jobProjectionLock) {
        // Initial subscription replay and binding attach can race a job callback. Read the
        // authoritative snapshot inside the projection lock instead of replaying a captured list.
        val snapshot = runtimeStateStore.jobManager.snapshotInfos()
        runtimeStateStore.projection.projectJobs(snapshot)
        projectJobSnapshotToRunStates(snapshot, this)
        if (notify) {
            notifyJobs(snapshot, runtimeStateStore.projection::publishError)
        }
    }

    private fun projectResourceSnapshot(@Suppress("UNUSED_PARAMETER") snapshot: HarnessResourceSnapshot) {
        synchronized(resourceProjectionLock) {
            val latest = runtimeStateStore.resourceSnapshot()
            bindings.values.toList().forEach { binding ->
                projectResourceSnapshot(binding, latest)
            }
        }
    }

    private fun projectResourceSnapshot(
        binding: LocalWorkRunBinding,
        snapshot: HarnessResourceSnapshot,
    ) {
        binding.state.update { current ->
            current.copy(
                kernel = current.kernel.copy(
                    resources = snapshot.toLocalHarnessResourceState(LocalUsageMode.WORK),
                    contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(current.modelState, snapshot),
                ),
            )
        }
    }
}
