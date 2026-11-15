package com.labteto.dshmobile.local.work

import kotlinx.coroutines.flow.update
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolExposure
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
import com.labteto.dshmobile.local.tools.latestUserTurnHasArchive
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.isParallelSafeReadTool
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import com.labteto.dshmobile.local.tools.LocalToolRouter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Work-owned model-visible tool surface and bound tool execution policy.
 *
 * Shared tool execution keeps registry/error semantics; Work owns optional capability activation,
 * plan-mode visibility and user approval against the run binding.
 */
/** Cold Work continuation must restore the user's task, not infer new tools from the
 * synthetic resume command. Use the canonical model history, or the durable Goal
 * if compaction removed the original user message.
 */
internal fun effectiveWorkCapabilityTask(
    input: String,
    continuationParentRunId: String?,
    history: List<JsonObject>,
    goalDescription: String?,
): String = if (continuationParentRunId == null) input else
    LocalToolCapabilityIntent.latestUserInput(history)
        ?: goalDescription?.takeIf(String::isNotBlank)
        ?: input

internal class LocalWorkTurnToolRuntime(
    private val registry: ToolRegistry,
    private val execution: LocalToolExecutionCoordinator,
    private val schemas: LocalToolSchemaProjection,
    private val approvalPreferences: LocalApprovalPreferences,
    private val githubConfigured: suspend () -> Boolean,
    private val workspaceRoot: java.io.File? = null,
) {
    internal fun captureBindings(schemas: JsonArray) = registry.captureBindings(schemas)

    /**
     * Selection only: never executes an unexposed tool or grants permission.
     * Explicit focus, current registry, network switches and plan scope stay authoritative.
     */
    internal fun prepareUnexposedForNextStep(
        binding: LocalWorkRunBinding,
        name: String,
    ): Boolean {
        val tool = registry.get(name) ?: return false
        if (tool.exposure != ToolExposure.OPTIONAL ||
            !execution.isNetworkSearchPermitted(name) ||
            (binding.state.value.work.planMode &&
                !LocalToolPolicy.allowedInPlan(name, tool.access))) return false
        return binding.enabledOptionalTools.requestForNextStep(name)
    }
    internal fun clear(binding: LocalWorkRunBinding) {
        execution.clearTurnCapabilities(binding.enabledOptionalTools)
        binding.enabledOptionalTools.resetTask()
    }

    internal suspend fun prepare(
        binding: LocalWorkRunBinding,
        input: String,
    ) {
        val history = binding.runHandle.modelHistory.snapshot()
        val task = effectiveWorkCapabilityTask(
            input, binding.continuationParentRunId, history,
            binding.workState.snapshot().goal?.description,
        )
        execution.prepareWorkTurnCapabilities(
            input = task,
            history = history,
            gitHubConfigured = githubConfigured,
            target = binding.enabledOptionalTools,
        )
    }

    internal fun schemas(
        policy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding,
    ): JsonArray = selectSchemas(policy, binding).also {
        recordSchemaAdmission(binding, it)
    }.schemas

    /** Select once for the model request; compaction may invalidate this candidate. */
    internal fun selectSchemas(
        policy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding,
    ): LocalToolRouter.ToolSchemaSelection {
        // 此时最新用户消息已从 PendingInput 队列写进历史，首轮集群标记才可靠。
        val history = binding.runHandle.modelHistory.snapshot()
        val teamMode = isLocalAgentTeamTurn(history)
        val activeTask = LocalToolCapabilityIntent.latestUserInput(history)
        val newTask = !activeTask.isNullOrBlank() && binding.enabledOptionalTools.isNewTask(activeTask)
        if (newTask) binding.enabledOptionalTools.beginNewTask()
        // Explicit select: owns the visible optional surface for this task. Discoverability,
        // registration and per-call approval continue to use their existing shared owners.
        val exactFocus = binding.enabledOptionalTools.exactFocus
        // A user attachment is authoritative even if their text is merely "analyse this".
        // Surface read-only archive tools for that turn without permanently enlarging CORE.
        if (!exactFocus && latestUserTurnHasArchive(history)) {
            synchronized(binding.enabledOptionalTools) {
                val requested = listOf("archive_list", "archive_read")
                    .filter { registry.get(it) != null }
                val previous = binding.enabledOptionalTools.toList()
                binding.enabledOptionalTools.clear()
                binding.enabledOptionalTools.addAll(requested)
                binding.enabledOptionalTools.addAll(previous)
            }
        }
        // A connection or plugin install can register new tools during this run.
        // Re-evaluate only the active user's task before each model step; newly registered
        // capabilities become available automatically without restarting or editing tool maps.
        if (newTask) {
            // Only a genuinely new user task may replace explicit discovery priorities.
            execution.enableTaskRelevantOptionalTools(
                requireNotNull(activeTask), binding.enabledOptionalTools, prioritize = true,
            )
        }
        // Observe registrations even while explicitly focused. A future user task can
        // reactivate them; the current exact choice is never silently expanded.
        val newMcpTools = binding.mcpDiscovery.observe(registry)
        if (!exactFocus) {
            execution.prioritizeRunCreatedMcpTools(newMcpTools, binding.enabledOptionalTools)
            execution.prioritizePinnedForModelStep(binding.enabledOptionalTools)
        }
        val enabled = synchronized(binding.enabledOptionalTools) {
            if (teamMode && !exactFocus) {
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
            // An unexposed call from the previous model step outranks heuristic pins.
            // It will still be omitted if the actual provider context cannot fit its schema.
            binding.enabledOptionalTools.applyRequestedPriority()
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
        return selection
    }

    /** Record omission facts only for the final model-facing selection. */
    internal fun recordSchemaAdmission(
        binding: LocalWorkRunBinding,
        selection: LocalToolRouter.ToolSchemaSelection,
    ) {
        // The request evidence already records the exact schema digest. Only record a separate
        // fact when a selected capability could not reach the model; no parallel tool registry.
        if (selection.omittedReasons.isNotEmpty()) {
            // Diagnostics are observational, not a new admission gate. A failed diagnostic
            // write must not make an otherwise legitimate model request unavailable.
            try {
                binding.eventLog.append("tool/schema-admission", buildJsonObject {
                    put("version", 1)
                    put("enabled_optional_count", synchronized(binding.enabledOptionalTools) {
                        binding.enabledOptionalTools.size
                    })
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
    }

    internal fun names(
        policy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding,
    ): List<String> = schemas.names(selectSchemas(policy, binding).schemas)

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
        val isolatedWorkspace = kotlinx.coroutines.currentCoroutineContext()[com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext]
        val goal = binding.workState.snapshot().goal
        val paths = goal?.artifactPaths.orEmpty()
        return execution.executeScoped(
            original = call,
            sessionId = binding.sessionId,
            allowMutation = allowMutation,
            planModeEnabled = binding.state.value.work.planMode,
            beforeExecution = stillCurrent,
            executionAttributes = buildMap {
                put("goal_id", goal?.id.orEmpty())
                isolatedWorkspace?.let { put("workspace_id", it.workspaceId); put("workspace_root", it.root.path) }
            },
            onExecutionActive = { active ->
                binding.executingToolCallIds.update { ids -> if (active) ids + call.id else ids - call.id }
            },
            observeFileVersions = { paths.mapNotNull { path ->
                workspaceRoot?.takeIf { isolatedWorkspace == null }?.let { localWorkFileSha256(it, path) }?.let { path to it }
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
    ): List<Pair<LocalToolCall, AgentToolResult>> {
        val output = mutableListOf<Pair<LocalToolCall, AgentToolResult>>()
        val groups = planLocalToolBatch(calls.size) { index -> isParallel(calls[index].name) }
        for (group in groups) {
            val part = calls.subList(group.startInclusive, group.endExclusive)
            if (!group.parallel) {
                val call = part.single()
                output += call to if (superseded()) workSteeredToolResult(call)
                    else execute(binding, call, allowMutation, stillCurrent = { !superseded() })
                continue
            }
            // Do not execute any later group before this entire group settles. Resource leases
            // remain in the shared tool coordinator, so approval and write semantics are intact.
            output += isolatedParallelMap(part) { call ->
                call to if (superseded()) workSteeredToolResult(call)
                    else execute(binding, call, allowMutation, stillCurrent = { !superseded() })
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

/** A committed correction invalidates unstarted calls from the preceding model sample. */
internal fun workSteeredToolResult(call: LocalToolCall): AgentToolResult = AgentToolResult(
    content = "用户已请求立即调整。旧模型采样生成的工具调用未开始执行：" + call.name,
    isError = true,
    errorCode = "WORK_TOOL_SUPERSEDED",
    retryable = false,
    sideEffect = AgentToolSideEffect.NONE,
    recoveryHint = "先读取本轮新的用户要求，不要重复执行已作废的工具调用。",
)
