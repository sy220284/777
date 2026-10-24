package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.ToolResult

/**
 * Adapter for legacy built-ins that still return display Strings.
 * Only unambiguous, source-owned failure sentinels are reclassified; never infer failure from
 * arbitrary substrings in command output, model text, filenames or successful tool data.
 * New built-ins should return structured ToolResult natively instead of expanding this map.
 */
internal fun localBuiltinStructuredResult(toolName: String, output: String): ToolResult {
    val canonical = LocalToolPolicy.canonical(toolName)
    val code = when {
        output.startsWith("[apply_patch][CHECK_FAILED]") && canonical == "apply_patch" ->
            "PATCH_CHECK_FAILED"
        output.startsWith("[apply_patch][APPLY_FAILED]") && canonical == "apply_patch" ->
            "PATCH_APPLY_FAILED"
        canonical in WORK_RUN_REQUIRED && output ==
            "Work 工具缺少活动运行上下文，已拒绝旧的无绑定执行路径" ->
            "WORK_SESSION_REQUIRED"
        canonical in WORK_AGENT_REQUIRED && output ==
            "Work 代理工具缺少活动运行上下文，已拒绝旧的无绑定执行路径" ->
            "WORK_SESSION_REQUIRED"
        canonical in IMAGE_TOOLS && output.startsWith("MEDIA_WORK_SESSION_REQUIRED：") ->
            "MEDIA_WORK_SESSION_REQUIRED"
        canonical == "media_image_status" && output.startsWith("MEDIA_JOB_NOT_FOUND：") ->
            "MEDIA_JOB_NOT_FOUND"
        canonical == "media_image_options" && output ==
            "没有配置可用的图像 API 创作账户；请到模型设置添加实际支持的 API Key。" ->
            "MEDIA_PROVIDER_UNAVAILABLE"
        canonical == "web_fetch" && output ==
            "当前为只读/规划作用域，不能创建后台网页抓取任务" ->
            "MUTATION_SCOPE_BLOCKED"
        canonical == "http_request" && output ==
            "只读/规划作用域仅允许 GET/HEAD 请求" ->
            "MUTATION_SCOPE_BLOCKED"
        canonical == "download_file" && output ==
            "只读子任务不能下载写入文件" ->
            "MUTATION_SCOPE_BLOCKED"
        canonical in setOf("write", "edit", "bash") &&
            output in setOf("子代理无写入权限", "该子任务处于只读模式") ->
            "MUTATION_SCOPE_BLOCKED"
        canonical == "list_subagent_models" &&
            output.contains("\n【指定配置的官方模型目录】\n官方模型目录查询未成功（") ->
            "MODEL_CATALOG_UNAVAILABLE"
        else -> null
    }
    return if (code == null) ToolResult(output)
    else ToolResult(
        content = output,
        isError = true,
        errorCode = code,
        recoveryHint = when (code) {
            "MEDIA_JOB_NOT_FOUND" -> "先检查当前会话媒体任务列表，不要重新提交收费创作请求。"
            "MEDIA_PROVIDER_UNAVAILABLE" -> "请先在模型设置配置可用媒体账户，再查询 media_image_options。"
            "MODEL_CATALOG_UNAVAILABLE" -> "检查已保存模型档案的凭据及供应商目录接口。"
            "WORK_SESSION_REQUIRED" -> "需要有效 Work 会话；不要在普通聊天或已结束任务中假装执行成功。"
            else -> "请核对当前执行作用域和真实工具结果；不要盲目重试可能产生副作用的操作。"
        },
    )
}

private val WORK_RUN_REQUIRED = setOf(
    "update_plan", "exit_plan_mode", "todo_write", "create_goal",
    "get_goal", "update_goal", "ask_user_question",
)
private val WORK_AGENT_REQUIRED = setOf(
    "subagent", "subagent_fork", "list_subagent_models", "list_agents", "agent_tree",
    "send_message", "interrupt_agent", "agent_inspect", "agent_watch", "agent_resume", "agent_close", "workflow",
    "team_members", "team_member_status", "team_create_member", "team_start_member",
    "team_spawn", "team_send_message", "team_request_shutdown", "team_messages", "team_message_receipt", "team_wait_for_message",
    "team_task_create", "team_task_get", "team_task_list", "team_task_update",
    "team_interrupt", "team_disable_member", "team_dismiss_member", "team_stop_all", "team_wait",
)
private val IMAGE_TOOLS = setOf("media_image_options", "media_image_create", "media_image_status")
