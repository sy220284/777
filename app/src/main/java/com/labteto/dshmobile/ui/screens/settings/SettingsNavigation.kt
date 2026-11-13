package com.labteto.dshmobile.ui.screens.settings

/** Cross-links return through their actual source, including direct feature entries. */
internal fun settingsNavigationPush(
    stack: List<SettingsDestination>, destination: SettingsDestination,
): List<SettingsDestination> = if (stack.lastOrNull() == destination) stack else stack + destination

internal fun settingsNavigationPop(stack: List<SettingsDestination>): List<SettingsDestination>? =
    stack.dropLast(1).takeIf { it.isNotEmpty() }
