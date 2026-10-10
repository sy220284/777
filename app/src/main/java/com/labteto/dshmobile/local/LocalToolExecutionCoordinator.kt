package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.local.agent.LocalAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.LocalToolCapabilityIntent
import com.labteto.dshmobile.local.tools.localToolFailure
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.LocalToolRouter
import com.labteto.dshmobile.observability.AppLog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
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
    private val executionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val networkSearchEnabled: () -> Boolean = { true },
) {
    // 前台 Lead 与所有后台成员共用此闸门，文件写入工具不得并行交错执行。
    private val workspaceMutationMutex = Mutex()
    private val serializedWorkspaceTools = setOf(
        "write", "edit", "apply_patch", "bash", "run_shell", "download_file",
    )

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
    ) {
        // GitHub pre-activation has its own intent and credential gate. Generic keyword matches
        // must not reactivate it from a negated current input or an older GitHub request.
        val tools = registry.names().mapNotNull(registry::get).filterNot(::isGitHubConnectorTool)
            .filter { isNetworkSearchPermitted(it.name) }
        enableOptionalTools(
            LocalToolRouter.relevantOptionalToolNames(tools, taskContext),
            target,
        )
    }

    suspend fun prepareWorkTurnCapabilities(
        input: String,
        history: List<JsonObject>,
        gitHubConfigured: suspend () -> Boolean,
        target: MutableSet<String>? = null,
    ) {
        val intent = LocalToolCapabilityIntent.from(input, history)
        val enableGitHub = if (intent.requestsGitHub) {
            try {
                gitHubConfigured()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        } else false
        prepareWorkTurnCapabilities(intent.context, enableGitHub, target)
    }

    fun prepareWorkTurnCapabilities(
        taskContext: String,
        enableGitHub: Boolean,
        target: MutableSet<String>? = null,
    ) {
        val resolvedTarget = target ?: enabledOptionalTools
        // Model-independent default network capabilities for every Work turn.
        if (networkSearchEnabled()) enableOptionalTools(DEFAULT_NETWORK_TOOLS, resolvedTarget)
        enableTaskRelevantOptionalTools(taskContext, resolvedTarget)
        if (enableGitHub) enableGitHubConnectorTools(resolvedTarget)
    }

    fun enableGitHubConnectorTools(target: MutableSet<String> = enabledOptionalTools) {
        val githubTools = registry.names().mapNotNull(registry::get)
            .filter { tool ->
                LocalToolRouter.isOptional(tool) && isGitHubConnectorTool(tool)
            }
            .map(HarnessTool::name)
        enableOptionalTools(githubTools, target)
    }

    private fun isGitHubConnectorTool(tool: HarnessTool): Boolean =
        tool.metadata.family.equals(GITHUB_TOOL_FAMILY, ignoreCase = true)

    fun capabilitySummary(enabledOptional: Set<String> = enabledOptionalSnapshot()): String {
        val tools = registry.names().mapNotNull(registry::get)
            .filter { isNetworkSearchPermitted(it.name) }
        return LocalToolRouter.capabilitySummary(tools, enabledOptional)
    }

    fun visibleSchemas(
        policy: LocalAgentRunPolicy,
        maxOptionalDefinitionTokens: Int = LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
    ): JsonArray {
        if (!policy.toolsEnabled) return JsonArray(emptyList())
        val tools = registry.names().mapNotNull(registry::get)
            .filter { isNetworkSearchPermitted(it.name) }
        return LocalToolRouter.visibleSchemas(
            tools = tools,
            enabledOptional = enabledOptionalSnapshot(),
            maxOptionalDefinitionTokens = maxOptionalDefinitionTokens,
        )
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
        val tools = registry.names().mapNotNull(registry::get)
        val matches = LocalToolRouter.search(
            tools.filter { isNetworkSearchPermitted(it.name) }, query,
        )
        if (matches.isEmpty()) {
            return "未找到匹配的扩展能力；可换用联网、下载、记忆、会话、GitHub、Android、视觉、运行时、MCP、LSP、自动化或 Webhook 等关键词"
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
        val projected = LocalToolRouter.visibleSchemas(
            tools = tools.filter { isNetworkSearchPermitted(it.name) },
            enabledOptional = currentEnabled,
        ).mapNotNull { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.content
        }.toSet()
        val omitted = matches.count { it.name !in projected }
        return buildString {
            appendLine("发现 ${matches.size} 个候选扩展工具；其中 ${matches.size - omitted} 个按当前模型工具表预算可在下一步使用：")
            matches.forEach { tool ->
                append("- ").append(tool.name)
                if (tool.name !in projected) append(" [未装入工具表：定义预算、复杂度或数量限制]")
                LocalToolRouter.conciseDescription(tool).takeIf(String::isNotBlank)?.let {
                    append("：").append(it)
                }
                appendLine()
            }
            if (omitted > 0) appendLine("有 $omitted 个候选未进入工具表，不能直接调用；请按精确工具名重新搜索，或精简其工具定义。")
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

        var approvalDenied = false
        var executionStarted = false
        val executionIdentity = LocalToolExecutionIdentity(
            executionId = executionIdFactory(),
            rootCallId = call.id,
        )
        val serializedMutation = !readLike && call.name in serializedWorkspaceTools
        if (serializedMutation) workspaceMutationMutex.lock()
        val invocation = try {
            registry.executeTracked(
                name = call.name,
                input = call.arguments,
                rawArguments = call.rawArguments,
                context = ToolContext(
                    sessionId = sessionId,
                    allowMutation = allowMutation,
                    attributes = buildMap {
                        put("call_id", call.id)
                        put("execution_id", executionIdentity.executionId)
                        put("root_call_id", executionIdentity.rootCallId)
                        executionIdentity.parentExecutionId?.let { put("parent_execution_id", it) }
                        currentCoroutineContext()[LocalModelRunContext]?.profile?.let { put("model_profile", it) }
                    },
                    approval = { tool ->
                        val granted = approval(call, tool, approvalSummary(call, tool))
                        if (!granted) approvalDenied = true
                        granted
                    },
                    onExecutionStarted = {
                        recordExecutionStarted(sessionId, call, executionIdentity)
                        executionStarted = true
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            return thrownFailure(call, registered, "TASK_CANCELLED", cancelled.message ?: "子任务自身被取消", executionStarted)
        } catch (error: LocalWebException) {
            return thrownFailure(call, registered, error.code, error.message ?: "网页工具失败", executionStarted)
        } catch (error: LocalModelException) {
            return thrownFailure(call, registered, error.code, error.message ?: "模型请求失败", executionStarted)
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
                executionStarted, error::class.java.simpleName,
            )
        } finally {
            if (serializedMutation) workspaceMutationMutex.unlock()
        }
        executionStarted = executionStarted || invocation.executionStarted
        val result = invocation.result

        if (!result.isError) {
            return AgentToolResult(
                content = result.content,
                retention = result.retention,
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
            else -> result.recoveryHint ?: when {
                approvalDenied -> "该工具没有获得批准；不要重复调用，改用已授权能力或等待用户调整权限。"
                timedOut && readLike -> "只读工具超时，可缩小范围后重试一次。"
                timedOut -> "工具执行超时；先检查当前状态，再决定是否重试。"
                else -> "根据工具返回内容检查前置条件；确认状态后再决定下一步。"
            }
        }
        AppLog.warn("LocalToolExecution", "工具执行失败 tool=${call.name} code=$errorCode retryable=$retryable")
        return AgentToolResult(
            content = result.content,
            isError = true,
            errorCode = errorCode,
            retryable = retryable,
            sideEffect = if (mutationMayHaveSideEffect) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE,
            recoveryHint = recoveryHint,
            retention = result.retention,
        )
    }

    private fun thrownFailure(
        call: LocalToolCall,
        tool: HarnessTool,
        code: String,
        message: String,
        executionStarted: Boolean,
        exceptionType: String? = null,
    ): AgentToolResult {
        val result = localToolFailure(code, message,
            readOnly = LocalToolPolicy.isReadOnlyInvocation(call.name, tool.access, call.arguments),
            executionStarted = executionStarted)
        AppLog.warn("LocalToolExecution", "工具执行异常 tool=${call.name} code=$code started=$executionStarted retryable=${result.retryable} exception_type=${exceptionType ?: "unknown"}")
        return result
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
)

private fun JsonObject.optionalStringForCoordinator(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
