package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import android.app.ActivityManager
import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.interaction.LocalInteractionCoordinator
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.localHistoryBudgetFor
import com.labteto.dshmobile.local.model.LocalImageCapabilityRegistry
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.model.LocalStreamingPreviewStore
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Process-wide owner of shared local runtime state and scarce-resource scheduling.
 *
 * Feature capabilities may observe shared resource snapshots, but this Runtime owner never imports
 * Feature internals. Engine bootstrap initializes the first visible snapshot exactly once.
 */
@Singleton
class LocalRuntimeStateStore internal constructor(
    internal val memoryClassMb: Int = 256,
    private val jobOwner: LocalRuntimeJobOwner = LocalRuntimeJobOwner.inMemory(),
) {
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        json: Json,
        approvals: LocalApprovalPreferences,
    ) : this(
        memoryClassMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 256,
        jobOwner = LocalRuntimeJobOwner.persistent(context, json),
    ) { bindApprovalPreferences(approvals) }
    private val mutable = MutableStateFlow(LocalHarnessState())
    internal val projection = LocalRuntimeProjection(mutable)
    private var approvalPreferences: LocalApprovalPreferences? = null
    private var approvalProjection: Job? = null

    @Synchronized
    internal fun bindApprovalPreferences(preferences: LocalApprovalPreferences) {
        if (approvalPreferences === preferences) return
        check(approvalPreferences == null) { "Approval authority cannot be replaced" }
        approvalPreferences = preferences
        approvalProjection = preferences.observeMode { enabled ->
            mutable.update { it.copy(safeAutoApprovalEnabled = enabled) }
        }
    }

    private val sendFeedbackMutable = MutableStateFlow(LocalSendFeedbackState())
    private val resourceProjectionLock = Any()
    private val resourceObservers = CopyOnWriteArrayList<(HarnessResourceSnapshot) -> Unit>()

    internal val imageCapabilities = LocalImageCapabilityRegistry()
    internal val resourceBudget = localResourceBudgetForMemoryClass(memoryClassMb)
    internal val resourceScheduler = HarnessResourceScheduler(
        budget = resourceBudget,
        onChanged = ::publishResourceSnapshot,
    )

    internal val foregroundInteractions = LocalInteractionCoordinator(mutable)
    internal val streamingPreviewStore = LocalStreamingPreviewStore()
    internal val foregroundModelHistory = LocalModelHistoryBuffer()
    internal val foregroundRunLock = Any()
    internal val foregroundPendingInputs = AgentInputQueue(MAX_PENDING_INPUTS)
    @Volatile internal var foregroundJob: Job? = null
    @Volatile internal var foregroundTranscriptProjectionCursor: Long? = null
    @Volatile internal var foregroundTurnsSinceModelHistoryCheckpoint: Int = 0
    internal val jobManager: LocalJobManager
        get() = jobOwner.manager
    @Volatile private var initialized = false
    @Volatile private var foregroundSessionId: String? = null

    internal val state: StateFlow<LocalHarnessState> = mutable.asStateFlow()
    internal val sendFeedbackState: StateFlow<LocalSendFeedbackState> = sendFeedbackMutable.asStateFlow()
    internal val currentSessionId: String
        get() = foregroundSessionId ?: error("LocalRuntimeStateStore 尚未初始化")

    internal fun observeResourceSnapshots(observer: (HarnessResourceSnapshot) -> Unit) {
        synchronized(resourceProjectionLock) {
            resourceObservers += observer
            runCatching { observer(resourceScheduler.snapshot()) }
        }
    }

    internal fun observeJobSnapshots(observer: (List<LocalJobInfo>) -> Unit) {
        jobOwner.observe(observer)
    }

    internal fun resourceSnapshot(): HarnessResourceSnapshot = resourceScheduler.snapshot()

    internal fun contextBudgetCharsFor(
        state: LocalHarnessState,
        snapshot: HarnessResourceSnapshot,
    ): Int = contextBudgetCharsFor(state.modelState, snapshot)

    internal fun contextBudgetCharsFor(
        modelState: LocalModelState,
        snapshot: HarnessResourceSnapshot,
    ): Int = localHistoryBudgetFor(
        memoryClassMb = memoryClassMb,
        pressure = snapshot.pressure,
        model = modelState.model,
        baseUrl = modelState.baseUrl,
        contextWindowTokensOverride =
            modelState.modelSelection.activeProfile?.contextWindowTokensOverride,
    ).maxHistoryChars

    internal suspend fun <T> withModelRequestResource(block: suspend () -> T): T =
        resourceScheduler.withResource(
            HarnessResourceKind.MODEL_REQUEST,
            "automation-planning",
            block,
        )

    @Synchronized
    internal fun initialize(initialState: LocalHarnessState): MutableStateFlow<LocalHarnessState> {
        check(!initialized) { "LocalRuntimeStateStore 已完成初始化" }
        require(initialState.sessionId.isNotBlank()) { "初始会话编号不能为空" }
        foregroundSessionId = initialState.sessionId
        mutable.value = projectResourceSnapshot(
            initialState.copy(safeAutoApprovalEnabled =
                approvalPreferences?.enabled?.value ?: initialState.safeAutoApprovalEnabled),
            resourceScheduler.snapshot(),
        )
        initialized = true
        return mutable
    }

    internal fun publishSendFeedback(feedback: LocalSendFeedbackState) {
        sendFeedbackMutable.value = feedback
    }

    internal fun clearSendFeedback() {
        sendFeedbackMutable.value = LocalSendFeedbackState()
    }

    /** UI-bound operations report failure only to the originating visible Session. */
    internal fun performVisibleOperation(failureMessage: String, block: () -> Unit) {
        val before = state.value
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutable.update { current ->
                if (current.sessionId == before.sessionId && current.usageMode == before.usageMode) {
                    current.copy(error = "$failureMessage：${error.message ?: error::class.java.simpleName}")
                } else current
            }
        }
    }

    /**
     * Cancel the one visible foreground run while preserving its single inbox and Job owner.
     *
     * The caller supplies the already-authorized Session EventLog; Runtime owns queue draining,
     * cancellation ordering and visible queued-count projection, but no Feature-specific state.
     */
    internal fun cancelForegroundRun(eventLog: LocalSessionEventLog): Boolean =
        requestForegroundCancellation(eventLog) {}

    internal suspend fun cancelForegroundRunAndJoin(eventLog: LocalSessionEventLog) {
        var owner: Job? = null
        try {
            requestForegroundCancellation(eventLog) { owner = it }
        } finally {
            withContext(NonCancellable) {
                owner?.join()
                synchronized(foregroundRunLock) {
                    if (foregroundJob === owner) foregroundJob = null
                }
            }
        }
    }

    private fun requestForegroundCancellation(
        eventLog: LocalSessionEventLog,
        captureOwner: (Job?) -> Unit,
    ): Boolean {
        foregroundInteractions.cancelAll()
        var running: Job? = null
        try {
            synchronized(foregroundRunLock) {
                running = foregroundJob
                captureOwner(running)
                val discarded = foregroundPendingInputs.drain()
                try {
                    if (discarded.isNotEmpty()) {
                        eventLog.append(
                            LOCAL_AGENT_INBOX_EVENT_TYPE,
                            encodeLocalAgentInboxEvent(
                                action = "cancelled",
                                pending = foregroundPendingInputs.snapshot(),
                                affected = discarded,
                            ),
                        )
                    }
                } finally {
                    mutable.update { current ->
                        current.copy(kernel = current.kernel.copy(queuedInputCount = 0))
                    }
                }
            }
            return running?.isCompleted == false
        } finally {
            // Durable cancellation diagnostics may fail; that cannot veto the real stop request.
            running?.cancel()
        }
    }

    @Synchronized
    internal fun activateSession(sessionId: String) {
        check(initialized) { "LocalRuntimeStateStore 尚未初始化" }
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        foregroundSessionId = sessionId
    }

    private fun publishResourceSnapshot(@Suppress("UNUSED_PARAMETER") snapshot: HarnessResourceSnapshot) {
        synchronized(resourceProjectionLock) {
            // Scheduler callbacks and initial replay may arrive out of order. Re-read the
            // authority under the projection lock instead of publishing a captured snapshot.
            val latest = resourceScheduler.snapshot()
            mutable.update { current -> projectResourceSnapshot(current, latest) }
            resourceObservers.forEach { observer ->
                runCatching { observer(resourceScheduler.snapshot()) }
            }
        }
    }

    private fun projectResourceSnapshot(
        state: LocalHarnessState,
        snapshot: HarnessResourceSnapshot,
    ): LocalHarnessState = state.copy(
        kernel = state.kernel.copy(
            resources = snapshot.toLocalHarnessResourceState(state.usageMode),
            contextBudgetChars = contextBudgetCharsFor(state, snapshot),
        ),
    )
}
