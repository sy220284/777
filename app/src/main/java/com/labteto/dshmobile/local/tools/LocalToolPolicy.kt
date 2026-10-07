package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
     * Model-facing exposure is an explicit policy decision, just like access and approval.
     *
     * Keep the tools needed for ordinary coding/execution loops permanently visible. Capabilities
     * that are only useful for a narrower user intent stay registered but are exposed on demand
     * through task-intent pre-activation or capability_search. This removes repeated schema cost
     * without removing the capability itself.
     */
    fun exposure(name: String): ToolExposure = when (canonical(name)) {
        "web_search", "web_fetch", "http_request", "download_file", "network_diagnose",
        "job_list", "job_output", "job_kill", "json_query", "environment_info",
        "todo_write", "create_goal", "get_goal", "update_goal",
        "skill", "subagent_fork", "list_subagent_models", "list_agents", "send_message",
        "interrupt_agent", "workflow", "team_members", "team_spawn", "team_send_message",
        "team_task_create", "team_task_get", "team_task_list", "team_task_update",
        "team_interrupt", "team_wait", "memory_search", "memory_list", "memory_remember",
        "memory_update", "memory_forget", "session_search", "session_trace",
        "session_event_trace", "session_event_read", "present" -> ToolExposure.OPTIONAL
        "read", "tool_output_read", "write", "edit", "apply_patch", "file_inspect", "list_files",
        "glob", "grep", "bash", "capability_search", "update_plan", "exit_plan_mode",
        "ask_user_question", "subagent", "session_event_search" -> ToolExposure.CORE
        else -> error("内置工具尚未声明模型暴露策略：$name")
    }

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
        "subagent", "subagent_fork", "workflow", "send_message", "interrupt_agent",
        "team_spawn", "team_send_message", "team_task_create", "team_task_update",
        "team_interrupt" -> ToolAccess.AGENT_CONTROL
        "web_search", "web_fetch", "network_diagnose" -> ToolAccess.NETWORK
        "http_request" -> ToolAccess.PRIVILEGED
        "read", "tool_output_read", "file_inspect", "list_files", "glob", "grep", "job_list", "job_output", "json_query",
        "environment_info", "capability_search", "get_goal", "skill", "list_subagent_models", "list_agents",
        "session_event_search", "session_search", "memory_search", "memory_list",
        "session_trace", "session_event_trace", "session_event_read",
        "team_members", "team_task_get", "team_task_list", "team_wait" -> ToolAccess.READ_ONLY
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
            "send_message", "interrupt_agent", "team_members", "team_spawn", "team_send_message",
            "team_task_create", "team_task_get", "team_task_list", "team_task_update",
            "team_interrupt", "team_wait" -> "智能体"
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
        val discoveryKeywords = when (canonical) {
            "web_search" -> setOf("联网", "网页搜索", "最新信息", "web", "search")
            "web_fetch" -> setOf("网页", "网址", "url", "抓取", "web")
            "http_request" -> setOf("http", "https", "api", "接口请求")
            "download_file" -> setOf("下载", "文件下载", "download", "url")
            "network_diagnose" -> setOf("网络诊断", "连通性", "dns", "tls", "vpn", "代理")
            "job_list", "job_output", "job_kill" -> setOf("后台", "后台任务", "进程", "job", "process")
            "json_query" -> setOf("json", "json查询", "结构化数据")
            "environment_info" -> setOf(
                "环境", "环境信息", "运行时信息", "自检", "诊断",
                "token", "token用量", "token消耗", "用量统计", "上下文消耗", "提示词消耗",
            )
            "todo_write" -> setOf("待办", "任务清单", "todo")
            "create_goal", "get_goal", "update_goal" -> setOf("目标", "goal")
            "skill" -> setOf("技能", "skill")
            "subagent_fork" -> setOf("子代理", "继承上下文", "fork", "并行代理")
            "list_subagent_models" -> setOf("子代理模型", "worker模型", "模型列表")
            "list_agents", "send_message", "interrupt_agent" -> setOf("代理", "agent", "子任务", "消息", "中断")
            "workflow" -> setOf("工作流", "workflow", "流水线", "pipeline", "并行任务")
            "team_members", "team_spawn", "team_send_message", "team_task_create",
            "team_task_get", "team_task_list", "team_task_update", "team_interrupt",
            "team_wait" -> setOf("智能体团队", "多代理协作", "agent team", "teammate", "任务板", "mailbox")
            "memory_search" -> setOf("搜索记忆", "查找记忆", "memory")
            "memory_list" -> setOf("记忆列表", "查看记忆", "memory")
            "memory_remember" -> setOf("记住", "长期记忆", "memory")
            "memory_update" -> setOf("修改记忆", "更新记忆", "memory")
            "memory_forget" -> setOf("忘记", "删除记忆", "memory")
            "session_search" -> setOf("搜索会话", "历史会话", "session")
            "session_trace" -> setOf("会话轨迹", "会话历史", "trace", "session")
            "session_event_trace" -> setOf("事件轨迹", "事件上下文", "trace", "session")
            "session_event_read" -> setOf("读取事件", "会话事件", "event", "session")
            "present" -> setOf("展示", "呈现", "预览", "present")
            else -> emptySet()
        }
        return ToolMetadata(
            family = family,
            discoveryKeywords = discoveryKeywords,
            usageNotes = usageNotes,
        )
    }

    /**
     * Tools whose execution is gated by the approval pipeline.
     *
     * `ALWAYS` means the call reaches the approval decision. When global automatic approval is
     * enabled, that decision may be resolved automatically for every approval-gated tool. The
     * [autoApprovalScope] below only classifies lower-risk operations; it is not a global allowlist.
     */
    fun approval(name: String): ToolApprovalPolicy = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file", "bash", "job_kill", "send_message", "interrupt_agent",
        "team_send_message", "team_interrupt",
        "http_request", "session_event_search", "session_trace", "session_event_trace", "session_event_read",
        "memory_update", "memory_forget" -> ToolApprovalPolicy.ALWAYS
        else -> { access(name); ToolApprovalPolicy.NEVER }
    }

    /**
     * Low-risk classification used by approval impact and UI messaging.
     *
     * - workspace-bounded writes and read-only tools are classified as lower risk;
     * - process, network, privileged, agent-control and session mutation capabilities stay outside it;
     * - global automatic approval is intentionally broader and may approve these higher-risk classes;
     * - Shell starts in the workspace but can leave cwd under the app UID, so it is never classified
     *   as a workspace-bounded operation.
     */
    fun autoApprovalScope(name: String): LocalAutoApprovalScope = when (canonical(name)) {
        "write", "edit", "apply_patch", "download_file" -> LocalAutoApprovalScope.WORKSPACE
        // A shell can leave cwd and reach same-UID app-private state; keep it outside low-risk classification.
        "bash" -> LocalAutoApprovalScope.NONE
        else -> if (access(name) == ToolAccess.READ_ONLY) {
            LocalAutoApprovalScope.READ_ONLY
        } else {
            LocalAutoApprovalScope.NONE
        }
    }

    fun isVisibleCall(name: String, allowedToolNames: Set<String>): Boolean {
        val canonical = canonical(name)
        return name in allowedToolNames || canonical in allowedToolNames
    }

    fun isReadOnlyInvocation(
        name: String,
        access: ToolAccess,
        arguments: JsonObject,
    ): Boolean = when (canonical(name)) {
        "web_fetch" -> (arguments["run_in_background"] as? JsonPrimitive)?.booleanOrNull != true
        else -> access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)
    }

    fun allowedInPlan(name: String, access: ToolAccess): Boolean =
        access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK) ||
            canonical(name) in setOf("update_plan", "exit_plan_mode", "ask_user_question")

    fun allowedInPlan(
        name: String,
        access: ToolAccess,
        arguments: JsonObject,
    ): Boolean =
        allowedInPlan(name, access) &&
            (
                canonical(name) in setOf("update_plan", "exit_plan_mode", "ask_user_question") ||
                    isReadOnlyInvocation(name, access, arguments)
            )
}
