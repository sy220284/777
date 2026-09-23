package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy

internal enum class LocalAutoApprovalScope {
    NONE,
    WORKSPACE,
    READ_ONLY,
}

/** Explicit classifications: adding a built-in requires deciding its permissions and approval boundary. */
internal object LocalToolPolicy {
    /**
     * Bash commands eligible for automatic approval, matched as a whole-command prefix.
     *
     * Only GitHub operations are listed. Every other command — including anything that can touch
     * arbitrary paths — keeps the interactive prompt, so the process-level escape hatch stays closed.
     */
    private val AUTO_APPROVED_COMMAND_PREFIXES = listOf("git ")

    /**
     * Git subcommands that rewrite history or discard local work. These keep the prompt even though
     * their parent command is allowlisted, because a mistake here destroys remote or local data.
     */
    private val PROMPT_REQUIRED_GIT_SUBCOMMANDS = listOf(
        "push --force", "push -f", "reset --hard", "clean -fd", "clean -fdx", "filter-branch",
    )

    /**
     * Shell metacharacters that let one command chain into another. Any command containing one of
     * these is rejected from automatic approval, otherwise `git status; rm -rf /` would slip through
     * a prefix-only check.
     */
    private val COMMAND_CHAINING_CHARACTERS = charArrayOf(';', '|', '&', '$', '`', '\n', '>', '<')

    private val aliases = mapOf(
        "read_file" to "read", "write_file" to "write", "edit_file" to "edit",
        "glob_files" to "glob", "search_text" to "grep", "run_shell" to "bash",
        "spawn_subagent" to "subagent", "fork_subagent" to "subagent_fork",
    )
    fun canonical(name: String): String = aliases[name] ?: name

    /**
     * Whether [command] is an allowlisted, non-chained, non-destructive shell command that may run
     * without an approval prompt.
     */
    fun canAutoApproveCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it in COMMAND_CHAINING_CHARACTERS }) return false
        if (AUTO_APPROVED_COMMAND_PREFIXES.none { trimmed.startsWith(it) }) return false
        return PROMPT_REQUIRED_GIT_SUBCOMMANDS.none { trimmed.contains(it) }
    }

    fun access(name: String): ToolAccess = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file", "present" -> ToolAccess.WORKSPACE_WRITE
        "update_plan", "exit_plan_mode", "todo_write", "create_goal", "update_goal",
        "ask_user_question", "memory_remember", "memory_update", "memory_forget" -> ToolAccess.SESSION_WRITE
        "bash", "job_kill" -> ToolAccess.PROCESS
        "subagent", "subagent_fork", "workflow", "send_message", "interrupt_agent" -> ToolAccess.AGENT_CONTROL
        "web_search", "web_fetch", "network_diagnose" -> ToolAccess.NETWORK
        "http_request" -> ToolAccess.PRIVILEGED
        "read", "file_inspect", "list_files", "glob", "grep", "job_list", "job_output", "json_query",
        "environment_info", "capability_search", "get_goal", "skill", "list_subagent_models", "list_agents",
        "session_event_search", "session_search", "memory_search", "memory_list",
        "session_trace", "session_event_trace", "session_event_read" -> ToolAccess.READ_ONLY
        else -> error("内置工具尚未声明权限：$name")
    }

    fun approval(name: String): ToolApprovalPolicy = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file", "bash", "job_kill", "send_message", "interrupt_agent",
        "http_request", "session_event_search", "session_trace", "session_event_trace", "session_event_read",
        "memory_update", "memory_forget" -> ToolApprovalPolicy.ALWAYS
        else -> { access(name); ToolApprovalPolicy.NEVER }
    }

    /**
     * Safe automatic approval is intentionally narrow:
     * - workspace writes are allowed only for built-ins whose implementation is path-confined to LocalWorkspace;
     * - read-only built-ins may be approved regardless of whether the data lives in or outside the workspace.
     *
     * Process, device, network mutation and privileged operations remain explicit.
     */
    fun autoApprovalScope(name: String): LocalAutoApprovalScope = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file" -> LocalAutoApprovalScope.WORKSPACE
        // bash is not broadly allowlisted: only commands passing canAutoApproveCommand() qualify,
        // and that check needs the command text, which this name-only function does not receive.
        else -> if (access(name) == ToolAccess.READ_ONLY) {
            LocalAutoApprovalScope.READ_ONLY
        } else {
            LocalAutoApprovalScope.NONE
        }
    }

    fun allowedInPlan(name: String, access: ToolAccess): Boolean =
        access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK) ||
            canonical(name) in setOf("update_plan", "exit_plan_mode", "ask_user_question")
}
