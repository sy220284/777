package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.interaction.LocalInteractionCoordinator
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
    init {
        runtime.bindApprovalPreferences(preferences)
        runs.bindApprovalPreferences(preferences)
    }

    private val modeLock = Any()

    private data class Target(
        val interactions: LocalInteractionCoordinator,
        val log: LocalSessionEventLog,
    )

    private fun currentTarget(): Target? {
        val sessionId = runtime.currentSessionId
        runs[sessionId]?.let { return Target(it.interactions, it.eventLog) }
        // Foreground identity can advance before its visible projection is loaded.
        if (runtime.state.value.sessionId != sessionId || runtime.state.value.loading) return null
        return Target(runtime.foregroundInteractions, events.get(sessionId))
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
        val active = mutableListOf<Target>()
        runs.forEachBinding { binding ->
            active += Target(binding.interactions, binding.eventLog)
        }
        if (active.none { it.interactions === target.interactions }) active += target
        var logFailure: Exception? = null
        active.forEach { owner ->
            val pending = owner.interactions.pendingApproval()
            try {
                owner.log.append("approval/mode", buildJsonObject {
                    put("mode", if (enabled) "global" else "ask")
                    pending?.toolName?.let { put("tool", it) }
                })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (logFailure == null) logFailure = error
                else logFailure?.addSuppressed(error)
            }
            // The device-wide preference has already committed. A failed diagnostic append in
            // one Session cannot leave this or another Run waiting under the previous policy.
            if (
                enabled &&
                pending != null &&
                !(skipTargetWaiter && owner.interactions === target.interactions)
            ) {
                owner.interactions.answerApproval(pending.callId, true)
            }
        }
        logFailure?.let { error ->
            runtime.performVisibleOperation("审批策略已更新，部分会话日志保存失败") { throw error }
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
                target.interactions.setDeviceApprovalLease(true)
                true
            }
        }
    }

    internal fun disableDeviceApprovalLease(sessionId: String) {
        val target = runs[sessionId]?.let { Target(it.interactions, it.eventLog) }
            ?: runtime.state.value
                .takeIf { it.sessionId == sessionId }
                ?.let { Target(runtime.foregroundInteractions, events.get(sessionId)) }
            ?: return
        target.log.append("approval/device-lease", buildJsonObject { put("active", false) })
        target.interactions.setDeviceApprovalLease(false)
        // The visible mirror may lag or no longer collect a detached binding during transition.
        if (runtime.state.value.sessionId == sessionId) {
            runtime.foregroundInteractions.setDeviceApprovalLease(false)
        }
    }
}
