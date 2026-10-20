package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.put

/** Owns mailbox reconciliation, job transitions and their visible projection. */
internal class LocalWorkTeamLifecycle(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val agentTeams: LocalAgentTeamRuntime,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
    private val teamUiProjection: LocalWorkTeamUiProjection,
    scope: CoroutineScope,
) {
    private data class TeamJobSignal(
        val status: String,
        val updatedAt: Long,
        val pendingMessageCount: Int,
    )
    private val teamJobSignals = ConcurrentHashMap<String, TeamJobSignal>()
    private val teamRefreshQueue: LocalTeamRefreshQueue = LocalTeamRefreshQueue(scope) { sessionId, reconcile ->
        try {
            if (reconcile) agentTeams.recoverMailboxAfterProjection(sessionId)
            else agentTeams.awaitProjection(sessionId)
            teamUiProjection.publish(sessionId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            AppLog.warn("LocalWorkComposition", "Agent Team 状态恢复或投影刷新失败 session=$sessionId", error)
        }
    }

    init {
        runtimeStateStore.observeJobSnapshots(::observeTeamJobTransitions)
    }

    private fun observeTeamJobTransitions(jobs: List<LocalJobInfo>) {
        val teamJobs = jobs.filter { LocalAgentTeamContract.isTeamJobId(it.id) }
        val visibleIds = teamJobs.mapTo(hashSetOf(), LocalJobInfo::id)
        teamJobSignals.keys.toList()
            .filterNot(visibleIds::contains)
            .forEach(teamJobSignals::remove)

        data class SessionRefresh(
            var reconcile: Boolean = false,
        )
        val sessions = linkedMapOf<String, SessionRefresh>()

        teamJobs.forEach { job ->
            val current = TeamJobSignal(
                status = job.status,
                updatedAt = job.updatedAt,
                pendingMessageCount = job.pendingMessageCount,
            )
            val previous = teamJobSignals.put(job.id, current)
            if (previous == current) return@forEach

            val sessionId = job.ownerSessionId ?: return@forEach
            val refresh = sessions.getOrPut(sessionId, ::SessionRefresh)
            if (job.status in TEAM_RECONCILE_JOB_STATUSES &&
                (previous?.status != current.status || previous.pendingMessageCount != current.pendingMessageCount)
            ) {
                refresh.reconcile = true
            }
        }

        sessions.forEach { (sessionId, refresh) ->
            teamRefreshQueue.schedule(sessionId, refresh.reconcile)
        }
    }

    fun projectionReady(sessionId: String) = teamRefreshQueue.schedule(sessionId, reconcile = true)

    fun recoverVisibleSession() {
        val sessionId = runtimeStateStore.currentSessionId
        // Historical replay may exceed one bounded batch; reconcile only after it is complete.
        teamRefreshQueue.schedule(sessionId, reconcile = true)
        persistentJobs.schedule(sessionId)
    }

    private companion object {
        val TEAM_RECONCILE_JOB_STATUSES = setOf(
            "dormant",
            "completed",
            "failed",
            "cancelled",
            "killed",
            "waiting_slice",
        )
    }

}
