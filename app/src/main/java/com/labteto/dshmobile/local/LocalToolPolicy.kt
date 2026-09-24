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
     * Shell commands are auto-approved by default, because the sandbox boundary is defined by what a
     * command can *reach*, not by what it is called.
     *
     * Everything the device already refused before this policy existed stays refused: firmware
     * partitions are mounted read-only and other apps' private directories are unreachable under the
     * untrusted-app SELinux domain. Auto-approval therefore does not create access the kernel does
     * not already grant.
     *
     * These markers are a best-effort second line, not a guarantee. Shell text admits too many
     * equivalent spellings — `cd / && cat system/x`, quoted splices, variable expansion — for static
     * matching to be airtight, and the kernel is what actually holds. Their purpose is to keep the
     * prompt in front of a command that visibly names firmware, so the user sees it before it runs.
     */
    // Reuse the boundary's canonical deny set so shell prompts and file enforcement cannot drift.
    // This is intentionally conservative for shell text: a visible forbidden prefix keeps a prompt.
    private val FIRMWARE_PATH_MARKERS = LocalSandboxBoundary.DEFAULT_FORBIDDEN_PREFIXES

    /**
     * Operation names that rewrite history or discard work that cannot be restored from a remote.
     *
     * These are deliberate user-visible guardrails rather than sandbox enforcement: the user data a
     * force-push destroys is exactly what the boundary is meant to leave reachable, so the prompt is
     * the only thing standing between a mistake and an unrecoverable loss.
     */
    private val DESTRUCTIVE_GIT_OPERATIONS = listOf("reset --hard", "clean -fd", "clean -fdx", "filter-branch")

    /** Force flags, matched anywhere in the command so argument order cannot hide them. */
    private val FORCE_FLAGS = listOf("--force", "-f")

    private val aliases = mapOf(
        "read_file" to "read", "write_file" to "write", "edit_file" to "edit",
        "glob_files" to "glob", "search_text" to "grep", "run_shell" to "bash",
        "spawn_subagent" to "subagent", "fork_subagent" to "subagent_fork",
    )
    fun canonical(name: String): String = aliases[name] ?: name

    /**
     * Whether [command] may run without an approval prompt.
     *
     * Default-allow: the sandbox, not the prompt, is what keeps firmware and other apps' private data
     * out of reach. A command is held back only when it names a firmware location, rewrites history,
     * or force-pushes — the first so the user sees a firmware-targeting command before it runs, the
     * latter two because the damage they do cannot be undone from a remote.
     */
    fun canAutoApproveCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        if (FIRMWARE_PATH_MARKERS.any { trimmed.contains(it) }) return false
        if (DESTRUCTIVE_GIT_OPERATIONS.any { trimmed.contains(it) }) return false
        return !isForcePush(trimmed)
    }

    /**
     * Force-push detection that does not depend on argument order.
     *
     * A substring test for `push --force` misses `git push origin main --force`, so the push verb and
     * a force flag are matched independently. `--force-with-lease` still matches `--force`, which is
     * intentional: it can still overwrite a remote branch when the lease is stale.
     */
    private fun isForcePush(command: String): Boolean =
        command.contains("push") && FORCE_FLAGS.any { command.contains(it) }

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

    /**
     * Tools whose execution is gated by the approval pipeline.
     *
     * `ALWAYS` means the call reaches the approval decision; it does not mean the user is prompted,
     * because safe auto-approval can resolve it. `bash` is listed here precisely so that its command
     * policy is consulted: a shell call should never bypass the pipeline just because most commands
     * are auto-approved.
     */
    fun approval(name: String): ToolApprovalPolicy = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file", "bash", "job_kill", "send_message", "interrupt_agent",
        "http_request", "session_event_search", "session_trace", "session_event_trace", "session_event_read",
        "memory_update", "memory_forget" -> ToolApprovalPolicy.ALWAYS
        else -> { access(name); ToolApprovalPolicy.NEVER }
    }

    /**
     * Safe automatic approval follows the sandbox, not the tool category:
     * - anything the sandbox can already reach may be approved automatically, because auto-approval
     *   cannot grant access the untrusted-app SELinux domain does not already permit;
     * - firmware partitions and other apps' private directories stay out of reach no matter what this
     *   policy returns, so they need no prompt-based guard here;
     * - tool categories that act outside the filesystem sandbox (device control, privileged HTTP)
     *   remain explicit.
     */
    fun autoApprovalScope(name: String): LocalAutoApprovalScope = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file" -> LocalAutoApprovalScope.WORKSPACE
        // Shell execution is approved by the sandbox boundary. The per-command check in
        // canAutoApproveCommand() still withholds firmware-targeting and history-rewriting commands,
        // and it needs the command text, so it runs in the parameter-aware caller instead.
        "bash" -> LocalAutoApprovalScope.WORKSPACE
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
