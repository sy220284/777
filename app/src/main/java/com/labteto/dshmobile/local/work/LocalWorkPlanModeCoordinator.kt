package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.recordRuntimeSystemPromptUpdate
import com.labteto.dshmobile.local.model.workSystemPrompt
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Work-owned plan-mode transition.
 *
 * Session EventLog is the durable authority; the materialized Session snapshot may lag and is
 * repaired from plan/mode on restore. Model-history continuity is updated through Shared Runtime,
 * so changing plan mode no longer routes through LocalHarnessEngine.
 */
@Singleton
internal class LocalWorkPlanModeCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val eventLogs: LocalSessionEventLogRegistry,
) {
    internal fun setEnabled(enabled: Boolean): Boolean {
        val sessionId = runtimeStateStore.currentSessionId
        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return false
        return try {
            val before = runtimeStateStore.state.value
            if (
                before.sessionId != sessionId ||
                before.loading ||
                before.usageMode != LocalUsageMode.WORK ||
                before.kernel.running ||
                workRunRegistry.live(sessionId) != null
            ) return false
            if (before.work.planMode == enabled) return true

            // Commit the authoritative fact before publishing its UI projection. A disk failure
            // must leave plan mode and model history unchanged, and the lease still closes below.
            val log = eventLogs.get(sessionId)
            log.append("plan/mode", buildJsonObject { put("active", enabled) })

            runtimeStateStore.projection.setWorkPlanMode(sessionId, enabled)
            val after = runtimeStateStore.state.value
            if (
                after.sessionId != sessionId ||
                after.usageMode != LocalUsageMode.WORK ||
                after.work.planMode != enabled
            ) return false

            val history = runtimeStateStore.foregroundModelHistory
            if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
                recordRuntimeSystemPromptUpdate(
                    history = history,
                    prompt = workSystemPrompt(after.workspacePath, enabled),
                    state = after,
                    log = log,
                )
                val resources = runtimeStateStore.resourceSnapshot()
                runtimeStateStore.projection.updateContextMetrics(
                    sessionId = sessionId,
                    contextChars = history.encodedChars,
                    contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(after, resources),
                )
            }
            true
        } finally {
            lease.close()
        }
    }
}
