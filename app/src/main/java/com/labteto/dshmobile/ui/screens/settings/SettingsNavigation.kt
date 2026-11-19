package com.labteto.dshmobile.ui.screens.settings

/** Cross-links return through their actual source, including direct feature entries. */
internal fun settingsNavigationPush(
    stack: List<SettingsDestination>, destination: SettingsDestination,
): List<SettingsDestination> = if (stack.lastOrNull() == destination) stack else stack + destination

internal fun settingsNavigationPop(stack: List<SettingsDestination>): List<SettingsDestination>? =
    stack.dropLast(1).takeIf { it.isNotEmpty() }


/** Routes are history entries. Peer switches and in-page usage details share a menu level. */
internal val SettingsDestination.menuLevel: Int
    get() = when (this) {
        SettingsDestination.ROOT -> 1
        SettingsDestination.PRICING,
        SettingsDestination.USAGE,
        SettingsDestination.USAGE_LOG,
        SettingsDestination.USAGE_DETAIL,
        SettingsDestination.MINIMAX_QUOTA,
        SettingsDestination.MEMORY_SETTINGS,
        SettingsDestination.MEMORY_MANAGEMENT,
        SettingsDestination.DIAGNOSTICS,
        SettingsDestination.CHAT -> 3
        else -> 2
    }
