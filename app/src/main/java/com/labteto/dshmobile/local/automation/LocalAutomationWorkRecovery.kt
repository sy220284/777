package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.resolveRecoveryModelProfile
import com.labteto.dshmobile.local.runtime.LocalAgentRunCoordinator
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.LocalAgentRunRecoveryContextPolicy
import com.labteto.dshmobile.local.runtime.LocalHarnessBlockedException
import com.labteto.dshmobile.local.session.LocalSessionEventLog

internal data class LocalAutomationWorkRecoveryPlan(
    val executionTask: String,
    val profile: LocalModelProfile? = null,
    val completedOutput: String? = null,
)

internal fun prepareAutomationWorkRecovery(
    prompt: String,
    sessionId: String,
    eventLog: LocalSessionEventLog,
    profiles: List<LocalModelProfile>,
    agentRunCoordinator: LocalAgentRunCoordinator,
    contextPolicy: LocalAgentRunRecoveryContextPolicy? = null,
): LocalAutomationWorkRecoveryPlan {
    val repair = eventLog.repairInterruptedTail(force = true)
    val decision = agentRunCoordinator.recoveryDecision(
        sessionId = sessionId,
        repair = repair,
        kind = LocalAgentRunKind.AUTOMATION,
        contextPolicy = contextPolicy,
    ) ?: return LocalAutomationWorkRecoveryPlan(prompt)

    decision.completedOutput?.let { output ->
        return LocalAutomationWorkRecoveryPlan(
            executionTask = prompt,
            completedOutput = output.ifBlank { "后台任务已完成" },
        )
    }

    decision.blockedReason?.let { reason ->
        agentRunCoordinator.markRecoveryBlocked(
            sessionId = sessionId,
            runId = decision.runId,
            reason = reason,
            kind = LocalAgentRunKind.AUTOMATION,
        )
        throw LocalHarnessBlockedException(reason, sessionId)
    }

    val queued = decision.queuedInput ?: return LocalAutomationWorkRecoveryPlan(prompt)
    val identity = decision.route
    val profile = identity?.let { resolveRecoveryModelProfile(profiles, it) }
    if (identity == null || profile == null) {
        val reason = "后台任务原模型路由已不存在或身份发生变化，已停止自动续跑。"
        agentRunCoordinator.markRecoveryBlocked(
            sessionId = sessionId,
            runId = decision.runId,
            reason = reason,
            kind = LocalAgentRunKind.AUTOMATION,
        )
        throw LocalHarnessBlockedException(reason, sessionId)
    }

    agentRunCoordinator.markRecoveryQueued(
        sessionId = sessionId,
        runId = decision.runId,
        kind = LocalAgentRunKind.AUTOMATION,
    )
    return LocalAutomationWorkRecoveryPlan(
        executionTask = queued.content,
        profile = profile,
    )
}
