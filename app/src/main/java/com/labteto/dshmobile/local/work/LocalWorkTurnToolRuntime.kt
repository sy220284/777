package com.labteto.dshmobile.local.work

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
import com.labteto.dshmobile.local.tools.LocalToolPolicy
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
        val enabled = synchronized(binding.enabledOptionalTools) {
            if (teamMode) {
                val priority = listOf(
                    "team_task_list", "team_task_update", "team_messages",
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
        return schemas.modelSchemas(
            policy = policy,
            modelState = binding.state.value.modelState,
            planModeEnabled = binding.state.value.work.planMode,
            history = history,
            enabledOptional = enabled,
            agentTeamMode = teamMode,
        )
    }

    internal fun names(
        policy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding,
    ): List<String> = schemas.names(schemas(policy, binding))

    internal fun isParallel(name: String): Boolean = name in PARALLEL_SUBAGENT_TOOLS

    internal suspend fun execute(
        binding: LocalWorkRunBinding,
        call: LocalToolCall,
        allowMutation: Boolean = true,
    ): AgentToolResult = execution.executeScoped(
        original = call,
        sessionId = binding.sessionId,
        allowMutation = allowMutation,
        planModeEnabled = binding.state.value.work.planMode,
        approval = { normalized, tool, summary ->
            approve(binding, normalized, summary, tool)
        },
    )

    internal suspend fun executeBatch(
        binding: LocalWorkRunBinding,
        calls: List<LocalToolCall>,
        allowMutation: Boolean = true,
    ): List<Pair<LocalToolCall, AgentToolResult>> {
        val parallel = calls.size > 1 && calls.all { isParallel(it.name) }
        if (!parallel) {
            return calls.map { call -> call to execute(binding, call, allowMutation) }
        }
        return isolatedParallelMap(calls) { call ->
            call to execute(binding, call, allowMutation)
        }.mapIndexed { index, result ->
            result.getOrElse { error ->
                val call = calls[index]
                call to parallelFailure(
                    call,
                    error.message ?: error::class.java.simpleName,
                )
            }
        }
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
