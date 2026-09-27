package com.labteto.dshmobile.local

internal object LocalHarnessEngineConfig {
    const val KEY_MODEL = "model"
    const val KEY_CONFIGURED_MODELS = "configured_models"
    const val KEY_MODEL_PROFILES = "model_profiles_v2"
    const val KEY_BASE_URL = "base_url"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_ATTACHMENT_GC_AT = "attachment_gc_at"
    const val DEFAULT_MODEL = "deepseek-flash"
    const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_MAIN_MAX_STEPS = 16
    const val DEFAULT_SUBAGENT_MAX_STEPS = 20
    const val DEFAULT_MODEL_ATTEMPTS = 3
    const val DEFAULT_WEB_FETCH_BYTES = 4 * 1024 * 1024
    const val MAX_WEB_FETCH_BYTES = 4 * 1024 * 1024
    const val PERSISTENT_RECOVERY_RETRY_MILLIS = 500L
    const val DEFAULT_DOWNLOAD_BYTES = 20 * 1024 * 1024
    const val MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024
    const val FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS = 45L
    const val BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS = 240L
    const val DEFAULT_FOREGROUND_SHELL_TIMEOUT_SECONDS = 30
    const val DEFAULT_BACKGROUND_SHELL_TIMEOUT_SECONDS = 300
    const val MAX_FOREGROUND_SHELL_TIMEOUT_SECONDS = 120
    const val MAX_BACKGROUND_SHELL_TIMEOUT_SECONDS = 900
    const val MAX_PATCH_CHARS = 512_000
    const val MAX_TOOL_RESULT_CHARS = 50_000
    const val MAX_EVENT_CHARS = 65_536
    const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
    const val MAX_HANDOFF_CHARS = 3_500
    const val MAX_EPHEMERAL_CONTEXT_CHARS = 10_000
    const val CHAT_GUARD_REWRITE_TAIL_MESSAGES = 5
    const val CHAT_RECENT_HISTORY_MESSAGES = 20
    const val CHAT_ROLEPLAY_TEMPERATURE = 0.85
    const val CHAT_DYNAMIC_CONTEXT_RESERVE_CHARS = 3_000
    const val MAX_PENDING_INPUTS = 16
    const val LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES = 200
    const val AUTOMATION_CHAT_HISTORY_MESSAGES = 48
    const val MAX_STREAM_PREVIEW_CHARS = 4_096
    const val PERSONA_CORRECTION_UNDO_MILLIS = 10_000L
    const val STREAM_PREVIEW_INTERVAL_MS = 50L
    const val CHAT_POST_TURN_MODEL_STEP = 10_000
    const val MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL = 8
    const val ATTACHMENT_GC_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
    const val LOCAL_PROJECT_ID = "local-workspace"
    const val PROJECTION_BASELINE_EVENT = "session/projection-baseline"


    val SUBAGENT_VIRTUAL_SCREEN_TOOLS = setOf(
        "android_vscreen_status",
        "android_vscreen_launch",
        "android_vscreen_tap",
        "android_vscreen_swipe",
        "android_vscreen_screenshot",
        "vision_analyze_vscreen",
    )
    val SUBAGENT_EXCLUDED_TOOLS = setOf(
        "subagent", "subagent_fork", "workflow", "ask_user_question",
        "session_event_search", "session_trace", "create_goal", "get_goal", "update_goal",
        "session_search", "session_event_trace", "session_event_read", "todo_write", "update_plan",
        "memory_remember", "memory_update", "memory_forget", "vision_analyze_screen",
        "list_agents", "send_message", "interrupt_agent", "list_subagent_models",
        "schedule_task", "schedule_recurring_task", "cancel_scheduled_task",
        "webhook_start", "webhook_stop", "webhook_copy_token", "webhook_rotate_token",
        "mcp_http_connect", "mcp_stdio_connect", "mcp_disconnect",
    )

    val PARALLEL_SUBAGENT_TOOLS = setOf("subagent", "spawn_subagent")

    val PLAN_MODE_BLOCKED_TOOLS = setOf(
        "write", "write_file", "edit", "edit_file", "apply_patch", "download_file", "http_request",
        "bash", "run_shell", "job_kill", "todo_write",
        "create_goal", "update_goal", "subagent", "spawn_subagent", "subagent_fork", "fork_subagent",
        "workflow", "present", "send_message", "interrupt_agent",
    )

    val PLAN_MODE_PROMPT = """
        当前处于规划模式。只允许读取、搜索和分析；禁止修改文件、执行命令、启动会改变状态的子任务或交付成果。
        完成决策充分的计划后，必须把完整计划作为 exit_plan_mode 的唯一工具调用提交给用户审批。
    """.trimIndent()
}
