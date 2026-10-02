package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolMetadata

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
     * Force-push detection uses shell-token boundaries instead of substring matching.
     *
     * This remains a best-effort guard rather than a shell parser, but ordinary branch names such
     * as `feature-fix` and unrelated flags no longer look like `-f`.
     */
    private fun isForcePush(command: String): Boolean {
        val tokens = Regex("""[^\s;&|]+""").findAll(command)
            .map { match -> match.value.trim('"', '\'') }
            .toList()
        val pushIndex = tokens.indexOfFirst { it == "push" }
        if (pushIndex <= 0) return false
        val gitBeforePush = tokens.take(pushIndex).any { token ->
            token.substringAfterLast('/') == "git"
        }
        if (!gitBeforePush) return false
        return tokens.drop(pushIndex + 1).any { token ->
            token == "-f" ||
                token == "--force" ||
                token == "--force-with-lease" ||
                token.startsWith("--force-with-lease=")
        }
    }

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

    fun metadata(name: String): ToolMetadata {
        val canonical = canonical(name)
        val family = when (canonical) {
            "read", "tool_output_read", "file_inspect", "list_files", "glob", "grep", "write", "edit",
            "apply_patch", "download_file", "json_query", "present" -> "文件"
            "bash", "job_list", "job_output", "job_kill" -> "运行时"
            "web_search", "web_fetch", "http_request", "network_diagnose" -> "网络"
            "environment_info", "capability_search" -> "环境"
            "update_plan", "exit_plan_mode", "todo_write", "create_goal", "get_goal", "update_goal",
            "ask_user_question" -> "任务"
            "skill" -> "技能"
            "subagent", "subagent_fork", "workflow", "list_subagent_models", "list_agents",
            "send_message", "interrupt_agent" -> "智能体"
            "memory_search", "memory_list", "memory_remember", "memory_update", "memory_forget" -> "记忆"
            "session_event_search", "session_search", "session_trace", "session_event_trace",
            "session_event_read" -> "会话"
            else -> error("内置工具尚未声明能力族：$name")
        }
        val usageNotes = when (canonical) {
            "read" -> listOf("模型可见输出受上下文预算限制；发生省略时按返回的 call_id 使用 tool_output_read 分段恢复完整结果")
            "subagent", "subagent_fork" -> listOf(
                "会独立发起模型请求并消耗所选路由对应的 API 或 ChatGPT 套餐额度；并行子代理会分别产生模型消费",
            )
            "workflow" -> listOf(
                "每个子任务可能独立发起模型请求并消耗所选 Worker 路由对应额度；并行模式会并行产生模型消费",
            )
            "environment_info" -> listOf("持久模型历史与最近成功请求的实际输入 Token 分开报告")
            else -> emptyList()
        }
        return ToolMetadata(
            family = family,
            discoveryKeywords = emptySet(),
            usageNotes = usageNotes,
        )
    }

    /**
     * Tools whose execution is gated by the approval pipeline.
     *
     * `ALWAYS` means the call reaches the approval decision; it does not mean the user is prompted,
     * because global auto-approval can resolve it. `bash` is listed here precisely so that its command
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
     * Low-risk classification follows the sandbox, not the tool category.
     *
     * This no longer limits global auto-approval; it is retained for impact/display metadata and
     * one-turn device policy decisions:
     * - workspace-bounded writes and read-only tools are classified low risk;
     * - firmware partitions and other apps' private directories stay out of reach regardless of
     *   approval mode because the kernel boundary still applies;
     * - categories that act outside the filesystem sandbox remain higher impact even though global
     *   auto-approval may skip their prompt when the user enables it.
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
