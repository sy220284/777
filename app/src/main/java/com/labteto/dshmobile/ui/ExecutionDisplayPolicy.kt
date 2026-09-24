package com.labteto.dshmobile.ui

import androidx.annotation.StringRes
import com.labteto.dshmobile.R

/**
 * Presentation-only policy for agent-driven execution.
 *
 * Runtime arguments/results stay available to the execution layer and durable logs. User-facing
 * transcript surfaces reduce every tool call to a semantic operation category plus lifecycle
 * state. Raw command text, file paths, search terms, arguments and tool output must never be used
 * as a fallback label.
 */
private val COMMAND_EXECUTION_TOOL_NAMES = setOf(
    "bash",
    "pwsh",
    "shell",
    "run_shell",
    "process_exec",
    "terminal_open",
    "terminal_send",
    "terminal_write",
    "pty-send",
)

internal fun isCommandExecutionTool(toolName: String?): Boolean =
    toolName?.lowercase() in COMMAND_EXECUTION_TOOL_NAMES

internal enum class AgentOperationKind {
    Inspect,
    Search,
    Update,
    Execute,
    Web,
    Device,
    Image,
    Background,
    Delegate,
    External,
    Generic,
}

/**
 * Classify a tool by the user-facing purpose of the step.
 *
 * This deliberately ignores arguments: even a harmless path/query becomes noisy execution detail
 * in a conversation. Unknown tools fall back to [AgentOperationKind.Generic] instead of exposing
 * their raw tool name.
 */
internal fun agentOperationKind(toolName: String?): AgentOperationKind {
    val name = toolName?.trim()?.lowercase().orEmpty()
    if (name.isEmpty()) return AgentOperationKind.Generic
    return when {
        name == "web_search" || name == "grep" || name == "glob" ||
            name == "find" || name == "search" || name.endsWith("_search") ->
            AgentOperationKind.Search

        name == "write" || name == "edit" || name == "write_file" || name == "edit_file" ||
            name == "apply_patch" || name == "patch" || name.startsWith("workspace_write") ->
            AgentOperationKind.Update

        isCommandExecutionTool(name) || name == "run_code" || name == "exec" ||
            name == "execute" || name.endsWith("_exec") || name == "cordis_run" ->
            AgentOperationKind.Execute

        name == "web_fetch" || name == "http_request" || name.startsWith("http_") ||
            name.startsWith("web_") ->
            AgentOperationKind.Web

        name.startsWith("android_") || name.startsWith("device_") ||
            name.startsWith("shizuku_") ->
            AgentOperationKind.Device

        name.startsWith("vision_") || name.contains("image") || name.contains("screenshot") ->
            AgentOperationKind.Image

        name.startsWith("job_") || name.contains("background") ->
            AgentOperationKind.Background

        name.startsWith("subagent") || name.startsWith("agent_") ||
            name.startsWith("delegate") ->
            AgentOperationKind.Delegate

        name.startsWith("plugin_") || name.startsWith("mcp_") ||
            name.startsWith("skill_") || name.startsWith("cordis_") ->
            AgentOperationKind.External

        name == "read" || name == "read_file" || name.startsWith("read_") ||
            name.startsWith("list_") || name.startsWith("lsp_") ||
            name.contains("inspect") ->
            AgentOperationKind.Inspect

        else -> AgentOperationKind.Generic
    }
}

@StringRes
internal fun agentOperationLabelRes(toolName: String?): Int = when (agentOperationKind(toolName)) {
    AgentOperationKind.Inspect -> R.string.agent_operation_inspect
    AgentOperationKind.Search -> R.string.agent_operation_search
    AgentOperationKind.Update -> R.string.agent_operation_update
    AgentOperationKind.Execute -> R.string.agent_operation_execute
    AgentOperationKind.Web -> R.string.agent_operation_web
    AgentOperationKind.Device -> R.string.agent_operation_device
    AgentOperationKind.Image -> R.string.agent_operation_image
    AgentOperationKind.Background -> R.string.agent_operation_background
    AgentOperationKind.Delegate -> R.string.agent_operation_delegate
    AgentOperationKind.External -> R.string.agent_operation_external
    AgentOperationKind.Generic -> R.string.agent_operation_generic
}

@StringRes
internal fun agentApprovalPurposeRes(toolName: String?): Int = when (agentOperationKind(toolName)) {
    AgentOperationKind.Update -> R.string.local_approval_purpose_update
    AgentOperationKind.Execute -> R.string.local_approval_purpose_shell
    AgentOperationKind.Inspect -> R.string.local_approval_purpose_inspect
    else -> R.string.local_approval_purpose_default
}

@StringRes
internal fun agentOperationStatusRes(running: Boolean, failed: Boolean): Int = when {
    failed -> R.string.agent_operation_status_failed
    running -> R.string.agent_operation_status_running
    else -> R.string.agent_operation_status_done
}
