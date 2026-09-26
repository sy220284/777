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
     * Android's app UID/SELinux sandbox is useful isolation, but it is not the official Harness
     * workspace-write filesystem sandbox: a child shell can still reach this app's own private files
     * outside the selected workspace. Until a process-level mount/namespace sandbox is available,
     * shell execution is therefore an explicit permission escalation and is never auto-approved.
     *
     * The markers below remain useful for diagnostics and UI risk summaries, but they are not used
     * as a substitute for an enforceable filesystem boundary.
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
        if (command.isBlank()) return false
        // Fail closed while Android has no enforceable process-level workspace sandbox.
        return false
    }

    fun shellRiskReason(command: String): String {
        val trimmed = command.trim()
        return when {
            trimmed.isEmpty() -> "空命令"
            FIRMWARE_PATH_MARKERS.any { trimmed.contains(it) } -> "命令显式引用系统/内核路径"
            DESTRUCTIVE_GIT_OPERATIONS.any { trimmed.contains(it) } -> "命令会不可逆重写工作历史"
            isForcePush(trimmed) -> "命令包含强制推送"
            else -> "Android 当前无法为子进程施加真正的 workspace-write 文件系统沙箱"
        }
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
        "read", "tool_output_read", "file_inspect", "list_files", "glob", "grep", "job_list", "job_output", "json_query",
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
        // Shell remains available, but without a process-level filesystem sandbox it must always
        // pass through explicit approval rather than inheriting workspace auto-approval.
        "bash" -> LocalAutoApprovalScope.NONE
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
