package com.labteto.dshmobile.ui

/**
 * Presentation-only policy for agent work.
 *
 * Execution/runtime layers keep full tool names, arguments, paths and results. Ordinary UI surfaces
 * intentionally collapse those details into a small set of user-facing purposes so transcripts
 * describe what is happening without leaking commands, file names, paths or raw payloads.
 */
internal enum class OperationDisplayKind {
    Search,
    Inspect,
    Modify,
    Save,
    Execute,
    Network,
    Device,
    Analyze,
    Delegate,
    Other,
}

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

internal fun operationDisplayKind(toolName: String?): OperationDisplayKind {
    val name = toolName?.lowercase().orEmpty()
    if (name in COMMAND_EXECUTION_TOOL_NAMES) return OperationDisplayKind.Execute

    return when {
        name.contains("search") || name.contains("grep") || name.contains("glob") ||
            name.contains("find") || name.contains("query") ->
            OperationDisplayKind.Search

        name.contains("read") || name.contains("inspect") || name.contains("list") ||
            name.contains("fetch") || name.contains("cat") || name.contains("view") ->
            OperationDisplayKind.Inspect

        name.contains("edit") || name.contains("patch") || name.contains("replace") ||
            name.contains("rename") || name.contains("delete") || name.contains("remove") ||
            name.contains("move") || name.contains("mkdir") ->
            OperationDisplayKind.Modify

        name.contains("write") || name.contains("create") || name.contains("save") ||
            name.contains("export") || name.contains("download") || name.contains("copy") ||
            name.contains("present") ->
            OperationDisplayKind.Save

        name.contains("web") || name.contains("http") || name.contains("network") ||
            name.contains("browser") ->
            OperationDisplayKind.Network

        name.contains("device") || name.contains("screen") || name.contains("tap") ||
            name.contains("swipe") || name.contains("input") || name.contains("shizuku") ->
            OperationDisplayKind.Device

        name.contains("lsp") || name.contains("code") || name.contains("diagnostic") ||
            name.contains("symbol") || name.contains("definition") || name.contains("reference") ||
            name.contains("analy") ->
            OperationDisplayKind.Analyze

        name.contains("agent") || name.contains("workflow") || name.contains("delegate") ||
            name.contains("cordis") ->
            OperationDisplayKind.Delegate

        else -> OperationDisplayKind.Other
    }
}
