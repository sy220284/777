package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalApprovalPreferences
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalInteractionCoordinator
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Work owns approval policy changes; shared capabilities own preferences, waits and durable logs. */
@Singleton
class LocalWorkApprovalCoordinator @Inject internal constructor(
    private val preferences: LocalApprovalPreferences,
    private val events: LocalSessionEventLogRegistry,
    private val runtime: LocalRuntimeStateStore,
    private val runs: LocalWorkRunRegistry,
) {
    private val modeLock = Any()
    private data class Target(
        val state: MutableStateFlow<LocalHarnessState>,
        val interactions: LocalInteractionCoordinator,
        val log: LocalSessionEventLog,
    )

    private fun currentTarget(): Target? {
        val sessionId = runtime.currentSessionId
        runs[sessionId]?.let { return Target(it.state, it.interactions, it.eventLog) }
        // Foreground identity can advance before its visible projection is loaded.
        if (runtime.state.value.sessionId != sessionId || runtime.state.value.loading) return null
        return Target(runtime.mutableState, runtime.foregroundInteractions, events.get(sessionId))
    }

    internal fun enableAutoApproval() = synchronized(modeLock) {
        val target = currentTarget() ?: return@synchronized
        setGlobalMode(true, target)
    }

    internal fun enableAutoApprovalForPending(callId: String): Unit = synchronized(modeLock) {
        val target = currentTarget() ?: return@synchronized
        target.interactions.resolveApproval(callId) {
            setGlobalMode(true, target, skipTargetWaiter = true)
            true
        }
        Unit
    }

    internal fun disableAutoApproval() = synchronized(modeLock) {
        val target = currentTarget() ?: return@synchronized
        setGlobalMode(false, target)
    }

    private fun setGlobalMode(enabled: Boolean, target: Target, skipTargetWaiter: Boolean = false) {
        // This is the durable authority; Session snapshots do not store this device-wide setting.
        preferences.setSafeAutoApprovalEnabled(enabled)
        runtime.mutableState.update { it.copy(safeAutoApprovalEnabled = enabled) }
        val active = mutableListOf<Target>()
        runs.forEachBinding { binding ->
            binding.state.update { it.copy(safeAutoApprovalEnabled = enabled) }
            active += Target(binding.state, binding.interactions, binding.eventLog)
        }
        if (active.none { it.interactions === target.interactions }) active += target
        active.forEach { owner ->
            val pending = owner.state.value.work.pendingApproval
            owner.log.append("approval/mode", buildJsonObject {
                put("mode", if (enabled) "global" else "ask")
                pending?.toolName?.let { put("tool", it) }
            })
            if (enabled && pending != null && !(skipTargetWaiter && owner.interactions === target.interactions)) {
                owner.interactions.answerApproval(pending.callId, true)
            }
        }
    }

    internal fun enableDeviceApprovalLease(callId: String) {
        val target = currentTarget() ?: return
        target.interactions.resolveApproval(callId) { pending ->
            if (!pending.canApproveDeviceTurn) {
                target.log.append("approval/device-lease-rejected", buildJsonObject {
                    put("reason", "pending-tool-requires-explicit-approval")
                    put("tool", pending.toolName)
                })
                false
            } else {
                target.log.append("approval/device-lease", buildJsonObject { put("active", true) })
                target.state.update { it.copy(deviceApprovalLease = true) }
                true
            }
        }
    }

    internal fun disableDeviceApprovalLease() {
        val target = currentTarget() ?: return
        target.log.append("approval/device-lease", buildJsonObject { put("active", false) })
        target.state.update { it.copy(deviceApprovalLease = false) }
        runtime.mutableState.update { visible ->
            if (visible.sessionId == target.state.value.sessionId) visible.copy(deviceApprovalLease = false)
            else visible
        }
    }
}
