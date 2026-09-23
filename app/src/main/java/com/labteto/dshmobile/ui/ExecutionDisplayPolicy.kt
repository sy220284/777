package com.labteto.dshmobile.ui

/**
 * Presentation-only policy for agent-driven command execution.
 *
 * Raw arguments and results remain available to the execution/runtime layers and durable logs.
 * Ordinary UI surfaces must not render command lines for these tools; they show only purpose,
 * lifecycle state and risk/approval information.
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
