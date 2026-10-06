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
        execution.prepareWorkTurnCapabilities(
            input = input,
            history = binding.runHandle.modelHistory.snapshot(),
            gitHubConfigured = githubConfigured,
            target = binding.enabledOptionalTools,
        )
    }

    internal fun schemas(
        policy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding,
    ): JsonArray {
        val enabled = synchronized(binding.enabledOptionalTools) {
            binding.enabledOptionalTools.toSet()
        }
        return schemas.modelSchemas(
            policy = policy,
            modelState = binding.state.value.modelState,
            planModeEnabled = binding.state.value.work.planMode,
            history = binding.runHandle.modelHistory.snapshot(),
            enabledOptional = enabled,
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
        if (approvalPreferences.isSafeAutoApprovalEnabled()) {
            binding.eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("impact", approvalImpact(tool).name.lowercase())
                put("mode", "global")
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
    ): AgentToolResult {
        val access = registry.get(LocalToolPolicy.canonical(call.name))?.access
        val sideEffect = if (
            access in setOf(
                ToolAccess.WORKSPACE_WRITE,
                ToolAccess.SESSION_WRITE,
                ToolAccess.PROCESS,
                ToolAccess.AGENT_CONTROL,
                ToolAccess.DEVICE,
                ToolAccess.PRIVILEGED,
            )
        ) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE
        return AgentToolResult(
            content = "[${call.name}][PARALLEL_TASK_ERROR] 工具执行失败：$detail\n调用 id：${call.id}",
            isError = true,
            errorCode = "PARALLEL_TASK_ERROR",
            retryable = true,
            sideEffect = sideEffect,
            recoveryHint = if (sideEffect == AgentToolSideEffect.POSSIBLE) {
                "该调用可能已产生部分副作用；先检查当前状态，再决定是否重试。"
            } else {
                "该错误允许重试；先检查前置状态后再重试。"
            },
        )
    }
}
