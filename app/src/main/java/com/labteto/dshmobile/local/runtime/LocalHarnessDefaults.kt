package com.labteto.dshmobile.local.runtime



internal const val KEY_SESSION_ID = "session_id"
internal const val KEY_ATTACHMENT_GC_AT = "attachment_gc_at"
internal const val DEFAULT_WEB_FETCH_BYTES = 4 * 1024 * 1024
internal const val MAX_WEB_FETCH_BYTES = 4 * 1024 * 1024
internal const val PERSISTENT_RECOVERY_RETRY_MILLIS = 500L
internal const val DEFAULT_DOWNLOAD_BYTES = 20 * 1024 * 1024
internal const val MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024
internal const val FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS = 45L
internal const val BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS = 240L
internal const val MAX_PATCH_CHARS = 512_000
internal const val MAX_TOOL_RESULT_CHARS = 50_000
internal const val MAX_EVENT_CHARS = 65_536
internal const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
internal const val MAX_HANDOFF_CHARS = 3_500
internal const val MAX_EPHEMERAL_CONTEXT_CHARS = 10_000
internal const val CHAT_RECENT_HISTORY_MESSAGES = 20
internal const val CHAT_DYNAMIC_CONTEXT_RESERVE_CHARS = 3_000
internal const val MAX_PENDING_INPUTS = 16
internal const val LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES = 200
internal const val AUTOMATION_CHAT_HISTORY_MESSAGES = 48
internal const val MAX_STREAM_PREVIEW_CHARS = 4_096
internal const val PERSONA_CORRECTION_UNDO_MILLIS = 10_000L
internal const val STREAM_PREVIEW_INTERVAL_MS = 50L
internal const val CHAT_POST_TURN_MODEL_STEP = 10_000
internal const val GROUP_POST_TURN_PENDING_BATCH = 8
internal const val MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL = 8
internal const val ATTACHMENT_GC_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
internal const val LOCAL_PROJECT_ID = "local-workspace"
internal const val PROJECTION_BASELINE_EVENT = "session/projection-baseline"
internal val SUBAGENT_VIRTUAL_SCREEN_TOOLS = setOf(
    "android_vscreen_status",
    "android_vscreen_launch",
    "android_vscreen_tap",
    "android_vscreen_swipe",
    "android_vscreen_screenshot",
    "vision_analyze_vscreen",
)

internal val SUBAGENT_EXCLUDED_TOOLS = setOf(
    "subagent", "subagent_fork", "workflow", "ask_user_question",
    "session_event_search", "session_trace", "create_goal", "get_goal", "update_goal",
    "session_search", "session_event_trace", "session_event_read", "todo_write", "update_plan",
    "exit_plan_mode",
    "memory_remember", "memory_update", "memory_forget", "vision_analyze_screen",
    "list_agents", "send_message", "interrupt_agent", "list_subagent_models",
    "team_members", "team_member_status", "team_create_member", "team_start_member",
    "team_spawn", "team_send_message", "team_messages", "team_wait_for_message",
    "team_task_create", "team_task_get", "team_task_list", "team_task_update",
    "team_interrupt", "team_disable_member", "team_dismiss_member", "team_stop_all", "team_wait",
    "schedule_task", "schedule_recurring_task", "cancel_scheduled_task",
    "webhook_start", "webhook_stop", "webhook_copy_token", "webhook_rotate_token",
    "mcp_http_connect", "mcp_stdio_connect", "mcp_reconnect", "mcp_disconnect",
)

internal val PARALLEL_SUBAGENT_TOOLS = setOf("subagent", "spawn_subagent")

internal val PLAN_MODE_BLOCKED_TOOLS = setOf(
    "write", "write_file", "edit", "edit_file", "apply_patch", "download_file", "http_request",
    "bash", "run_shell", "job_kill", "todo_write",
    "create_goal", "update_goal", "subagent", "spawn_subagent", "subagent_fork", "fork_subagent",
    "workflow", "present", "send_message", "interrupt_agent",
    "team_create_member", "team_start_member", "team_spawn", "team_send_message",
    "team_task_create", "team_task_update", "team_interrupt", "team_disable_member",
    "team_dismiss_member", "team_stop_all",
)
internal val PLAN_MODE_PROMPT = """
    当前为规划模式：只读、搜索和分析，不执行改变状态的操作。
    规划过程中，每一轮读取、搜索、检查或分析都必须先用一句具体中文说明这一轮正在核对什么、比较什么或判断什么；不得使用“处理当前步骤”“检查相关内容”“查找相关信息”“运行任务步骤”等模板文案，也不得只显示工具类别。
    即使当前模型或工具协议允许 assistant.content 为空，也不能省略规划过程说明；reasoning_content 不能替代用户可见的规划进度。
    计划充分后，仅调用 exit_plan_mode 提交完整计划审批。用户在审阅时选择重新生成方案，需保留原始目标和已取得的只读证据，重新拟订方案并再次调用 exit_plan_mode 提交审核；在批准之前保持规划权限边界。
""".trimIndent()
