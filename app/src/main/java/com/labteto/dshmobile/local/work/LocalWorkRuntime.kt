package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import javax.inject.Inject
import javax.inject.Singleton

/** Work/interaction capability boundary for the local UI. */
@Singleton
class LocalWorkRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
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
    internal fun stop() {
        if (workRunRegistry.requestCancel(runtimeStateStore.currentSessionId)) return
        engine.stopForegroundRun()
    }

    internal fun setPlanMode(enabled: Boolean) {
        planMode.setEnabled(enabled)
    }
}
