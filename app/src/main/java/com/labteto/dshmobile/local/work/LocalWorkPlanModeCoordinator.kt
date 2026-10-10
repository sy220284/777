package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.interaction.LocalInteractionCoordinator
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.recordRuntimeSystemPromptUpdate
import com.labteto.dshmobile.local.model.workSystemPrompt
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Work-owned plan-mode transition.
 *
 * Session EventLog is the durable authority; the materialized Session snapshot may lag and is
 * repaired from plan/mode on restore. Model-history continuity is updated through Shared Runtime,
 * so changing plan mode stays within the Work/Shared capability boundary.
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
                // A restored pending interaction must not silently release plan read-only limits.
                before.work.pendingQuestion != null ||
                before.work.pendingApproval != null ||
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

            val history = runtimeStateStore.foregroundRunHandle.modelHistory
            if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
                recordRuntimeSystemPromptUpdate(
                    history = history,
                    prompt = workSystemPrompt(after.workspacePath, enabled),
                    modelState = after.modelState,
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


/**
 * Work-owned transition from planning into execution during an active run.
 *
 * The caller supplies only narrow Work/shared capabilities. Work remains the owner of plan
 * approval semantics while Runtime keeps
 * interaction, model-history and Session persistence mechanics.
 */
internal suspend fun exitWorkPlanMode(
    call: LocalToolCall,
    plan: String,
    state: LocalWorkStatePort,
    interactions: LocalInteractionCoordinator,
    aggregateSnapshot: () -> LocalHarnessState,
    history: LocalModelHistoryBuffer,
    eventLog: LocalSessionEventLog,
    persist: () -> Unit,
    updateContextMetrics: () -> Unit,
): String {
    if (!state.snapshot().planMode) return "当前未启用规划模式"

    val answer = interactions.awaitQuestion(
        LocalQuestion(
            callId = call.id,
            question = "Harness 已完成计划，是否批准并进入执行模式？\n\n${plan.take(8_000)}",
            options = listOf("批准并进入执行模式", "继续规划", "重新生成方案"),
        ),
    )
    if (answer == "重新生成方案") {
        return "用户要求重新生成方案。请保留原始目标、附件和当前只读分析，重新检查约束与可行性，形成一版完整的新方案，再次调用 exit_plan_mode 等待用户审阅。规划模式仍然生效，禁止执行修改类工具。"
    }
    if (answer != "批准并进入执行模式") {
        return "用户要求继续规划。反馈：$answer"
    }

    val approvedPlan = plan.lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(20)

    commitApprovedWorkPlan(state, approvedPlan) { items ->
        eventLog.append("plan/approved", buildJsonObject {
            put("items", JsonArray(items.map { item -> JsonPrimitive(item) }))
            put("active", false)
        })
    }

    val snapshot = aggregateSnapshot()
    if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
        recordRuntimeSystemPromptUpdate(
            history = history,
            prompt = workSystemPrompt(snapshot.workspacePath, planMode = false),
            modelState = snapshot.modelState,
            log = eventLog,
        )
        updateContextMetrics()
    }
    persist()
    return "计划已获批准，已进入执行模式"
}

/** Commit both approval facts before exposing either through Work state. */
internal fun commitApprovedWorkPlan(
    state: LocalWorkStatePort,
    approvedPlan: List<String>,
    commit: (List<String>) -> Unit,
) {
    commit(approvedPlan)
    state.update { current -> current.copy(planMode = false, plan = approvedPlan) }
}
