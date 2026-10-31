package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentRunExecutionContext
import com.labteto.dshmobile.harness.session.SessionExecutionContext
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolInvocationContext
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.local.agent.LocalAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.LocalToolCapabilityIntent
import com.labteto.dshmobile.local.tools.localToolFailure
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.LocalToolRouter
import com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate
import com.labteto.dshmobile.observability.AppLog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Owns the model-visible tool surface and the common permission/error boundary for foreground runs.
 *
 * Built-in implementations stay registered in [ToolRegistry]; this coordinator decides visibility,
 * capability activation, approval and structured error projection so the Runtime Kernel never
 * duplicates those rules around the Agent loop.
 */
internal class LocalToolExecutionCoordinator(
    private val registry: ToolRegistry,
    private val currentSessionId: () -> String,
    private val planMode: () -> Boolean,
    private val enabledOptionalTools: MutableSet<String> = linkedSetOf(),
    private val requestApproval: suspend (
        call: LocalToolCall,
        tool: HarnessTool,
        summary: String,
    ) -> Boolean,
    private val recordExecutionStarted: suspend (
        String,
        LocalToolCall,
        LocalToolExecutionIdentity,
    ) -> Unit = { _, _, _ -> },
    /** Durable per-call decision, scoped to the owning session; never a reusable grant. */
    private val recordAuthorizationDecision: suspend (
        String, LocalToolCall, LocalToolExecutionIdentity, Boolean,
    ) -> Unit = { _, _, _, _ -> },
    private val executionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val networkSearchEnabled: () -> Boolean = { true },
    private val pinnedConnectorIds: () -> List<String> = { emptyList() },
    private val pinnedSkillIds: () -> List<String> = { emptyList() },
    private val workspaceReadWriteGate: LocalWorkspaceReadWriteGate = LocalWorkspaceReadWriteGate(),
    /** Real handler receipts survive cancellation; they never authorize a continuation. */
    private val recordCancelledExecutionFinished: suspend (
        String, LocalToolCall, LocalToolExecutionIdentity,
        com.labteto.dshmobile.harness.tools.ToolResult,
    ) -> Unit = { _, _, _, _ -> },
) {
    // 前台 Lead 与所有后台成员共用此闸门，文件写入工具不得并行交错执行。

    internal fun isNetworkSearchPermitted(name: String): Boolean =
        networkSearchEnabled() || LocalToolPolicy.canonical(name) !in NETWORK_SEARCH_TOOLS

    fun clearTurnCapabilities(target: MutableSet<String> = enabledOptionalTools) {
        synchronized(target) { target.clear() }
    }

    fun enabledOptionalSnapshot(): Set<String> =
        synchronized(enabledOptionalTools) { enabledOptionalTools.toSet() }

    fun enableOptionalTools(
        names: Collection<String>,
        target: MutableSet<String> = enabledOptionalTools,
    ) {
        synchronized(target) {
            target += names.filter { name ->
                registry.get(name)?.takeIf { isNetworkSearchPermitted(it.name) }
                    ?.let(LocalToolRouter::isOptional) == true
            }
        }
    }

    fun enableTaskRelevantOptionalTools(
        taskContext: String,
        target: MutableSet<String> = enabledOptionalTools,
        prioritize: Boolean = true,
    ) {
        // GitHub pre-activation has its own intent and credential gate. Generic keyword matches
        // must not reactivate it from a negated current input or an older GitHub request.
        val tools = registry.names().mapNotNull(registry::get).filterNot(::isGitHubConnectorTool)
            .filter { isNetworkSearchPermitted(it.name) }
        val relevant = LocalToolRouter.relevantOptionalToolNames(tools, taskContext)
            .filter { name ->
                registry.get(name)?.takeIf { isNetworkSearchPermitted(it.name) }
                    ?.let(LocalToolRouter::isOptional) == true
            }
        synchronized(target) {
            if (prioritize && relevant.isNotEmpty()) {
                // Re-rank the current task even when its tools were selected in an earlier step.
                // This prevents previous-turn/history tools from occupying the entire budget.
                val previouslySelected = target.toList()
                target.clear()
                target.addAll(relevant)
                target.addAll(previouslySelected)
            } else {
                target.addAll(relevant)
            }
        }
    }

    suspend fun prepareWorkTurnCapabilities(
        input: String,
        history: List<JsonObject>,
        gitHubConfigured: suspend () -> Boolean,
        target: MutableSet<String>? = null,
    ) {
        val intent = LocalToolCapabilityIntent.from(input, history)
        val enableGitHub = if (intent.requestsGitHub || "github" in pinnedConnectorIds()) {
            try {
                gitHubConfigured()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("LocalToolExecution", "check_github_tool_availability", error)
                false
            }
        } else false
        val selected = target ?: enabledOptionalTools
        // Put the current user's concrete request ahead of older contextual hints.
        // Registered tools are looked up on every run, so newly installed MCP tools need no catalog patch.
        if (enableGitHub) enableGitHubConnectorTools(selected)
        enableTaskRelevantOptionalTools(input, selected)
        prepareWorkTurnCapabilities(intent.context, enableGitHub, selected, prioritizeTask = false)
    }

    fun prepareWorkTurnCapabilities(
        taskContext: String,
        enableGitHub: Boolean,
        target: MutableSet<String>? = null,
        prioritizeTask: Boolean = true,
    ) {
        val resolvedTarget = target ?: enabledOptionalTools
        // User-requested provider tools outrank background defaults under the schema-token budget.
        if (enableGitHub) enableGitHubConnectorTools(resolvedTarget)
        enableTaskRelevantOptionalTools(taskContext, resolvedTarget, prioritize = prioritizeTask)
        if (networkSearchEnabled()) enableOptionalTools(DEFAULT_NETWORK_TOOLS, resolvedTarget)
        // Preserve explicit user choices after ALL heuristic re-ranking, not before it.
        // The credential gate above determines whether GitHub is admitted.
        enablePinnedMediaTools(resolvedTarget)
        prioritizePinnedConnectorTools(resolvedTarget, enableGitHub)
    }

    fun enableGitHubConnectorTools(target: MutableSet<String> = enabledOptionalTools) {
        val githubTools = registry.names().mapNotNull(registry::get)
            .filter { tool ->
                LocalToolRouter.isOptional(tool) && isGitHubConnectorTool(tool)
            }
            .map(HarnessTool::name)
        enableOptionalTools(githubTools, target)
    }

    /** Give MCP tools registered during this run a chance to enter the next schema budget.
     * Discovery is never authorization; the live registry and per-call approval remain authoritative. */
    fun prioritizeRunCreatedMcpTools(
        names: List<String>, target: MutableSet<String> = enabledOptionalTools,
    ) {
        val preferred = names.mapNotNull(registry::get)
            .filter { LocalToolRouter.isOptional(it) &&
                it.metadata.family.equals("MCP", ignoreCase = true) &&
                isNetworkSearchPermitted(it.name) }
            .map(HarnessTool::name)
        enableOptionalTools(preferred, target)
        synchronized(target) {
            val existing = target.toList()
            target.clear()
            target.addAll(preferred.filter { it in existing })
            target.addAll(existing)
        }
    }

    /** Reassert explicit pins after each model-step heuristic rerank, including step-2+ schemas. */
    fun prioritizePinnedForModelStep(target: MutableSet<String> = enabledOptionalTools) {
        // Only re-order tools already credential-admitted during prepare().
        // Avoid adding GitHub tools after an unavailable/auth-failed preflight.
        val admittedGithub = synchronized(target) {
            target.any { name -> registry.get(name)?.let(::isGitHubConnectorTool) == true }
        }
        enablePinnedMediaTools(target)
        prioritizePinnedConnectorTools(target, enableGitHub = admittedGithub)
    }

    private fun enablePinnedMediaTools(target: MutableSet<String>) {
        if (pinnedSkillIds().none { it == "imagegen" || it == "minimax-media" }) return
        enableOptionalTools(listOf("media_image_options", "media_image_status", "media_image_create"), target)
    }

    private fun prioritizePinnedConnectorTools(target: MutableSet<String>, enableGitHub: Boolean) {
        val pins = pinnedConnectorIds().distinct()
        if (pins.isEmpty()) return
        val registered = registry.names().mapNotNull(registry::get)
        val preferred = pins.flatMap { id ->
            if (id == "github") {
                if (enableGitHub) registered.filter(::isGitHubConnectorTool).map(HarnessTool::name)
                else emptyList()
            } else if (Regex("[A-Za-z0-9_-]{1,48}").matches(id)) {
                registered.filter { it.metadata.family.equals("MCP", ignoreCase = true) &&
                    it.name.startsWith("mcp_${id}_") }.map(HarnessTool::name)
            } else emptyList()
        }
        // Registration, network policy and approval are still the single execution authority.
        enableOptionalTools(preferred, target)
        synchronized(target) {
            val previous = target.toList()
            target.clear()
            target.addAll(preferred.filter { it in previous })
            target.addAll(previous)
        }
    }

    private fun isGitHubConnectorTool(tool: HarnessTool): Boolean =
        tool.metadata.family.equals(GITHUB_TOOL_FAMILY, ignoreCase = true)

    fun capabilitySummary(enabledOptional: Set<String> = enabledOptionalSnapshot()): String {
        val registered = registry.names().mapNotNull(registry::get)
        val blocked = registered.filterNot { isNetworkSearchPermitted(it.name) }
            .map(HarnessTool::name).toSet()
        return LocalToolRouter.capabilitySummary(registered, enabledOptional, blocked)
    }

    fun visibleSchemas(
        policy: LocalAgentRunPolicy,
        maxOptionalDefinitionTokens: Int = LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
    ): JsonArray = selectVisibleSchemas(policy, maxOptionalDefinitionTokens).schemas

    /** Foreground requests and capability inspection share one admission decision. */
    internal fun selectVisibleSchemas(
        policy: LocalAgentRunPolicy,
        maxOptionalDefinitionTokens: Int = LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
    ): LocalToolRouter.ToolSchemaSelection {
        if (!policy.toolsEnabled) return LocalToolRouter.ToolSchemaSelection(
            schemas = JsonArray(emptyList()), omittedReasons = emptyMap(), compactedNames = emptySet(),
        )
        val enabled = enabledOptionalSnapshot()
        val tools = registry.names().mapNotNull(registry::get)
            .filter { isNetworkSearchPermitted(it.name) }
        val selection = LocalToolRouter.selectSchemas(
            tools = tools, enabledOptional = enabled,
            maxOptionalDefinitionTokens = maxOptionalDefinitionTokens,
        )
        val unavailable = enabled.mapNotNull { name ->
            val registered = registry.get(name)
            val reason = when {
                registered == null -> "已选工具未注册或已卸载；请重新发现能力"
                !isNetworkSearchPermitted(name) -> "网络搜索设置已关闭"
                else -> null
            }
            reason?.let { name to it }
        }.toMap()
        return selection.copy(omittedReasons = unavailable + selection.omittedReasons)
    }

    fun visibleToolNames(policy: LocalAgentRunPolicy): List<String> =
        visibleSchemas(policy).mapNotNull { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.content
        }

    fun searchCapabilities(
        query: String,
        target: MutableSet<String> = enabledOptionalTools,
    ): String {
        val registered = registry.names().mapNotNull(registry::get)
        val blocked = registered.filterNot { isNetworkSearchPermitted(it.name) }
            .map(HarnessTool::name).toSet()
        val tools = registered.filter { it.name !in blocked }
        if (LocalToolRouter.isInventoryRequest(query)) {
            return LocalToolRouter.capabilityDirectory(registered,
                synchronized(target) { target.toSet() }, blocked)
        }
        val matches = LocalToolRouter.search(tools, query)
        if (matches.isEmpty()) {
            // Don't bounce the user back to guess an internal capability name.
            return "实时工具表没有精确匹配的扩展能力。请勿猜测调用不存在的工具，也不必因此放弃任务。\n" +
                "在允许联网与写入的 Work 中，可先用 web_search / GitHub 查找可复用实现，" +
                "或使用工作区文件和终端制作脚本、技能或 MCP stdio 服务，" +
                "经 mcp_stdio_connect 注册后在下一模型步骤确认实际暴露与审批，再做真实测试。\n" +
                "以下为当前真实已注册能力（目录不代表远端服务已验证）：\n" +
                LocalToolRouter.capabilityDirectory(registered,
                    synchronized(target) { target.toSet() }, blocked)
        }
        val currentEnabled = synchronized(target) {
            // Explicitly discovered capabilities outrank speculative pre-activation. This changes
            // the next tool surface intentionally, so the request projection can update its cache generation.
            val selected = matches.map(HarnessTool::name)
            val previous = target.filterNot { it in selected }
            target.clear()
            target.addAll(selected)
            target.addAll(previous)
            target.toSet()
        }
        val selection = LocalToolRouter.selectSchemas(
            tools = tools,
            enabledOptional = currentEnabled,
        )
        val projected = selection.schemas.mapNotNull { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.content
        }.toSet()
        val omitted = matches.count { it.name !in projected }
        return buildString {
            appendLine("发现 ${matches.size} 个已注册的候选扩展工具；按默认工具定义预算估算 ${matches.size - omitted} 个可在下一步使用：")
            // A large live MCP catalog is discoverable, but should not flood a model turn.
            matches.take(MAX_SEARCH_RESULT_PREVIEW).forEach { tool ->
                append("- ").append(tool.name)
                selection.omittedReasons[tool.name]?.let { reason ->
                    append(" [当前未装入：").append(reason).append("]")
                }
                if (tool.name in selection.compactedNames) append(" [已精简冗长说明，保留完整参数定义]")
                LocalToolRouter.conciseDescription(tool).takeIf(String::isNotBlank)?.let {
                    append("：").append(it)
                }
                appendLine()
            }
            if (matches.size > MAX_SEARCH_RESULT_PREVIEW) {
                appendLine("另有 ${matches.size - MAX_SEARCH_RESULT_PREVIEW} 个匹配工具未在此展开；可按类别或精确名称进一步搜索。")
            }
            if (omitted > 0) appendLine("有 $omitted 个候选当前未装入，原因已逐项标注；仍无法装入时需按实际工具定义修复，而非猜测调用参数。")
            append("实际调用仍以下一次模型请求真实提供的工具表及代理权限为准。")
        }.trimEnd()
    }

    suspend fun execute(
        original: LocalToolCall,
        allowMutation: Boolean,
    ): AgentToolResult = executeScoped(
        original = original,
        sessionId = currentSessionId(),
        allowMutation = allowMutation,
        planModeEnabled = planMode(),
        approval = requestApproval,
    )

    suspend fun executeScoped(
        original: LocalToolCall,
        sessionId: String,
        allowMutation: Boolean,
        planModeEnabled: Boolean,
        approval: suspend (LocalToolCall, HarnessTool, String) -> Boolean,
        beforeExecution: suspend () -> Boolean = { true },
        observeFileVersions: (() -> Map<String, String>)? = null,
        executionAttributes: Map<String, String> = emptyMap(),
        onExecutionActive: (Boolean) -> Unit = {},
    ): AgentToolResult {
        val call = original.copy(name = LocalToolPolicy.canonical(original.name))
        if (!isNetworkSearchPermitted(call.name)) return AgentToolResult(
            content = "网络搜索已关闭，请先在能力中心开启网络搜索。",
            isError = true,
            errorCode = "NETWORK_SEARCH_DISABLED",
            recoveryHint = "前往能力中心开启网络搜索，或使用现有本地资料完成任务。",
        )
        val registered = registry.get(call.name)
            ?: return AgentToolResult(
                content = "未知工具：" + call.name,
                isError = true,
                errorCode = "UNKNOWN_TOOL",
                recoveryHint = "先使用 capability_search 或检查工具名称。",
            )

        if (planModeEnabled && !LocalToolPolicy.allowedInPlan(call.name, registered.access, call.arguments)) {
            return AgentToolResult(
                content = "当前处于规划模式，只能检查和制定方案；请先通过 exit_plan_mode 提交计划。",
                isError = true,
                errorCode = "PLAN_MODE_BLOCKED",
                recoveryHint = "提交并批准计划后再执行修改类工具。",
            )
        }

        val readLike = LocalToolPolicy.isReadOnlyInvocation(call.name, registered.access, call.arguments)
        if (!allowMutation && !readLike) {
            return AgentToolResult(
                content = "当前子任务是只读作用域，不能执行会改变状态的工具：" + call.name,
                isError = true,
                errorCode = "MUTATION_SCOPE_BLOCKED",
                recoveryHint = "改用只读检查工具，或由父智能体在可写作用域执行该操作。",
            )
        }

        val environment = currentCoroutineContext()[com.labteto.dshmobile.harness.tools.ToolExecutionEnvironmentContext]
            ?.takeIf { registered.metadata.environmentBound }
        val executionOwner = currentCoroutineContext()[SessionExecutionContext]?.lease
        val runOwner = currentCoroutineContext()[AgentRunExecutionContext]
        val callerJob = currentCoroutineContext()[Job]
        var authorizationRecorded = false
        var approvalDenied = false
        var executionStarted = false
        val executionIdentity = LocalToolExecutionIdentity(
            executionId = executionIdFactory(),
            rootCallId = call.id,
            attributes = buildMap {
                putAll(executionAttributes)
                environment?.let { put("execution_environment", it.environmentId) }
                currentCoroutineContext()[com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext]?.takeIf { environment == null }?.let {
                    put("workspace_id", it.workspaceId)
                    put("workspace_root", it.root.path)
                }
                runOwner?.let {
                    put("run_id", it.identity.runId)
                    it.parentRunId?.let { parent -> put("parent_run_id", parent) }
                }
                executionOwner?.identity?.let {
                    put("session_owner_incarnation", it.incarnation)
                    put("session_owner_epoch", it.epoch.toString())
                }
            },
            verifiedReadOnly = readLike,
        )
        var executionLease: LocalWorkspaceReadWriteGate.Lease? = null
        val invocation = try {
            executionOwner?.requireCurrent(sessionId)
            runOwner?.requireCurrent(sessionId)
            withContext(ToolInvocationContext(sessionId, call.id, executionIdentity.executionId)) {
                registry.executeTracked(
                    name = call.name,
                    input = call.arguments,
                    rawArguments = call.rawArguments,
                    context = ToolContext(
                        sessionId = sessionId,
                        allowMutation = allowMutation,
                        attributes = buildMap {
                            putAll(executionIdentity.attributes)
                            put("call_id", call.id)
                            put("execution_id", executionIdentity.executionId)
                            put("root_call_id", executionIdentity.rootCallId)
                            put("plan_mode", planModeEnabled)
                            executionIdentity.parentExecutionId?.let { put("parent_execution_id", it) }
                            currentCoroutineContext()[LocalModelRunContext]?.profile?.let { put("model_profile", it) }
                        },
                        expectedRegistration = registered,
                        onExecutionFinished = { _, result ->
                            if (callerJob?.isCancelled == true) {
                                recordCancelledExecutionFinished(sessionId, call, executionIdentity, result)
                            }
                        },
                        approval = { tool ->
                            val granted = approval(call, tool, approvalSummary(call, tool))
                            // No tool may start if the exact user's decision failed to
                            // persist. Do not reuse a previous call's selection/grant.
                            recordAuthorizationDecision(sessionId, call, executionIdentity, granted)
                            authorizationRecorded = true
                            if (!granted) approvalDenied = true
                            granted
                        },
                        onExecutionStarted = { admittedTool ->
                            // A new correction can arrive while awaiting approval or resource admission.
                            // Reject the stale call before entering its executor, with no side effect.
                            executionOwner?.requireCurrent(sessionId)
                            kotlinx.coroutines.currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentBackendExecutionContext]?.requireCurrent()
                            runOwner?.requireCurrent(sessionId)
                            if (!beforeExecution()) throw LocalToolSampleSuperseded()
                            // The registry calls this only after schema checks and user approval.
                            // Waiting for approval must never hold a filesystem read/write lease.
                            executionLease = if (environment == null) workspaceReadWriteGate.acquire(admittedTool) else null
                            // Admission can suspend behind another read/write operation. A correction
                            // committed during that wait must invalidate this still-unstarted call.
                            executionOwner?.requireCurrent(sessionId)
                            kotlinx.coroutines.currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentBackendExecutionContext]?.requireCurrent()
                            runOwner?.requireCurrent(sessionId)
                            if (!beforeExecution()) throw LocalToolSampleSuperseded()
                            // Approval may outlive the exact plugin registration. Re-check after waiting
                            // for the shared read/write gate and before writing the started fact.
                            if (registry.get(call.name) !== admittedTool) {
                                throw LocalToolRegistrationChanged()
                            }
                            // NEVER/automatic policies still produce a decision for this exact
                            // execution. Registry admission is the authority, not UI selection.
                            if (!authorizationRecorded) {
                                recordAuthorizationDecision(sessionId, call, executionIdentity, true)
                                authorizationRecorded = true
                            }
                            recordExecutionStarted(sessionId, call, executionIdentity)
                            executionStarted = true
                            onExecutionActive(true)
                        },
                    ),
                )
            }.let { invocation ->
                // Observe while the real workspace lease is still held. The caller owns
                // what to observe; this neutral coordinator never interprets goal semantics.
                if (invocation.executionStarted && executionLease != null && !invocation.result.isError) {
                    invocation.copy(result = invocation.result.copy(
                        fileVersions = invocation.result.fileVersions + observeFileVersions?.invoke().orEmpty(),
                    ))
                } else invocation
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            AppLog.info("LocalToolExecution", "operation=execute_tool_cancelled tool=${call.name} call_id=${call.id} session_id=$sessionId")
            return thrownFailure(call, registered, "TASK_CANCELLED", cancelled.message ?: "子任务自身被取消", executionStarted, sessionId = sessionId, executionId = executionIdentity.executionId)
        } catch (changed: LocalToolRegistrationChanged) {
            return AgentToolResult(
                content = "工具已在执行接纳期间更新或卸载：${call.name}；旧审批与旧调用不再有效。",
                isError = true,
                errorCode = "TOOL_REGISTRATION_CHANGED",
                retryable = false,
                sideEffect = AgentToolSideEffect.NONE,
                recoveryHint = "重新发现当前工具并核对权限；此调用没有进入工具执行器。",
            )
        } catch (superseded: LocalToolSampleSuperseded) {
            return AgentToolResult(
                content = "用户已要求立即调整，本次旧工具调用尚未开始执行：" + call.name,
                isError = true,
                errorCode = "WORK_TOOL_SUPERSEDED",
                retryable = false,
                sideEffect = AgentToolSideEffect.NONE,
                recoveryHint = "读取最新已提交的用户要求，不要重复执行旧调用。",
            )
        } catch (error: LocalWebException) {
            return thrownFailure(call, registered, error.code, error.message ?: "网页工具失败", executionStarted,
                cause = error, sessionId = sessionId, executionId = executionIdentity.executionId)
        } catch (error: LocalModelException) {
            return thrownFailure(call, registered, error.code, error.message ?: "模型请求失败", executionStarted,
                cause = error, sessionId = sessionId, executionId = executionIdentity.executionId)
        } catch (error: Exception) {
            val code = when {
                error is java.io.FileNotFoundException ||
                    error is java.nio.file.NoSuchFileException -> "TOOL_NOT_FOUND"
                error is java.nio.file.AccessDeniedException || error is SecurityException ->
                    "TOOL_PERMISSION_DENIED"
                call.name.startsWith("lsp_") && error is IllegalStateException &&
                    error.message.orEmpty().contains("用户拒绝") -> "APPROVAL_DENIED"
                call.name.startsWith("lsp_") && error is IllegalStateException &&
                    error.message.orEmpty().contains("人工审批") -> "APPROVAL_REQUIRED"
                call.name.startsWith("lsp_") && error is IllegalArgumentException &&
                    error.message.orEmpty().contains("语言服务器") -> "TOOL_UNAVAILABLE"
                error is IllegalArgumentException -> "TOOL_INVALID_ARGUMENT"
                error is IllegalStateException && call.name.startsWith("lsp_") -> "TOOL_UNAVAILABLE"
                error is IllegalStateException && !executionStarted -> "TOOL_PRECONDITION_FAILED"
                error is java.io.IOException -> "TOOL_IO_ERROR"
                else -> "TOOL_ERROR"
            }
            return thrownFailure(
                call, registered, code, error.message ?: error::class.java.simpleName,
                executionStarted, error::class.java.simpleName, cause = error, sessionId = sessionId,
                executionId = executionIdentity.executionId,
            )
        } finally {
            try { executionLease?.close() }
            finally { if (executionStarted) onExecutionActive(false) }
        }
        executionStarted = executionStarted || invocation.executionStarted
        currentCoroutineContext().ensureActive()
        val result = invocation.result

        if (!result.isError) {
            return AgentToolResult(
                content = result.content,
                retention = result.retention,
                processExitCode = result.processExitCode,
                fileVersions = result.fileVersions,
                richContent = result.richContent,
                executionId = executionIdentity.executionId.takeIf { executionStarted },
                sideEffect = if (executionStarted && !readLike) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE,
            )
        }

        val providerCode = result.errorCode?.takeIf(String::isNotBlank)
        val timedOut = providerCode == "TOOL_TIMEOUT" ||
            (providerCode == null && result.content.startsWith("工具执行超时："))
        val errorCode = when {
            approvalDenied -> "APPROVAL_DENIED"
            providerCode != null -> providerCode
            timedOut -> "TOOL_TIMEOUT"
            else -> "TOOL_REPORTED_ERROR"
        }
        val mutationMayHaveSideEffect = executionStarted && !readLike
        val retryable = when {
            !executionStarted -> result.retryable
            readLike -> result.retryable || (providerCode == null && timedOut)
            else -> false
        }
        val recoveryHint = when {
            mutationMayHaveSideEffect -> "工具可能已经产生副作用；先检查当前状态，不要直接重试。"
            // Registry-proven PRE-execution schema rejection can be corrected deterministically
            // using this exact registered tool. No guessed aliases, user approval or replay.
            !executionStarted && errorCode == "INVALID_TOOL_ARGUMENTS" ->
                "工具尚未进入执行器；" +
                    com.labteto.dshmobile.local.tools.toolArgumentContractSummary(registered.schema) +
                    "；仅使用本轮实际提供的工具名和参数字段，修正后重新提交。"
            else -> result.recoveryHint ?: when {
                approvalDenied -> "该工具没有获得批准；不要重复调用，改用已授权能力或等待用户调整权限。"
                timedOut && readLike -> "只读工具超时，可缩小范围后重试一次。"
                timedOut -> "工具执行超时；先检查当前状态，再决定是否重试。"
                else -> "根据工具返回内容检查前置条件；确认状态后再决定下一步。"
            }
        }
        AppLog.warn("LocalToolExecution", "failure=true operation=execute_tool session_id=$sessionId call_id=${call.id} tool=${call.name} code=$errorCode retryable=$retryable side_effect=$mutationMayHaveSideEffect")
        return AgentToolResult(
            content = result.content,
            isError = true,
            errorCode = errorCode,
            retryable = retryable,
            sideEffect = if (mutationMayHaveSideEffect) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE,
            recoveryHint = recoveryHint,
            retention = result.retention,
            processExitCode = result.processExitCode,
            fileVersions = result.fileVersions,
            richContent = result.richContent,
            executionId = executionIdentity.executionId.takeIf { executionStarted },
        )
    }

    private fun thrownFailure(
        call: LocalToolCall,
        tool: HarnessTool,
        code: String,
        message: String,
        executionStarted: Boolean,
        exceptionType: String? = null,
        cause: Throwable? = null,
        sessionId: String? = null,
        executionId: String? = null,
    ): AgentToolResult {
        val baseResult = localToolFailure(code, message,
            readOnly = LocalToolPolicy.isReadOnlyInvocation(call.name, tool.access, call.arguments),
            executionStarted = executionStarted)
        val result = if (code == "TOOL_INVALID_ARGUMENT") baseResult.copy(
            recoveryHint = baseResult.recoveryHint.orEmpty() + "\n" +
                com.labteto.dshmobile.local.tools.toolArgumentContractSummary(tool.schema),
        ) else baseResult
        if (cause != null) {
            AppLog.failure(
                "LocalToolExecution",
                "execute_tool tool=${call.name} code=$code started=$executionStarted retryable=${result.retryable}",
                cause,
                sessionId = sessionId,
                requestId = call.id,
            )
        } else {
            AppLog.warn("LocalToolExecution", "failure=true operation=execute_tool tool=${call.name} call_id=${call.id} code=$code started=$executionStarted retryable=${result.retryable} exception_type=${exceptionType ?: "unknown"}")
        }
        return result.copy(executionId = executionId.takeIf { executionStarted })
    }

    private fun approvalSummary(call: LocalToolCall, tool: HarnessTool): String = when (tool.name) {
        "write", "edit", "apply_patch", "download_file" ->
            tool.name + "：" + call.arguments.optionalStringForCoordinator("path").orEmpty()
        "bash", "pwsh", "shell", "run_shell", "process_exec",
        "terminal_open", "terminal_send", "terminal_write" ->
            "执行本机操作以完成当前任务"
        "lsp_start" -> "启用代码智能分析"
        else -> "执行 " + tool.name + "（权限级别：" + tool.access.name.lowercase() + "）"
    }

    private companion object {
        val DEFAULT_NETWORK_TOOLS = listOf("web_search", "web_fetch")
        val NETWORK_SEARCH_TOOLS = setOf("web_search", "web_fetch")
        const val GITHUB_TOOL_FAMILY = "GitHub"
        const val MAX_SEARCH_RESULT_PREVIEW = 100
        val MUTATING_ACCESSES = setOf(
            ToolAccess.WORKSPACE_WRITE,
            ToolAccess.SESSION_WRITE,
            ToolAccess.PROCESS,
            ToolAccess.AGENT_CONTROL,
            ToolAccess.DEVICE,
            ToolAccess.PRIVILEGED,
        )
    }
}

internal data class LocalToolExecutionIdentity(
    val executionId: String,
    val rootCallId: String,
    val parentExecutionId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val verifiedReadOnly: Boolean = false,
)

private fun JsonObject.optionalStringForCoordinator(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Work-owned revision guard rejected a stale call before it entered the registered executor. */
internal class LocalToolSampleSuperseded : IllegalStateException("Work model sample superseded by user")

/** Registry revision changed during admission/resource wait; handler was never entered. */
internal class LocalToolRegistrationChanged : IllegalStateException("Tool registration changed before execution")
