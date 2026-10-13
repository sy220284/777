package com.labteto.dshmobile.ui.screens.settings

/** A direct subpage entry returns to its caller; deeper settings still unwind locally. */
internal fun settingsBackDestination(
    current: SettingsDestination,
    entry: SettingsDestination,
): SettingsDestination? = if (current == entry) null else current.parentDestination()
