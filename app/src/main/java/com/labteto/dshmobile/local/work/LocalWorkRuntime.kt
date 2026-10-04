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
) {
    internal fun backgroundJobOutputForUi(jobId: String): String = engine.backgroundJobOutputForUi(jobId)
    internal fun stopBackgroundJobForUi(jobId: String): String = engine.stopBackgroundJobForUi(jobId)
    internal fun answerApproval(callId: String, approved: Boolean) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.answerApproval(callId, approved) == true) return
        runtimeStateStore.foregroundInteractions.answerApproval(callId, approved)
    }
    internal fun enableAutoApproval() = engine.enableAutoApproval()
    internal fun enableAutoApprovalForPending(callId: String) = engine.enableAutoApprovalForPending(callId)
    internal fun enableDeviceApprovalLease(callId: String) = engine.enableDeviceApprovalLease(callId)
    internal fun disableDeviceApprovalLease() = engine.disableDeviceApprovalLease()
    internal fun disableAutoApproval() = engine.disableAutoApproval()
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
    internal fun stop() = engine.stop()
    internal fun setPlanMode(enabled: Boolean) = engine.setPlanMode(enabled)
}
