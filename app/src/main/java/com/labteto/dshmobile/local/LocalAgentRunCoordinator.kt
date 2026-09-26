package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.harness.session.SessionRecovery
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_AGENT_RUN_CHECKPOINT_EVENT = "agent/run-checkpoint"
internal const val LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT = "agent/subagent-run-checkpoint"
internal const val LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT = "agent/automation-run-checkpoint"
private const val LOCAL_AGENT_RUN_CHECKPOINT_VERSION = 1
private const val MAX_RECOVERY_INPUT_CHARS = 8_000

internal enum class LocalAgentRunKind {
    FOREGROUND,
    SUBAGENT,
    AUTOMATION,
}

internal enum class LocalAgentRunCheckpointStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    STEP_LIMIT,
    RECOVERY_QUEUED,
    RECOVERY_BLOCKED,
}

internal enum class LocalAgentRunPhase {
    TURN_STARTED,
    STEP_STARTED,
    ASSISTANT_OBSERVED,
    TOOL_STARTED,
    TOOL_FINISHED,
    STEP_FINISHED,
    TURN_FINISHED,
}

internal data class LocalAgentRunResourceBudget(
    val maxModelRequests: Int,
    val maxAgents: Int,
    val maxTerminals: Int,
    val maxVirtualDisplays: Int,
    val maxLanguageServers: Int,
)

internal data class LocalAgentRunContext(
    val runId: String,
    val kind: LocalAgentRunKind,
    val sessionId: String,
    val usageMode: LocalUsageMode,
    val model: String,
    val baseUrl: String,
    val planMode: Boolean,
    val policy: LocalAgentRunPolicy,
    val safeAutoApprovalEnabled: Boolean,
    val allowMutation: Boolean,
    val maxSteps: Int,
    val resourceBudget: LocalAgentRunResourceBudget?,
    val toolNames: List<String>,
    val contextChars: Int,
    val input: String,
    val memoryInput: String,
    val startedAt: Long,
)

internal data class LocalAgentRunRecoveryDecision(
    val runId: String,
    val queuedInput: QueuedAgentInput? = null,
    val blockedReason: String? = null,
)

/**
 * Owns the stable scope and durable recovery checkpoints for one foreground Agent run.
 *
 * The event log remains the source of truth. A restart may automatically continue only after
 * [SessionRecovery] has repaired the interrupted turn and proved that no started tool has an
 * unknown outcome. Unsafe side effects are never retried automatically.
 */
internal class LocalAgentRunCoordinator(
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun start(
        sessionId: String,
        usageMode: LocalUsageMode,
        model: String,
        baseUrl: String,
        planMode: Boolean,
        policy: LocalAgentRunPolicy,
        safeAutoApprovalEnabled: Boolean,
        maxSteps: Int,
        input: String,
        memoryInput: String,
        kind: LocalAgentRunKind = LocalAgentRunKind.FOREGROUND,
        allowMutation: Boolean = true,
        resourceBudget: LocalAgentRunResourceBudget? = null,
        toolNames: List<String> = emptyList(),
        contextChars: Int = 0,
    ): LocalAgentRunContext {
        val context = LocalAgentRunContext(
            runId = idFactory(),
            kind = kind,
            sessionId = sessionId,
            usageMode = usageMode,
            model = model,
            baseUrl = baseUrl,
            planMode = planMode,
            policy = policy,
            safeAutoApprovalEnabled = safeAutoApprovalEnabled,
            allowMutation = allowMutation,
            maxSteps = maxSteps,
            resourceBudget = resourceBudget,
            toolNames = toolNames.distinct().sorted(),
            contextChars = contextChars.coerceAtLeast(0),
            input = input.take(MAX_RECOVERY_INPUT_CHARS),
            memoryInput = memoryInput.take(MAX_RECOVERY_INPUT_CHARS),
            startedAt = now(),
        )
        append(
            context = context,
            status = LocalAgentRunCheckpointStatus.RUNNING,
            phase = LocalAgentRunPhase.TURN_STARTED,
        )
        return context
    }

    fun recordEvent(context: LocalAgentRunContext, event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnStarted -> append(
                context,
                LocalAgentRunCheckpointStatus.RUNNING,
                LocalAgentRunPhase.TURN_STARTED,
            )
            is AgentEvent.StepStarted -> append(
                context,
                LocalAgentRunCheckpointStatus.RUNNING,
                LocalAgentRunPhase.STEP_STARTED,
                step = event.step,
            )
            is AgentEvent.AssistantObserved -> append(
                context,
                LocalAgentRunCheckpointStatus.RUNNING,
                LocalAgentRunPhase.ASSISTANT_OBSERVED,
                step = event.step,
                toolCallCount = event.toolCalls.size,
            )
            is AgentEvent.ToolStarted -> append(
                context,
                LocalAgentRunCheckpointStatus.RUNNING,
                LocalAgentRunPhase.TOOL_STARTED,
                step = event.step,
                callId = event.call.id,
                toolName = event.call.name,
            )
            is AgentEvent.ToolFinished -> append(
                context,
                LocalAgentRunCheckpointStatus.RUNNING,
                LocalAgentRunPhase.TOOL_FINISHED,
                step = event.step,
                callId = event.call.id,
                toolName = event.call.name,
            )
            // ToolFinished already captures the durable continuation point for tool-using steps.
            // Keeping a generic StepFinished checkpoint would erase whether an AssistantObserved
            // event was a final no-tool answer and could cause duplicate replies after restart.
            is AgentEvent.StepFinished -> Unit
            is AgentEvent.TurnCompleted -> append(
                context,
                LocalAgentRunCheckpointStatus.COMPLETED,
                LocalAgentRunPhase.TURN_FINISHED,
                step = event.steps,
            )
            is AgentEvent.TurnStepLimit -> append(
                context,
                LocalAgentRunCheckpointStatus.STEP_LIMIT,
                LocalAgentRunPhase.TURN_FINISHED,
                step = event.steps,
                reason = "step_limit",
            )
            is AgentEvent.TurnFailed -> append(
                context,
                LocalAgentRunCheckpointStatus.FAILED,
                LocalAgentRunPhase.TURN_FINISHED,
                reason = event.reason,
            )
            is AgentEvent.TurnCancelled -> append(
                context,
                LocalAgentRunCheckpointStatus.CANCELLED,
                LocalAgentRunPhase.TURN_FINISHED,
                reason = "cancelled",
            )
        }
    }

    fun recoveryDecision(
        sessionId: String,
        repair: SessionRepairResult,
    ): LocalAgentRunRecoveryDecision? {
        val event = eventLogFor(sessionId).latest(LOCAL_AGENT_RUN_CHECKPOINT_EVENT) ?: return null
        val data = event.data
        if (data["version"]?.jsonPrimitive?.intOrNull != LOCAL_AGENT_RUN_CHECKPOINT_VERSION) return null
        val status = data["status"]?.jsonPrimitive?.contentOrNull ?: return null
        if (status != LocalAgentRunCheckpointStatus.RUNNING.name.lowercase()) return null
        val runId = data["run_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val phase = data["phase"]?.jsonPrimitive?.contentOrNull
        val toolCallCount = data["tool_call_count"]?.jsonPrimitive?.intOrNull
        if (
            phase == LocalAgentRunPhase.ASSISTANT_OBSERVED.name.lowercase() &&
            toolCallCount == 0
        ) {
            // The user-visible final answer is already durable. SessionRecovery only needs to close
            // the interrupted bookkeeping tail; replaying the model would duplicate the answer.
            return null
        }

        // A process may die after turn/end is durable but before the terminal run checkpoint is
        // appended. In that narrow window the stale RUNNING checkpoint must never replay a turn
        // that already completed.
        if (!repair.repaired) {
            val durableTurnEnd = eventLogFor(sessionId).latest("turn/end")
            if (durableTurnEnd != null && durableTurnEnd.sequence > event.sequence) return null
        }

        val unsafe = repair.toolResults.any { recovered ->
            recovered.code == SessionRecovery.TOOL_OUTCOME_UNKNOWN
        }
        if (unsafe) {
            return LocalAgentRunRecoveryDecision(
                runId = runId,
                blockedReason = "上次执行在工具调用期间被系统中断，工具副作用状态未知。已停止自动续跑，请先检查外部状态后再继续。",
            )
        }

        val originalInput = data["memory_input"]?.jsonPrimitive?.contentOrNull
            ?: data["input"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val continuation = QueuedAgentInput(
            id = "run-recovery:$runId",
            content = RECOVERY_CONTINUATION_PROMPT,
            memoryInput = originalInput.ifBlank { RECOVERY_CONTINUATION_PROMPT },
        )
        return LocalAgentRunRecoveryDecision(runId = runId, queuedInput = continuation)
    }

    fun markRecoveryQueued(sessionId: String, runId: String) {
        appendRecoveryState(
            sessionId = sessionId,
            runId = runId,
            status = LocalAgentRunCheckpointStatus.RECOVERY_QUEUED,
            reason = "process_restart",
        )
    }

    fun markRecoveryBlocked(sessionId: String, runId: String, reason: String) {
        appendRecoveryState(
            sessionId = sessionId,
            runId = runId,
            status = LocalAgentRunCheckpointStatus.RECOVERY_BLOCKED,
            reason = reason,
        )
    }

    private fun append(
        context: LocalAgentRunContext,
        status: LocalAgentRunCheckpointStatus,
        phase: LocalAgentRunPhase,
        step: Int? = null,
        callId: String? = null,
        toolName: String? = null,
        reason: String? = null,
        toolCallCount: Int? = null,
    ) {
        eventLogFor(context.sessionId).append(
            eventType(context.kind),
            buildJsonObject {
                put("version", LOCAL_AGENT_RUN_CHECKPOINT_VERSION)
                put("run_id", context.runId)
                put("run_kind", context.kind.name.lowercase())
                put("session_id", context.sessionId)
                put("status", status.name.lowercase())
                put("phase", phase.name.lowercase())
                put("mode", context.usageMode.name.lowercase())
                put("model", context.model)
                put("base_url", context.baseUrl)
                put("plan_mode", context.planMode)
                put("safe_auto_approval", context.safeAutoApprovalEnabled)
                put("tools_enabled", context.policy.toolsEnabled)
                put("allow_tool_execution", context.policy.allowToolExecution)
                put("allow_mutation", context.allowMutation)
                put("max_steps", context.maxSteps)
                put("context_chars", context.contextChars)
                put("tool_names", JsonArray(context.toolNames.map(::JsonPrimitive)))
                context.resourceBudget?.let { budget ->
                    put("max_model_requests", budget.maxModelRequests)
                    put("max_agents", budget.maxAgents)
                    put("max_terminals", budget.maxTerminals)
                    put("max_virtual_displays", budget.maxVirtualDisplays)
                    put("max_language_servers", budget.maxLanguageServers)
                }
                put("input", context.input)
                put("memory_input", context.memoryInput)
                put("started_at", context.startedAt)
                put("updated_at", now())
                step?.let { put("step", it) }
                callId?.let { put("call_id", it) }
                toolName?.let { put("tool_name", it) }
                toolCallCount?.let { put("tool_call_count", it) }
                reason?.takeIf(String::isNotBlank)?.let { put("reason", it.take(2_000)) }
            },
        )
    }

    private fun eventType(kind: LocalAgentRunKind): String = when (kind) {
        LocalAgentRunKind.FOREGROUND -> LOCAL_AGENT_RUN_CHECKPOINT_EVENT
        LocalAgentRunKind.SUBAGENT -> LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT
        LocalAgentRunKind.AUTOMATION -> LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT
    }

    private fun appendRecoveryState(
        sessionId: String,
        runId: String,
        status: LocalAgentRunCheckpointStatus,
        reason: String,
    ) {
        eventLogFor(sessionId).append(
            LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
            buildJsonObject {
                put("version", LOCAL_AGENT_RUN_CHECKPOINT_VERSION)
                put("run_id", runId)
                put("session_id", sessionId)
                put("status", status.name.lowercase())
                put("phase", LocalAgentRunPhase.TURN_FINISHED.name.lowercase())
                put("updated_at", now())
                put("reason", reason.take(2_000))
            },
        )
    }

    private companion object {
        const val RECOVERY_CONTINUATION_PROMPT =
            "继续执行上次因系统中断而停止的任务。先核对已有结果，再从安全位置继续；不要重复已完成且可能产生副作用的操作。"
    }
}
