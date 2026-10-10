package com.labteto.dshmobile.local.tools

/** Only observed configuration is projected; actual execution still enforces permissions. */
internal enum class LocalTaskCapabilityKind { GITHUB, WEB_SEARCH }

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
        "不用网页搜索", "禁止网络搜索", "不用web_search", "no web search",
    )

    /**
     * The current user's task is authoritative. Do not infer live authorization from
     * registry presence, an earlier unrelated request or a stored credential alone.
     */
    fun project(
        task: String,
        githubConfigured: Boolean?,
        networkSearchEnabled: Boolean,
    ): List<LocalTaskCapabilityReadiness> {
        if (task.isBlank()) return emptyList()
        val normalized = task.lowercase()
        val asksGitHub = LocalToolCapabilityIntent.from(task, emptyList()).requestsGitHub
        val asksWeb = webHints.any(normalized::contains) &&
            webNegations.none(normalized::contains)
        return buildList {
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
