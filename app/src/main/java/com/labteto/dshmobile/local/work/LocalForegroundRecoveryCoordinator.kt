package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.resolveRecoveryModelProfile
import com.labteto.dshmobile.local.runtime.LocalAgentRunCoordinator
import com.labteto.dshmobile.local.runtime.LocalAgentRunRecoveryDecision
import com.labteto.dshmobile.local.session.LocalSessionEventLog

internal data class LocalForegroundRecoveryResult(
    val profile: LocalModelProfile? = null,
    val error: String? = null,
    val autoResumeAllowed: Boolean = true,
)

/** Owns persisted foreground-run recovery after runtime ownership has been ruled out. */
internal class LocalForegroundRecoveryCoordinator(
    private val agentRunCoordinator: LocalAgentRunCoordinator,
    private val hasCredential: suspend (LocalModelProfile) -> Boolean,
) {
    suspend fun restore(
        sessionId: String,
        decision: LocalAgentRunRecoveryDecision?,
        profiles: List<LocalModelProfile>,
        pendingInputs: AgentInputQueue,
        eventLog: LocalSessionEventLog,
    ): LocalForegroundRecoveryResult {
        decision ?: return LocalForegroundRecoveryResult()
        val route = decision.route
        val exactProfile = route?.let { resolveRecoveryModelProfile(profiles, it) }
        val blocked = when {
            decision.blockedReason != null -> decision.blockedReason
            route != null && (exactProfile == null || !hasCredential(exactProfile)) ->
                "上次任务绑定的模型账户或凭据身份已变化，已停止自动续跑。请恢复原模型配置后再继续。"
            else -> null
        }
        if (blocked != null) {
            // Keep blocked inputs in the event archive, outside the executable inbox.
            pendingInputs.drainCommitted { blockedInputs ->
                eventLog.append(
                    LOCAL_AGENT_INBOX_EVENT_TYPE,
                    encodeLocalAgentInboxEvent(
                        action = "recovery-blocked",
                        pending = emptyList(),
                        affected = blockedInputs,
                    ),
                )
            }
            agentRunCoordinator.markRecoveryBlocked(sessionId, decision.runId, blocked)
            return LocalForegroundRecoveryResult(error = blocked, autoResumeAllowed = false)
        }

        val queued = decision.queuedInput ?: return LocalForegroundRecoveryResult(profile = exactProfile)
        if (pendingInputs.snapshot().any { it.id == queued.id }) {
            return LocalForegroundRecoveryResult(profile = exactProfile)
        }
        if (!pendingInputs.offer(queued) {
            eventLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "recovered-run",
                    pending = pendingInputs.snapshot(),
                    affected = listOf(queued),
                ),
            )
        }) {
            val error = "上次任务可以安全续跑，但待处理输入队列已满，请先处理现有任务。"
            agentRunCoordinator.markRecoveryBlocked(sessionId, decision.runId, error)
            return LocalForegroundRecoveryResult(profile = exactProfile, error = error, autoResumeAllowed = false)
        }

        agentRunCoordinator.markRecoveryQueued(sessionId, decision.runId)
        return LocalForegroundRecoveryResult(profile = exactProfile)
    }
}
