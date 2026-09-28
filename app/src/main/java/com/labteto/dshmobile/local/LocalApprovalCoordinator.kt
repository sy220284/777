package com.labteto.dshmobile.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class LocalApprovalCoordinator(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val approvalPreferences: LocalApprovalPreferences,
    private val interactions: LocalInteractionCoordinator,
    private val eventLog: () -> LocalSessionEventLog,
    private val persist: () -> Unit,
) {
    fun answerApproval(callId: String, approved: Boolean) {
        interactions.answerApproval(callId, approved)
    }

    fun enableAutoApproval() {
        enableAutoApprovalInternal(expectedCallId = null)
    }

    fun enableAutoApprovalForPending(callId: String) {
        enableAutoApprovalInternal(expectedCallId = callId)
    }

    fun enableDeviceApprovalLease(callId: String) {
        val pending = state.value.pendingApproval?.takeIf { it.callId == callId }
        if (pending?.canApproveDeviceTurn != true) {
            eventLog().append("approval/device-lease-rejected", buildJsonObject {
                put("reason", "pending-tool-requires-explicit-approval")
                pending?.toolName?.let { put("tool", it) }
            })
            return
        }
        state.update { it.copy(deviceApprovalLease = true) }
        eventLog().append("approval/device-lease", buildJsonObject { put("active", true) })
        interactions.answerApproval(pending.callId, true)
    }

    fun disableDeviceApprovalLease() {
        state.update { it.copy(deviceApprovalLease = false) }
        eventLog().append("approval/device-lease", buildJsonObject { put("active", false) })
    }

    fun disableAutoApproval() {
        approvalPreferences.setSafeAutoApprovalEnabled(false)
        state.update { it.copy(safeAutoApprovalEnabled = false) }
        eventLog().append("approval/mode", buildJsonObject { put("mode", "ask") })
        persist()
    }

    fun answerQuestion(callId: String, answer: String) {
        interactions.answerQuestion(callId, answer)
    }

    fun cancelQuestion(callId: String) {
        interactions.cancelQuestion(callId)
    }

    private fun enableAutoApprovalInternal(expectedCallId: String?) {
        val pending = state.value.pendingApproval
        if (expectedCallId != null && pending?.callId != expectedCallId) return
        approvalPreferences.setSafeAutoApprovalEnabled(true)
        state.update { it.copy(safeAutoApprovalEnabled = true) }
        eventLog().append("approval/mode", buildJsonObject {
            put("mode", "global")
            pending?.toolName?.let { put("tool", it) }
        })
        persist()
        if (canResolvePendingByEnablingAutoApproval(pending)) {
            pending?.callId?.let { interactions.answerApproval(it, true) }
        }
    }
}
