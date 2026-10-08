package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** Work/interaction capability boundary for the local UI. */
@Singleton
class LocalWorkRuntime @Inject internal constructor(
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val eventLogs: LocalSessionEventLogRegistry,
    private val approvals: LocalWorkApprovalCoordinator,
    private val planMode: LocalWorkPlanModeCoordinator,
    private val execution: LocalWorkExecutionPort,
    private val agentUi: LocalWorkAgentUiPort,
) {
    internal fun send(
        text: String,
        attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList(),
    ): com.labteto.dshmobile.local.send.LocalSendResult = execution.send(text, attachments)

    internal fun sendWithTeam(
        text: String,
        attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList(),
    ): com.labteto.dshmobile.local.send.LocalSendResult = execution.sendWithTeam(text, attachments)

    internal fun regenerateReply(messageId: String): Boolean = execution.regenerateReply(messageId)

    internal fun artifactsForUi(sessionId: String): List<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem> {
        if (sessionId != runtimeStateStore.currentSessionId) return emptyList()
        return projectLocalWorkArtifacts(eventLogs.get(sessionId).pageBeforeChronological(limit = 384))
    }

    internal fun backgroundJobOutputForUi(jobId: String): String =
        runtimeStateStore.jobManager.output(jobId, runtimeStateStore.currentSessionId)
    internal fun stopBackgroundJobForUi(jobId: String): String =
        runtimeStateStore.jobManager.kill(jobId, runtimeStateStore.currentSessionId)
    internal suspend fun startBackgroundAgentForUi(task: String): LocalWorkAgentUiResult =
        agentUi.startBackgroundAgent(task)
    internal suspend fun startResearchAgentForUi(task: String): LocalWorkAgentUiResult =
        agentUi.startResearchAgent(task)
    internal suspend fun sendBackgroundAgentMessageForUi(agentId: String, message: String): LocalWorkAgentUiResult =
        agentUi.sendMessage(agentId, message)
    internal suspend fun sendTeamMemberMessageForUi(memberId: String, message: String): LocalWorkAgentUiResult =
        agentUi.sendTeamMessage(memberId, message)
    internal suspend fun stopTeamMemberForUi(memberId: String): LocalWorkAgentUiResult =
        agentUi.stopTeamMember(memberId)
    internal suspend fun stopTeamForUi(): LocalWorkAgentUiResult =
        agentUi.stopTeam()
    internal fun answerApproval(callId: String, approved: Boolean) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.answerApproval(callId, approved) == true) return
        runtimeStateStore.foregroundInteractions.answerApproval(callId, approved)
    }
    internal fun enableAutoApproval() =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.enableAutoApproval() }
    internal fun enableAutoApprovalForPending(callId: String) =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.enableAutoApprovalForPending(callId) }
    internal fun enableDeviceApprovalLease(callId: String) =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.enableDeviceApprovalLease(callId) }
    internal fun disableDeviceApprovalLease() =
        runtimeStateStore.performVisibleOperation("设备授权撤销保存失败") {
            approvals.disableDeviceApprovalLease(runtimeStateStore.state.value.sessionId)
        }
    internal fun disableAutoApproval() =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.disableAutoApproval() }
    internal fun answerQuestion(callId: String, answer: String) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.answerQuestion(callId, answer) == true) return
        runtimeStateStore.foregroundInteractions.answerQuestion(callId, answer)
    }
    internal fun cancelQuestion(callId: String) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.cancelQuestion(callId) == true) return
        runtimeStateStore.foregroundInteractions.cancelQuestion(callId)
    }
    internal fun stop() = runtimeStateStore.performVisibleOperation("停止任务时保存失败") {
        val sessionId = runtimeStateStore.currentSessionId
        val snapshot = runtimeStateStore.state.value
        if (snapshot.sessionId != sessionId || snapshot.usageMode != LocalUsageMode.WORK) return@performVisibleOperation
        if (workRunRegistry.requestCancel(sessionId)) return@performVisibleOperation

        runtimeStateStore.cancelForegroundRun(eventLogs.get(sessionId))
    }

    internal fun setPlanMode(enabled: Boolean) =
        runtimeStateStore.performVisibleOperation("计划模式切换失败") {
            planMode.setEnabled(enabled)
        }
}
