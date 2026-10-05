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
) {
    internal fun backgroundJobOutputForUi(jobId: String): String =
        runtimeStateStore.jobManager.output(jobId, runtimeStateStore.currentSessionId)
    internal fun stopBackgroundJobForUi(jobId: String): String =
        runtimeStateStore.jobManager.kill(jobId, runtimeStateStore.currentSessionId)
    internal fun answerApproval(callId: String, approved: Boolean) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.answerApproval(callId, approved) == true) return
        runtimeStateStore.foregroundInteractions.answerApproval(callId, approved)
    }
    internal fun enableAutoApproval() = approvals.enableAutoApproval()
    internal fun enableAutoApprovalForPending(callId: String) = approvals.enableAutoApprovalForPending(callId)
    internal fun enableDeviceApprovalLease(callId: String) = approvals.enableDeviceApprovalLease(callId)
    internal fun disableDeviceApprovalLease() = approvals.disableDeviceApprovalLease(runtimeStateStore.state.value.sessionId)
    internal fun disableAutoApproval() = approvals.disableAutoApproval()
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
