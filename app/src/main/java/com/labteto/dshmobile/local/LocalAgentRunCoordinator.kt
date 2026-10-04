package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LOCAL_MODEL_TOOL_CALLS_EVENT_KEY
import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.harness.session.SessionRecovery
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_AGENT_RUN_CHECKPOINT_EVENT = "agent/run-checkpoint"
internal const val LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT = "agent/subagent-run-checkpoint"
internal const val LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT = "agent/automation-run-checkpoint"
private const val LOCAL_AGENT_RUN_CHECKPOINT_VERSION = 2
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
    val profileId: String? = null,
    val authKind: String? = null,
    val protocol: String? = null,
    val credentialRef: String? = null,
    val routeFingerprint: String? = null,
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
    val parentRunId: String? = null,
    val agentId: String? = null,
)

internal data class LocalAgentRunRouteIdentity(
    val profileId: String?,
    val authKind: String?,
    val protocol: String?,
    val credentialRef: String?,
    val fingerprint: String?,
    val model: String,
    val baseUrl: String,
)

internal data class LocalAgentRunRecoveryDecision(
    val runId: String,
    val queuedInput: QueuedAgentInput? = null,
    val blockedReason: String? = null,
    val completedOutput: String? = null,
    val route: LocalAgentRunRouteIdentity? = null,
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
        routeProfile: LocalModelProfile? = null,
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
        parentRunId: String? = null,
        agentId: String? = null,
    ): LocalAgentRunContext {
        val context = LocalAgentRunContext(
            runId = idFactory(),
            kind = kind,
            sessionId = sessionId,
            usageMode = usageMode,
            model = model,
            baseUrl = baseUrl,
            profileId = routeProfile?.id,
            authKind = routeProfile?.authKind?.name,
            protocol = routeProfile?.let {
                if (it.authKind == LocalModelAuthKind.CHATGPT_PLAN) LocalModelProtocol.RESPONSES.name else it.protocol.name
            },
            credentialRef = routeProfile?.credentialRef,
            routeFingerprint = routeProfile?.routeFingerprint(),
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
            parentRunId = parentRunId,
            agentId = agentId,
        )
        append(
            context = context,
            status = LocalAgentRunCheckpointStatus.RUNNING,
            phase = LocalAgentRunPhase.TURN_STARTED,
        )
        return context
    }

    /**
     * Foreground runs have a single durable owner per session. Once recovery or a newer foreground
     * run takes over, late results from the old coroutine must not revive its checkpoint stream.
     */
    fun isCurrentForegroundRun(context: LocalAgentRunContext): Boolean {
        if (context.kind != LocalAgentRunKind.FOREGROUND) return true
        val data = eventLogFor(context.sessionId)
            .latest(LOCAL_AGENT_RUN_CHECKPOINT_EVENT)
            ?.data
            ?: return false
        return data["run_id"]?.jsonPrimitive?.contentOrNull == context.runId &&
            data["status"]?.jsonPrimitive?.contentOrNull ==
                LocalAgentRunCheckpointStatus.RUNNING.name.lowercase()
    }

    fun ensureCurrentForegroundRun(context: LocalAgentRunContext) {
        if (!isCurrentForegroundRun(context)) {
            throw CancellationException("当前运行已由新的执行或恢复流程接管")
        }
    }

    fun recordEvent(context: LocalAgentRunContext, event: AgentEvent) {
        if (!isCurrentForegroundRun(context)) return
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
                sideEffect = event.sideEffect.name.lowercase(),
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
                answer = event.answer,
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
        kind: LocalAgentRunKind = LocalAgentRunKind.FOREGROUND,
    ): LocalAgentRunRecoveryDecision? {
        val log = eventLogFor(sessionId)
        val checkpointType = eventType(kind)
        val event = log.latest(checkpointType) ?: return null
        val data = event.data
        val version = data["version"]?.jsonPrimitive?.intOrNull ?: return null
        if (version !in 1..LOCAL_AGENT_RUN_CHECKPOINT_VERSION) return null
        val status = data["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val runId = data["run_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val route = LocalAgentRunRouteIdentity(
            profileId = data["profile_id"]?.jsonPrimitive?.contentOrNull,
            authKind = data["auth_kind"]?.jsonPrimitive?.contentOrNull,
            protocol = data["protocol"]?.jsonPrimitive?.contentOrNull,
            credentialRef = data["credential_ref"]?.jsonPrimitive?.contentOrNull,
            fingerprint = data["route_fingerprint"]?.jsonPrimitive?.contentOrNull,
            model = data["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            baseUrl = data["base_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ).takeIf { version >= 2 && !it.profileId.isNullOrBlank() }

        val requiresExecutableRecovery =
            status == LocalAgentRunCheckpointStatus.RUNNING.name.lowercase() ||
                status == LocalAgentRunCheckpointStatus.RECOVERY_QUEUED.name.lowercase()
        if (version < 2 && requiresExecutableRecovery) {
            return LocalAgentRunRecoveryDecision(
                runId = runId,
                blockedReason = "上次任务来自旧版检查点，缺少完整模型路由身份，已停止自动续跑。请手动确认后继续。",
            )
        }

        if (status == LocalAgentRunCheckpointStatus.COMPLETED.name.lowercase()) {
            if (kind == LocalAgentRunKind.FOREGROUND) return null
            return LocalAgentRunRecoveryDecision(
                runId = runId,
                completedOutput = data["answer"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                route = route,
            )
        }
        if (status == LocalAgentRunCheckpointStatus.RECOVERY_BLOCKED.name.lowercase()) {
            return LocalAgentRunRecoveryDecision(
                runId = runId,
                blockedReason = data["reason"]?.jsonPrimitive?.contentOrNull
                    ?: "上次执行已被恢复保护阻断，请先检查外部状态后再继续。",
                route = route,
            )
        }
        if (status == LocalAgentRunCheckpointStatus.RECOVERY_QUEUED.name.lowercase()) {
            val continuation = recoveryContinuationPrompt(log, data)
            return LocalAgentRunRecoveryDecision(
                runId = runId,
                queuedInput = QueuedAgentInput(
                    id = "run-recovery:$runId",
                    content = continuation,
                    memoryInput = continuation,
                ),
                route = route,
            )
        }
        if (status != LocalAgentRunCheckpointStatus.RUNNING.name.lowercase()) return null
        val unsafe = repair.toolResults.any { recovered ->
            recovered.code == SessionRecovery.TOOL_OUTCOME_UNKNOWN
        }
        if (unsafe) {
            return LocalAgentRunRecoveryDecision(
                runId = runId,
                blockedReason = "上次执行在工具调用期间被系统中断，工具副作用状态未知。已停止自动续跑，请先检查外部状态后再继续。",
                route = route,
            )
        }

        if (kind != LocalAgentRunKind.FOREGROUND) {
            val allowMutation = data["allow_mutation"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: true
            if (allowMutation && hasPossibleReplaySideEffect(log, checkpointType, runId)) {
                return LocalAgentRunRecoveryDecision(
                    runId = runId,
                    blockedReason = "上次后台执行已经进入可能产生副作用的工具阶段。为避免系统重跑造成重复操作，已停止自动续跑，请先检查外部状态。",
                    route = route,
                )
            }
            val continuation = recoveryContinuationPrompt(log, data)
            return LocalAgentRunRecoveryDecision(
                runId = runId,
                queuedInput = QueuedAgentInput(
                    id = "run-recovery:$runId",
                    content = continuation,
                    memoryInput = data["memory_input"]?.jsonPrimitive?.contentOrNull
                        ?: data["input"]?.jsonPrimitive?.contentOrNull
                        ?: continuation,
                ),
                route = route,
            )
        }

        if (repair.repaired && repair.toolResults.isEmpty() && hasDurableFinalAssistant(log)) {
            // Covers the narrower crash window where assistant/message reached disk but the
            // AssistantObserved checkpoint itself did not. Pending/recovered tools always win over
            // this shortcut so unknown side effects can never be hidden.
            return null
        }

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
            val durableTurnEnd = log.latest("turn/end")
            if (durableTurnEnd != null && durableTurnEnd.sequence > event.sequence) return null
        }

        val originalInput = data["memory_input"]?.jsonPrimitive?.contentOrNull
            ?: data["input"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val continuationPrompt = recoveryContinuationPrompt(log, data)
        val continuation = QueuedAgentInput(
            id = "run-recovery:$runId",
            content = continuationPrompt,
            memoryInput = originalInput.ifBlank { continuationPrompt },
        )
        return LocalAgentRunRecoveryDecision(runId = runId, queuedInput = continuation, route = route)
    }

    private fun recoveryContinuationPrompt(
        log: LocalSessionEventLog,
        runCheckpoint: JsonObject,
    ): String {
        if (runCheckpoint["mode"]?.jsonPrimitive?.contentOrNull != LocalUsageMode.WORK.name.lowercase()) {
            return RECOVERY_CONTINUATION_PROMPT
        }
        val historyEvent = log.latest(ModelHistoryCheckpointCodec.EVENT_TYPE) ?: return RECOVERY_CONTINUATION_PROMPT
        val messages = ModelHistoryCheckpointCodec().decode(historyEvent.data) ?: return RECOVERY_CONTINUATION_PROMPT
        val checkpoint = LocalWorkCheckpoint.latestFrom(messages) ?: return RECOVERY_CONTINUATION_PROMPT
        return buildString {
            append(RECOVERY_CONTINUATION_PROMPT)
            append("\n\n最近持久工作检查点如下。先核对当前工作区和外部状态，再继续未完成事项；不要重做已完成步骤。\n")
            append(checkpoint.toModelBlock())
        }
    }

    fun markRecoveryQueued(
        sessionId: String,
        runId: String,
        kind: LocalAgentRunKind = LocalAgentRunKind.FOREGROUND,
        reason: String = "durable_session_recovery",
    ) {
        appendRecoveryState(
            sessionId = sessionId,
            runId = runId,
            kind = kind,
            status = LocalAgentRunCheckpointStatus.RECOVERY_QUEUED,
            reason = reason,
        )
    }

    fun markRecoveryBlocked(
        sessionId: String,
        runId: String,
        reason: String,
        kind: LocalAgentRunKind = LocalAgentRunKind.FOREGROUND,
    ) {
        appendRecoveryState(
            sessionId = sessionId,
            runId = runId,
            kind = kind,
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
        sideEffect: String? = null,
        answer: String? = null,
    ) {
        eventLogFor(context.sessionId).append(
            eventType(context.kind),
            buildJsonObject {
                put("version", LOCAL_AGENT_RUN_CHECKPOINT_VERSION)
                put("run_id", context.runId)
                put("run_kind", context.kind.name.lowercase())
                context.parentRunId?.takeIf(String::isNotBlank)?.let { put("parent_run_id", it) }
                context.agentId?.takeIf(String::isNotBlank)?.let { put("agent_id", it) }
                put("session_id", context.sessionId)
                put("status", status.name.lowercase())
                put("phase", phase.name.lowercase())
                put("mode", context.usageMode.name.lowercase())
                put("model", context.model)
                put("base_url", context.baseUrl)
                context.profileId?.let { put("profile_id", it) }
                context.authKind?.let { put("auth_kind", it) }
                context.protocol?.let { put("protocol", it) }
                context.credentialRef?.let { put("credential_ref", it) }
                context.routeFingerprint?.let { put("route_fingerprint", it) }
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
                sideEffect?.takeIf(String::isNotBlank)?.let { put("side_effect", it) }
                answer?.let { put("answer", it.take(MAX_RECOVERY_OUTPUT_CHARS)) }
                reason?.takeIf(String::isNotBlank)?.let { put("reason", it.take(2_000)) }
            },
        )
    }

    private fun hasPossibleReplaySideEffect(
        log: LocalSessionEventLog,
        checkpointType: String,
        runId: String,
    ): Boolean = log.withEvents { events ->
        val startedCalls = linkedSetOf<String>()
        for (event in events) {
            if (event.type != checkpointType) continue
            val data = event.data
            if (data["run_id"]?.jsonPrimitive?.contentOrNull != runId) continue
            val callId = data["call_id"]?.jsonPrimitive?.contentOrNull
            when (data["phase"]?.jsonPrimitive?.contentOrNull) {
                LocalAgentRunPhase.TOOL_STARTED.name.lowercase() -> {
                    if (!callId.isNullOrBlank()) startedCalls += callId
                }
                LocalAgentRunPhase.TOOL_FINISHED.name.lowercase() -> {
                    if (!callId.isNullOrBlank()) startedCalls -= callId
                    if (data["side_effect"]?.jsonPrimitive?.contentOrNull != "none") return@withEvents true
                }
            }
        }
        startedCalls.isNotEmpty()
    }

    private fun hasDurableFinalAssistant(log: LocalSessionEventLog): Boolean {
        val turnStart = log.latest("turn/start") ?: return false
        val assistant = log.latest("assistant/message") ?: return false
        if (assistant.sequence <= turnStart.sequence) return false
        val genericToolCalls = assistant.data[LOCAL_MODEL_TOOL_CALLS_EVENT_KEY] as? JsonArray
        if (genericToolCalls != null) return genericToolCalls.isEmpty()
        val legacyToolCalls = assistant.data["tool_calls"] as? JsonArray
        return legacyToolCalls == null || legacyToolCalls.isEmpty()
    }

    private fun eventType(kind: LocalAgentRunKind): String = when (kind) {
        LocalAgentRunKind.FOREGROUND -> LOCAL_AGENT_RUN_CHECKPOINT_EVENT
        LocalAgentRunKind.SUBAGENT -> LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT
        LocalAgentRunKind.AUTOMATION -> LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT
    }

    private fun appendRecoveryState(
        sessionId: String,
        runId: String,
        kind: LocalAgentRunKind,
        status: LocalAgentRunCheckpointStatus,
        reason: String,
    ) {
        val log = eventLogFor(sessionId)
        val latestRunId = log.latest(eventType(kind))
            ?.data
            ?.get("run_id")
            ?.jsonPrimitive
            ?.contentOrNull
        if (latestRunId != runId) return
        log.append(
            eventType(kind),
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
        const val MAX_RECOVERY_OUTPUT_CHARS = 20_000
        const val RECOVERY_CONTINUATION_PROMPT =
            "继续执行上次因系统中断而停止的任务。先核对已有结果，再从安全位置继续；不要重复已完成且可能产生副作用的操作。"
    }
}
