package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.R
import java.text.Normalizer
import java.util.Locale

/** Navigation metadata only. Configuration and availability still belong to each Feature. */
internal data class SettingsSearchEntry(
    val id: String,
    val destination: SettingsDestination,
    val titleRes: Int,
    val aliases: List<String>,
    val anchorTitleRes: Int? = null,
    val expandAppearance: Boolean = false,
)

internal val settingsPageEntries = listOf(
    SettingsSearchEntry("session", SettingsDestination.SESSION, R.string.settings_page_session, listOf("对话", "conversation")),
    SettingsSearchEntry("appearance", SettingsDestination.APPEARANCE, R.string.settings_page_appearance, listOf("appearance", "theme")),
    SettingsSearchEntry("chat", SettingsDestination.CHAT, R.string.settings_page_chat, listOf("角色", "文风", "chat")),
    SettingsSearchEntry("models", SettingsDestination.MODELS, R.string.settings_page_models, listOf("model", "供应商", "provider")),
    SettingsSearchEntry("performance", SettingsDestination.MODEL_PERFORMANCE, R.string.settings_model_performance_title, listOf("思考", "reasoning", "温度", "temperature", "上下文摘要")),
    SettingsSearchEntry("account", SettingsDestination.ACCOUNT, R.string.chatgpt_account_title, listOf("登录", "ChatGPT", "订阅", "account")),
    SettingsSearchEntry("model-usage", SettingsDestination.MODEL_USAGE, R.string.settings_page_model_usage, listOf("消耗", "usage", "费用")),
    SettingsSearchEntry("minimax", SettingsDestination.MINIMAX_QUOTA, R.string.minimax_quota_title, listOf("MiniMax", "额度", "quota")),
    SettingsSearchEntry("pricing", SettingsDestination.PRICING, R.string.settings_page_pricing, listOf("price", "价格", "计费")),
    SettingsSearchEntry("usage", SettingsDestination.USAGE, R.string.usage_calculation_title, listOf("token", "用量", "花费", "消耗统计")),
    SettingsSearchEntry("usage-log", SettingsDestination.USAGE_LOG, R.string.usage_log_title, listOf("请求记录", "usage log")),
    SettingsSearchEntry("memory", SettingsDestination.MEMORY, R.string.settings_page_memory, listOf("memory", "personalization", "个人偏好")),
    SettingsSearchEntry("memory-settings", SettingsDestination.MEMORY_SETTINGS, R.string.advanced_memory_settings, listOf("记忆规则", "memory settings")),
    SettingsSearchEntry("memory-management", SettingsDestination.MEMORY_MANAGEMENT, R.string.advanced_manage_memory, listOf("记忆内容", "删除记忆", "memory management")),
    SettingsSearchEntry("permissions", SettingsDestination.PERMISSIONS, R.string.settings_page_permissions, listOf("permission", "授权")),
    SettingsSearchEntry("notifications", SettingsDestination.NOTIFICATIONS, R.string.settings_page_notifications, listOf("notification", "提醒")),
    SettingsSearchEntry("advanced", SettingsDestination.ADVANCED, R.string.settings_page_advanced, listOf("advanced", "运行策略")),
    SettingsSearchEntry("storage", SettingsDestination.SESSION_STORAGE, R.string.settings_local_session_storage, listOf("存储", "备份", "恢复", "导入", "导出", "storage", "backup", "restore")),
    SettingsSearchEntry("diagnostics", SettingsDestination.DIAGNOSTICS, R.string.settings_runtime_diagnostics, listOf("诊断", "错误", "网络检测", "日志", "diagnostics")),
)

private fun row(id: String, page: SettingsDestination, title: Int, vararg aliases: String,
    expandAppearance: Boolean = false) = SettingsSearchEntry(id, page, title, aliases.toList(), title, expandAppearance)

internal val settingsSearchEntries = settingsPageEntries + listOf(
    row("theme", SettingsDestination.APPEARANCE, R.string.settings_appearance, "深色", "浅色", "明暗", "跟随系统", "dark", "light"),
    row("accent", SettingsDestination.APPEARANCE, R.string.settings_accent_theme, "颜色", "color", "accent"),
    row("reading", SettingsDestination.APPEARANCE, R.string.settings_reading_preferences, "排版", "reading"),
    row("text-size", SettingsDestination.APPEARANCE, R.string.settings_text_size, "字号", "字体大小", "font size"),
    row("wallpaper", SettingsDestination.APPEARANCE, R.string.settings_background_image, "壁纸", "图片背景", "wallpaper"),
    row("advanced-appearance", SettingsDestination.APPEARANCE, R.string.settings_advanced_appearance, "玻璃", "透明度", "动态材质", "glass", "blur", expandAppearance = true),
    row("motion", SettingsDestination.APPEARANCE, R.string.settings_advanced_motion, "动画", "动效", "animation", "motion", expandAppearance = true),
    row("api-models", SettingsDestination.MODELS, R.string.local_api_models_title, "API Key", "密钥", "接口地址", "base url", "添加模型"),
    row("image-mode", SettingsDestination.MODELS, R.string.local_model_image_mode_title, "视觉", "图片理解", "原生图片", "image input"),
    row("approval", SettingsDestination.PERMISSIONS, R.string.settings_approval_mode_title, "别问我", "自动执行", "执行确认", "approval"),
    row("device", SettingsDestination.PERMISSIONS, R.string.advanced_device_capabilities, "手机操控", "设备授权", "device"),
    row("accessibility", SettingsDestination.PERMISSIONS, R.string.advanced_accessibility, "无障碍", "屏幕控制", "accessibility"),
    row("notification-access", SettingsDestination.PERMISSIONS, R.string.advanced_notification_access, "通知读取", "通知使用权", "notification access"),
    row("virtual-display", SettingsDestination.PERMISSIONS, R.string.advanced_virtual_display, "虚拟屏幕", "virtual display"),
    row("notification-permission", SettingsDestination.NOTIFICATIONS, R.string.settings_notifications_system_group, "允许通知", "通知权限", "notification permission"),
    row("job-notifications", SettingsDestination.NOTIFICATIONS, R.string.settings_notifications_group_jobs, "任务通知", "完成通知", "失败通知"),
    row("quiet-hours", SettingsDestination.NOTIFICATIONS, R.string.settings_notification_quiet_title, "免打扰", "静音", "quiet hours"),
    row("reasoning", SettingsDestination.MODEL_PERFORMANCE, R.string.settings_model_performance_reasoning, "推理深度", "思考档位", "reasoning effort"),
    row("temperature", SettingsDestination.MODEL_PERFORMANCE, R.string.settings_model_performance_temperature, "温度", "表达变化", "temperature"),
    row("chat-intimacy-mode", SettingsDestination.CHAT, R.string.settings_chat_intimacy_mode,
        "亲密互动", "亲密开关", "恋爱", "intimacy"),
)

internal fun settingsPageEntry(page: SettingsDestination): SettingsSearchEntry? =
    settingsPageEntries.firstOrNull { it.destination == page }

private fun searchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

/** Stable ranking, common aliases and full-width input; no silent result-count limit. */
internal fun searchSettingsEntries(
    query: String,
    entries: List<SettingsSearchEntry> = settingsSearchEntries,
    title: (Int) -> String,
): List<SettingsSearchEntry> {
    val q = searchText(query)
    if (q.isEmpty()) return entries
    return entries.mapNotNull { entry ->
        val name = searchText(title(entry.titleRes))
        val score = when {
            name == q -> 4
            name.startsWith(q) -> 3
            name.contains(q) -> 2
            entry.aliases.any { searchText(it).contains(q) } -> 1
            else -> return@mapNotNull null
        }
        entry to score
    }.sortedByDescending { it.second }.map { it.first }
}
