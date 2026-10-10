package com.labteto.dshmobile.local.tools

/** Only observed configuration is projected; actual execution still enforces permissions. */
internal enum class LocalTaskCapabilityKind { GITHUB, WEB_SEARCH, MODEL, MCP, PLUGINS, ACCESSIBILITY, NOTIFICATION_ACCESS }

internal enum class LocalTaskCapabilityState {
    CONFIGURED, CONNECTION_REQUIRED, DISABLED, UNKNOWN,
}

internal data class LocalTaskCapabilityReadiness(
    val kind: LocalTaskCapabilityKind,
    val state: LocalTaskCapabilityState,
)

internal object LocalTaskCapabilityReadinessProjector {
    private val webHints = listOf(
        "联网", "网页搜索", "网络搜索", "上网查", "在线搜索", "web_search", "web search",
    )
    private val webNegations = listOf(
        "不联网", "不用联网", "无需联网", "不要联网", "离线处理", "不要网页搜索",
        "不用网页搜索", "不需要网页搜索", "无需网页搜索", "不需要网络搜索",
        "禁止网络搜索", "不用web_search", "no web search",
    )

    /**
     * The current user's task is authoritative. Do not infer live authorization from
     * registry presence, an earlier unrelated request or a stored credential alone.
     */
    fun project(
        task: String,
        githubConfigured: Boolean?,
        networkSearchEnabled: Boolean,
        showModelStatus: Boolean = false,
        modelConfigured: Boolean? = null,
        mcpToolsAvailable: Boolean? = null,
        pluginsInstalled: Boolean? = null,
        accessibilityActive: Boolean? = null,
        notificationAccessActive: Boolean? = null,
    ): List<LocalTaskCapabilityReadiness> {
        if (task.isBlank()) return emptyList()
        val normalized = task.lowercase()
        val asksGitHub = LocalToolCapabilityIntent.from(task, emptyList()).requestsGitHub
        val asksWeb = webHints.any(normalized::contains) &&
            webNegations.none(normalized::contains)
        val asksMcp = normalized.contains("mcp") || normalized.contains("工具服务器")
        val asksPlugins = normalized.contains("插件") || normalized.contains("plugin")
        val asksAccessibility = listOf("无障碍", "操控手机", "手机自动操作", "自动点击", "设备操作")
            .any(normalized::contains) &&
            listOf("不要自动点击", "不需要无障碍", "不要操作设备", "禁止操控手机")
                .none(normalized::contains)
        val asksNotificationAccess = listOf("读取通知", "监听通知", "通知监听", "通知访问")
            .any(normalized::contains) &&
            listOf("不读取通知", "不要读取通知", "不需要通知访问").none(normalized::contains)
        return buildList {
            // Work requires a configured model, but configuration alone does not prove connectivity.
            if (showModelStatus) {
                add(LocalTaskCapabilityReadiness(
                    LocalTaskCapabilityKind.MODEL,
                    when (modelConfigured) {
                        true -> LocalTaskCapabilityState.CONFIGURED
                        false -> LocalTaskCapabilityState.CONNECTION_REQUIRED
                        null -> LocalTaskCapabilityState.UNKNOWN
                    },
                ))
            }
            if (asksMcp) {
                add(LocalTaskCapabilityReadiness(
                    LocalTaskCapabilityKind.MCP,
                    when (mcpToolsAvailable) {
                        true -> LocalTaskCapabilityState.CONFIGURED
                        false -> LocalTaskCapabilityState.CONNECTION_REQUIRED
                        null -> LocalTaskCapabilityState.UNKNOWN
                    },
                ))
            }
            if (asksPlugins) {
                add(LocalTaskCapabilityReadiness(
                    LocalTaskCapabilityKind.PLUGINS,
                    when (pluginsInstalled) {
                        true -> LocalTaskCapabilityState.CONFIGURED
                        false -> LocalTaskCapabilityState.CONNECTION_REQUIRED
                        null -> LocalTaskCapabilityState.UNKNOWN
                    },
                ))
            }
            if (asksAccessibility) {
                add(LocalTaskCapabilityReadiness(
                    LocalTaskCapabilityKind.ACCESSIBILITY,
                    when (accessibilityActive) {
                        true -> LocalTaskCapabilityState.CONFIGURED
                        false -> LocalTaskCapabilityState.CONNECTION_REQUIRED
                        null -> LocalTaskCapabilityState.UNKNOWN
                    },
                ))
            }
            if (asksNotificationAccess) {
                add(LocalTaskCapabilityReadiness(
                    LocalTaskCapabilityKind.NOTIFICATION_ACCESS,
                    when (notificationAccessActive) {
                        true -> LocalTaskCapabilityState.CONFIGURED
                        false -> LocalTaskCapabilityState.CONNECTION_REQUIRED
                        null -> LocalTaskCapabilityState.UNKNOWN
                    },
                ))
            }
            if (asksGitHub) {
                add(LocalTaskCapabilityReadiness(
                    LocalTaskCapabilityKind.GITHUB,
                    when (githubConfigured) {
                        true -> LocalTaskCapabilityState.CONFIGURED
                        false -> LocalTaskCapabilityState.CONNECTION_REQUIRED
                        null -> LocalTaskCapabilityState.UNKNOWN
                    },
                ))
            }
            if (asksWeb) {
                add(LocalTaskCapabilityReadiness(
                    LocalTaskCapabilityKind.WEB_SEARCH,
                    if (networkSearchEnabled) LocalTaskCapabilityState.CONFIGURED
                    else LocalTaskCapabilityState.DISABLED,
                ))
            }
        }
    }
}
