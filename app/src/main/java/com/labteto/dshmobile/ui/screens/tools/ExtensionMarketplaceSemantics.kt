package com.labteto.dshmobile.ui.screens.tools

import com.labteto.dshmobile.uicontract.UiSkillRequirement
import com.labteto.dshmobile.uicontract.UiToolConnection
import com.labteto.dshmobile.uicontract.UiToolRegistration

/** Codex-style separation: installed, configured and runtime-ready are different facts. */
internal enum class ExtensionMarketStatus {
    INSTALLABLE, INSTALLED, MANUAL_ONLY, NEEDS_SETUP, BUILTIN, REGISTERED, CONNECTING, FAILED, UNSUPPORTED
}

internal data class ExtensionMarketState(
    val status: ExtensionMarketStatus,
    val label: String,
    val advice: String = "",
)

internal fun installedSkillMarketState(
    modelInvocable: Boolean,
    sourceWarning: String?,
    requirements: List<UiSkillRequirement>,
): ExtensionMarketState {
    if (sourceWarning != null) return ExtensionMarketState(
        ExtensionMarketStatus.FAILED, "来源需核验", sourceWarning,
    )
    val missing = requirements.filterNot { it.registered }
    if (missing.isNotEmpty()) return ExtensionMarketState(
        ExtensionMarketStatus.NEEDS_SETUP, "待连接",
        "有 ${missing.size} 项运行依赖尚未连接：" +
            missing.joinToString("、") { it.description.ifBlank { it.value } },
    )
    if (!modelInvocable) return ExtensionMarketState(
        ExtensionMarketStatus.MANUAL_ONLY, "手动调用", "此技能已关闭模型自动调用",
    )
    return ExtensionMarketState(
        ExtensionMarketStatus.INSTALLED, "已安装", "已登记的依赖仍在实际调用时检查授权",
    )
}

internal fun connectorMarketState(
    registration: UiToolRegistration,
    connection: UiToolConnection,
): ExtensionMarketState = when {
    registration == UiToolRegistration.FAILED || connection == UiToolConnection.FAILED ->
        ExtensionMarketState(ExtensionMarketStatus.FAILED, "连接异常", "检查服务连接并重新授权")
    registration == UiToolRegistration.PENDING ||
        connection == UiToolConnection.CONNECTING ||
        connection == UiToolConnection.RECONNECTING ->
        ExtensionMarketState(ExtensionMarketStatus.CONNECTING, "连接中")
    registration == UiToolRegistration.REMOVING ||
        connection == UiToolConnection.DISCONNECTING ->
        ExtensionMarketState(ExtensionMarketStatus.CONNECTING, "正在断开")
    else -> ExtensionMarketState(
        ExtensionMarketStatus.REGISTERED, "已登记 · 待验证",
        "已发现 MCP 工具；健康检查与单次工具授权在使用时完成",
    )
}

/**
 * A skill's metadata is user-supplied. Only explicit public HTTPS links can prefill a one-tap
 * connection; actual OAuth and tool authorization still require the user to act.
 */
internal fun skillMcpHttpsEndpoint(requirement: UiSkillRequirement): String? {
    if (requirement.type.lowercase() != "mcp") return null
    val transport = requirement.transport?.lowercase()
    if (transport != null && transport !in setOf("http", "sse", "streamable-http")) return null
    val url = requirement.url ?: return null
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null ||
        uri.port !in setOf(-1, 443) || !host.contains('.') ||
        host.endsWith(".local") || host == "localhost" ||
        host.matches(Regex("[0-9.]+")) || host.contains(':')) return null
    return url
}

/** Filtering is based on actual installation/source, never on translated display labels. */
internal fun marketplaceTabAllows(
    selectedTab: Int,
    skillsOnly: Boolean,
    installedSkill: Boolean,
    installable: Boolean,
    connectedService: Boolean,
    localPlugin: Boolean,
    group: ExtensionGroup,
): Boolean = if (skillsOnly) when (selectedTab) {
    0 -> installedSkill || installable
    1 -> installedSkill
    2 -> (installedSkill || installable) && group == ExtensionGroup.OFFICE
    3 -> (installedSkill || installable) && group == ExtensionGroup.DEVELOPMENT
    4 -> (installedSkill || installable) && group == ExtensionGroup.CREATIVE
    else -> false
} else when (selectedTab) {
    0 -> installedSkill || installable || localPlugin || connectedService
    1 -> connectedService
    2 -> installedSkill || localPlugin
    3 -> (installedSkill || installable || localPlugin) && group == ExtensionGroup.AUTOMATION
    4 -> (installedSkill || installable || localPlugin) && group == ExtensionGroup.CREATIVE
    else -> false
}

/** Use importer-validated immutable source identity for every official plugin ownership check. */
internal fun codexOfficialPluginSourceMatches(
    source: com.labteto.dshmobile.uicontract.UiSkillSourceIdentity?,
    pluginRoot: String,
    skillDirectory: String? = null,
): Boolean {
    if (source == null || !source.owner.equals("openai", ignoreCase = true) ||
        !source.repository.equals("plugins", ignoreCase = true) ||
        !source.commit.matches(Regex("[0-9a-f]{40}"))) return false
    return if (skillDirectory == null) source.directory.startsWith("$pluginRoot/")
        else source.directory == "$pluginRoot/$skillDirectory"
}

internal fun codexPluginSkillInstalled(
    skills: List<com.labteto.dshmobile.uicontract.UiSkillSnapshot>,
    pluginRoot: String,
    skillDirectory: String,
): Boolean = skills.any { skill ->
    codexOfficialPluginSourceMatches(skill.sourceIdentity, pluginRoot, skillDirectory)
}
