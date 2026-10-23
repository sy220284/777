package com.labteto.dshmobile.local.work

import kotlinx.coroutines.flow.update
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.local.LocalToolExecutionCoordinator
import com.labteto.dshmobile.local.agent.LocalAgentRunPolicy
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.PARALLEL_SUBAGENT_TOOLS
import com.labteto.dshmobile.local.runtime.approvalImpact
import com.labteto.dshmobile.local.runtime.canAutoApproveSafely
import com.labteto.dshmobile.local.runtime.canUseDeviceApprovalLease
import com.labteto.dshmobile.local.runtime.isolatedParallelMap
import com.labteto.dshmobile.local.runtime.shouldAutoApproveTool
import com.labteto.dshmobile.local.tools.localToolFailure
import com.labteto.dshmobile.local.tools.LocalToolCapabilityIntent
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.isParallelSafeReadTool
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Work-owned model-visible tool surface and bound tool execution policy.
 *
 * Shared tool execution keeps registry/error semantics; Work owns optional capability activation,
 * plan-mode visibility and user approval against the run binding.
 */
internal class LocalWorkTurnToolRuntime(
    private val registry: ToolRegistry,
    private val execution: LocalToolExecutionCoordinator,
    private val schemas: LocalToolSchemaProjection,
    private val approvalPreferences: LocalApprovalPreferences,
    private val githubConfigured: suspend () -> Boolean,
    private val workspaceRoot: java.io.File? = null,
) {
    internal fun clear(binding: LocalWorkRunBinding) {
        execution.clearTurnCapabilities(binding.enabledOptionalTools)
    }

    internal suspend fun prepare(
        binding: LocalWorkRunBinding,
        input: String,
    ) {
        val history = binding.runHandle.modelHistory.snapshot()
        execution.prepareWorkTurnCapabilities(
            input = input,
            history = history,
            gitHubConfigured = githubConfigured,
            target = binding.enabledOptionalTools,
        )
    }

    internal fun schemas(
        policy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding,
    ): JsonArray {
        // 此时最新用户消息已从 PendingInput 队列写进历史，首轮集群标记才可靠。
        val history = binding.runHandle.modelHistory.snapshot()
        val teamMode = isLocalAgentTeamTurn(history)
        // A connection or plugin install can register new tools during this run.
        // Re-evaluate only the active user's task before each model step; newly registered
        // capabilities become available automatically without restarting or editing tool maps.
        val activeTask = LocalToolCapabilityIntent.latestUserInput(history)
        if (!activeTask.isNullOrBlank()) {
            // A model's explicit capability_search selection must survive this step's
            // heuristic refresh. The turn-start prepare() still reorders for a NEW user task.
            // Here we only append newly registered relevant tools, preserving priority of
            // the tools the model just discovered under the optional schema-token budget.
            execution.enableTaskRelevantOptionalTools(
                activeTask, binding.enabledOptionalTools, prioritize = false,
            )
        }
        execution.prioritizePinnedForModelStep(binding.enabledOptionalTools)
        val enabled = synchronized(binding.enabledOptionalTools) {
            if (teamMode) {
                val priority = listOf(
                    "team_task_create", "team_task_list", "team_task_update", "team_messages",
                    "team_wait_for_message", "team_members", "team_task_get",
                    "team_create_member", "team_start_member", "team_spawn",
                    "team_send_message", "team_wait", "skill",
                ).filter { registry.get(it) != null }
                val previous = binding.enabledOptionalTools.toList()
                binding.enabledOptionalTools.clear()
                binding.enabledOptionalTools.addAll(priority)
                binding.enabledOptionalTools.addAll(previous)
            }
            binding.enabledOptionalTools.toSet()
        }
        val selection = schemas.modelSelection(
            policy = policy,
            modelState = binding.state.value.modelState,
            planModeEnabled = binding.state.value.work.planMode,
            history = history,
            enabledOptional = enabled,
            agentTeamMode = teamMode,
        )
        // The request evidence already records the exact schema digest. Only record a separate
        // fact when a selected capability could not reach the model; no parallel tool registry.
        if (selection.omittedReasons.isNotEmpty()) {
            // Diagnostics are observational, not a new admission gate. A failed diagnostic
            // write must not make an otherwise legitimate model request unavailable.
            try {
                binding.eventLog.append("tool/schema-admission", buildJsonObject {
                    put("version", 1)
                    put("enabled_optional_count", enabled.size)
                    put("omitted_total", selection.omittedReasons.size)
                    put("omitted", buildJsonObject {
                        selection.omittedReasons.entries.take(24).forEach { (name, reason) ->
                            put(name, reason)
                        }
                    })
                })
            } catch (error: Exception) {
                com.labteto.dshmobile.observability.AppLog.failure(
                    "LocalWorkToolSurface", "record_schema_admission", error,
                )
            }
        }
        return selection.schemas
    }

    internal fun names(
        policy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding,
    ): List<String> = schemas.names(schemas(policy, binding))

    /** Pure read proven by the registered tool contract, not by its display name alone. */
    internal fun isParallel(name: String): Boolean =
        name in PARALLEL_SUBAGENT_TOOLS ||
            isSafeParallelReadTool(registry.get(LocalToolPolicy.canonical(name)))

    internal suspend fun execute(
        binding: LocalWorkRunBinding,
        call: LocalToolCall,
        allowMutation: Boolean = true,
        stillCurrent: () -> Boolean = { true },
    ): AgentToolResult {
        val goal = binding.workState.snapshot().goal
        val paths = goal?.artifactPaths.orEmpty()
        return execution.executeScoped(
            original = call,
            sessionId = binding.sessionId,
            allowMutation = allowMutation,
            planModeEnabled = binding.state.value.work.planMode,
            beforeExecution = stillCurrent,
            executionAttributes = mapOf("goal_id" to goal?.id.orEmpty()),
            onExecutionActive = { active ->
                binding.executingToolCallIds.update { ids -> if (active) ids + call.id else ids - call.id }
            },
            observeFileVersions = { paths.mapNotNull { path ->
                workspaceRoot?.let { localWorkFileSha256(it, path) }?.let { path to it }
            }.toMap() },
            approval = { normalized, tool, summary ->
                approve(binding, normalized, summary, tool)
            },
        )
    }

    internal suspend fun executeBatch(
        binding: LocalWorkRunBinding,
        calls: List<LocalToolCall>,
        allowMutation: Boolean = true,
        superseded: () -> Boolean = { false },
        // The caller's frozen model-step surface is a separate admission requirement from
        // Registry registration. Apply it to EVERY batch member, even parallel groups.
        blockedCall: (String) -> AgentToolResult? = { null },
    ): List<Pair<LocalToolCall, AgentToolResult>> {
        val output = mutableListOf<Pair<LocalToolCall, AgentToolResult>>()
        val groups = planLocalToolBatch(calls.size) { index -> isParallel(calls[index].name) }
        for (group in groups) {
            val part = calls.subList(group.startInclusive, group.endExclusive)
            if (!group.parallel) {
                val call = part.single()
                output += call to executeAdmittedWorkBatchCall(call, superseded, blockedCall) {
                    execute(binding, call, allowMutation, stillCurrent = { !superseded() })
                }
                continue
            }
            // Do not execute any later group before this entire group settles. Resource leases
            // remain in the shared tool coordinator, so approval and write semantics are intact.
            output += isolatedParallelMap(part) { call ->
                call to executeAdmittedWorkBatchCall(call, superseded, blockedCall) {
                    execute(binding, call, allowMutation, stillCurrent = { !superseded() })
                }
            }.mapIndexed { index, result ->
                result.getOrElse { error ->
                    val call = part[index]
                    call to parallelFailure(
                        call,
                        error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
        return output
    }

    private suspend fun approve(
        binding: LocalWorkRunBinding,
        call: LocalToolCall,
        summary: String,
        tool: HarnessTool,
    ): Boolean {
        if (
            binding.interactions.deviceApprovalLeaseEnabled() &&
            canUseDeviceApprovalLease(tool)
        ) {
            binding.eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("mode", "device-turn-lease")
            })
            return true
        }
        val approvalMode = approvalPreferences.currentMode()
        if (shouldAutoApproveTool(approvalMode, tool)) {
            binding.eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("impact", approvalImpact(tool).name.lowercase())
                put("mode", approvalMode.name.lowercase())
            })
            return true
        }
        return binding.interactions.awaitApproval(
            LocalApproval(
                callId = call.id,
                toolName = call.name,
                summary = summary,
                arguments = call.rawArguments,
                access = tool.access.name.lowercase(),
                impact = approvalImpact(tool),
                canAutoApproveSafely = canAutoApproveSafely(tool),
                canApproveDeviceTurn = canUseDeviceApprovalLease(tool),
            ),
        )
    }

    private fun parallelFailure(
        call: LocalToolCall,
        detail: String,
    ): AgentToolResult = parallelToolFailure(
        call = call,
        access = registry.get(LocalToolPolicy.canonical(call.name))?.access,
        detail = detail,
    )
}

internal fun parallelToolFailure(
    call: LocalToolCall,
    access: ToolAccess?,
    detail: String,
): AgentToolResult {
    return localToolFailure("PARALLEL_TASK_ERROR",
        "[${call.name}][PARALLEL_TASK_ERROR] 工具执行失败：$detail\n调用 id：${call.id}",
        readOnly = access != null && LocalToolPolicy.isReadOnlyInvocation(call.name, access, call.arguments),
        executionStarted = null)
}

/**
 * Shared-read admission is opt-in rather than inferred from READ_ONLY/NETWORK access:
 * a third-party tool can claim READ_ONLY while writing a remote session or seeking approval.
 * Write and agent-control calls retain their existing exclusive, ordered execution path.
 */
internal fun isSafeParallelReadTool(tool: HarnessTool?): Boolean = isParallelSafeReadTool(tool)

/**
 * The SAME frozen model-step tool-surface check applies to serial and parallel batches.
 * This pure gate prevents a model's unexposed guessed tool from reaching Registry execution
 * even if that tool is globally installed or has automatic approval.
 */
internal suspend fun executeAdmittedWorkBatchCall(
    call: LocalToolCall,
    superseded: () -> Boolean,
    blockedCall: (String) -> AgentToolResult?,
    execute: suspend () -> AgentToolResult,
): AgentToolResult {
    if (superseded()) return workSteeredToolResult(call)
    return blockedCall(call.name) ?: execute()
}

/** A committed correction invalidates unstarted calls from the preceding model sample. */
internal fun workSteeredToolResult(call: LocalToolCall): AgentToolResult = AgentToolResult(
    content = "用户已请求立即调整。旧模型采样生成的工具调用未开始执行：" + call.name,
    isError = true,
    errorCode = "WORK_TOOL_SUPERSEDED",
    retryable = false,
    sideEffect = AgentToolSideEffect.NONE,
    recoveryHint = "先读取本轮新的用户要求，不要重复执行已作废的工具调用。",
)
