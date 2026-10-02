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
    // Reuse the sandbox boundary's canonical deny set so shell approval and file enforcement cannot drift.
    private val FIRMWARE_PATH_MARKERS = LocalSandboxBoundary.DEFAULT_FORBIDDEN_PREFIXES

    // Destructive Git operations remain explicit user-visible approval boundaries.
    private val DESTRUCTIVE_GIT_OPERATIONS =
        listOf("reset --hard", "clean -fd", "clean -fdx", "filter-branch")

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
     * `ALWAYS` means the call reaches the approval decision. Safe auto-approval may resolve only
     * tools whose [autoApprovalScope] is explicitly safe; process-level Shell stays outside that
     * scope and therefore always requires an explicit approval.
     */
    fun approval(name: String): ToolApprovalPolicy = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file", "bash", "job_kill", "send_message", "interrupt_agent",
        "http_request", "session_event_search", "session_trace", "session_event_trace", "session_event_read",
        "memory_update", "memory_forget" -> ToolApprovalPolicy.ALWAYS
        else -> { access(name); ToolApprovalPolicy.NEVER }
    }

    /**
     * Authoritative scope for safe auto-approval.
     *
     * - workspace-bounded writes and read-only tools may be approved by the safe mode;
     * - process, network, privileged, agent-control and session mutation capabilities stay outside it;
     * - Shell starts in the workspace but can leave cwd under the app UID, so it is never classified
     *   as a workspace-bounded operation.
     */
    fun autoApprovalScope(name: String): LocalAutoApprovalScope = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file" -> LocalAutoApprovalScope.WORKSPACE
        // A shell can leave cwd and reach same-UID app-private state; require explicit approval.
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
