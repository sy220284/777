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
        ExtensionMarketStatus.NEEDS_SETUP, "需要配置",
        "缺少依赖：" + missing.joinToString("、") { it.description.ifBlank { it.value } },
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
