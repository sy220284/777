package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.local.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.MAX_PENDING_INPUTS
import com.labteto.dshmobile.local.encodeLocalAgentInboxEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update

/**
 * Process-local owner of the legacy visible foreground run slot.
 *
 * The slot is shared runtime state: Chat and the remaining migration fallback may use it, while
 * session-bound Work owns its own [com.labteto.dshmobile.local.LocalWorkRunBinding]. Keeping the
 * Job and durable pending inbox here prevents Feature runtimes from depending on LocalHarnessEngine
 * merely to stop the visible run.
 */
@Singleton
class LocalForegroundRunOwner @Inject internal constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val eventLogRegistry: LocalSessionEventLogRegistry,
) {
    internal val pendingInputs = AgentInputQueue(MAX_PENDING_INPUTS)

    @Volatile
    private var activeJob: Job? = null

    internal fun isActive(): Boolean = activeJob?.isCompleted == false

    internal fun currentJob(): Job? = activeJob

    internal fun bind(job: Job): Job = job.also {
        synchronized(this) { activeJob = it }
    }

    internal fun clearIf(job: Job?) {
        synchronized(this) {
            if (activeJob === job) activeJob = null
        }
    }

    /**
     * Cancel the visible foreground slot without waiting for teardown.
     *
     * Session-bound Work cancellation is handled by LocalWorkRunBinding first. This method owns only
     * the legacy/shared visible slot and its durable pending inbox.
     */
    internal fun requestCancel(): Boolean {
        runtimeStateStore.foregroundInteractions.cancelAll()
        val sessionId = runtimeStateStore.currentSessionId
        val (running, discarded) = synchronized(this) {
            val removed = pendingInputs.drain()
            if (removed.isNotEmpty()) {
                eventLogRegistry.get(sessionId).append(
                    LOCAL_AGENT_INBOX_EVENT_TYPE,
                    encodeLocalAgentInboxEvent(
                        action = "cancelled",
                        pending = pendingInputs.snapshot(),
                        affected = removed,
                    ),
                )
            }
            runtimeStateStore.mutableState.update { current ->
                current.copy(
                    work = current.work.copy(
                        pendingApproval = null,
                        pendingQuestion = null,
                    ),
                    kernel = current.kernel.copy(queuedInputCount = 0),
                )
            }
            activeJob to removed
        }
        running?.cancel()
        return running != null || discarded.isNotEmpty()
    }

    /** Cancel and fully release the current visible slot before a session transition. */
    internal suspend fun cancelAndJoin() {
        requestCancel()
        val job = currentJob()
        job?.cancelAndJoin()
        clearIf(job)
    }
}
