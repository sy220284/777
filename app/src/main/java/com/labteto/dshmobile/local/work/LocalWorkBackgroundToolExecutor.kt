package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.LocalToolApprovalRuntime
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.MAX_EVENT_CHARS
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.shouldAutoApproveTool
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.string
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


/** Owns background dispatch, noninteractive approvals and event recording. Composition only wires it. */
internal class LocalWorkBackgroundToolExecutor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val approvalPreferences: LocalApprovalPreferences,
    private val tools: LocalToolCompositionRoot,
    private val toolApproval: LocalToolApprovalRuntime,
) {
    suspend fun executeAutomationSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: com.labteto.dshmobile.local.memory.LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
        onApprovalBlocked: (String) -> Unit,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        val normalized = if (
            canonical.name in setOf("bash", "run_shell", "web_fetch") &&
            canonical.arguments["run_in_background"]?.let { value ->
                (value as? JsonPrimitive)?.booleanOrNull == true
            } == true
        ) {
            canonical.copy(
                arguments = JsonObject(
                    canonical.arguments + ("run_in_background" to JsonPrimitive(false)),
                ),
            )
        } else {
            canonical
        }
        val log = sessionStorage.eventLogs.get(sessionId)
        log.append("tool/call", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("arguments", normalized.arguments)
            put("execution_started", false)
            put("automation", true)
        })
        val result = try {
            executeUtility(normalized, sessionId, memoryTools, enabledOptionalTools)
                ?: executeAutomationRegistered(normalized, allowMutation, sessionId, onApprovalBlocked)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            automationToolFailure(
                normalized,
                "AUTOMATION_TOOL_ERROR",
                error.message ?: error::class.java.simpleName,
            )
        }
        log.append("tool/result", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("content", com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair(result.content, MAX_EVENT_CHARS))
            put("is_error", result.isError)
            result.errorCode?.let { put("error_code", it) }
            put("retryable", result.retryable)
            put("side_effect", result.sideEffect.name.lowercase())
            result.recoveryHint?.let { put("recovery_hint", it) }
            put("automation", true)
        })
        return result
    }

    private suspend fun executeAutomationRegistered(
        original: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        onApprovalBlocked: (String) -> Unit,
    ): AgentToolResult {
        val result = tools.execution.executeScoped(
            original = original,
            sessionId = sessionId,
            allowMutation = allowMutation,
            planModeEnabled = false,
            approval = { call, tool, _ ->
                val approvalMode = approvalPreferences.currentMode()
                if (shouldAutoApproveTool(approvalMode, tool)) {
                    sessionStorage.eventLogs.get(sessionId).append("approval/auto", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-" + approvalMode.name.lowercase())
                    })
                    true
                } else {
                    val reason = "后台任务需要人工审批：" + tool.name
                    onApprovalBlocked(reason)
                    sessionStorage.eventLogs.get(sessionId).append("approval/blocked", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-noninteractive")
                    })
                    false
                }
            },
        )
        return if (result.isError) {
            result.copy(
                recoveryHint = "后台任务不能弹出人工审批；可在工作模式中打开该任务继续处理。" +
                    result.recoveryHint?.let { " " + it }.orEmpty(),
            )
        } else {
            result
        }
    }

    private fun automationToolFailure(
        call: LocalToolCall,
        code: String,
        detail: String,
    ): AgentToolResult {
        return com.labteto.dshmobile.local.tools.localToolFailure(code,
            "[${call.name}][$code] 工具执行失败：$detail\n调用 id：${call.id}",
            readOnly = tools.registry.get(LocalToolPolicy.canonical(call.name))?.let {
                LocalToolPolicy.isReadOnlyInvocation(call.name, it.access, call.arguments)
            } == true, executionStarted = null)
    }

    suspend fun executePersistentSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: com.labteto.dshmobile.local.memory.LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        return executeUtility(canonical, sessionId, memoryTools, enabledOptionalTools)
            ?: tools.execution.executeScoped(
                original = canonical,
                sessionId = sessionId,
                allowMutation = allowMutation,
                planModeEnabled = false,
                approval = { normalized, tool, summary ->
                    if (sessionId == runtimeStateStore.currentSessionId) {
                        toolApproval.approve(normalized, tool, summary)
                    } else {
                        shouldAutoApproveTool(approvalPreferences.currentMode(), tool)
                    }
                },
            )
    }

    private fun executeUtility(call: LocalToolCall, sessionId: String,
        memoryTools: com.labteto.dshmobile.local.memory.LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>): AgentToolResult? = when (call.name) {
        "capability_search" -> AgentToolResult(tools.execution.searchCapabilities(call.arguments.string("query"), enabledOptionalTools))
        "memory_search", "memory_list" -> AgentToolResult(memoryTools.execute(call.name, call.arguments, allowMutation = false))
        "tool_output_read" -> AgentToolResult(tools.toolOutputStore.read(sessionId, call.arguments.string("call_id"),
            call.arguments.int("start_byte", 0), call.arguments.int("max_bytes", com.labteto.dshmobile.local.LocalToolOutputStore.DEFAULT_READ_BYTES)))
        else -> null
    }

}
