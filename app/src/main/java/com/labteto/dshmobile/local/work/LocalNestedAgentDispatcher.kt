package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.agent.LocalAgentParentLink
import com.labteto.dshmobile.local.agent.LocalAgentSubtreeBudget
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.LocalSubagentResult
import com.labteto.dshmobile.local.agent.LocalSubagentStatus
import com.labteto.dshmobile.local.agent.compileNestedReadonlyCapabilities
import com.labteto.dshmobile.local.agent.parseLocalNestedDelegation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One typed orchestration boundary for model-authored recursive Agent tool calls.
 * Caller supplies its live parent-Run capture and a fresh child Runner implementation.
 * No additional persisted owner or model/tool dispatcher is created.
 */
internal suspend fun dispatchLocalNestedSubagent(
    call: AgentToolCall,
    parentCapabilities: LocalSubagentCapabilities,
    parentProfileId: String,
    parentMaxSteps: Int,
    subtreeBudget: LocalAgentSubtreeBudget,
    availableAgentSlots: () -> Int,
    captureParent: suspend (toolCallId: String) -> LocalAgentParentLink?,
    ensureParentOwner: () -> Unit = {},
    runChild: suspend (LocalSubagentLaunchSpec, LocalAgentSubtreeBudget) -> LocalSubagentResult,
    executionDeadlineOverrideMillis: Long? = null,
): AgentToolResult {
    try {
        require(call.name == "subagent") { "NESTED_DELEGATION_TOOL_INVALID" }
        val parsed = parseLocalNestedDelegation(call.arguments, parentMaxSteps)
        val childCapabilities = compileNestedReadonlyCapabilities(parentCapabilities)
        ensureParentOwner()
        require(availableAgentSlots() > 0) { "NESTED_DELEGATION_NO_AGENT_SLOT" }
        val parent = requireNotNull(captureParent(call.id)) {
            "NESTED_DELEGATION_REQUIRES_LIVE_PARENT_RUN"
        }
        val child = LocalSubagentLaunchSpec(
            task = parsed.task,
            instructions = parsed.instructions,
            modelOverride = parentProfileId,
            maxSteps = parsed.maxSteps,
            parentLink = parent,
            capabilities = childCapabilities,
        )
        val deadline = executionDeadlineOverrideMillis
            ?: (parsed.maxSteps.toLong() * 60_000L).coerceIn(120_000L, 900_000L)
        require(deadline in 1L..900_000L) { "NESTED_DELEGATION_DEADLINE_INVALID" }
        // Admit after all validation. A replayed tool id in one parent Run cannot
        // consume another descendant slot or launch a second model invocation.
        subtreeBudget.claim(
            "${parent.sessionId}:${parent.parentSlotId}:${parent.parentRunId}:${call.id}"
        )
        val result = withTimeoutOrNull(deadline) {
            runChild(child, subtreeBudget)
        }
        // Reject a late success after the caller's Run lease was superseded or cancelled.
        ensureParentOwner()
        return if (result == null) AgentToolResult(
            content = "NESTED_DELEGATION_TIMEOUT：下级子代理已超时取消",
            isError = true,
        ) else AgentToolResult(
            content = result.output,
            isError = result.status != LocalSubagentStatus.COMPLETED,
        )
    } catch (error: CancellationException) {
        // Parent cancellation and owner fencing must propagate through all descendants.
        throw error
    } catch (error: Exception) {
        return AgentToolResult(
            content = "NESTED_DELEGATION_REJECTED：" + error.message.orEmpty(),
            isError = true,
        )
    }
}
