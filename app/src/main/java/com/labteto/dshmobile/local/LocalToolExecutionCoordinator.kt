package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.observability.AppLog
import kotlinx.coroutines.currentCoroutineContext
import com.labteto.dshmobile.local.model.LocalModelRunContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Owns the model-visible tool surface and the common permission/error boundary for foreground runs.
 *
 * Built-in implementations stay registered in [ToolRegistry]; this coordinator decides visibility,
 * capability activation, approval and structured error projection so LocalHarnessEngine no longer
 * duplicates those rules around the Agent loop.
 */
internal class LocalToolExecutionCoordinator(
    private val registry: ToolRegistry,
    private val currentSessionId: () -> String,
    private val planMode: () -> Boolean,
    private val enabledOptionalTools: MutableSet<String>,
    private val requestApproval: suspend (
        call: LocalToolCall,
        tool: HarnessTool,
        summary: String,
    ) -> Boolean,
) {
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
                registry.get(name)?.let(LocalToolRouter::isOptional) == true
            }
        }
    }

    fun enableTaskRelevantOptionalTools(
        taskContext: String,
        target: MutableSet<String> = enabledOptionalTools,
    ) {
        val tools = registry.names().mapNotNull(registry::get)
        enableOptionalTools(
            LocalToolRouter.relevantOptionalToolNames(tools, taskContext),
            target,
        )
    }

    fun prepareWorkTurnCapabilities(
        taskContext: String,
        enableGitHub: Boolean,
        target: MutableSet<String>? = null,
    ) {
        val resolvedTarget = target ?: enabledOptionalTools
        enableTaskRelevantOptionalTools(taskContext, resolvedTarget)
        if (enableGitHub) enableGitHubConnectorTools(resolvedTarget)
    }

    fun enableGitHubConnectorTools(target: MutableSet<String> = enabledOptionalTools) {
        val githubTools = registry.names().mapNotNull(registry::get)
            .filter { tool ->
                LocalToolRouter.isOptional(tool) &&
                    tool.metadata.family.equals(GITHUB_TOOL_FAMILY, ignoreCase = true)
            }
            .map(HarnessTool::name)
        enableOptionalTools(githubTools, target)
    }

    fun capabilitySummary(enabledOptional: Set<String> = enabledOptionalSnapshot()): String {
        val tools = registry.names().mapNotNull(registry::get)
        return LocalToolRouter.capabilitySummary(tools, enabledOptional)
    }

    fun visibleSchemas(
        policy: LocalAgentRunPolicy,
        maxOptionalDefinitionTokens: Int = LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
    ): JsonArray {
        if (!policy.toolsEnabled) return JsonArray(emptyList())
        val tools = registry.names().mapNotNull(registry::get)
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
        val matches = LocalToolRouter.search(tools, query)
        if (matches.isEmpty()) {
            return "未找到匹配的扩展能力；可换用联网、下载、记忆、会话、GitHub、Android、视觉、运行时、MCP、LSP、自动化或 Webhook 等关键词"
        }
        synchronized(target) {
            target += matches.map(HarnessTool::name)
        }
        return buildString {
            appendLine("已为当前回合启用 " + matches.size + " 个扩展工具：")
            matches.forEach { tool ->
                append("- ").append(tool.name)
                LocalToolRouter.conciseDescription(tool).takeIf(String::isNotBlank)?.let {
                    append("：").append(it)
                }
                appendLine()
            }
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
        val registered = registry.get(call.name)
            ?: return AgentToolResult(
                content = "未知工具：" + call.name,
                isError = true,
                errorCode = "UNKNOWN_TOOL",
                recoveryHint = "先使用 capability_search 或检查工具名称。",
            )

        if (planModeEnabled && !LocalToolPolicy.allowedInPlan(call.name, registered.access)) {
            return AgentToolResult(
                content = "当前处于规划模式，只能检查和制定方案；请先通过 exit_plan_mode 提交计划。",
                isError = true,
                errorCode = "PLAN_MODE_BLOCKED",
                recoveryHint = "提交并批准计划后再执行修改类工具。",
            )
        }

        if (!allowMutation && registered.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)) {
            return AgentToolResult(
                content = "当前子任务是只读作用域，不能执行会改变状态的工具：" + call.name,
                isError = true,
                errorCode = "MUTATION_SCOPE_BLOCKED",
                recoveryHint = "改用只读检查工具，或由父智能体在可写作用域执行该操作。",
            )
        }

        var approvalDenied = false
        val invocation = registry.executeTracked(
            name = call.name,
            input = call.arguments,
            rawArguments = call.rawArguments,
            context = ToolContext(
                sessionId = sessionId,
                allowMutation = allowMutation,
                attributes = buildMap {
                    put("call_id", call.id)
                    currentCoroutineContext()[LocalModelRunContext]?.profile?.let { put("model_profile", it) }
                },
                approval = { tool ->
                    val granted = approval(call, tool, approvalSummary(call, tool))
                    if (!granted) approvalDenied = true
                    granted
                },
            ),
        )
        val result = invocation.result

        if (!result.isError) {
            return AgentToolResult(
                content = result.content,
                retention = result.retention,
                sideEffect = if (registered.access in MUTATING_ACCESSES) {
                    AgentToolSideEffect.POSSIBLE
                } else {
                    AgentToolSideEffect.NONE
                },
            )
        }

        val providerCode = result.errorCode?.takeIf(String::isNotBlank)
        val timedOut = providerCode == "TOOL_TIMEOUT" ||
            (providerCode == null && result.content.startsWith("工具执行超时："))
        val readLike = registered.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)
        val errorCode = when {
            approvalDenied -> "APPROVAL_DENIED"
            providerCode != null -> providerCode
            timedOut -> "TOOL_TIMEOUT"
            else -> "TOOL_REPORTED_ERROR"
        }
        val mutationMayHaveSideEffect =
            registered.access in MUTATING_ACCESSES && invocation.executionStarted
        // Registry is the authority for whether the executor actually started. Provider error codes
        // are descriptive only and cannot downgrade a mutating call to a pre-execution failure.
        val retryable = when {
            !invocation.executionStarted -> result.retryable
            readLike -> result.retryable || (providerCode == null && timedOut)
            else -> false
        }
        val recoveryHint = when {
            mutationMayHaveSideEffect ->
                "工具可能已经产生副作用；先检查当前状态，不要直接重试。"
            else -> result.recoveryHint ?: when {
                approvalDenied -> "该工具没有获得批准；不要重复调用，改用已授权能力或等待用户调整权限。"
                timedOut && readLike -> "只读工具超时，可缩小范围后重试一次。"
                timedOut -> "工具执行超时；先检查当前状态，再决定是否重试。"
                else -> "根据工具返回内容检查前置条件；确认状态后再决定下一步。"
            }
        }
        AppLog.warn(
            "LocalToolExecution",
            "工具执行失败 tool=${call.name} code=$errorCode retryable=$retryable",
        )
        return AgentToolResult(
            content = result.content,
            isError = true,
            errorCode = errorCode,
            retryable = retryable,
            sideEffect = if (mutationMayHaveSideEffect) {
                AgentToolSideEffect.POSSIBLE
            } else {
                AgentToolSideEffect.NONE
            },
            recoveryHint = recoveryHint,
            retention = result.retention,
        )
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

private fun JsonObject.optionalStringForCoordinator(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
