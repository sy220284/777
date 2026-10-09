package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.observability.AppLog
import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.LocalToolApprovalRuntime
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.project.ProjectContextPort
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.MAX_EVENT_CHARS
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.shouldAutoApproveTool
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.string
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
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
        if (reconcile) runCatching { agentTeams.recoverMailbox(sessionId) }
            .onFailure { AppLog.warn("LocalWorkComposition", "Agent Team 后台状态恢复失败 session=$sessionId", it) }
        runCatching { teamUiProjection.publish(sessionId) }
            .onFailure { AppLog.warn("LocalWorkComposition", "Agent Team UI 投影刷新失败 session=$sessionId", it) }
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
        runCatching { agentTeams.recoverMailbox(sessionId) }
            .onFailure { error ->
                AppLog.warn(
                    "LocalWorkComposition",
                    "Agent Team 启动恢复失败 session=$sessionId",
                    error,
                )
            }
        runCatching { teamUiProjection.publish(sessionId) }
            .onFailure { error ->
                AppLog.warn(
                    "LocalWorkComposition",
                    "Agent Team 启动投影刷新失败 session=$sessionId",
                    error,
                )
            }
        persistentJobs.schedule(sessionId)
    }

    private companion object {
        val TEAM_RECONCILE_JOB_STATUSES = setOf(
            "dormant",
            "completed",
            "failed",
            "cancelled",
            "killed",
        )
    }

}
