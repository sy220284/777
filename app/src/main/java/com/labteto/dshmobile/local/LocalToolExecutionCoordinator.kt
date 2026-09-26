package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolRegistry
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
    fun clearTurnCapabilities() {
        synchronized(enabledOptionalTools) { enabledOptionalTools.clear() }
    }

    fun enabledOptionalSnapshot(): Set<String> =
        synchronized(enabledOptionalTools) { enabledOptionalTools.toSet() }

    fun visibleSchemas(policy: LocalAgentRunPolicy): JsonArray {
        if (!policy.toolsEnabled) return JsonArray(emptyList())
        val tools = registry.names().mapNotNull(registry::get)
        return LocalToolRouter.visibleSchemas(tools, enabledOptionalSnapshot())
    }

    fun searchCapabilities(
        query: String,
        target: MutableSet<String> = enabledOptionalTools,
    ): String {
        val tools = registry.names().mapNotNull(registry::get)
        val matches = LocalToolRouter.search(tools, query)
        if (matches.isEmpty()) {
            return "未找到匹配的扩展能力；可换用 Android、视觉、运行时、MCP、LSP、自动化或 Webhook 等关键词"
        }
        synchronized(target) {
            target += matches.map(HarnessTool::name)
        }
        return buildString {
            appendLine("已为当前回合启用 " + matches.size + " 个扩展工具：")
            matches.forEach { tool ->
                append("- ").append(tool.name)
                LocalToolRouter.description(tool).takeIf(String::isNotBlank)?.let {
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

        val result = registry.execute(
            name = call.name,
            input = call.arguments,
            rawArguments = call.rawArguments,
            context = ToolContext(
                sessionId = sessionId,
                allowMutation = allowMutation,
                attributes = mapOf("call_id" to call.id),
                approval = { tool ->
                    approval(call, tool, approvalSummary(call, tool))
                },
            ),
        )

        if (!result.isError) return AgentToolResult(result.content)

        return AgentToolResult(
            content = result.content,
            isError = true,
            errorCode = "TOOL_REPORTED_ERROR",
            sideEffect = if (registered.access in MUTATING_ACCESSES) {
                AgentToolSideEffect.POSSIBLE
            } else {
                AgentToolSideEffect.NONE
            },
            recoveryHint = "根据工具返回内容检查前置条件；若可能有副作用，先核对当前状态。",
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
